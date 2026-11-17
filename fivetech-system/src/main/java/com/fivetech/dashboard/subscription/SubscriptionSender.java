package com.fivetech.dashboard.subscription;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.mapper.DashboardSubscriptionMapper;
import com.fivetech.dashboard.service.IOverviewService;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.system.service.ISysUserService;

/**
 * 订阅简报的取数与发送。
 *
 * <ol>
 *   <li>{@link #prepare}：按站点时区算统计区间，在该站点的 SiteContext 下调用运营总览服务取数（与页面同一口径），整理成卡片；</li>
 *   <li>{@link #deliver}：逐个接收者发 Lark 卡片（群：chat_id；个人：用户当前邮箱，receive_id_type = email），
 *       每个接收者写一条发送记录，最后更新订阅的最近发送状态。</li>
 * </ol>
 * 一个接收者失败不影响其他接收者；Lark 凭证无效时后面的接收者不再请求 Lark，直接记失败。
 *
 * @author fivetech
 */
@Component
public class SubscriptionSender
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionSender.class);

    static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final String SCHEDULE = "SCHEDULE";

    public static final String MANUAL = "MANUAL";

    public static final String TEST = "TEST";

    private final DashboardSubscriptionMapper mapper;

    private final IOverviewService overviewService;

    private final MetricRegistry registry;

    private final DashboardProperties dashboard;

    private final SubscriptionProperties properties;

    private final MonitorProperties monitorProperties;

    private final LarkClient larkClient;

    private final ISysUserService userService;

    private final ObjectMapper objectMapper;

    public SubscriptionSender(DashboardSubscriptionMapper mapper, IOverviewService overviewService, MetricRegistry registry,
            DashboardProperties dashboard, SubscriptionProperties properties, MonitorProperties monitorProperties,
            LarkClient larkClient, ISysUserService userService, ObjectMapper objectMapper)
    {
        this.mapper = mapper;
        this.overviewService = overviewService;
        this.registry = registry;
        this.dashboard = dashboard;
        this.properties = properties;
        this.monitorProperties = monitorProperties;
        this.larkClient = larkClient;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    /** AI 摘要（没有 copilot 配置的环境、单测里为 null：不加摘要） */
    private SubscriptionAiAnalyst aiAnalyst;

    /** 数据异动分析与它要用的告警事件（单测里为 null：不加） */
    private SubscriptionAnomalyAnalyst anomalyAnalyst;

    private com.fivetech.monitor.service.IMonitorEventService monitorEvents;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAnomalyAnalyst(SubscriptionAnomalyAnalyst anomalyAnalyst)
    {
        this.anomalyAnalyst = anomalyAnalyst;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMonitorEvents(com.fivetech.monitor.service.IMonitorEventService monitorEvents)
    {
        this.monitorEvents = monitorEvents;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAiAnalyst(SubscriptionAiAnalyst aiAnalyst)
    {
        this.aiAnalyst = aiAnalyst;
    }

    /**
     * 订阅开了 AI 摘要时生成摘要写进卡片；任何失败都不影响发送（卡片不带摘要，预览里给原因）。
     * 在站点上下文里调用，调用记录带站点。
     */
    public void addAi(DashboardSubscription s, SubscriptionBrief brief, String operator)
    {
        if (!s.isAiOn() || aiAnalyst == null || brief == null)
        {
            return;
        }
        try
        {
            aiAnalyst.apply(s, brief, operator == null ? "system" : operator);
        }
        catch (RuntimeException e)
        {
            log.error("[subscription] AI 摘要生成异常 id={}，本次不带摘要", s.getId(), e);
            brief.applyAi(null, s.getAiModel(), s.getAiModel(), "AI 摘要生成失敗");
        }
        addAnomaly(s, brief, operator);
    }

    /**
     * 数据异动分析：读统计周期内本站的告警事件（失败时只按指标波动判断），交给 {@link SubscriptionAnomalyAnalyst}；
     * 任何失败都不影响发送。
     */
    void addAnomaly(DashboardSubscription s, SubscriptionBrief brief, String operator)
    {
        if (anomalyAnalyst == null)
        {
            return;
        }
        List<com.fivetech.monitor.domain.vo.MonitorEventVO> events = null;
        if (monitorEvents != null && brief.getWindowFrom() != null && brief.getWindowTo() != null)
        {
            try
            {
                String site = dashboard.canonical(s.getSiteCode());
                events = monitorEvents.eventsInWindow(new com.fivetech.monitor.domain.MonitorScope(site, null),
                    brief.getWindowFrom(), brief.getWindowTo(), anomalyAnalyst.getProperties().getAnomaly().getMaxEvents());
            }
            catch (RuntimeException e)
            {
                log.warn("[subscription] 读取告警事件失败 id={}，异动分析只看指标波动：{}", s.getId(), e.getMessage());
            }
        }
        try
        {
            anomalyAnalyst.apply(s, brief, events, operator == null ? "system" : operator);
        }
        catch (RuntimeException e)
        {
            log.error("[subscription] 数据异动分析生成异常 id={}，本次不带", s.getId(), e);
            brief.applyAnomaly(null, s.getAiModel(), "數據異動分析生成失敗");
        }
    }

    /** 订阅截图（可选；没有这个 bean 时不截） */
    private com.fivetech.dashboard.subscription.snapshot.SubscriptionSnapshotService snapshots;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSnapshots(com.fivetech.dashboard.subscription.snapshot.SubscriptionSnapshotService snapshots)
    {
        this.snapshots = snapshots;
    }

    /** 后端是否开了截图（管理后台据此显示「帶運營總覽截圖」开关） */
    public boolean snapshotAvailable()
    {
        return snapshots != null && snapshots.isEnabled();
    }

    /**
     * 订阅开了截图时截运营总览页放进卡片；任何失败都不影响发送（卡片写「截圖生成失敗」，原因记日志、预览里显示）。
     *
     * @param upload true：上传 Lark 拿 image_key（发送）；false：只截图，预览里以图片返回
     */
    public void addSnapshot(DashboardSubscription s, SubscriptionBrief brief, boolean upload)
    {
        if (!s.isSnapshotOn() || brief == null)
        {
            return;
        }
        if (snapshots == null || !snapshots.isEnabled())
        {
            brief.applySnapshot(null, null, null, "截圖功能未開啟（dashboard.subscription.snapshot.enabled）");
            return;
        }
        String site = dashboard.canonical(s.getSiteCode());
        com.fivetech.dashboard.subscription.snapshot.SubscriptionSnapshotService.Result r =
            snapshots.capture(s, site, brief.getWindowFrom(), brief.getWindowTo());
        if (!r.isOk())
        {
            log.warn("[subscription] 订阅 {} 截图失败，本次不带截图：{}", s.getId(), r.getError());
            brief.applySnapshot(null, null, r.getUrl(), r.getError());
            return;
        }
        if (!upload)
        {
            brief.applySnapshot(null, r.getPng(), r.getUrl(), null);
            return;
        }
        try
        {
            brief.applySnapshot(snapshots.upload(r.getPng()), null, r.getUrl(), null);
        }
        catch (RuntimeException e)
        {
            log.warn("[subscription] 订阅 {} 截图上传失败，本次不带截图：{}", s.getId(), e.getMessage());
            brief.applySnapshot(null, null, r.getUrl(), e.getMessage());
        }
    }

    /** 取数失败、没有可输出的指标等：不发送，调用方决定重试还是报错 */
    public static class PrepareException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        public PrepareException(String message)
        {
            super(message);
        }
    }

    /** 站点时区的「现在」，截到整点（立即发送、测试、预览用它当发送时刻） */
    public LocalDateTime nowHour(String siteCode)
    {
        return LocalDateTime.now(zone(siteCode)).truncatedTo(ChronoUnit.HOURS);
    }

    public ZoneId zone(String siteCode)
    {
        return ZoneId.of(dashboard.siteTimezone(siteCode));
    }

    /**
     * 取数并整理成简报。
     *
     * @param sendAt 发送时刻（站点时区，整点）；今日范围统计到这个整点
     * @param test   测试发送：卡片标题加【測試】、表头灰色
     */
    public SubscriptionBrief prepare(DashboardSubscription s, LocalDateTime sendAt, boolean test)
    {
        String site = dashboard.canonical(s.getSiteCode());
        if (site == null)
        {
            throw new PrepareException("站點 " + s.getSiteCode() + " 已不存在");
        }
        SubscriptionRange range = SubscriptionRange.of(s.getRangeCode());
        if (range == null)
        {
            throw new PrepareException("統計範圍不正確：" + s.getRangeCode());
        }
        if (range == SubscriptionRange.TODAY && sendAt.getHour() == 0)
        {
            throw new PrepareException("今日 00:00 還沒有完整小時的資料");
        }
        List<String> codes = orderedCodes(site, s.getMetrics());
        if (codes.isEmpty())
        {
            throw new PrepareException("勾選的指標都已在本站隱藏或下線，請重新勾選");
        }
        if (range == SubscriptionRange.CUSTOM && (s.getViewFrom() == null || s.getViewTo() == null))
        {
            throw new PrepareException("固定日期缺少起止日期");
        }
        LocalDateTime[] w = range == SubscriptionRange.CUSTOM ? SubscriptionRange.window(s.getViewFrom(), s.getViewTo())
            : range.window(sendAt.toLocalDate(), sendAt.getHour());
        OverviewQuery q = new OverviewQuery();
        q.setGranularity(granularity(s, range));
        q.setSlotFrom(w[0].format(SLOT));
        q.setSlotTo(w[1].format(SLOT));
        q.setCompareType(CompareType.PREV_PERIOD);
        if (s.isCustomCompare())
        {
            q.setCompareType(CompareType.CUSTOM);
            q.setCompareFrom(s.getViewCmpFrom().atStartOfDay().format(SLOT));
            q.setCompareTo(s.getViewCmpTo().plusDays(1).atStartOfDay().format(SLOT));
            q.setCmpLock(false);
        }
        q.setBlocks(List.of("METRICS"));
        q.setMetrics(codes);
        q.setIncludeSeries(false);
        OverviewVO vo;
        try
        {
            vo = SiteContext.callWith(site, dashboard.udsSite(site), () -> overviewService.query(q));
        }
        catch (RuntimeException e)
        {
            log.warn("[subscription] 取数失败 id={} site={} range={} {}", s.getId(), site, range, e.getMessage());
            throw new PrepareException("取數失敗：" + e.getMessage());
        }
        if (!(vo.getBlocks().get("METRICS") instanceof com.fivetech.dashboard.domain.vo.MetricsBlockVO))
        {
            throw new PrepareException("資料平台取數失敗，稍後重試");
        }
        SubscriptionBrief.Context ctx = new SubscriptionBrief.Context();
        ctx.siteName = dashboard.siteName(site);
        ctx.name = s.getName();
        ctx.description = s.getDescription();
        ctx.currencySymbol = dashboard.siteCurrencySymbol(site);
        ctx.timezone = dashboard.siteTimezone(site);
        ctx.sendHour = s.getSendHour();
        ctx.frequency = SubscriptionSchedule.text(s);
        ctx.overviewUrl = properties.getOverviewUrl();
        ctx.test = test;
        return SubscriptionBrief.build(vo, codes, ctx);
    }

    /** 取数粒度：订阅视图的粒度（发起时页面的选择），没有就用统计范围的默认粒度 */
    static com.fivetech.dashboard.enums.Granularity granularity(DashboardSubscription s, SubscriptionRange range)
    {
        if (s.getViewGran() != null)
        {
            try
            {
                return com.fivetech.dashboard.enums.Granularity.valueOf(s.getViewGran().trim().toUpperCase(java.util.Locale.ROOT));
            }
            catch (IllegalArgumentException e)
            {
                // 不认识的粒度按统计范围
            }
        }
        return range.getGranularity();
    }

    /** 勾选的指标按站点运营总览的顺序排好；站点隐藏或下线的去掉 */
    public List<String> orderedCodes(String site, String metrics)
    {
        return order(registry.columnsOf(DashboardPage.OVERVIEW, site), metrics);
    }

    public static List<String> splitCodes(String metrics)
    {
        List<String> out = new ArrayList<>();
        if (metrics == null)
        {
            return out;
        }
        for (String c : metrics.split(","))
        {
            if (!c.trim().isEmpty())
            {
                out.add(c.trim());
            }
        }
        return out;
    }

    public List<SubscriptionReceiver> receivers(DashboardSubscription s)
    {
        if (s.getReceivers() == null || s.getReceivers().trim().isEmpty())
        {
            return new ArrayList<>();
        }
        try
        {
            return objectMapper.readValue(s.getReceivers(), new TypeReference<List<SubscriptionReceiver>>() {});
        }
        catch (Exception e)
        {
            log.error("[subscription] 接收者 JSON 解析失败 id={}：{}", s.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 本站群状态（发送失败「机器人不在群」时把群置为失效）；单测里为 null */
    private com.fivetech.monitor.service.IMonitorLarkChatService chatService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChatService(com.fivetech.monitor.service.IMonitorLarkChatService chatService)
    {
        this.chatService = chatService;
    }

    /** 自动重试之间的等待（毫秒，第 n 次重试等 n 倍）；单测设为 0 */
    private long retryBackoffMillis = 2000;

    void setRetryBackoffMillis(long retryBackoffMillis)
    {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /** 超时、限流、5xx 的自动重试次数（原型：已自动重试 2 次） */
    static final int RETRY_TIMES = 2;

    /**
     * 发给接收者并记录（定时、立即发送写推送批次与逐群结果；测试发送不记录）。
     *
     * <ul>
     *   <li>超时、限流、5xx 自动重试 {@value #RETRY_TIMES} 次；其他失败不重试；</li>
     *   <li>机器人凭证失效或没有配置：不再请求 Lark，各群记「已跳過 · 機器人憑證失效」（恢复后不补发）；</li>
     *   <li>一次发送的结果：各群结果相同就是该结果，否则为部分失敗；写回订阅的 last_status（测试发送不写）。</li>
     * </ul>
     *
     * @param receivers 接收者
     * @param slot      定时发送的发送时刻（写入 last_slot）；立即发送、测试为 null
     * @return { status, result, runId, period, sent, failed, results: [{ type, id, name, success, result, reasonCode, reasonText, error, messageId }] }
     */
    public Map<String, Object> deliver(DashboardSubscription s, SubscriptionBrief brief, List<SubscriptionReceiver> receivers,
            String trigger, String operator, String slot)
    {
        boolean record = !TEST.equals(trigger);
        DashboardSubscriptionRun run = record ? startRun(s, brief, trigger, operator, slot) : null;
        MonitorProperties.Lark lark = monitorProperties.getLark();
        boolean noBot = isBlank(lark.getAppId()) || isBlank(lark.getAppSecret());
        String credentialError = noBot ? "未配置 Lark 機器人（MONITOR_LARK_APP_ID / MONITOR_LARK_APP_SECRET）" : null;
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        int ok = 0;
        String firstError = null;
        for (SubscriptionReceiver r : receivers)
        {
            String error = null;
            String messageId = null;
            String name = r.getName();
            SubscriptionReason reason = null;
            int attempts = 0;
            if (credentialError != null)
            {
                error = credentialError;
                reason = SubscriptionReason.CRED;
            }
            else
            {
                String idType;
                String target;
                if (r.isChat())
                {
                    idType = "chat_id";
                    target = r.getId();
                }
                else
                {
                    idType = "email";
                    target = emailOf(r.getId());
                    if (!isBlank(target))
                    {
                        name = target;
                    }
                }
                if (isBlank(target))
                {
                    error = r.isChat() ? "群 ID 為空" : "用戶不存在、已停用或沒有郵箱";
                    reason = r.isChat() ? SubscriptionReason.GONE : SubscriptionReason.REJECTED;
                }
                else
                {
                    LarkResult<String> res = null;
                    while (true)
                    {
                        attempts++;
                        res = larkClient.sendCard(lark.getAppId(), lark.getAppSecret(), idType, target, brief.getCard());
                        reason = SubscriptionReason.of(res);
                        if (reason == null || !reason.retryable() || attempts > RETRY_TIMES)
                        {
                            break;
                        }
                        pause(attempts);
                    }
                    if (res.isSuccess())
                    {
                        messageId = res.getData();
                    }
                    else
                    {
                        error = reason(res, r.isChat());
                        if (reason == SubscriptionReason.CRED)
                        {
                            credentialError = error;
                        }
                        if (r.isChat() && res.isChatGone())
                        {
                            markChatGone(s, target, res);
                        }
                        log.warn("[subscription] 发送失败 id={} site={} {}={} attempts={} {}", s.getId(), s.getSiteCode(), idType,
                                r.isChat() ? target : r.getId(), attempts, res);
                    }
                }
            }
            boolean success = error == null;
            String outcome = success ? SubscriptionReason.OK : reason.getResult();
            outcomes.add(outcome);
            if (success)
            {
                ok++;
            }
            else if (firstError == null && !SubscriptionReason.SKIP.equals(outcome))
            {
                firstError = (name == null ? r.getId() : name) + "：" + reason.getText();
            }
            if (record)
            {
                writeLog(s, trigger, brief.getPeriod(), r, name, error, messageId, operator, run, outcome,
                    reason == null ? null : reason.getCode(), attempts);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.getType());
            m.put("id", r.getId());
            m.put("name", name);
            m.put("success", success);
            m.put("result", outcome);
            m.put("reasonCode", reason == null ? null : reason.getCode());
            m.put("reasonText", reason == null ? null : reason.getText());
            m.put("error", error);
            m.put("messageId", messageId);
            results.add(m);
        }
        String result = SubscriptionReason.aggregate(outcomes);
        String status = SubscriptionReason.lastStatus(result);
        if (firstError == null && !SubscriptionReason.OK.equals(result) && credentialError != null)
        {
            firstError = SubscriptionReason.CRED.getText();
        }
        if (record)
        {
            run.setResult(result);
            run.setReceiverTotal(receivers.size());
            run.setSent(ok);
            run.setFailed(receivers.size() - ok);
            run.setSentAt(SubscriptionReason.SKIP.equals(result) ? run.getPlanAt() : nowSecond(s.getSiteCode()));
            run.setError(firstError == null ? null : cut(firstError, 500));
            finishRun(s, run);
            mapper.markRun(s.getId(), slot, nowSecond(s.getSiteCode()), status, firstError == null ? null : cut(firstError, 500));
        }
        log.info("[subscription] {} id={} site={} period={} result={} 成功 {}/{}", trigger, s.getId(), s.getSiteCode(),
                brief.getPeriod(), result, ok, receivers.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("result", result);
        out.put("runId", run == null ? null : run.getId());
        out.put("period", brief.getPeriod());
        out.put("sent", ok);
        out.put("failed", receivers.size() - ok);
        out.put("results", results);
        return out;
    }

    /**
     * 发送前的整次失败（取数失败 data、截图失败 shot）：本次所有群都未发送，写一个 FAIL 批次和逐群 FAIL 记录。
     * 测试发送不记录。
     *
     * @param brief 取数失败时为 null
     */
    public Map<String, Object> failWhole(DashboardSubscription s, SubscriptionBrief brief, List<SubscriptionReceiver> receivers,
            String trigger, String operator, String slot, LocalDateTime planAt, SubscriptionReason reason, String detail)
    {
        String period = brief == null ? null : brief.getPeriod();
        if (!TEST.equals(trigger))
        {
            DashboardSubscriptionRun run = new DashboardSubscriptionRun();
            run.setSubscriptionId(s.getId());
            run.setSiteCode(s.getSiteCode());
            run.setTriggerType(trigger);
            run.setPlanAt(planAt);
            run.setSentAt(nowSecond(s.getSiteCode()));
            run.setPeriod(cut(period, 64));
            run.setComparePeriod(brief == null ? null : cut(brief.getCompare(), 64));
            run.setResult(SubscriptionReason.FAIL);
            run.setReasonCode(reason.getCode());
            run.setError(cut(detail, 500));
            run.setReceiverTotal(receivers.size());
            run.setSent(0);
            run.setFailed(receivers.size());
            fillExtras(s, brief, run);
            run.setOperator(cut(operator, 64));
            insertRun(run);
            for (SubscriptionReceiver r : receivers)
            {
                writeLog(s, trigger, period, r, r.getName(), detail, null, operator, run, SubscriptionReason.FAIL, reason.getCode(), 0);
            }
            mapper.markRun(s.getId(), slot, nowSecond(s.getSiteCode()), "FAILED", cut(reason.getText() + "：" + nvl(detail), 500));
        }
        log.warn("[subscription] {} id={} site={} 整次未發送（{}）：{}", trigger, s.getId(), s.getSiteCode(), reason.getCode(), detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "FAILED");
        out.put("result", SubscriptionReason.FAIL);
        out.put("reasonCode", reason.getCode());
        out.put("reasonText", reason.getText());
        out.put("error", detail);
        out.put("period", period);
        out.put("sent", 0);
        out.put("failed", receivers.size());
        out.put("results", new ArrayList<>());
        return out;
    }

    /**
     * 订阅开了截图却没拿到截图（截图或上传失败）：按 2026-11-13 的决定整次不发，返回失败原因；否则返回 null。
     */
    public String snapshotFailure(DashboardSubscription s, SubscriptionBrief brief)
    {
        if (!s.isSnapshotOn() || brief == null)
        {
            return null;
        }
        return brief.getSnapshotKey() == null ? (brief.getSnapshotError() == null ? "截圖生成失敗" : brief.getSnapshotError()) : null;
    }

    /**
     * 截图正常就发送，否则整次失败（shot）。定时、立即发送都走这里。
     */
    public Map<String, Object> deliverChecked(DashboardSubscription s, SubscriptionBrief brief, List<SubscriptionReceiver> receivers,
            String trigger, String operator, String slot, LocalDateTime planAt)
    {
        String shot = snapshotFailure(s, brief);
        if (shot != null)
        {
            return failWhole(s, brief, receivers, trigger, operator, slot, planAt, SubscriptionReason.SHOT, shot);
        }
        return deliver(s, brief, receivers, trigger, operator, slot, planAt);
    }

    /** 同 {@link #deliver(DashboardSubscription, SubscriptionBrief, List, String, String, String)}，指定排程时刻 */
    public Map<String, Object> deliver(DashboardSubscription s, SubscriptionBrief brief, List<SubscriptionReceiver> receivers,
            String trigger, String operator, String slot, LocalDateTime planAt)
    {
        planAtHint.set(planAt);
        try
        {
            return deliver(s, brief, receivers, trigger, operator, slot);
        }
        finally
        {
            planAtHint.remove();
        }
    }

    private final ThreadLocal<LocalDateTime> planAtHint = new ThreadLocal<>();

    private DashboardSubscriptionRun startRun(DashboardSubscription s, SubscriptionBrief brief, String trigger, String operator, String slot)
    {
        DashboardSubscriptionRun run = new DashboardSubscriptionRun();
        run.setSubscriptionId(s.getId());
        run.setSiteCode(s.getSiteCode());
        run.setTriggerType(trigger);
        LocalDateTime plan = planAtHint.get();
        if (plan == null && slot != null)
        {
            plan = LocalDateTime.parse(slot, SLOT);
        }
        run.setPlanAt(plan == null ? nowHour(s.getSiteCode()) : plan);
        run.setPeriod(cut(brief.getPeriod(), 64));
        run.setComparePeriod(cut(brief.getCompare(), 64));
        run.setOperator(cut(operator, 64));
        fillExtras(s, brief, run);
        insertRun(run);
        return run;
    }

    /** AI 摘要、截图的状态写进批次 */
    private static void fillExtras(DashboardSubscription s, SubscriptionBrief brief, DashboardSubscriptionRun run)
    {
        run.setAiStatus(!s.isAiOn() ? "OFF" : brief != null && brief.getAiSummary() != null ? "OK" : "FAILED");
        if (!s.isSnapshotOn())
        {
            run.setSnapshotStatus("OFF");
        }
        else if (brief != null && brief.getSnapshotKey() != null)
        {
            run.setSnapshotStatus("OK");
        }
        else
        {
            run.setSnapshotStatus("FAILED");
            run.setSnapshotError(brief == null ? null : cut(brief.getSnapshotError(), 500));
        }
    }

    private void insertRun(DashboardSubscriptionRun run)
    {
        try
        {
            mapper.insertRun(run);
        }
        catch (RuntimeException e)
        {
            log.error("[subscription] 写推送批次失败 id={}：{}", run.getSubscriptionId(), e.getMessage());
        }
    }

    private void finishRun(DashboardSubscription s, DashboardSubscriptionRun run)
    {
        if (run.getId() == null)
        {
            return;
        }
        try
        {
            mapper.finishRun(run);
        }
        catch (RuntimeException e)
        {
            log.error("[subscription] 结束推送批次失败 id={} run={}：{}", s.getId(), run.getId(), e.getMessage());
        }
    }

    private void markChatGone(DashboardSubscription s, String chatId, LarkResult<?> res)
    {
        if (chatService == null)
        {
            return;
        }
        try
        {
            chatService.markDeadIfChatGone(new com.fivetech.monitor.domain.MonitorScope(dashboard.canonical(s.getSiteCode()), null),
                chatId, res);
        }
        catch (RuntimeException e)
        {
            log.warn("[subscription] 群置为失效失败 chat={}：{}", chatId, e.getMessage());
        }
    }

    private void pause(int attempt)
    {
        if (retryBackoffMillis <= 0)
        {
            return;
        }
        try
        {
            Thread.sleep(retryBackoffMillis * attempt);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    LocalDateTime nowSecond(String siteCode)
    {
        return LocalDateTime.now(zone(siteCode)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }

    /** 取数失败也记一笔最近状态（不写 last_slot，定时任务在补发期内会再试） */
    public void markPrepareFailed(DashboardSubscription s, String error)
    {
        mapper.markRun(s.getId(), null, LocalDateTime.now(zone(s.getSiteCode())).truncatedTo(ChronoUnit.SECONDS), "FAILED",
                cut(error, 500));
    }

    private void writeLog(DashboardSubscription s, String trigger, String period, SubscriptionReceiver r, String name,
            String error, String messageId, String operator, DashboardSubscriptionRun run, String result, String reasonCode,
            int attempts)
    {
        try
        {
            DashboardSubscriptionLog row = new DashboardSubscriptionLog();
            row.setSubscriptionId(s.getId());
            row.setSiteCode(s.getSiteCode());
            row.setTriggerType(trigger);
            row.setPeriod(cut(period, 64));
            row.setReceiverType(r.getType());
            row.setReceiverId(cut(r.getId(), 64));
            row.setReceiverName(cut(name, 128));
            row.setStatus(error == null ? "SUCCESS" : "FAILED");
            row.setError(cut(error, 500));
            row.setMessageId(cut(messageId, 64));
            row.setOperator(cut(operator, 64));
            row.setRunId(run == null ? null : run.getId());
            row.setResult(result);
            row.setReasonCode(reasonCode);
            row.setAttempts(attempts);
            mapper.insertLog(row);
        }
        catch (RuntimeException e)
        {
            log.error("[subscription] 写发送记录失败 id={}：{}", s.getId(), e.getMessage());
        }
    }

    private String emailOf(String userId)
    {
        try
        {
            SysUser u = userService.selectUserById(Long.valueOf(userId.trim()));
            if (u == null || !"0".equals(u.getStatus()) || "2".equals(u.getDelFlag()))
            {
                return null;
            }
            return isBlank(u.getEmail()) ? null : u.getEmail().trim();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** 失败原因：可读文字 +（Lark 错误码 / HTTP 状态）；Lark 原文只进日志 */
    static String reason(LarkResult<?> r, boolean chat)
    {
        String text;
        switch (r.getCategory())
        {
            case CREDENTIAL_INVALID:
                text = "Lark 機器人憑證失效";
                break;
            case TARGET_INVALID:
                text = chat ? (r.isChatGone() ? "機器人已不在群裡或群已解散" : "沒有向該群發送消息的權限")
                    : "找不到該郵箱的 Lark 用戶，或對方不在機器人的可用範圍內";
                break;
            default:
                // 未识别的 4xx 错误码（如邮箱不存在）不是网络问题，别写成「超时」
                boolean rejected = r.getLarkCode() != null && r.getHttpStatus() >= 400 && r.getHttpStatus() < 500
                    && r.getHttpStatus() != 429;
                text = !rejected ? "網絡超時或 Lark 限流"
                    : chat ? "Lark 拒絕發送" : "Lark 拒絕發送（確認郵箱已註冊 Lark、且在機器人的可用範圍內）";
                break;
        }
        List<String> codes = new ArrayList<>();
        if (r.getLarkCode() != null)
        {
            codes.add("code=" + r.getLarkCode());
        }
        if (r.getHttpStatus() > 0)
        {
            codes.add("http=" + r.getHttpStatus());
        }
        return codes.isEmpty() ? text : text + "（" + String.join(", ", codes) + "）";
    }

    static String cut(String s, int max)
    {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }

    /** 逗号分隔的编码按给定顺序（站点运营总览顺序）排，不在顺序里的去掉 */
    static List<String> order(List<String> siteOrder, String metrics)
    {
        Set<String> picked = new LinkedHashSet<>(splitCodes(metrics));
        List<String> out = new ArrayList<>();
        for (String c : siteOrder)
        {
            if (picked.contains(c))
            {
                out.add(c);
            }
        }
        return out;
    }
}
