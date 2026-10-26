package com.fivetech.monitor.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.fivetech.common.enums.UserTypeEnum;
import com.fivetech.common.core.redis.RedisCache;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.vo.PageResultVO;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorMetricOption;
import com.fivetech.monitor.domain.MonitorOccupiedMetric;
import com.fivetech.monitor.domain.MonitorOwner;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorRuleQuery;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.dto.MonitorRuleBody;
import com.fivetech.monitor.domain.dto.Threshold;
import com.fivetech.monitor.domain.vo.MinSampleVO;
import com.fivetech.monitor.domain.vo.MonitorMetricVO;
import com.fivetech.monitor.domain.vo.MonitorOwnerVO;
import com.fivetech.monitor.domain.vo.MonitorRuleCreatedVO;
import com.fivetech.monitor.domain.vo.MonitorRuleDetailVO;
import com.fivetech.monitor.domain.vo.MonitorRuleVO;
import com.fivetech.monitor.domain.vo.NotifyChatVO;
import com.fivetech.monitor.domain.vo.OccupiedByVO;
import com.fivetech.monitor.domain.vo.OwnerVO;
import com.fivetech.monitor.domain.vo.ThresholdVO;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.mapper.MonitorMetaMapper;
import com.fivetech.monitor.mapper.MonitorRuleMapper;
import com.fivetech.monitor.mapper.MonitorSiteMapper;
import com.fivetech.monitor.service.IMonitorRuleService;
import com.fivetech.monitor.service.MonitorCacheKeys;
import com.fivetech.monitor.service.MonitorRuleValidator;

/**
 * 告警规则服务实现。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>数据范围只按站点（2026-10-20 按原型调整）：有页面权限的人看得到、改得了本站全部规则；负责人只用于展示和筛选。</li>
 *   <li>一个指标一条规则：先查占用给出 4801；并发新建由唯一索引 uk_monitor_rule_metric 兜底，同样转成 4801。</li>
 *   <li>规则号按站点自增：{@code monitor_site_setting.next_rule_no} 加行锁取号，删除后不复用。</li>
 *   <li>通知群直接存 Lark chat_id：必须在本站群列表缓存里且未失效；修改时原来的群已失效可以保留。</li>
 *   <li>修改只对之后的小时生效：已有事件的 rule_snapshot 不变；改了周期或持续次数时清掉运行状态，连续计数重新累计。</li>
 *   <li>暂停 / 删除：异常中的事件改为 stopped，end_hour = 本站最近一个已判定小时。</li>
 *   <li>数据库是唯一的真实来源：事务提交后才清 Redis 缓存，清失败只记日志。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class MonitorRuleServiceImpl implements IMonitorRuleService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorRuleServiceImpl.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 分页条数只允许这几档，与明细查询一致 */
    private static final Set<Integer> PAGE_SIZES = Set.of(20, 30, 50, 100);

    private final MonitorRuleMapper ruleMapper;

    private final MonitorMetaMapper metaMapper;

    private final MonitorLarkChatMapper chatMapper;

    private final MonitorSiteMapper siteMapper;

    private final MonitorRuleValidator validator;

    private final RedisCache redisCache;

    private final MonitorProperties monitorProperties;

    private final DashboardProperties properties;

    private final ZoneId zone;

    public MonitorRuleServiceImpl(MonitorRuleMapper ruleMapper, MonitorMetaMapper metaMapper,
            MonitorLarkChatMapper chatMapper, MonitorSiteMapper siteMapper, MonitorRuleValidator validator,
            RedisCache redisCache, DashboardProperties properties, MonitorProperties monitorProperties)
    {
        this.ruleMapper = ruleMapper;
        this.metaMapper = metaMapper;
        this.chatMapper = chatMapper;
        this.siteMapper = siteMapper;
        this.validator = validator;
        this.redisCache = redisCache;
        this.monitorProperties = monitorProperties;
        this.properties = properties;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    // ------------------------------------------------------------------ 查询

    @Override
    public PageResultVO<MonitorRuleVO> listRules(MonitorScope scope, MonitorRuleQuery query)
    {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() != null && PAGE_SIZES.contains(query.getPageSize()) ? query.getPageSize() : 20;
        List<MonitorRuleVO> rows = new ArrayList<>();
        long total;
        try (Page<MonitorRule> page = PageHelper.startPage(pageNum, pageSize))
        {
            List<MonitorRule> rules = ruleMapper.selectRuleList(scope, query);
            total = page.getTotal();
            for (MonitorRule rule : rules)
            {
                MonitorRuleVO vo = new MonitorRuleVO();
                fillRuleVO(vo, rule);
                rows.add(vo);
            }
        }
        return PageResultVO.of(rows, total, pageNum, pageSize);
    }

    @Override
    public MonitorRuleDetailVO getRule(MonitorScope scope, Long ruleId)
    {
        MonitorRule rule = requireRule(scope, ruleId);
        MonitorRuleDetailVO vo = new MonitorRuleDetailVO();
        fillRuleVO(vo, rule);
        MinSampleVO minSample = new MinSampleVO();
        minSample.setKey(rule.getMinSampleKey());
        minSample.setLabel(rule.getMinSampleLabel());
        minSample.setValue(num(rule.getMinSampleValue()));
        vo.setMinSample(minSample);
        vo.setCreateBy(emailOr(rule.getCreateByEmail(), rule.getCreateBy()));
        vo.setCreateTime(fmt(rule.getCreateTime()));
        return vo;
    }

    @Override
    public List<MonitorMetricVO> listMetrics(MonitorScope scope)
    {
        Map<String, MonitorOccupiedMetric> occupied = new HashMap<>();
        for (MonitorOccupiedMetric o : ruleMapper.selectOccupiedMetrics(scope))
        {
            occupied.put(o.getMetricCode(), o);
        }
        List<MonitorMetricVO> result = new ArrayList<>();
        for (MonitorMetricOption m : metaMapper.selectMonitorableMetrics())
        {
            MonitorMetricVO vo = new MonitorMetricVO();
            vo.setMetricCode(m.getMetricCode());
            vo.setMetricName(m.getMetricName());
            vo.setUnit(unit(m.getValueFormat()));
            vo.setJudgeType(m.getJudgeType());
            MinSampleVO minSample = new MinSampleVO();
            minSample.setKey(m.getMinSampleKey());
            minSample.setLabel(m.getMinSampleLabel());
            minSample.setDefaultValue(num(m.getMinSampleDefault() == null ? BigDecimal.ZERO : m.getMinSampleDefault()));
            vo.setMinSample(minSample);
            vo.setAllowNegative(m.isAllowNegative());
            MonitorOccupiedMetric o = occupied.get(m.getMetricCode());
            vo.setOccupied(o != null);
            if (o != null)
            {
                OccupiedByVO by = new OccupiedByVO();
                by.setRuleCode(ruleCode(o.getRuleNo()));
                by.setOwnerEmail(o.getOwnerEmail());
                vo.setOccupiedBy(by);
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<MonitorOwnerVO> listOwners(MonitorScope scope)
    {
        List<MonitorOwnerVO> result = new ArrayList<>();
        for (MonitorOwner o : metaMapper.selectOwnerCandidates(scope))
        {
            if (!ownerInSite(o, scope.getSiteCode()))
            {
                continue;
            }
            MonitorOwnerVO vo = new MonitorOwnerVO();
            vo.setUserId(o.getUserId());
            vo.setEmail(o.getEmail());
            result.add(vo);
        }
        return result;
    }

    // ------------------------------------------------------------------ 写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MonitorRuleCreatedVO createRule(MonitorScope scope, MonitorRuleBody body, String operator)
    {
        Map<String, String> errors = new LinkedHashMap<>();

        // 第 1 条：指标必须可监控，且本站还没有规则
        String metricCode = StringUtils.trim(body.getMetricCode());
        if (StringUtils.isEmpty(metricCode))
        {
            throw MonitorFieldException.of("metricCode", "請選擇指標");
        }
        MonitorMetricOption metric = metaMapper.selectMonitorableMetric(metricCode);
        if (metric == null)
        {
            throw MonitorFieldException.of("metricCode", "該指標不能設定告警");
        }
        for (MonitorOccupiedMetric o : ruleMapper.selectOccupiedMetrics(scope))
        {
            if (metricCode.equals(o.getMetricCode()))
            {
                throw new MonitorException(MonitorErrorCode.METRIC_OCCUPIED, "該指標已有規則 " + ruleCode(o.getRuleNo()));
            }
        }

        // 第 2–10 条
        MonitorRuleValidator.Result checked = validator.validate(body, metric, metric.getJudgeType(), errors);
        // 第 11、12 条
        MonitorLarkChat chat = checkChat(scope, body.getNotifyChatId(), errors);
        checkOwner(scope, body.getOwnerUserId(), null, errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (chat.isDead())
        {
            throw new MonitorException(MonitorErrorCode.CHAT_DEAD, "通知群已失效");
        }

        // 取号：锁住本站设置行，规则号从 1 自增，删除后不复用
        siteMapper.ensureSiteSetting(scope);
        Integer ruleNo = siteMapper.lockNextRuleNo(scope);
        siteMapper.increaseNextRuleNo(scope);

        LocalDateTime now = now();
        boolean enabled = body.getEnabled() == null || body.getEnabled();
        if (enabled)
        {
            // 已持有本站设置行的锁，并发新建 / 启用在这里串行，计数准确
            checkEnabledLimit(scope);
        }
        MonitorRule rule = new MonitorRule();
        rule.setSiteCode(scope.getSiteCode());
        rule.setRuleNo(ruleNo);
        rule.setMetricCode(metricCode);
        rule.setOwnerUserId(body.getOwnerUserId());
        rule.setJudgeType(metric.getJudgeType());
        rule.setMinSampleKey(metric.getMinSampleKey());
        applyParams(rule, body, checked, chat);
        rule.setEnabled(enabled);
        rule.setPausedAt(enabled ? null : now);
        rule.setCreateBy(operator);
        rule.setCreateTime(now);
        try
        {
            ruleMapper.insertRule(rule);
        }
        catch (DuplicateKeyException e)
        {
            // 两个人同时为同一指标建规则：唯一索引拦下后一个
            log.warn("并发新建规则被唯一索引拦下 site={} metric={}", scope.getSiteCode(), metricCode);
            throw new MonitorException(MonitorErrorCode.METRIC_OCCUPIED, "該指標已有規則");
        }
        if (enabled)
        {
            resetState(scope, rule.getRuleId(), now);
        }
        evictAfterCommit(scope.getSiteCode());

        MonitorRuleCreatedVO vo = new MonitorRuleCreatedVO();
        vo.setRuleId(rule.getRuleId());
        vo.setRuleNo(ruleNo);
        vo.setRuleCode(ruleCode(ruleNo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRule(MonitorScope scope, Long ruleId, MonitorRuleBody body, String operator)
    {
        MonitorRule existing = requireRule(scope, ruleId);
        Map<String, String> errors = new LinkedHashMap<>();

        String metricCode = StringUtils.trim(body.getMetricCode());
        if (StringUtils.isNotEmpty(metricCode) && !metricCode.equals(existing.getMetricCode()))
        {
            errors.put("metricCode", "不能修改指標");
        }

        // 判定类型用规则里的快照，保证已有规则的阈值含义不变
        MonitorRuleValidator.Result checked = validator.validate(body, metricOf(existing), existing.getJudgeType(), errors);
        MonitorLarkChat chat = checkChat(scope, body.getNotifyChatId(), errors);
        checkOwner(scope, body.getOwnerUserId(), existing.getOwnerUserId(), errors);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        // 原来的群已失效可以保留，只是不能新选
        if (chat.isDead() && !Objects.equals(chat.getChatId(), existing.getNotifyChatId()))
        {
            throw new MonitorException(MonitorErrorCode.CHAT_DEAD, "通知群已失效");
        }

        LocalDateTime now = now();
        MonitorRule rule = new MonitorRule();
        rule.setRuleId(existing.getRuleId());
        rule.setOwnerUserId(body.getOwnerUserId());
        applyParams(rule, body, checked, chat);
        rule.setUpdateBy(operator);
        rule.setUpdateTime(now);
        ruleMapper.updateRule(scope, rule);

        boolean countingChanged = !Objects.equals(existing.getPeriodHours(), rule.getPeriodHours())
                || !Objects.equals(existing.getDurationCount(), rule.getDurationCount());
        if (countingChanged)
        {
            // 改了检测周期或持续次数：连续计数清零重新累计；异常中的事件保持，下一小时按新阈值判定
            if (Boolean.TRUE.equals(existing.getEnabled()))
            {
                resetState(scope, ruleId, now);
            }
            else
            {
                ruleMapper.deleteRuleState(scope, ruleId);
            }
        }
        if (body.getEnabled() != null && body.getEnabled() != Boolean.TRUE.equals(existing.getEnabled()))
        {
            applyEnabled(scope, ruleId, body.getEnabled(), operator, now);
        }
        evictAfterCommit(scope.getSiteCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(MonitorScope scope, Long ruleId, boolean enabled, String operator)
    {
        MonitorRule existing = requireRule(scope, ruleId);
        if (enabled == Boolean.TRUE.equals(existing.getEnabled()))
        {
            return;
        }
        applyEnabled(scope, ruleId, enabled, operator, now());
        evictAfterCommit(scope.getSiteCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(MonitorScope scope, Long ruleId, String operator)
    {
        requireRule(scope, ruleId);
        LocalDateTime now = now();
        ruleMapper.deleteRuleLogically(scope, ruleId, operator, now);
        stopEvents(scope, ruleId, now);
        ruleMapper.deleteRuleState(scope, ruleId);
        evictAfterCommit(scope.getSiteCode());
    }

    // ------------------------------------------------------------------ 内部

    private MonitorRule requireRule(MonitorScope scope, Long ruleId)
    {
        MonitorRule rule = ruleId == null ? null : ruleMapper.selectRuleById(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在");
        }
        return rule;
    }

    /**
     * 暂停：异常中的事件改为 stopped，记暂停时间，删掉运行状态；
     * 启用：先检查本站启用规则数上限，再把运行状态重置为「已处理到当前小时的上一小时」，
     * 只判定启用之后才结束的小时，连续计数从 0 算。
     */
    private void applyEnabled(MonitorScope scope, Long ruleId, boolean enabled, String operator, LocalDateTime now)
    {
        if (enabled)
        {
            // 锁住本站设置行，让并发启用 / 新建串行，避免两个请求同时通过上限检查
            siteMapper.ensureSiteSetting(scope);
            siteMapper.lockNextRuleNo(scope);
            checkEnabledLimit(scope);
        }
        ruleMapper.updateEnabled(scope, ruleId, enabled, enabled ? null : now, operator, now);
        if (enabled)
        {
            resetState(scope, ruleId, now);
        }
        else
        {
            stopEvents(scope, ruleId, now);
            ruleMapper.deleteRuleState(scope, ruleId);
        }
    }

    /**
     * 一期每个站点同时启用的规则不超过 monitor.max-enabled-rules-per-site（默认 40）。
     * 一个指标只能有一条规则由唯一索引 (site_code, metric_code) 保证，所以实际上限也不会超过可监控指标数。
     * 判定任务每个站点每轮只发一次 UDS 请求取全部指标，这个上限同时限住了单次请求的字段数。
     */
    private void checkEnabledLimit(MonitorScope scope)
    {
        int limit = monitorProperties.getMaxEnabledRulesPerSite();
        int enabledCount = ruleMapper.countEnabledRules(scope);
        if (limit > 0 && enabledCount >= limit)
        {
            throw new MonitorException(MonitorErrorCode.RULE_LIMIT,
                    "本站啟用中的規則已達上限 " + limit + " 條，請先暫停其他規則");
        }
    }

    /** 运行状态重置：连续计数清零，已处理到当前小时的上一小时（该小时在启用前已结束，不判定） */
    private void resetState(MonitorScope scope, Long ruleId, LocalDateTime now)
    {
        ruleMapper.resetRuleState(scope, ruleId, now.truncatedTo(ChronoUnit.HOURS).minusHours(1), now);
    }

    /** 异常中的事件 → stopped，end_hour = 本站最近一个已判定小时（还没判定过时取当前整点） */
    private void stopEvents(MonitorScope scope, Long ruleId, LocalDateTime now)
    {
        LocalDateTime endHour = siteMapper.selectLastJudgedHour(scope);
        if (endHour == null)
        {
            endHour = now.truncatedTo(ChronoUnit.HOURS);
        }
        ruleMapper.stopOpenEvents(scope, ruleId, endHour, now);
    }

    /**
     * 第 11 条：通知群必填，且在本站的群列表缓存里（前端从 GET /monitor/lark/chats 选，该接口会刷新缓存）。
     * 是否失效由调用方判断：修改时原来的群已失效可以保留。
     */
    private MonitorLarkChat checkChat(MonitorScope scope, String chatId, Map<String, String> errors)
    {
        String id = StringUtils.trim(chatId);
        if (StringUtils.isEmpty(id))
        {
            errors.putIfAbsent("notifyChatId", "請選擇通知群");
            return null;
        }
        MonitorLarkChat chat = chatMapper.selectChat(scope, id);
        if (chat == null)
        {
            errors.putIfAbsent("notifyChatId", "通知群不存在，請重新選擇");
        }
        return chat;
    }

    /** 第 12 条：负责人必填且在可选范围内；修改时保留原负责人不报错（失效负责人由其他人改掉） */
    void checkOwner(MonitorScope scope, Long ownerUserId, Long existingOwnerId, Map<String, String> errors)
    {
        if (ownerUserId == null)
        {
            errors.putIfAbsent("ownerUserId", "請選擇負責人");
            return;
        }
        if (ownerUserId.equals(existingOwnerId))
        {
            return;
        }
        if (!ownerInSite(metaMapper.selectOwnerCandidate(scope, ownerUserId), scope.getSiteCode()))
        {
            errors.putIfAbsent("ownerUserId", "負責人不在可選範圍內");
        }
    }

    /**
     * 负责人候选必须能进入本站：与登录后可切换的站点同一规则（超级管理员全部站点；站点角色所属站点；
     * 通用角色或没有站点角色 → 默认站点）。否则别的站点的人会出现在下拉里、也能被设成负责人。
     */
    boolean ownerInSite(MonitorOwner owner, String siteCode)
    {
        if (owner == null)
        {
            return false;
        }
        String site = properties.canonical(siteCode);
        return site != null && properties.accessibleSites(UserTypeEnum.isSuperAdmin(owner.getUserType()),
                owner.getRoleSiteCodes()).contains(site);
    }

    /** 修改时取指标的单位与是否允许负数；指标后来被设为不可监控时退回规则里带出的展示格式 */
    private MonitorMetricOption metricOf(MonitorRule rule)
    {
        MonitorMetricOption metric = metaMapper.selectMonitorableMetric(rule.getMetricCode());
        if (metric != null)
        {
            return metric;
        }
        MonitorMetricOption fallback = new MonitorMetricOption();
        fallback.setMetricCode(rule.getMetricCode());
        fallback.setMetricName(rule.getMetricName());
        fallback.setValueFormat(rule.getValueFormat());
        fallback.setJudgeType(rule.getJudgeType());
        fallback.setMinSampleKey(rule.getMinSampleKey());
        fallback.setMinSampleDefault(rule.getMinSampleValue());
        return fallback;
    }

    private void applyParams(MonitorRule rule, MonitorRuleBody body, MonitorRuleValidator.Result checked,
            MonitorLarkChat chat)
    {
        Threshold warn = checked.getWarn();
        Threshold crit = checked.getCrit();
        rule.setWarnLow(warn == null ? null : warn.getLow());
        rule.setWarnHigh(warn == null ? null : warn.getHigh());
        rule.setCritLow(crit.getLow());
        rule.setCritHigh(crit.getHigh());
        rule.setPeriodHours(body.getPeriodHours());
        rule.setDurationCount(body.getDurationCount());
        rule.setEscalateHours(body.getEscalateHours());
        rule.setMuteHours(body.getMuteHours());
        rule.setMinSampleValue(checked.getMinSampleValue());
        rule.setNotifyChatId(chat.getChatId());
        rule.setNotifyChatName(chat.getName());
    }

    private void fillRuleVO(MonitorRuleVO vo, MonitorRule rule)
    {
        vo.setRuleId(rule.getRuleId());
        vo.setRuleNo(rule.getRuleNo());
        vo.setRuleCode(ruleCode(rule.getRuleNo()));
        vo.setMetricCode(rule.getMetricCode());
        vo.setMetricName(rule.getMetricName());
        vo.setUnit(unit(rule.getValueFormat()));
        vo.setJudgeType(rule.getJudgeType());
        vo.setWarn(ThresholdVO.of(num(rule.getWarnLow()), num(rule.getWarnHigh())));
        vo.setCrit(ThresholdVO.of(num(rule.getCritLow()), num(rule.getCritHigh())));
        vo.setPeriodHours(rule.getPeriodHours());
        vo.setDurationCount(rule.getDurationCount());
        vo.setEscalateHours(rule.getEscalateHours());
        vo.setMuteHours(rule.getMuteHours());

        OwnerVO owner = new OwnerVO();
        owner.setUserId(rule.getOwnerUserId());
        owner.setEmail(rule.getOwnerEmail());
        owner.setValid(Boolean.TRUE.equals(rule.getOwnerValid()));
        vo.setOwner(owner);

        if (StringUtils.isNotEmpty(rule.getNotifyChatId()))
        {
            NotifyChatVO chat = new NotifyChatVO();
            chat.setChatId(rule.getNotifyChatId());
            chat.setName(rule.getChatName());
            // 缓存里没有这个群（从没拉到过或被清理）时按失效处理，让前端提示重新选
            chat.setStatus(rule.getChatStatus() == null ? "dead" : rule.getChatStatus());
            vo.setNotifyChat(chat);
        }
        vo.setLastTriggerHour(fmt(rule.getLastTriggerHour()));
        vo.setLastJudgedAt(fmt(rule.getLastJudgedAt()));
        vo.setJudgeError(rule.getJudgeError());
        vo.setUpdateBy(emailOr(rule.getUpdateByEmail(), rule.getUpdateBy()));
        vo.setUpdateTime(fmt(rule.getUpdateTime()));
        vo.setEnabled(Boolean.TRUE.equals(rule.getEnabled()));
    }

    /** 全站只显示邮箱；账号没有邮箱（或已删除）时退回登录名 */
    private static String emailOr(String email, String fallback)
    {
        return StringUtils.isNotEmpty(email) ? email : fallback;
    }

    /** 事务提交后清掉本站规则与状态缓存；判定任务 / 总览读不到时从数据库重建 */
    private void evictAfterCommit(String siteCode)
    {
        Runnable evict = () -> {
            try
            {
                redisCache.deleteObject(List.of(MonitorCacheKeys.rules(siteCode), MonitorCacheKeys.state(siteCode)));
            }
            catch (Exception e)
            {
                log.warn("清除监控缓存失败 site={}：{}", siteCode, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    evict.run();
                }
            });
        }
        else
        {
            evict.run();
        }
    }

    private LocalDateTime now()
    {
        return LocalDateTime.now(zone);
    }

    private static String fmt(LocalDateTime t)
    {
        return t == null ? null : t.format(MINUTE);
    }

    static String ruleCode(Integer ruleNo)
    {
        return ruleNo == null ? null : String.format("R-%03d", ruleNo);
    }

    /**
     * 阈值的单位，前端直接拼在数字后面显示：百分比 %、倍数 x、分钟 min、金额 ₹、人数 / 笔数为空串。
     * 指标格式未知时返回空串，不让前端显示内部编码。
     */
    static String unit(String valueFormat)
    {
        if (valueFormat == null)
        {
            return "";
        }
        switch (valueFormat.toUpperCase(Locale.ROOT))
        {
            case "PCT":
                return "%";
            case "MULTIPLE":
                return "x";
            case "MIN":
                return "min";
            case "MONEY":
                return "₹";
            default:
                return "";
        }
    }

    /** 去掉 NUMERIC(18,4) 补出来的尾零：95.0000 → 95，0.5000 → 0.5；不出现 1E+2 这种写法 */
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
