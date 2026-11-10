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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
 * 订阅截图：用截图帐号的临时令牌，让本机无头 Chromium 打开运营总览页，截图后上传 Lark。
 *
 * <p>前端的登录令牌、当前站点、筛选条件都在 localStorage（{@code access-token}、{@code yata.site.{userId}}、
 * {@code overview-filters}），打开页面前写进去：筛选条件取订阅的 snapshot_filters，为空按统计范围生成（与卡片同一区间）。</p>
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

    /** 筛选条件的键、值允许的字符（值里可有占位符 {from} {to}） */
    static final Pattern FILTER_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    static final Pattern FILTER_VALUE = Pattern.compile("[A-Za-z0-9_.:{}\\-]{0,64}");

    /** 自定义日期：yyyy-MM-dd 或占位符 */
    static final Pattern FILTER_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\{from}|\\{to}");

    static final int MAX_FILTERS = 500;

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        String filters;
        try
        {
            url = pageUrl(props.getPageUrl(), site);
            filters = filters(s.getSnapshotFilters(), SubscriptionRange.of(s.getRangeCode()), windowFrom, windowTo);
        }
        catch (IllegalArgumentException e)
        {
            return new Result(null, null, e.getMessage());
        }
        String described = url + " · overview-filters=" + filters;
        boolean got = false;
        try
        {
            got = one.tryAcquire(Math.max(1, props.getQueueSeconds()), TimeUnit.SECONDS);
            if (!got)
            {
                return new Result(null, described, "截圖排隊超過 " + props.getQueueSeconds() + " 秒，本次不帶截圖");
            }
            return new Result(shoot(url, site, filters), described, null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new Result(null, described, "截圖被中斷");
        }
        catch (RuntimeException e)
        {
            log.warn("[snapshot] 订阅 {} 截图失败 {}：{}", s.getId(), described, e.getMessage());
            return new Result(null, described, e.getMessage());
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
    private byte[] shoot(String url, String site, String filters)
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
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("{site}", site == null ? "" : site);
            vars.put("{token}", token.getJwt());
            vars.put("{userId}", String.valueOf(token.getUserId()));
            vars.put("{filters}", filters);
            if (notBlank(props.getTokenCookie()))
            {
                shot.cookieName = props.getTokenCookie();
                shot.cookieValue = token.getJwt();
            }
            if (notBlank(props.getTokenLocalStorageKey()))
            {
                shot.localStorage.put(fill(props.getTokenLocalStorageKey(), vars), fill(props.getTokenLocalStorageValue(), vars));
            }
            if (notBlank(props.getSiteStorageKey()))
            {
                shot.localStorage.put(fill(props.getSiteStorageKey(), vars), fill(props.getSiteStorageValue(), vars));
            }
            if (notBlank(props.getFiltersStorageKey()))
            {
                shot.localStorage.put(fill(props.getFiltersStorageKey(), vars), filters);
            }
            props.getLocalStorage().forEach((k, v) -> shot.localStorage.put(fill(k, vars), fill(v, vars)));
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
            log.info("[snapshot] 截图完成 site={} {} 字节 {} ms url={} filters={}", site, png.length, System.currentTimeMillis() - t0, url, filters);
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

    // ------------------------------------------------------------------ 地址与筛选条件

    /** 截图页地址：page-url（可用 {site}）；筛选条件不放地址里，写 localStorage。包内可见供测试 */
    static String pageUrl(String base, String site)
    {
        if (!notBlank(base))
        {
            throw new IllegalArgumentException("未配置運營總覽頁地址（DASHBOARD_SNAPSHOT_PAGE_URL）");
        }
        return base.trim().replace("{site}", URLEncoder.encode(site == null ? "" : site, StandardCharsets.UTF_8));
    }

    /**
     * 写进 localStorage overview-filters 的 JSON。订阅没填时按统计范围生成（与卡片同一区间）：
     * 今日 / 昨日按小时用页面的预设范围，近 7 / 30 日用 custom + 具体日期（不含发送当天）。
     * 值里的 {from} {to} 换成统计区间首尾日期（to 为最后一天）。包内可见供测试。
     */
    static String filters(String stored, SubscriptionRange range, LocalDateTime from, LocalDateTime to)
    {
        String json = notBlank(stored) ? stored.trim() : defaultFilters(range);
        String fromDate = from == null ? "" : from.toLocalDate().toString();
        LocalDate last = to == null ? null
            : (to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? to.toLocalDate().minusDays(1) : to.toLocalDate());
        return json.replace("{from}", fromDate).replace("{to}", last == null ? "" : last.toString());
    }

    /** 统计范围 → 页面筛选条件 */
    static String defaultFilters(SubscriptionRange range)
    {
        if (range == null || range == SubscriptionRange.TODAY)
        {
            return "{\"range\":\"today\",\"gran\":\"hour\",\"cmp\":\"previous-period\"}";
        }
        if (range == SubscriptionRange.YESTERDAY)
        {
            return "{\"range\":\"yesterday\",\"gran\":\"hour\",\"cmp\":\"previous-period\"}";
        }
        return "{\"range\":\"custom\",\"gran\":\"day\",\"from\":\"{from}\",\"to\":\"{to}\",\"cmp\":\"previous-period\"}";
    }

    /**
     * 规范化用户填的筛选条件，返回紧凑的 JSON 字符串；空返回 ""（按统计范围生成）；不合法返回 null。
     *
     * <p>接受：JSON 对象（Map）、JSON 字符串、或带查询参数的页面地址 / 查询串（range=…&gran=…，转成 JSON）。
     * 必须有 range；键为字母开头的标识符、值为短字符串（字母数字与 _ . : - 和占位符）；range = custom 时 from、to 必填，
     * 为 yyyy-MM-dd 或 {from} / {to}。最多 500 字。</p>
     */
    public static String normalizeFilters(Object raw)
    {
        if (raw == null)
        {
            return "";
        }
        Map<String, String> m = new LinkedHashMap<>();
        if (raw instanceof Map)
        {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet())
            {
                if (e.getValue() != null && !(e.getValue() instanceof String || e.getValue() instanceof Number))
                {
                    return null;
                }
                m.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        else
        {
            String text = String.valueOf(raw).trim();
            if (text.isEmpty())
            {
                return "";
            }
            if (text.startsWith("{"))
            {
                JsonNode node;
                try
                {
                    node = JSON.readTree(text);
                }
                catch (RuntimeException e)
                {
                    return null;
                }
                if (!node.isObject())
                {
                    return null;
                }
                for (Map.Entry<String, JsonNode> e : node.properties())
                {
                    JsonNode v = e.getValue();
                    if (!(v.isString() || v.isNumber() || v.isNull()))
                    {
                        return null;
                    }
                    m.put(e.getKey(), v.isNull() ? null : v.asString());
                }
            }
            else
            {
                // 带参数的页面地址或查询串
                int q = text.indexOf('?');
                String query = q >= 0 ? text.substring(q + 1) : text.contains("://") ? "" : text;
                int hash = query.indexOf('#');
                if (hash >= 0)
                {
                    query = query.substring(0, hash);
                }
                for (String pair : query.split("&"))
                {
                    if (pair.isEmpty())
                    {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    String k = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                    String v = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    m.put(k, v);
                }
                if (m.isEmpty())
                {
                    return "";
                }
            }
        }
        m.values().removeIf(v -> v == null);
        String range = m.get("range");
        if (range == null || range.isBlank())
        {
            return null;
        }
        for (Map.Entry<String, String> e : m.entrySet())
        {
            if (!FILTER_KEY.matcher(e.getKey()).matches() || !FILTER_VALUE.matcher(e.getValue()).matches())
            {
                return null;
            }
        }
        if ("custom".equals(range)
            && (m.get("from") == null || m.get("to") == null
                || !FILTER_DATE.matcher(m.get("from")).matches() || !FILTER_DATE.matcher(m.get("to")).matches()))
        {
            return null;
        }
        String out = JSON.writeValueAsString(m);
        return out.length() > MAX_FILTERS ? null : out;
    }

    private static String fill(String template, Map<String, String> vars)
    {
        if (template == null)
        {
            return "";
        }
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet())
        {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private static boolean notBlank(String s)
    {
        return s != null && !s.isBlank();
    }
}
