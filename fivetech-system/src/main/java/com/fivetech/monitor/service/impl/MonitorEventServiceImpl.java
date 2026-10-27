package com.fivetech.monitor.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorEventQuery;
import com.fivetech.monitor.domain.MonitorEventRow;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorEventPageVO;
import com.fivetech.monitor.domain.vo.MonitorEventVO;
import com.fivetech.monitor.engine.MonitorMessageBuilder;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorEventMapper;
import com.fivetech.monitor.mapper.MonitorOverviewMapper;
import com.fivetech.monitor.service.IMonitorEventService;
import com.fivetech.monitor.service.MonitorSnapshot;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * 监控管理 · 异常事件：列表、标记已处理、撤销处理。阈值、判定类型、周期一律取事件的规则快照，不读当前规则。
 *
 * @author fivetech
 */
@Service
public class MonitorEventServiceImpl implements IMonitorEventService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorEventServiceImpl.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Set<Integer> PAGE_SIZES = Set.of(20, 30, 50, 100);

    static final int HANDLE_MAX_IDS = 100;

    static final int NOTE_MAX = 500;

    private final MonitorEventMapper eventMapper;

    private final MonitorOverviewMapper overviewMapper;

    private final MonitorMessageBuilder messages;

    private final ObjectMapper json;

    private final ZoneId zone;

    public MonitorEventServiceImpl(MonitorEventMapper eventMapper, MonitorOverviewMapper overviewMapper,
            MonitorMessageBuilder messages, ObjectMapper json, DashboardProperties dashboard)
    {
        this.eventMapper = eventMapper;
        this.overviewMapper = overviewMapper;
        this.messages = messages;
        this.json = json;
        this.zone = ZoneId.of(dashboard.getTimezone());
    }

    @Override
    public MonitorEventPageVO listEvents(MonitorScope scope, String range, String handle, String metricCode, String status,
            Long ownerId, Integer pageNum, Integer pageSize)
    {
        MonitorEventQuery q = new MonitorEventQuery();
        q.setHandle(handleFilter(handle));
        q.setStatus(statusFilter(status));
        q.setMetricCode(StringUtils.trim(metricCode));
        q.setOwnerId(ownerId);
        applyRange(q, range, LocalDate.now(zone));

        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize != null && PAGE_SIZES.contains(pageSize) ? pageSize : 20;

        LocalDateTime lastJudged = overviewMapper.selectLastJudgedHour(scope);
        LocalDateTime asOf = lastJudged == null ? null : lastJudged.plusHours(1);

        MonitorEventPageVO vo = new MonitorEventPageVO();
        vo.setAsOf(fmt(asOf));
        vo.setPendingTotal(overviewMapper.countPendingEvents(scope));
        vo.setPageNum(num);
        vo.setPageSize(size);
        try (Page<MonitorEventRow> page = PageHelper.startPage(num, size))
        {
            List<MonitorEventRow> rows = eventMapper.selectEventList(scope, q);
            vo.setTotal(page.getTotal());
            for (MonitorEventRow row : rows)
            {
                vo.getRows().add(toVO(row, asOf));
            }
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int handle(MonitorScope scope, List<Long> eventIds, String note)
    {
        Set<Long> ids = new LinkedHashSet<>();
        if (eventIds != null)
        {
            for (Long id : eventIds)
            {
                if (id != null)
                {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty())
        {
            throw MonitorFieldException.of("eventIds", "請選擇事件");
        }
        if (ids.size() > HANDLE_MAX_IDS)
        {
            throw MonitorFieldException.of("eventIds", "一次最多處理 " + HANDLE_MAX_IDS + " 條事件");
        }
        String text = note == null ? "" : note.trim();
        if (text.isEmpty())
        {
            throw MonitorFieldException.of("note", "請填寫處理說明");
        }
        if (text.codePointCount(0, text.length()) > NOTE_MAX)
        {
            throw MonitorFieldException.of("note", "處理說明不能超過 " + NOTE_MAX + " 字");
        }
        List<Long> list = new ArrayList<>(ids);
        // 不做部分成功：有一条不属于本站（或不存在）就整体 404
        if (eventMapper.countEvents(scope, list) != list.size())
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "事件不存在");
        }
        return eventMapper.markHandled(scope, list, text, scope.getUserId(), now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unhandle(MonitorScope scope, Long eventId)
    {
        if (eventId == null || eventMapper.unhandle(scope, eventId, now()) == 0)
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "事件不存在");
        }
    }

    // ------------------------------------------------------------------ 组装

    MonitorEventVO toVO(MonitorEventRow e, LocalDateTime asOf)
    {
        MonitorEventVO vo = new MonitorEventVO();
        vo.setEventId(e.getEventId());
        vo.setRuleId(e.getRuleId());
        vo.setRuleNo(e.getRuleNo());
        vo.setRuleCode(MonitorRuleServiceImpl.ruleCode(e.getRuleNo()));
        vo.setRuleDeleted(e.isRuleDeleted());
        vo.setMetricCode(e.getMetricCode());
        vo.setMetricName(com.fivetech.monitor.service.MonitorText.metricName(e.getMetricCode(), e.getMetricName()));
        vo.setUnit(MonitorRuleServiceImpl.unit(e.getValueFormat()));
        vo.setLevel(e.getLevel());
        vo.setEscalated(e.isEscalated());
        vo.setStatus(e.getStatus());
        vo.setCurrentValue(num(e.getCurrentValue()));
        vo.setBaselineValue(num(e.getBaselineValue()));

        JudgeRule judge = null;
        try
        {
            judge = MonitorSnapshot.parse(json, e.getRuleSnapshot());
        }
        catch (RuntimeException ex)
        {
            log.warn("[monitor] 事件 {} 的规则快照无法解析：{}", e.getEventId(), ex.getMessage());
        }
        if (judge != null)
        {
            vo.setJudgeType(judge.getJudgeType());
            vo.setPeriodHours(judge.getPeriodHours());
            Integer esc = judge.getEscalateHours();
            vo.setEscalateHours(esc == null || esc <= 0 ? null : esc);
            String against = MonitorMessageBuilder.against(judge, e.getLevel(), e.isEscalated());
            boolean crit = Levels.CRIT.equals(against);
            vo.setThreshold(new MonitorEventVO.ThresholdVO(against,
                num(crit ? judge.getCritLow() : judge.getWarnLow()), num(crit ? judge.getCritHigh() : judge.getWarnHigh())));
            vo.setJudgeBasis(messages.judgeBasisParts(e.getValueFormat(), judge, e.getLevel(), e.isEscalated(),
                e.getCurrentValue(), e.getBaselineValue()));
        }
        else
        {
            vo.setPeriodHours(1);
            vo.setJudgeBasis(List.of());
        }

        vo.setStartHour(fmt(e.getStartHour()));
        vo.setTriggerHour(fmt(e.getTriggerHour()));
        boolean open = "open".equals(e.getStatus());
        vo.setEndHour(open ? null : fmt(e.getEndHour()));
        vo.setStoppedAt("stopped".equals(e.getStatus()) ? fmt(e.getStoppedAt()) : null);
        LocalDateTime end = open ? (e.getLastPeriodEnd() != null ? e.getLastPeriodEnd() : asOf)
                : (e.getEndHour() != null ? e.getEndHour() : e.getLastPeriodEnd());
        vo.setDurationHours(e.getStartHour() == null || end == null ? 0
                : Math.max(0, ChronoUnit.HOURS.between(e.getStartHour(), end)));
        vo.setDurationTip(MonitorMessageBuilder.durationTip(e.getStatus(), e.isRuleDeleted(), e.getStartHour(),
            e.getEndHour(), e.getStoppedAt()));

        vo.setOwner(e.getOwnerUserId() == null ? null : new MonitorEventVO.OwnerVO(e.getOwnerUserId(), e.getOwnerEmail()));

        MonitorEventVO.NotifyVO notify = new MonitorEventVO.NotifyVO();
        notify.setStatus(e.getNotifyStatus());
        notify.setSentAt(fmt(e.getNotifiedAt()));
        notify.setChatId(e.getChatId());
        notify.setChatName(e.getChatName());
        // 缓存里没有这个群时按失效处理（与规则列表一致）
        notify.setChatStatus(StringUtils.isEmpty(e.getChatId()) ? null : (e.getChatStatus() == null ? "dead" : e.getChatStatus()));
        if ("failed".equals(e.getNotifyStatus()))
        {
            notify.setError(StringUtils.isEmpty(e.getNotifyError()) ? "發送失敗" : e.getNotifyError());
        }
        if ("paused".equals(e.getNotifyStatus()))
        {
            notify.setPausedReason(com.fivetech.monitor.engine.MonitorNotifySender.PAUSED_REASON);
        }
        if ("muted".equals(e.getNotifyStatus()))
        {
            notify.setMuted(new MonitorEventVO.MutedVO(fmt(e.getMuteSentAt()), e.getMuteHours()));
        }
        vo.setNotify(notify);

        MonitorEventVO.HandleVO handle = new MonitorEventVO.HandleVO();
        handle.setStatus(e.getHandleStatus());
        handle.setNote(e.getHandleNote());
        handle.setBy(e.getHandledBy());
        handle.setAt(fmt(e.getHandledAt()));
        handle.setReset(e.isHandleReset());
        vo.setHandle(handle);
        return vo;
    }

    // ------------------------------------------------------------------ 筛选

    /** 默认 pending；all = 不筛 */
    static String handleFilter(String handle)
    {
        String h = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(h))
        {
            return null;
        }
        return "done".equals(h) ? "done" : "pending";
    }

    static String statusFilter(String status)
    {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return "open".equals(s) || "resolved".equals(s) || "stopped".equals(s) ? s : null;
    }

    /** range → trigger_hour 区间（IST 自然日）；all / 未知值 = 不限 */
    static void applyRange(MonitorEventQuery q, String range, LocalDate today)
    {
        String r = range == null ? "" : range.trim().toLowerCase(Locale.ROOT);
        LocalDateTime start = today.atStartOfDay();
        switch (r)
        {
            case "today":
                q.setRangeFrom(start);
                break;
            case "yest":
                q.setRangeFrom(start.minusDays(1));
                q.setRangeTo(start);
                break;
            case "d7":
                q.setRangeFrom(start.minusDays(6));
                break;
            case "d30":
                q.setRangeFrom(start.minusDays(29));
                break;
            default:
                break;
        }
    }

    private LocalDateTime now()
    {
        return LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String fmt(LocalDateTime t)
    {
        return t == null ? null : t.format(MINUTE);
    }

    /** 去掉 NUMERIC(18,4) 的尾零 */
    private static BigDecimal num(BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
