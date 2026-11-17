package com.fivetech.monitor.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.fivetech.common.enums.UserTypeEnum;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorOrderRuleFilter;
import com.fivetech.monitor.domain.MonitorOwner;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.OrderRuleConfig;
import com.fivetech.monitor.domain.OrderRuleOpenQuery;
import com.fivetech.monitor.domain.dto.MonitorOrderRuleBody;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.mapper.MonitorMetaMapper;
import com.fivetech.monitor.mapper.MonitorOrderRuleMapper;
import com.fivetech.monitor.mapper.MonitorSiteMapper;
import com.fivetech.monitor.service.MonitorOrderRules;
import com.fivetech.promo.budget.PromoChannelDictionary;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单规则：后台新建 / 编辑 / 详情，以及给判定服务的对外查询（设计文档 5.1、5.3）。
 *
 * <ul>
 *   <li>与指标规则同一张表、同一套编号（monitor_site_setting.next_rule_no 加行锁取号）；启停复用
 *       POST /monitor/rules/{ruleId}/enabled，不提供删除。</li>
 *   <li>Yata 只存规则，不判定：不占指标规则的启用上限，没有运行状态和异常事件。</li>
 *   <li>每次新建、修改、启停，version 从序列 monitor_rule_version_seq 取新值，判定服务按 sinceVersion 增量同步。</li>
 *   <li>时间：库里按统计时区（dashboard.timezone）存，同监控其他表；对外返回和筛选都按站点时区。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class MonitorOrderRuleService
{
    private final MonitorOrderRuleMapper mapper;

    private final MonitorMetaMapper metaMapper;

    private final MonitorLarkChatMapper chatMapper;

    private final MonitorSiteMapper siteMapper;

    private final PromoChannelDictionary channels;

    private final DashboardProperties properties;

    private final ObjectMapper json;

    public MonitorOrderRuleService(MonitorOrderRuleMapper mapper, MonitorMetaMapper metaMapper, MonitorLarkChatMapper chatMapper,
            MonitorSiteMapper siteMapper, PromoChannelDictionary channels, DashboardProperties properties, ObjectMapper json)
    {
        this.mapper = mapper;
        this.metaMapper = metaMapper;
        this.chatMapper = chatMapper;
        this.siteMapper = siteMapper;
        this.channels = channels;
        this.properties = properties;
        this.json = json;
    }

    // ------------------------------------------------------------------ 后台

    /** 新建：返回 { ruleId, ruleNo, ruleCode } */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(MonitorScope scope, MonitorOrderRuleBody body, String operator)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRules.Checked checked = MonitorOrderRules.check(body, channels.promotersOf(scope.getSiteCode()), null, errors);
        checkOwner(scope, body.getOwnerUserId(), null, errors);
        List<MonitorLarkChat> chats = checkChats(scope, checked.notifyChatIds, errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        MonitorRuleServiceImpl.rejectDeadChats(chats, List.of());

        // 取号：锁住本站设置行，与指标规则共用编号，删除后不复用
        siteMapper.ensureSiteSetting(scope);
        Integer ruleNo = siteMapper.lockNextRuleNo(scope);
        siteMapper.increaseNextRuleNo(scope);

        LocalDateTime now = now();
        boolean enabled = body.getEnabled() == null || body.getEnabled();
        MonitorRule rule = new MonitorRule();
        rule.setSiteCode(scope.getSiteCode());
        rule.setRuleNo(ruleNo);
        rule.setRuleType(MonitorOrderRules.TYPE);
        rule.setOwnerUserId(body.getOwnerUserId());
        apply(rule, checked, chats, scope);
        rule.setEnabled(enabled);
        rule.setPausedAt(enabled ? null : now);
        rule.setCreateBy(operator);
        rule.setCreateTime(now);
        mapper.insertOrderRule(rule);
        fire(scope, rule.getRuleId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.getRuleId());
        out.put("ruleNo", ruleNo);
        out.put("ruleCode", MonitorOrderRules.ruleCode(ruleNo));
        return out;
    }

    /** 编辑：整条提交（同新建），不改启停；原来的群已失效时可以保留 */
    @Transactional(rollbackFor = Exception.class)
    public void update(MonitorScope scope, Long ruleId, MonitorOrderRuleBody body, String operator)
    {
        MonitorRule existing = require(scope, ruleId);
        Map<String, String> errors = new LinkedHashMap<>();
        MonitorOrderRules.Checked checked = MonitorOrderRules.check(body, channels.promotersOf(scope.getSiteCode()),
                config(existing), errors);
        checkOwner(scope, body.getOwnerUserId(), existing.getOwnerUserId(), errors);
        List<MonitorLarkChat> chats = checkChats(scope, checked.notifyChatIds, errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        // 原来已选的群失效了可以保留，只是不能新选
        MonitorRuleServiceImpl.rejectDeadChats(chats, MonitorRuleServiceImpl.chatIdsOf(existing));
        MonitorRule rule = new MonitorRule();
        rule.setRuleId(ruleId);
        rule.setOwnerUserId(body.getOwnerUserId());
        apply(rule, checked, chats, scope);
        rule.setUpdateBy(operator);
        rule.setUpdateTime(now());
        mapper.updateOrderRule(scope, rule);
        fire(scope, ruleId);
    }

    /** 后台详情：结构同对外查询的一条 */
    public Map<String, Object> detail(MonitorScope scope, Long ruleId)
    {
        return view(require(scope, ruleId), scope.getSiteCode());
    }

    /**
     * 测试告警（FY-95 R7 / FY-91 R9）：用一笔示例订单拼一张订单告警卡片，正文加「【測試】」，发到规则当前所有正常的群。
     * 不写订单告警事件、不占每日上限；规则暂停时也能发。
     *
     * @return { success, chatId, messageId, error, chats[{chatId, name, status, messageId, error}], ruleId, ruleCode, content }
     */
    public Map<String, Object> testAlert(MonitorScope scope, Long ruleId, String operator)
    {
        if (testSender == null)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "測試發送不可用");
        }
        MonitorRule rule = require(scope, ruleId);
        String site = scope.getSiteCode();
        OrderRuleConfig cfg = config(rule);
        String symbol = properties.siteCurrency(site) != null && properties.siteCurrency(site).equals(rule.getCurrency())
            ? properties.siteCurrencySymbol(site) : rule.getCurrency();
        boolean daily = MonitorOrderRules.DAILY_TOTAL.equals(cfg.getAmountMode());
        boolean paid = MonitorOrderRules.PAID.equals(cfg.getTriggerAt());
        java.math.BigDecimal threshold = cfg.getAmountThreshold() == null ? java.math.BigDecimal.ZERO : cfg.getAmountThreshold();
        String code = MonitorOrderRules.ruleCode(rule.getRuleNo());
        String siteName = properties.siteName(site);
        StringBuilder sb = new StringBuilder(com.fivetech.monitor.engine.MonitorTestSender.TEST_PREFIX);
        if (StringUtils.isNotEmpty(siteName))
        {
            sb.append("【").append(siteName).append("】");
        }
        sb.append("Yata 訂單告警 · ").append("CRIT".equals(rule.getLevel()) ? "嚴重" : "注意").append('\n');
        sb.append("規則：").append(rule.getRuleName()).append(" · ").append(code).append('\n');
        sb.append("條件：").append(MonitorOrderRules.summary(cfg, symbol)).append('\n');
        sb.append("訂單：TEST-000001（示例）· 本筆 ").append(MonitorOrderRules.money(threshold, symbol));
        if (daily)
        {
            sb.append(" · 當日累計 3 筆 ").append(MonitorOrderRules.money(threshold.multiply(java.math.BigDecimal.valueOf(3)), symbol));
        }
        sb.append('\n');
        sb.append(paid ? "出款時間：" : "提交時間：")
            .append(properties.toSiteTime(site, now()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("這是一條測試消息，不會記錄訂單告警。");
        String content = sb.toString();
        Map<String, Object> sent = testSender.send(scope, rule.getNotifyChats(), rule.getNotifyChatId(), rule.getNotifyChatName(), content);
        Map<String, Object> out = new LinkedHashMap<>(sent);
        out.put("ruleId", rule.getRuleId());
        out.put("ruleCode", code);
        out.put("content", content);
        return out;
    }

    private com.fivetech.monitor.engine.MonitorTestSender testSender;

    /** 规则变更事件 → MSK；没配 spring.kafka.bootstrap-servers 时为 null，不发 */
    private com.fivetech.monitor.order.OrderRuleEventPublisher events;

    /** setter 注入，避免改构造器 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(com.fivetech.monitor.order.OrderRuleEventPublisher events)
    {
        this.events = events;
    }

    /** 规则变更后通知判定服务（事务提交后才真正发） */
    void fire(MonitorScope scope, Long ruleId)
    {
        if (events != null)
        {
            events.publish(scope, ruleId);
        }
    }

    /** setter 注入，避免改构造器 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTestSender(com.fivetech.monitor.engine.MonitorTestSender testSender)
    {
        this.testSender = testSender;
    }

    // ------------------------------------------------------------------ 对外查询

    /**
     * 按条件查列表（分页）：{ rows, total, pageNum, pageSize, maxVersion }。
     *
     * <p>maxVersion = 本页规则里最大的 version（本页为空时为请求的 sinceVersion，没传为 0）。
     * 按 version 升序分页拉完后，以最后一页的 maxVersion 作为下次的 sinceVersion。</p>
     */
    public Map<String, Object> list(MonitorScope scope, OrderRuleOpenQuery query)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        ZoneId site = properties.siteZone(scope.getSiteCode());
        ZoneId stat = statZone();
        MonitorOrderRuleFilter f = MonitorOrderRules.filter(query,
                t -> t.atZone(site).withZoneSameInstant(stat).toLocalDateTime(), errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long total;
        long maxVersion = f.sinceVersion == null ? 0L : f.sinceVersion;
        try (Page<MonitorRule> page = PageHelper.startPage(f.pageNum, f.pageSize))
        {
            List<MonitorRule> rules = mapper.selectOrderRuleList(scope, f);
            total = page.getTotal();
            for (MonitorRule r : rules)
            {
                rows.add(view(r, scope.getSiteCode()));
                if (r.getVersion() != null && r.getVersion() > maxVersion)
                {
                    maxVersion = r.getVersion();
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("pageNum", f.pageNum);
        out.put("pageSize", f.pageSize);
        out.put("maxVersion", maxVersion);
        return out;
    }

    /** 单条：ref 为规则 ID（数字）或编号（R-023）；不存在、已删除、不是订单规则或不属于本站 → 404 */
    public Map<String, Object> get(MonitorScope scope, String ref)
    {
        Long ruleId = MonitorOrderRules.ruleIdOf(ref);
        Integer ruleNo = ruleId == null ? MonitorOrderRules.ruleNoOf(ref) : null;
        if (ruleId == null && ruleNo == null)
        {
            throw MonitorFieldException.of("ruleId", "ruleId 須是規則 ID（數字）或編號（如 R-023）");
        }
        MonitorRule rule = ruleId != null ? mapper.selectOrderRuleById(scope, ruleId) : mapper.selectOrderRuleByNo(scope, ruleNo);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在、已刪除或不屬於當前站點");
        }
        return view(rule, scope.getSiteCode());
    }

    // ------------------------------------------------------------------ 内部

    private MonitorRule require(MonitorScope scope, Long ruleId)
    {
        MonitorRule rule = ruleId == null ? null : mapper.selectOrderRuleById(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在");
        }
        return rule;
    }

    private void apply(MonitorRule rule, MonitorOrderRules.Checked checked, List<MonitorLarkChat> chats, MonitorScope scope)
    {
        rule.setRuleName(checked.name);
        rule.setDescription(checked.description);
        rule.setLevel(checked.level);
        rule.setNotifyEnabled(checked.notifyEnabled);
        MonitorRuleServiceImpl.setChats(rule, chats);
        // 保存即按当前站点币种确认门槛
        rule.setCurrency(properties.siteCurrency(scope.getSiteCode()));
        rule.setConfig(json.writeValueAsString(checked.config));
    }

    /** 一条规则的对外结构；Kafka 事件里的 rule 快照也用它（{@link com.fivetech.monitor.order.OrderRuleEventPublisher}） */
    public Map<String, Object> view(MonitorRule rule, String siteCode)
    {
        MonitorOrderRules.Site site = new MonitorOrderRules.Site();
        site.siteCode = siteCode;
        site.siteName = properties.siteName(siteCode);
        site.timezone = properties.siteTimezone(siteCode);
        site.currency = properties.siteCurrency(siteCode);
        site.currencySymbol = properties.siteCurrencySymbol(siteCode);
        site.toSiteTime = t -> properties.toSiteTime(siteCode, t);
        return MonitorOrderRules.view(rule, config(rule), site);
    }

    OrderRuleConfig config(MonitorRule rule)
    {
        return StringUtils.isEmpty(rule.getConfig()) ? new OrderRuleConfig() : json.readValue(rule.getConfig(), OrderRuleConfig.class);
    }

    /** 通知群：选了的都要在本站群列表缓存里（前端从 GET /monitor/lark/chats 选）；个数、是否必填由 MonitorOrderRules.check 判断 */
    private List<MonitorLarkChat> checkChats(MonitorScope scope, List<String> ids, Map<String, String> errors)
    {
        List<MonitorLarkChat> out = new ArrayList<>();
        for (String id : ids)
        {
            MonitorLarkChat chat = chatMapper.selectChat(scope, id);
            if (chat == null)
            {
                errors.putIfAbsent("notifyChatIds", "通知群不存在，請重新選擇");
                errors.putIfAbsent("notifyChatId", "通知群不存在，請重新選擇");
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
        String site = properties.canonical(scope.getSiteCode());
        if (owner == null || site == null
            || !properties.accessibleSites(UserTypeEnum.isSuperAdmin(owner.getUserType()), owner.getRoleSiteCodes()).contains(site))
        {
            errors.putIfAbsent("ownerUserId", "負責人不在可選範圍內");
        }
    }

    private ZoneId statZone()
    {
        String tz = properties.getTimezone();
        return ZoneId.of(StringUtils.isEmpty(tz) ? "Asia/Kolkata" : tz.trim());
    }

    /** 同指标规则：按统计时区（dashboard.timezone）记时间 */
    private LocalDateTime now()
    {
        return LocalDateTime.now(statZone());
    }
}
