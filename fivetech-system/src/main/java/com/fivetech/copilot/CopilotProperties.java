package com.fivetech.copilot;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Yata Copilot（AI 能力）配置：application.yml 的 {@code copilot.*}。
 *
 * <p>API Key 只从环境变量 {@code ANTHROPIC_API_KEY} 读，不写进 yml 或代码；为空时 AI 功能不调用模型，
 * 业务照常（例如订阅简报照常发送，只是没有 AI 摘要）。</p>
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "copilot")
public class CopilotProperties
{
    /** 总开关 */
    private boolean enabled = true;

    /** 默认模型（页面不选时用） */
    private String defaultModel = "claude-haiku-5-5";

    /** 页面上可选的模型；id 以 Anthropic /v1/models 返回的为准 */
    private List<Model> models = new ArrayList<>(List.of(new Model("claude-haiku-5-5", "Claude Haiku 5.5")));

    private Anthropic anthropic = new Anthropic();

    private Brief brief = new Brief();

    /** 开关打开且配了 API Key */
    public boolean isConfigured()
    {
        return enabled && anthropic.getApiKey() != null && !anthropic.getApiKey().trim().isEmpty();
    }

    /** 按 id 找可选模型；不在列表里返回 null */
    public Model find(String id)
    {
        if (id == null)
        {
            return null;
        }
        for (Model m : models)
        {
            if (m.getId() != null && m.getId().equalsIgnoreCase(id.trim()))
            {
                return m;
            }
        }
        return null;
    }

    /** 实际使用的模型：传入的为空或不在列表里时用默认模型 */
    public String effectiveModel(String id)
    {
        Model m = find(id);
        return m != null ? m.getId() : defaultModel;
    }

    /** 模型显示名；不在列表里时就用 id */
    public String label(String id)
    {
        Model m = find(id);
        return m == null || m.getLabel() == null || m.getLabel().isEmpty() ? id : m.getLabel();
    }

    public static class Model
    {
        private String id;

        private String label;

        public Model()
        {
        }

        public Model(String id, String label)
        {
            this.id = id;
            this.label = label;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    /** Anthropic Claude API（直连）；以后接 Bedrock 再加一组配置 */
    public static class Anthropic
    {
        private String baseUrl = "https://api.anthropic.com";

        /** 只从环境变量注入：copilot.anthropic.api-key: ${ANTHROPIC_API_KEY:} */
        private String apiKey = "";

        private String version = "2023-06-01";

        private int connectTimeoutSeconds = 10;

        /** 单次请求超时（秒） */
        private int timeoutSeconds = 60;

        /** 429 / 5xx / 529 时的重试次数 */
        private int retries = 2;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
    }

    /** 订阅简报的 AI 摘要 */
    public static class Brief
    {
        /** 每天最多调用几次（全部站点、定时 + 立即发送 + 测试 + 预览合计；按数据库日期） */
        private int dailyLimit = 20;

        /** 单次最多输出 token */
        private int maxTokens = 800;

        public int getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    /** 订阅简报 · 数据异动分析 */
    public static class Anomaly
    {
        /** 每天最多调用几次（全部站点；与 AI 摘要分开计数） */
        private int dailyLimit = 20;

        private int maxTokens = 400;

        /** 分析文字最多几个字（不计空白），超过不采用 */
        private int maxChars = 100;

        /** 明显波动：非比率类指标变化率绝对值 ≥ 它（%） */
        private java.math.BigDecimal changePct = new java.math.BigDecimal("20");

        /** 明显波动：比率类指标变化 ≥ 它（百分点） */
        private java.math.BigDecimal changePt = new java.math.BigDecimal("5");

        /** 最多给模型几条告警事件 */
        private int maxEvents = 10;

        public int getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public java.math.BigDecimal getChangePct() { return changePct; }
        public void setChangePct(java.math.BigDecimal changePct) { this.changePct = changePct; }
        public java.math.BigDecimal getChangePt() { return changePt; }
        public void setChangePt(java.math.BigDecimal changePt) { this.changePt = changePt; }
        public int getMaxEvents() { return maxEvents; }
        public void setMaxEvents(int maxEvents) { this.maxEvents = maxEvents; }
    }

    private Anomaly anomaly = new Anomaly();

    /** 管理后台 · AI 助手（右下角聊天） */
    public static class Chat
    {
        /** 模型；为空用 default-model */
        private String model;

        /** 每人每天最多问几次（按数据库日期） */
        private int dailyLimitPerUser = 50;

        private int maxTokens = 1500;

        /** 最多带上最近几条消息（含本次提问） */
        private int maxMessages = 20;

        /** 单条提问最多几个字 */
        private int maxChars = 2000;

        /** 站点数据上下文缓存秒数（同一站点几分钟内的提问共用一份数据，少打 UDS） */
        private int contextCacheSeconds = 300;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDailyLimitPerUser() { return dailyLimitPerUser; }
        public void setDailyLimitPerUser(int dailyLimitPerUser) { this.dailyLimitPerUser = dailyLimitPerUser; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getContextCacheSeconds() { return contextCacheSeconds; }
        public void setContextCacheSeconds(int contextCacheSeconds) { this.contextCacheSeconds = contextCacheSeconds; }
    }

    private Chat chat = new Chat();

    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat == null ? new Chat() : chat; }

    public Anomaly getAnomaly() { return anomaly; }
    public void setAnomaly(Anomaly anomaly) { this.anomaly = anomaly == null ? new Anomaly() : anomaly; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public List<Model> getModels() { return models; }
    public void setModels(List<Model> models) { this.models = models == null ? new ArrayList<>() : models; }
    public Anthropic getAnthropic() { return anthropic; }
    public void setAnthropic(Anthropic anthropic) { this.anthropic = anthropic == null ? new Anthropic() : anthropic; }
    public Brief getBrief() { return brief; }
    public void setBrief(Brief brief) { this.brief = brief == null ? new Brief() : brief; }
}
