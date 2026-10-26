package com.fivetech.monitor.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 监控管理配置（application.yml 的 {@code monitor.*}）。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties
{
    /**
     * 每个站点同时启用的规则上限（一期 40）。一个指标只能有一条规则由唯一索引保证，
     * 这里再限住总数：判定任务每个站点每轮只发一次 UDS 请求取全部指标，规则数决定了请求里的字段数。
     * 0 或负数表示不限。
     */
    private int maxEnabledRulesPerSite = 40;

    private Engine engine = new Engine();

    private Lark lark = new Lark();

    public int getMaxEnabledRulesPerSite()
    {
        return maxEnabledRulesPerSite;
    }

    public void setMaxEnabledRulesPerSite(int maxEnabledRulesPerSite)
    {
        this.maxEnabledRulesPerSite = maxEnabledRulesPerSite;
    }

    public Engine getEngine()
    {
        return engine;
    }

    public void setEngine(Engine engine)
    {
        this.engine = engine == null ? new Engine() : engine;
    }

    public Lark getLark()
    {
        return lark;
    }

    public void setLark(Lark lark)
    {
        this.lark = lark == null ? new Lark() : lark;
    }

    /**
     * 监控告警用的 Lark 自建应用（一期一个应用服务所有站点，凭证只放环境变量）。
     * 与 notification.lark.enabled 无关：配了 App ID / App Secret 就能拉群列表。
     */
    public static class Lark
    {
        private String appId;

        /** 只写不读：不进日志、不返回给前端 */
        private String appSecret;

        /** 同一站点多少秒内重复打开群下拉直接返回上次拉取结果；0 表示每次都实时拉取 */
        private int chatsCacheSeconds = 30;

        /** 管理后台「手动触发测试告警」固定发到的 Lark 群 chat_id（oc_ 开头）；为空时该功能不可用 */
        private String testChatId;

        public String getAppId()
        {
            return appId;
        }

        public void setAppId(String appId)
        {
            this.appId = appId;
        }

        public String getAppSecret()
        {
            return appSecret;
        }

        public void setAppSecret(String appSecret)
        {
            this.appSecret = appSecret;
        }

        public int getChatsCacheSeconds()
        {
            return chatsCacheSeconds;
        }

        public void setChatsCacheSeconds(int chatsCacheSeconds)
        {
            this.chatsCacheSeconds = chatsCacheSeconds;
        }

        public String getTestChatId()
        {
            return testChatId;
        }

        public void setTestChatId(String testChatId)
        {
            this.testChatId = testChatId;
        }

        @Override
        public String toString()
        {
            return "Lark{appId=" + appId + ", appSecret=" + (appSecret == null || appSecret.isEmpty() ? "<未配置>" : "<已配置>")
                    + ", chatsCacheSeconds=" + chatsCacheSeconds + ", testChatId=" + testChatId + "}";
        }
    }

    /** 判定引擎 */
    public static class Engine
    {
        /** 总开关；关闭时不注册定时任务 */
        private boolean enabled = false;

        /** 每个数据小时最后一次检查的分钟（:50）；此时值仍为空的指标本周期不判定（不记录、不告警、不通知） */
        private int lastCheckMinute = 50;

        /** 事件起点最多回看的小时数 */
        private int lookbackHours = 72;

        /** 轮询锁的有效期（秒），要短于轮询间隔 */
        private int lockSeconds = 540;

        /**
         * 每个站点每轮最多请求 UDS 几次：第 1 次取全部指标；只有第 1 次失败（非「数据未就绪」）才发第 2 次。
         * 限流（429）时 UdsClient 自身的等待重试不另计。
         */
        private int udsMaxCalls = 2;

        /**
         * 跌幅类指标的基准字段：指标编码 → dashboard_overview_hour_v2 里的列名（UDS 提供近 4 周同星期同小时的中位数）。
         * 没配置的跌幅类规则无法判定，写 judge_error「跌幅基准字段未配置」。
         */
        private Map<String, String> baselineFields = new LinkedHashMap<>();

        /** 告警消息里「查看」的后台异常事件页地址；为空则不带链接 */
        private String eventPageUrl;

        /** 发送任务每个站点每轮最多投递几条（每分钟一轮） */
        private int notifyBatchSize = 20;

        /** 发送任务每轮的时间预算（秒），超过后剩下的留到下一轮；要短于发送锁的有效期 */
        private int notifyBudgetSeconds = 40;

        /** 发送锁 monitor:lock:notify 的有效期（秒），要短于发送间隔（1 分钟） */
        private int notifyLockSeconds = 55;

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public int getLastCheckMinute()
        {
            return lastCheckMinute;
        }

        public void setLastCheckMinute(int lastCheckMinute)
        {
            this.lastCheckMinute = lastCheckMinute;
        }

        public int getLookbackHours()
        {
            return lookbackHours;
        }

        public void setLookbackHours(int lookbackHours)
        {
            this.lookbackHours = lookbackHours;
        }

        public int getLockSeconds()
        {
            return lockSeconds;
        }

        public void setLockSeconds(int lockSeconds)
        {
            this.lockSeconds = lockSeconds;
        }

        public int getUdsMaxCalls()
        {
            return udsMaxCalls;
        }

        public void setUdsMaxCalls(int udsMaxCalls)
        {
            this.udsMaxCalls = udsMaxCalls;
        }

        public Map<String, String> getBaselineFields()
        {
            return baselineFields;
        }

        public void setBaselineFields(Map<String, String> baselineFields)
        {
            this.baselineFields = baselineFields == null ? new LinkedHashMap<>() : baselineFields;
        }

        public String getEventPageUrl()
        {
            return eventPageUrl;
        }

        public void setEventPageUrl(String eventPageUrl)
        {
            this.eventPageUrl = eventPageUrl;
        }

        public int getNotifyBatchSize()
        {
            return notifyBatchSize;
        }

        public void setNotifyBatchSize(int notifyBatchSize)
        {
            this.notifyBatchSize = notifyBatchSize;
        }

        public int getNotifyBudgetSeconds()
        {
            return notifyBudgetSeconds;
        }

        public void setNotifyBudgetSeconds(int notifyBudgetSeconds)
        {
            this.notifyBudgetSeconds = notifyBudgetSeconds;
        }

        public int getNotifyLockSeconds()
        {
            return notifyLockSeconds;
        }

        public void setNotifyLockSeconds(int notifyLockSeconds)
        {
            this.notifyLockSeconds = notifyLockSeconds;
        }
    }
}
