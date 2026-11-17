package com.fivetech.monitor.cond;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorCondEvent;
import com.fivetech.monitor.domain.MonitorCondState;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorNotifyRecord;
import com.fivetech.monitor.mapper.MonitorCondMapper;
import com.fivetech.monitor.mapper.MonitorEngineMapper;
import com.fivetech.monitor.service.MonitorChats;

/**
 * 条件规则一个检测点的写库（一条规则一个事务）：连续计数、开 / 续 / 结束事件、待发送通知。
 *
 * <ul>
 *   <li>命中：连续次数 +1；已有进行中的事件就更新「最近一次」；没有且达到 durationCount 次就开事件，开启了通知则写待发送记录
 *       （每个群一条，由 MonitorNotifySender 投递；静默期内不发，事件记 muted）；</li>
 *   <li>不命中：连续次数清零；有进行中的事件就结束它（回到正常）。</li>
 * </ul>
 * 事件、状态里的时间是统计时区；detail 里的检测点是站点时区。
 *
 * @author fivetech
 */
@Component
public class CondEventWriter
{
    static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 连续命中最多记多少个检测点 */
    static final int STREAK_MAX = 48;

    private final MonitorCondMapper mapper;

    private final MonitorEngineMapper engineMapper;

    private final CondSupport support;

    private final MonitorProperties monitorProperties;

    public CondEventWriter(MonitorCondMapper mapper, MonitorEngineMapper engineMapper, CondSupport support,
            MonitorProperties monitorProperties)
    {
        this.mapper = mapper;
        this.engineMapper = engineMapper;
        this.support = support;
        this.monitorProperties = monitorProperties;
    }

    /** 不是检测点（或这一小时取数失败放弃）：只记已处理到哪一小时 */
    @Transactional(rollbackFor = Exception.class)
    public void skip(MonitorScope scope, MonitorCondState state, Long ruleId, LocalDateTime hourStat)
    {
        MonitorCondState st = state == null ? new MonitorCondState() : state;
        st.setRuleId(ruleId);
        st.setLastDataHour(hourStat);
        mapper.upsertState(scope, st);
    }

    /**
     * @param end   检测点（站点时区整点）
     * @param state 当前状态（含进行中的事件）
     * @return 开了新事件时为事件 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long apply(MonitorScope scope, MonitorRule rule, CondSpec spec, CondEvaluator.Point point, LocalDateTime end,
            MonitorCondState state, Map<String, Object> stateDetail, CondRules.DimLabels labels, LocalDateTime nowStat)
    {
        String site = scope.getSiteCode();
        MonitorCondState st = state == null ? new MonitorCondState() : state;
        st.setRuleId(rule.getRuleId());
        List<String> streak = streak(st.getRecentPeriods());
        Long opened = null;
        CondWindow w = spec.getWindow();
        Map<String, Object> snap = checkpoint(end, point);
        if (point.hit())
        {
            streak.add(end.format(MINUTE));
            while (streak.size() > STREAK_MAX)
            {
                streak.remove(0);
            }
            if (st.getRunStartHour() == null)
            {
                st.setRunStartHour(support.toStat(site, w.start(end)));
            }
            if (st.getEventId() != null)
            {
                Map<String, Object> detail = parse(st.getEventDetail());
                detail.put("latest", snap);
                mapper.updateEventLatest(scope, st.getEventId(), CondSupport.JSON.writeValueAsString(detail),
                    support.toStat(site, end), nowStat);
            }
            else if (streak.size() >= spec.getDurationCount())
            {
                opened = open(scope, rule, spec, point, end, st, snap, labels, nowStat);
                st.setEventId(opened);
            }
        }
        else
        {
            streak.clear();
            st.setRunStartHour(null);
            if (st.getEventId() != null)
            {
                mapper.resolveEvent(scope, st.getEventId(), support.toStat(site, w.start(end)), nowStat);
                st.setEventId(null);
            }
        }
        st.setRecentPeriods(CondSupport.JSON.writeValueAsString(streak));
        st.setLastPeriodEnd(support.toStat(site, end));
        st.setLastDataHour(support.toStat(site, end.minusHours(1)));
        st.setDetail(stateDetail == null || stateDetail.isEmpty() ? null : CondSupport.JSON.writeValueAsString(stateDetail));
        mapper.upsertState(scope, st);
        return opened;
    }

    private Long open(MonitorScope scope, MonitorRule rule, CondSpec spec, CondEvaluator.Point point, LocalDateTime end,
            MonitorCondState st, Map<String, Object> snap, CondRules.DimLabels labels, LocalDateTime nowStat)
    {
        String site = scope.getSiteCode();
        Map<String, CondField> fields = support.catalog().fields();
        List<MonitorChats.Chat> chats = MonitorChats.parse(rule.getNotifyChats(), rule.getNotifyChatId(), rule.getNotifyChatName(), null);
        boolean notify = !Boolean.FALSE.equals(rule.getNotifyEnabled());
        String status;
        if (!notify)
        {
            status = "none";
        }
        else if (chats.isEmpty())
        {
            status = "unset";
        }
        else if (rule.getLastNotifiedAt() != null && rule.getMuteHours() != null && rule.getMuteHours() > 0
            && rule.getLastNotifiedAt().plusHours(rule.getMuteHours()).isAfter(nowStat))
        {
            status = "muted";
        }
        else
        {
            status = "pending";
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("window", spec.getWindow().getCode());
        detail.put("matchMode", spec.getMatchMode());
        detail.put("durationCount", spec.getDurationCount());
        detail.put("judgedAt", support.toSite(site, nowStat).format(MINUTE));
        detail.put("triggered", snap);
        detail.put("latest", snap);

        MonitorCondEvent e = new MonitorCondEvent();
        e.setRuleId(rule.getRuleId());
        e.setLevel(spec.getLevel());
        e.setStartHour(st.getRunStartHour());
        e.setTriggerHour(support.toStat(site, spec.getWindow().start(end)));
        e.setLastPeriodEnd(support.toStat(site, end));
        e.setRuleSnapshot(CondSupport.JSON.writeValueAsString(snapshot(rule, spec, labels, site)));
        e.setDetail(CondSupport.JSON.writeValueAsString(detail));
        e.setNotifyStatus(status);
        mapper.insertEvent(scope, e, nowStat);

        if ("pending".equals(status))
        {
            String content = CondMessages.alert(support.dashboard().siteName(site), rule.getRuleName(), ruleCode(rule.getRuleNo()), spec,
                fields, labels, support.symbol(site), end, point.outcomes(),
                CondMessages.eventUrl(monitorProperties.getEngine().getEventPageUrl(), e.getEventId()));
            for (MonitorChats.Chat chat : chats)
            {
                MonitorNotifyRecord n = new MonitorNotifyRecord();
                n.setSiteCode(site);
                n.setMsgType("alert");
                n.setNotifyType("COND_NEW");
                n.setEventId(e.getEventId());
                n.setDataHour(support.toStat(site, end.minusHours(1)));
                n.setChatId(chat.chatId());
                n.setContent(content);
                n.setNow(nowStat);
                engineMapper.insertNotifyLog(n);
            }
        }
        return e.getEventId();
    }

    /** 触发时的规则快照（事件详情的「规则信息」按它展示） */
    Map<String, Object> snapshot(MonitorRule rule, CondSpec spec, CondRules.DimLabels labels, String site)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", rule.getRuleName());
        m.put("ruleCode", ruleCode(rule.getRuleNo()));
        m.put("description", rule.getDescription());
        m.put("ownerEmail", rule.getOwnerEmail());
        m.put("version", rule.getVersion());
        m.put("level", spec.getLevel());
        m.put("matchMode", spec.getMatchMode());
        m.put("window", spec.getWindow().getCode());
        m.put("durationCount", spec.getDurationCount());
        m.put("notifyEnabled", !Boolean.FALSE.equals(rule.getNotifyEnabled()));
        List<Map<String, Object>> chats = new ArrayList<>();
        for (MonitorChats.Chat c : MonitorChats.parse(rule.getNotifyChats(), rule.getNotifyChatId(), rule.getNotifyChatName(), null))
        {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("chatId", c.chatId());
            ch.put("name", c.name());
            chats.add(ch);
        }
        m.put("chats", chats);
        m.put("muteHours", rule.getMuteHours());
        List<Map<String, Object>> conds = new ArrayList<>();
        for (CondCondition c : spec.getConditions())
        {
            conds.add(CondSupport.conditionView(c, CondRules.scopeLabel(c.getScope(), labels)));
        }
        m.put("conditions", conds);
        m.put("summary", CondRules.summary(spec, support.catalog().fields(), labels, support.symbol(site)));
        return m;
    }

    /** 一个检测点的结果（存 detail.triggered / latest） */
    static Map<String, Object> checkpoint(LocalDateTime end, CondEvaluator.Point point)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("end", end.format(MINUTE));
        List<Map<String, Object>> outs = new ArrayList<>();
        for (CondEvaluator.Outcome o : point.outcomes())
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("index", o.index());
            x.put("status", o.status().name());
            x.put("value", CondSupport.round(o.value()));
            x.put("base", CondSupport.round(o.base()));
            x.put("change", CondSupport.round(o.change()));
            x.put("sample", CondSupport.round(o.sample()));
            outs.add(x);
        }
        m.put("outcomes", outs);
        m.put("hit", point.hit());
        return m;
    }

    static String ruleCode(Integer ruleNo)
    {
        return ruleNo == null ? null : String.format("R-%03d", ruleNo);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json)
    {
        if (StringUtils.isEmpty(json))
        {
            return new LinkedHashMap<>();
        }
        try
        {
            return new LinkedHashMap<>(CondSupport.JSON.readValue(json, Map.class));
        }
        catch (RuntimeException e)
        {
            return new LinkedHashMap<>();
        }
    }

    static List<String> streak(String json)
    {
        List<String> out = new ArrayList<>();
        if (StringUtils.isEmpty(json))
        {
            return out;
        }
        try
        {
            for (Object o : CondSupport.JSON.readValue(json, List.class))
            {
                if (o instanceof String s)
                {
                    out.add(s);
                }
            }
        }
        catch (RuntimeException e)
        {
            out.clear();
        }
        return out;
    }
}
