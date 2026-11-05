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
