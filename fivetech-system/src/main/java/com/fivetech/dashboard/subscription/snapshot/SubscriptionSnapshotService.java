package com.fivetech.dashboard.subscription.snapshot;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.dashboard.subscription.DashboardSubscription;
import com.fivetech.dashboard.subscription.SubscriptionRange;
import com.fivetech.monitor.config.MonitorProperties;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/**
 * 订阅截图：用截图帐号的临时令牌，让本机无头 Chromium 打开运营总览页（订阅的站点 + 查询条件），截图后上传 Lark。
 *
 * <ul>
 *   <li>全局同一时间只截一张（2 核机器，浏览器很占 CPU），排队超过 {@code queue-seconds} 本次不截；</li>
 *   <li>令牌每次现签（{@link SnapshotTokens}），截完立即作废，到期也自动失效；</li>
 *   <li>任何失败都只返回原因，由调用方照常发文字卡片。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class SubscriptionSnapshotService
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionSnapshotService.class);

    /** 查询条件允许的字符：参数名、值、占位符、百分号编码 */
    static final Pattern QUERY = Pattern.compile("[A-Za-z0-9_.~=&%:,+{}\\-]*");

    static final int MAX_QUERY = 500;

    /** 一次截图的结果：png 有值 = 成功；否则 error 为原因 */
    public static final class Result
    {
        private final byte[] png;
        private final String url;
        private final String error;

        Result(byte[] png, String url, String error)
        {
            this.png = png;
            this.url = url;
            this.error = error;
        }

        public byte[] getPng() { return png; }
        /** 截的页面地址（不含令牌） */
        public String getUrl() { return url; }
        public String getError() { return error; }
        public boolean isOk() { return png != null; }
    }

    private final SnapshotProperties props;

    private final SnapshotTokens tokens;

    private final LarkClient larkClient;

    private final MonitorProperties monitorProperties;

    private final ObjectMapper json;

    private final Semaphore one = new Semaphore(1, true);

    private HeadlessChrome chrome;

    public SubscriptionSnapshotService(SnapshotProperties props, SnapshotTokens tokens, LarkClient larkClient,
        MonitorProperties monitorProperties, ObjectMapper json)
    {
        this.props = props;
        this.tokens = tokens;
        this.larkClient = larkClient;
        this.monitorProperties = monitorProperties;
        this.json = json;
    }

    public boolean isEnabled()
    {
        return props.isEnabled();
    }

    // ------------------------------------------------------------------ 截图

    /**
     * 截订阅对应的运营总览页。
     *
     * @param site       站点编码（规范化后）
     * @param windowFrom 订阅的统计区间开始（站点时区，含）
     * @param windowTo   统计区间结束（不含）
     */
    public Result capture(DashboardSubscription s, String site, LocalDateTime windowFrom, LocalDateTime windowTo)
    {
        String url;
        try
        {
            url = pageUrl(props.getPageUrl(), s.getSnapshotQuery(), SubscriptionRange.of(s.getRangeCode()), site, windowFrom, windowTo);
        }
        catch (IllegalArgumentException e)
        {
            return new Result(null, null, e.getMessage());
        }
        boolean got = false;
        try
        {
            got = one.tryAcquire(Math.max(1, props.getQueueSeconds()), TimeUnit.SECONDS);
            if (!got)
            {
                return new Result(null, url, "截圖排隊超過 " + props.getQueueSeconds() + " 秒，本次不帶截圖");
            }
            return new Result(shoot(url, site), url, null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new Result(null, url, "截圖被中斷");
        }
        catch (RuntimeException e)
        {
            log.warn("[snapshot] 订阅 {} 截图失败 url={}：{}", s.getId(), url, e.getMessage());
            return new Result(null, url, e.getMessage());
        }
        finally
        {
            if (got)
            {
                one.release();
            }
        }
    }

    /** 持有信号量时调用 */
    private byte[] shoot(String url, String site)
    {
        SnapshotTokens.Issued token = tokens.issue(props.getUsername(), Math.max(60, props.getTokenTtlSeconds()));
        long t0 = System.currentTimeMillis();
        try
        {
            if (chrome == null || chrome.getShots() >= Math.max(1, props.getRestartAfter()))
            {
                if (chrome != null)
                {
                    chrome.close();
                }
                chrome = new HeadlessChrome(props.getChromiumPath(), props.getChromiumArgs(), json);
            }
            HeadlessChrome.Shot shot = new HeadlessChrome.Shot();
            shot.url = url;
            shot.cookieName = props.getTokenCookie();
            shot.cookieValue = token.getJwt();
            if (notBlank(props.getTokenLocalStorageKey()))
            {
                shot.localStorage.put(props.getTokenLocalStorageKey(), token.getJwt());
            }
            props.getLocalStorage().forEach((k, v) -> shot.localStorage.put(k, fill(v, site, token.getJwt())));
            shot.width = props.getWidth();
            shot.height = props.getHeight();
            shot.maxHeight = props.getMaxHeight();
            shot.scale = props.getScale();
            shot.captureSelector = props.getCaptureSelector();
            shot.readySelector = props.getReadySelector();
            shot.busySelector = props.getBusySelector();
            shot.hideSelector = props.getHideSelector();
            shot.loginKeyword = props.getLoginPathKeyword();
            shot.idleMillis = props.getIdleMillis();
            shot.settleMillis = props.getSettleMillis();
            shot.timeout = Duration.ofSeconds(Math.max(10, props.getTimeoutSeconds()));
            byte[] png = chrome.shoot(shot);
            log.info("[snapshot] 截图完成 site={} {} 字节 {} ms url={}", site, png.length, System.currentTimeMillis() - t0, url);
            return png;
        }
        finally
        {
            tokens.revoke(token);
        }
    }

    /** 上传到 Lark，返回 image_key；失败抛 IllegalStateException（message 为原因） */
    public String upload(byte[] png)
    {
        MonitorProperties.Lark lark = monitorProperties.getLark();
        LarkResult<String> r = larkClient.uploadImage(lark.getAppId(), lark.getAppSecret(), png, "overview.png");
        if (!r.isSuccess())
        {
            throw new IllegalStateException("上傳截圖到 Lark 失敗：" + r.getMsg());
        }
        return r.getData();
    }

    @PreDestroy
    public void shutdown()
    {
        if (chrome != null)
        {
            chrome.close();
            chrome = null;
        }
    }

    // ------------------------------------------------------------------ 地址与查询条件

    /**
     * 截图页地址 = page-url ? 查询条件。查询条件为空时按订阅的统计范围生成（与卡片同一区间）：
     * 今日 / 昨日按小时用页面的预设范围，近 7 / 30 日用 custom + 具体日期（不含发送当天，与卡片一致）。
     * 查询条件与地址里可用 {from} {to}（统计区间首尾日期，to 为最后一天）、{site}。包内可见供测试。
     */
    static String pageUrl(String base, String query, SubscriptionRange range, String site, LocalDateTime from, LocalDateTime to)
    {
        if (!notBlank(base))
        {
            throw new IllegalArgumentException("未配置運營總覽頁地址（DASHBOARD_SNAPSHOT_PAGE_URL）");
        }
        String q = notBlank(query) ? query.trim() : defaultQuery(range);
        String fromDate = from == null ? "" : from.toLocalDate().toString();
        LocalDate last = to == null ? null : (to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? to.toLocalDate().minusDays(1) : to.toLocalDate());
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{from}", fromDate);
        vars.put("{to}", last == null ? "" : last.toString());
        vars.put("{site}", site == null ? "" : site);
        String b = base.trim();
        for (Map.Entry<String, String> e : vars.entrySet())
        {
            q = q.replace(e.getKey(), URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            b = b.replace(e.getKey(), URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        if (q.isEmpty())
        {
            return b;
        }
        return b + (b.contains("?") ? "&" : "?") + q;
    }

    /** 统计范围 → 页面查询条件 */
    static String defaultQuery(SubscriptionRange range)
    {
        if (range == null)
        {
            return "range=today&gran=hour&cmp=previous-period";
        }
        switch (range)
        {
            case TODAY:
                return "range=today&gran=hour&cmp=previous-period";
            case YESTERDAY:
                return "range=yesterday&gran=hour&cmp=previous-period";
            default:
                return "range=custom&gran=day&from={from}&to={to}&cmp=previous-period";
        }
    }

    /**
     * 规范化用户填的查询条件：可以贴整条页面地址（取 ? 后面的部分）或只填查询条件；去掉开头的 ?。
     * 不合法返回 null（调用方报错）；空串表示「按统计范围生成」。包内可见供测试。
     */
    public static String normalizeQuery(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String q = raw.trim();
        int i = q.indexOf('?');
        if (q.contains("://") || i >= 0)
        {
            q = i < 0 ? "" : q.substring(i + 1);
        }
        int hash = q.indexOf('#');
        if (hash >= 0)
        {
            q = q.substring(0, hash);
        }
        if (q.length() > MAX_QUERY || !QUERY.matcher(q).matches())
        {
            return null;
        }
        return q;
    }

    private static String fill(String template, String site, String token)
    {
        return template == null ? "" : template.replace("{site}", site == null ? "" : site).replace("{token}", token);
    }

    private static boolean notBlank(String s)
    {
        return s != null && !s.isBlank();
    }
}
