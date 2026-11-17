package com.fivetech.monitor.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.enums.UserTypeEnum;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.cond.CondCondition;
import com.fivetech.monitor.cond.CondDataSource;
import com.fivetech.monitor.cond.CondDims;
import com.fivetech.monitor.cond.CondField;
import com.fivetech.monitor.cond.CondJob;
import com.fivetech.monitor.cond.CondPreviewService;
import com.fivetech.monitor.cond.CondRuleBody;
import com.fivetech.monitor.cond.CondRules;
import com.fivetech.monitor.cond.CondSpec;
import com.fivetech.monitor.cond.CondSupport;
import com.fivetech.monitor.cond.CondWindow;
import com.fivetech.monitor.domain.MonitorCondRow;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorOwner;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorCondMapper;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.mapper.MonitorMetaMapper;
import com.fivetech.monitor.mapper.MonitorSiteMapper;
import com.fivetech.monitor.service.IMonitorRuleService;
import com.fivetech.monitor.service.MonitorChats;

/**
 * 监控管理 · 条件规则（设计文档第 4 节、7.3.1–7.3.2）：字段目录、详情、新建、编辑、删除、触发预览。
 *
 * <p>字段目录来自「指标管理」（MONITOR_COND），维度选项来自配置中心；与指标规则、订单规则共用编号、启停接口与告警规则列表。
 * 新建 / 启用的规则只判定之后的数据（检测状态的 last_data_hour 置为当时的上一小时）。</p>
 *
 * @author fivetech
 */
@Service
public class MonitorCondRuleService
{
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final Map<String, String> GROUPS = Map.of("user", "用戶與獲客", "fund", "資金", "game", "遊戲");

    private final MonitorCondMapper mapper;

    private final MonitorMetaMapper metaMapper;

    private final MonitorLarkChatMapper chatMapper;

    private final MonitorSiteMapper siteMapper;

    private final CondSupport support;

    private final CondDims dims;

    private final CondPreviewService previews;

    private final IMonitorRuleService rules;

    private final CondJob job;

    public MonitorCondRuleService(MonitorCondMapper mapper, MonitorMetaMapper metaMapper, MonitorLarkChatMapper chatMapper,
            MonitorSiteMapper siteMapper, CondSupport support, CondDims dims, CondPreviewService previews, IMonitorRuleService rules,
            CondJob job)
    {
        this.job = job;
        this.mapper = mapper;
        this.metaMapper = metaMapper;
        this.chatMapper = chatMapper;
        this.siteMapper = siteMapper;
        this.support = support;
        this.dims = dims;
        this.previews = previews;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ 字段目录

    /** GET /monitor/cond-rules/fields */
    public Map<String, Object> fields(MonitorScope scope)
    {
        String site = scope.getSiteCode();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (String g : List.of("user", "fund", "game"))
        {
            groups.add(Map.of("code", g, "name", GROUPS.get(g)));
        }
        out.put("groups", groups);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CondField f : support.catalog().fields().values())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.getCode());
            m.put("name", f.getName());
            m.put("group", f.getGroup());
            m.put("valueFormat", f.getValueFormat());
            m.put("unit", MonitorRuleServiceImpl.unit(f.getValueFormat()));
            m.put("additive", f.isAdditive());
            m.put("snapshot", f.isSnapshot());
            m.put("defaultHow", f.getDefaultHow());
            m.put("defaultOp", f.getDefaultOp());
            m.put("dims", availableDims(f.getDims()));
            m.put("caliber", f.getCaliber());
            m.put("absUnit", f.getAbsUnit());
            if (f.getSampleField() == null)
            {
                m.put("sample", null);
            }
            else
            {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("key", f.getSampleKey());
                s.put("name", f.getSampleName());
                s.put("unit", f.getSampleUnit());
                m.put("sample", s);
            }
            fields.add(m);
        }
        out.put("fields", fields);
        Map<String, Object> dimOptions = dims.of(site).options();
        dimOptions.keySet().removeIf(d -> !dimConfigured(d));
        out.put("dims", dimOptions);
        List<Map<String, Object>> hows = new ArrayList<>();
        CondRules.HOW_TEXT.forEach((k, v) -> hows.add(Map.of("code", k, "name", v)));
        out.put("hows", hows);
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("CUR", opList(CondRules.CUR_OPS));
        ops.put("BASE", opList(CondRules.CHANGE_OPS));
        ops.put("YD", opList(CondRules.CHANGE_OPS));
        out.put("ops", ops);
        List<Map<String, Object>> windows = new ArrayList<>();
        for (CondWindow w : CondWindow.values())
        {
            windows.add(Map.of("code", w.getCode(), "name", w.getText(), "durationMax", support.props().durationMax(w.getCode())));
        }
        out.put("windows", windows);
        List<Map<String, Object>> levels = new ArrayList<>();
        CondRules.LEVEL_TEXT.forEach((k, v) -> levels.add(Map.of("code", k, "name", v)));
        out.put("levels", levels);
        out.put("muteHours", support.props().getMuteHours());
        out.put("defaultMuteHours", support.props().getDefaultMuteHours());
        out.put("maxConditions", support.props().getMaxConditions());
        out.put("maxChats", support.props().getMaxChats());
        out.put("currencySymbol", support.symbol(site));
        out.put("dataReady", support.data().available());
        return out;
    }

    private List<String> availableDims(List<String> fieldDims)
    {
        List<String> out = new ArrayList<>();
        for (String d : fieldDims)
        {
            if (dimConfigured(d))
            {
                out.add(d);
            }
        }
        return out;
    }

    /** 维度在 UDS 上有字段才能选（ch 至少要有推广商字段） */
    private boolean dimConfigured(String dim)
    {
        return support.props().dimField("ch".equals(dim) ? "chPromoter" : dim) != null;
    }

    private static List<Map<String, Object>> opList(List<String> codes)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : codes)
        {
            out.add(Map.of("code", c, "name", CondRules.OP_TEXT.get(c)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 详情

    /** GET /monitor/cond-rules/{ruleId} */
    public Map<String, Object> detail(MonitorScope scope, Long ruleId)
    {
        MonitorRule rule = require(scope, ruleId);
        String site = scope.getSiteCode();
        CondSpec spec = support.spec(rule, mapper.selectConditions(scope, List.of(ruleId)));
        CondDims.Snapshot labels = dims.of(site);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", rule.getRuleId());
        m.put("ruleCode", MonitorRuleServiceImpl.ruleCode(rule.getRuleNo()));
        m.put("name", rule.getRuleName());
        m.put("description", rule.getDescription());
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("userId", rule.getOwnerUserId());
        owner.put("email", rule.getOwnerEmail());
        owner.put("valid", Boolean.TRUE.equals(rule.getOwnerValid()));
        m.put("owner", owner);
        m.put("matchMode", spec.getMatchMode());
        m.put("window", spec.getWindow().getCode());
        m.put("durationCount", spec.getDurationCount());
        m.put("level", spec.getLevel());
        m.put("notifyEnabled", !Boolean.FALSE.equals(rule.getNotifyEnabled()));
        m.put("chats", MonitorChats.view(MonitorChats.parse(rule.getNotifyChats(), rule.getNotifyChatId(), rule.getChatName(),
            rule.getChatStatus())));
        m.put("muteHours", rule.getMuteHours());
        List<Map<String, Object>> conds = new ArrayList<>();
        for (CondCondition c : spec.getConditions())
        {
            conds.add(CondSupport.conditionView(c, CondRules.scopeLabel(c.getScope(), labels)));
        }
        m.put("conditions", conds);
        m.put("summary", CondRules.summary(spec, support.catalog().fields(), labels, support.symbol(site)));
        m.put("enabled", Boolean.TRUE.equals(rule.getEnabled()));
        m.put("currency", rule.getCurrency());
        String siteCurrency = support.dashboard().siteCurrency(site);
        m.put("currencyChanged", siteCurrency != null && rule.getCurrency() != null && !siteCurrency.equals(rule.getCurrency()));
        m.put("createBy", StringUtils.isNotEmpty(rule.getCreateByEmail()) ? rule.getCreateByEmail() : rule.getCreateBy());
        m.put("createTime", fmt(support.toSite(site, rule.getCreateTime())));
        m.put("updateBy", StringUtils.isNotEmpty(rule.getUpdateByEmail()) ? rule.getUpdateByEmail() : rule.getUpdateBy());
        m.put("updateTime", fmt(support.toSite(site, rule.getUpdateTime())));
        return m;
    }

    // ------------------------------------------------------------------ 新建 / 编辑 / 删除

    /** POST /monitor/cond-rules：返回 { ruleId, ruleNo, ruleCode } */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(MonitorScope scope, CondRuleBody body, String operator)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        CondRules.Checked checked = CondRules.check(body, support.catalog().fields(), dims.of(scope.getSiteCode()), support.props(), errors);
        checkOwner(scope, body.getOwnerUserId(), null, errors);
        List<MonitorLarkChat> chats = checkChats(scope, checked.notifyChatIds, errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        MonitorRuleServiceImpl.rejectDeadChats(chats, List.of());
        siteMapper.ensureSiteSetting(scope);
        Integer ruleNo = siteMapper.lockNextRuleNo(scope);
        siteMapper.increaseNextRuleNo(scope);
        LocalDateTime now = statNow();
        boolean enabled = body.getEnabled() == null || body.getEnabled();
        MonitorRule rule = new MonitorRule();
        rule.setSiteCode(scope.getSiteCode());
        rule.setRuleNo(ruleNo);
        rule.setRuleType(CondRules.TYPE);
        rule.setOwnerUserId(body.getOwnerUserId());
        apply(rule, checked, chats, scope);
        rule.setEnabled(enabled);
        rule.setPausedAt(enabled ? null : now);
        rule.setCreateBy(operator);
        rule.setCreateTime(now);
        mapper.insertCondRule(rule);
        for (MonitorCondRow r : support.rows(rule.getRuleId(), checked.spec))
        {
            mapper.insertCondition(scope, r);
        }
        // 只判定创建之后的数据
        mapper.resetState(scope, rule.getRuleId(), now.truncatedTo(ChronoUnit.HOURS).minusHours(1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.getRuleId());
        out.put("ruleNo", ruleNo);
        out.put("ruleCode", MonitorRuleServiceImpl.ruleCode(ruleNo));
        return out;
    }

    /** POST /monitor/cond-rules/{ruleId}/update：整条提交，不改启停；已有事件保持原判定，新设置只对之后的检测生效 */
    @Transactional(rollbackFor = Exception.class)
    public void update(MonitorScope scope, Long ruleId, CondRuleBody body, String operator)
    {
        MonitorRule existing = require(scope, ruleId);
        Map<String, String> errors = new LinkedHashMap<>();
        CondRules.Checked checked = CondRules.check(body, support.catalog().fields(), dims.of(scope.getSiteCode()), support.props(), errors);
        checkOwner(scope, body.getOwnerUserId(), existing.getOwnerUserId(), errors);
        List<MonitorLarkChat> chats = checkChats(scope, checked.notifyChatIds, errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        MonitorRuleServiceImpl.rejectDeadChats(chats, MonitorRuleServiceImpl.chatIdsOf(existing));
        MonitorRule rule = new MonitorRule();
        rule.setRuleId(ruleId);
        rule.setOwnerUserId(body.getOwnerUserId());
        apply(rule, checked, chats, scope);
        rule.setUpdateBy(operator);
        rule.setUpdateTime(statNow());
        mapper.updateCondRule(scope, rule);
        mapper.deleteConditions(scope, ruleId);
        for (MonitorCondRow r : support.rows(ruleId, checked.spec))
        {
            mapper.insertCondition(scope, r);
        }
    }

    /** POST /monitor/cond-rules/{ruleId}/delete：同告警规则的删除（结束进行中的事件、清检测状态） */
    public void delete(MonitorScope scope, Long ruleId, String operator)
    {
        require(scope, ruleId);
        rules.deleteRule(scope, ruleId, operator);
    }

    /** POST /monitor/cond-rules/preview：表单校验后回测 */
    public Map<String, Object> preview(MonitorScope scope, CondRuleBody body)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        CondDims.Snapshot labels = dims.of(scope.getSiteCode());
        CondSpec spec = CondRules.spec(body, support.catalog().fields(), labels, support.props(), errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (!support.data().available())
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dataPending", true);
            out.put("message", "條件規則的小時數據尚未接入，暫時無法回測");
            return out;
        }
        try
        {
            return previews.preview(scope.getSiteCode(), spec, labels);
        }
        catch (CondDataSource.CondDataException e)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "回測取數失敗，請稍後再試");
        }
    }

    /**
     * POST /monitor/cond-rules/condition-info：编辑抽屉里某个条件的参考信息（未保存也能查）。
     * body { field, scope, window }；返回 { sample{median, periods, key, name, unit} | null, historyDays, historyAtLeast,
     * baseReady, baseFrom, ydReady, ydFrom }；UDS 未接入为 { dataPending: true }。
     */
    public Map<String, Object> conditionInfo(MonitorScope scope, Map<String, Object> body)
    {
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", in.get("field"));
        m.put("scope", in.get("scope"));
        m.put("how", "CUR");
        m.put("op", "GT");
        m.put("threshold", 0);
        Map<String, String> raw = new LinkedHashMap<>();
        CondCondition c = CondRules.condition(0, m, support.catalog().fields(), dims.of(scope.getSiteCode()), raw);
        raw.forEach((k, v) -> {
            if (k.endsWith(".field") || k.endsWith(".scope"))
            {
                errors.put(k.substring(k.lastIndexOf('.') + 1), v);
            }
        });
        CondWindow w = CondWindow.of(in.get("window") == null ? "1h" : String.valueOf(in.get("window")));
        if (w == null)
        {
            errors.put("window", "檢測時段只能是 " + String.join(" / ", CondWindow.codes()));
        }
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (!support.data().available())
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dataPending", true);
            return out;
        }
        try
        {
            return previews.conditionInfo(scope.getSiteCode(), c, w);
        }
        catch (CondDataSource.CondDataException e)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "取數失敗，請稍後再試");
        }
    }

    /** POST /monitor/cond-rules/{ruleId}/test-alert：按最近一个检测点发一条测试告警到测试群，不写事件 */
    public Map<String, Object> testAlert(MonitorScope scope, Long ruleId, String operator)
    {
        MonitorRule rule = require(scope, ruleId);
        return job.testAlert(scope, rule, mapper.selectConditions(scope, List.of(ruleId)), operator);
    }

    // ------------------------------------------------------------------ 内部

    MonitorRule require(MonitorScope scope, Long ruleId)
    {
        MonitorRule rule = ruleId == null ? null : mapper.selectCondRule(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在");
        }
        return rule;
    }

    private void apply(MonitorRule rule, CondRules.Checked checked, List<MonitorLarkChat> chats, MonitorScope scope)
    {
        rule.setRuleName(checked.name);
        rule.setDescription(checked.description);
        rule.setLevel(checked.spec.getLevel());
        rule.setMuteHours(checked.muteHours);
        rule.setNotifyEnabled(checked.notifyEnabled);
        MonitorRuleServiceImpl.setChats(rule, chats);
        rule.setCurrency(support.dashboard().siteCurrency(scope.getSiteCode()));
        rule.setConfig(support.config(checked.spec));
    }

    private List<MonitorLarkChat> checkChats(MonitorScope scope, List<String> ids, Map<String, String> errors)
    {
        List<MonitorLarkChat> out = new ArrayList<>();
        for (String id : ids)
        {
            MonitorLarkChat chat = chatMapper.selectChat(scope, id);
            if (chat == null)
            {
                errors.putIfAbsent("notifyChatIds", "通知群不存在，請重新選擇");
                return out;
            }
            out.add(chat);
        }
        return out;
    }

    /** 负责人必填且能进入本站（同指标规则）；编辑时保留原负责人不报错 */
    private void checkOwner(MonitorScope scope, Long ownerUserId, Long existingOwnerId, Map<String, String> errors)
    {
        if (ownerUserId == null)
        {
            errors.putIfAbsent("ownerUserId", "請從列表中選擇負責人（輸入郵箱搜尋）");
            return;
        }
        if (ownerUserId.equals(existingOwnerId))
        {
            return;
        }
        MonitorOwner owner = metaMapper.selectOwnerCandidate(scope, ownerUserId);
        String site = support.dashboard().canonical(scope.getSiteCode());
        if (owner == null || site == null || !support.dashboard()
            .accessibleSites(UserTypeEnum.isSuperAdmin(owner.getUserType()), owner.getRoleSiteCodes()).contains(site))
        {
            errors.putIfAbsent("ownerUserId", "負責人不在可選範圍內");
        }
    }

    private LocalDateTime statNow()
    {
        return LocalDateTime.now(support.statZone()).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String fmt(LocalDateTime t)
    {
        return t == null ? null : t.format(MINUTE);
    }
}
