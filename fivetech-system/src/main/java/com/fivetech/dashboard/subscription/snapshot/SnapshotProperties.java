package com.fivetech.dashboard.subscription.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订阅截图（application.yml 的 {@code dashboard.subscription.snapshot.*}）：后端用本机无头 Chromium 打开运营总览页、
 * 截图后上传 Lark 放进订阅卡片。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "dashboard.subscription.snapshot")
public class SnapshotProperties
{
    /** 总开关：关闭时订阅照常发文字卡片，截图配置不生效 */
    private boolean enabled = false;

    /** Chromium / Chrome 可执行文件（如 /usr/bin/google-chrome） */
    private String chromiumPath = "/usr/bin/google-chrome";

    /** 额外的启动参数（以 root 运行时要加 --no-sandbox） */
    private List<String> chromiumArgs = new ArrayList<>();

    /** 截图帐号：后端每次截图给它签发临时令牌（不配密码） */
    private String username = "";

    /** 临时令牌有效期（秒）；截完立即作废，到期也自动失效 */
    private int tokenTtlSeconds = 180;

    /** 运营总览页地址（不带查询条件），如 https://xxx.cloudfront.net/zh-TW/overview */
    private String pageUrl = "";

    /** 令牌写进哪个 cookie（为空不写；Yata 前端用 localStorage） */
    private String tokenCookie = "";

    /** 令牌写进 localStorage 的键（为空不写） */
    private String tokenLocalStorageKey = "access_token";

    /** 令牌在 localStorage 里的值（{token} = JWT 原文） */
    private String tokenLocalStorageValue = "{token}";

    /** 当前站点的 localStorage 键（{userId} = 截图帐号的用户 ID；为空不写） */
    private String siteStorageKey = "yata.site.{userId}";

    /** 当前站点的值（{site} = 订阅的站点编码） */
    private String siteStorageValue = "{site}";

    /** 运营总览筛选条件的 localStorage 键（值为订阅的筛选 JSON；为空不写） */
    private String filtersStorageKey = "overview-filters";

    /** 额外写进 localStorage 的键值；键和值里可用 {site} {token} {userId} {filters} */
    private Map<String, String> localStorage = new LinkedHashMap<>();

    /** 页面宽度、首屏高度（CSS 像素） */
    private int width = 1280;

    private int height = 900;

    /** 截图最大高度（超出截断），防止长页面图太大 */
    private int maxHeight = 6000;

    /** 像素比：2 更清晰，图约大 3–4 倍 */
    private double scale = 1.5;

    /** 只截这个元素（CSS 选择器）；为空截整页 */
    private String captureSelector = "";

    /** 出现这个元素才算页面就绪（CSS 选择器）；为空只看网络空闲 */
    private String readySelector = "";

    /** 仍在加载的标志：这些元素可见时继续等（逗号分隔的 CSS 选择器） */
    private String busySelector = ".el-loading-mask, .ant-spin-spinning, .ant-skeleton-active, [aria-busy=\"true\"]";

    /** 截图前隐藏的元素（导航、菜单、聊天浮窗等，逗号分隔的 CSS 选择器） */
    private String hideSelector = "";

    /** 地址里含这个片段说明跳到了登录页（令牌无效） */
    private String loginPathKeyword = "login";

    /** 网络连续空闲多少毫秒算加载完 */
    private int idleMillis = 800;

    /** 就绪后再等多少毫秒（图表动画） */
    private int settleMillis = 1500;

    /** 单张截图超时（秒） */
    private int timeoutSeconds = 45;

    /** 排队等待上一张截图的最长时间（秒）；超过本次不截 */
    private int queueSeconds = 120;

    /** 浏览器截多少张后重启一次（释放内存） */
    private int restartAfter = 50;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getChromiumPath() { return chromiumPath; }
    public void setChromiumPath(String chromiumPath) { this.chromiumPath = chromiumPath; }
    public List<String> getChromiumArgs() { return chromiumArgs; }
    public void setChromiumArgs(List<String> chromiumArgs) { this.chromiumArgs = chromiumArgs == null ? new ArrayList<>() : chromiumArgs; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(int tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public String getTokenCookie() { return tokenCookie; }
    public void setTokenCookie(String tokenCookie) { this.tokenCookie = tokenCookie; }
    public String getTokenLocalStorageKey() { return tokenLocalStorageKey; }
    public void setTokenLocalStorageKey(String tokenLocalStorageKey) { this.tokenLocalStorageKey = tokenLocalStorageKey; }
    public String getTokenLocalStorageValue() { return tokenLocalStorageValue; }
    public void setTokenLocalStorageValue(String tokenLocalStorageValue) { this.tokenLocalStorageValue = tokenLocalStorageValue; }
    public String getSiteStorageKey() { return siteStorageKey; }
    public void setSiteStorageKey(String siteStorageKey) { this.siteStorageKey = siteStorageKey; }
    public String getSiteStorageValue() { return siteStorageValue; }
    public void setSiteStorageValue(String siteStorageValue) { this.siteStorageValue = siteStorageValue; }
    public String getFiltersStorageKey() { return filtersStorageKey; }
    public void setFiltersStorageKey(String filtersStorageKey) { this.filtersStorageKey = filtersStorageKey; }
    public Map<String, String> getLocalStorage() { return localStorage; }
    public void setLocalStorage(Map<String, String> localStorage) { this.localStorage = localStorage == null ? new LinkedHashMap<>() : localStorage; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public int getMaxHeight() { return maxHeight; }
    public void setMaxHeight(int maxHeight) { this.maxHeight = maxHeight; }
    public double getScale() { return scale; }
    public void setScale(double scale) { this.scale = scale; }
    public String getCaptureSelector() { return captureSelector; }
    public void setCaptureSelector(String captureSelector) { this.captureSelector = captureSelector; }
    public String getReadySelector() { return readySelector; }
    public void setReadySelector(String readySelector) { this.readySelector = readySelector; }
    public String getBusySelector() { return busySelector; }
    public void setBusySelector(String busySelector) { this.busySelector = busySelector; }
    public String getHideSelector() { return hideSelector; }
    public void setHideSelector(String hideSelector) { this.hideSelector = hideSelector; }
    public String getLoginPathKeyword() { return loginPathKeyword; }
    public void setLoginPathKeyword(String loginPathKeyword) { this.loginPathKeyword = loginPathKeyword; }
    public int getIdleMillis() { return idleMillis; }
    public void setIdleMillis(int idleMillis) { this.idleMillis = idleMillis; }
    public int getSettleMillis() { return settleMillis; }
    public void setSettleMillis(int settleMillis) { this.settleMillis = settleMillis; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getQueueSeconds() { return queueSeconds; }
    public void setQueueSeconds(int queueSeconds) { this.queueSeconds = queueSeconds; }
    public int getRestartAfter() { return restartAfter; }
    public void setRestartAfter(int restartAfter) { this.restartAfter = restartAfter; }
}
