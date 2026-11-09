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
        LocalDateTime[] w = range.window(sendAt.toLocalDate(), sendAt.getHour());
        OverviewQuery q = new OverviewQuery();
        q.setGranularity(range.getGranularity());
        q.setSlotFrom(w[0].format(SLOT));
        q.setSlotTo(w[1].format(SLOT));
        q.setCompareType(CompareType.PREV_PERIOD);
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
        ctx.overviewUrl = properties.getOverviewUrl();
        ctx.test = test;
        return SubscriptionBrief.build(vo, codes, ctx);
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

    /**
     * 发给接收者并记录。
     *
     * @param receivers 接收者；测试发送时只有操作人自己
     * @param slot      定时发送的发送时刻（写入 last_slot）；立即发送、测试为 null
     * @return { status, period, sent, failed, results: [{ type, id, name, success, error, messageId }] }
     */
    public Map<String, Object> deliver(DashboardSubscription s, SubscriptionBrief brief, List<SubscriptionReceiver> receivers,
            String trigger, String operator, String slot)
    {
        MonitorProperties.Lark lark = monitorProperties.getLark();
        boolean noBot = isBlank(lark.getAppId()) || isBlank(lark.getAppSecret());
        String credentialError = noBot ? "未配置 Lark 機器人（MONITOR_LARK_APP_ID / MONITOR_LARK_APP_SECRET）" : null;
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        String firstError = null;
        for (SubscriptionReceiver r : receivers)
        {
            String error = null;
            String messageId = null;
            String name = r.getName();
            if (credentialError != null)
            {
                error = credentialError;
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
                }
                else
                {
                    LarkResult<String> res = larkClient.sendCard(lark.getAppId(), lark.getAppSecret(), idType, target,
                            brief.getCard());
                    if (res.isSuccess())
                    {
                        messageId = res.getData();
                    }
                    else
                    {
                        error = reason(res, r.isChat());
                        if (res.getCategory() == LarkErrorCategory.CREDENTIAL_INVALID)
                        {
                            credentialError = error;
                        }
                        log.warn("[subscription] 发送失败 id={} site={} {}={} {}", s.getId(), s.getSiteCode(), idType,
                                r.isChat() ? target : r.getId(), res);
                    }
                }
            }
            boolean success = error == null;
            if (success)
            {
                ok++;
            }
            else if (firstError == null)
            {
                firstError = (name == null ? r.getId() : name) + "：" + error;
            }
            writeLog(s, trigger, brief.getPeriod(), r, name, error, messageId, operator);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.getType());
            m.put("id", r.getId());
            m.put("name", name);
            m.put("success", success);
            m.put("error", error);
            m.put("messageId", messageId);
            results.add(m);
        }
        String status = ok == receivers.size() ? "SUCCESS" : ok == 0 ? "FAILED" : "PARTIAL";
        // 测试发送只发给操作人自己，不算订阅的发送结果
        if (!TEST.equals(trigger))
        {
            mapper.markRun(s.getId(), slot, LocalDateTime.now(zone(s.getSiteCode())).truncatedTo(ChronoUnit.SECONDS),
                    status, firstError == null ? null : cut(firstError, 500));
        }
        log.info("[subscription] {} id={} site={} period={} 成功 {}/{}", trigger, s.getId(), s.getSiteCode(),
                brief.getPeriod(), ok, receivers.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("period", brief.getPeriod());
        out.put("sent", ok);
        out.put("failed", receivers.size() - ok);
        out.put("results", results);
        return out;
    }

    /** 取数失败也记一笔最近状态（不写 last_slot，定时任务在补发期内会再试） */
    public void markPrepareFailed(DashboardSubscription s, String error)
    {
        mapper.markRun(s.getId(), null, LocalDateTime.now(zone(s.getSiteCode())).truncatedTo(ChronoUnit.SECONDS), "FAILED",
                cut(error, 500));
    }

    private void writeLog(DashboardSubscription s, String trigger, String period, SubscriptionReceiver r, String name,
            String error, String messageId, String operator)
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
