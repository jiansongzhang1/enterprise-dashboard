package com.fivetech.promo.budget;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推广分析 · 预算与消耗读写数据平台（《市场预算、消耗：后端端到端联调手册》2026-11-06）。
 *
 * <ul>
 *   <li>写：采集平台统一提交接口 {@code POST {collectBaseUrl}/v1/imports/market-cost:submit}、{@code market-budget:submit}，
 *       Bearer token + Idempotency-Key，同步返回结果；站点（租户）由 token 决定，请求体不带身份；</li>
 *   <li>读：UDS 数据集 {@code import_yata_promo_cost / budget}，走现有 UDS 客户端（地址、证书、X-Uds-* 身份头）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "promo.budget-import")
public class BudgetImportProperties
{
    /** 写入开关：关闭时导入只做校验、不写 */
    private boolean enabled = false;

    /** 读取开关：关闭时表格不查 UDS（显示「—」），预算模板不预填，导入也不能合并已有数据（写入同样不可用） */
    private boolean readEnabled = true;

    /** 采集接口地址，不带 /v1：内网 http://10.32.16.5:8090，本机转发 http://127.0.0.1:18090 */
    private String collectBaseUrl = "";

    /** 采集 Bearer token（只从环境变量读）；须与 UDS 身份指向同一站点 */
    private String token = "";

    /** 多个站点各有 token 时按站点编码配置；没配的站点用 token */
    private Map<String, String> siteTokens = new LinkedHashMap<>();

    private String costSubmitPath = "/v1/imports/market-cost:submit";

    private String budgetSubmitPath = "/v1/imports/market-budget:submit";

    /** 单次提交的 HTTP 超时（手册建议至少 20 秒） */
    private int requestTimeoutSeconds = 20;

    /** 一次导入请求里，对 202 / 429 / 503 / 超时最多重发多久；超过后返回「處理中」，用户可点「重新查詢結果」用同一 key 再发 */
    private int submitWaitSeconds = 60;

    /** 待确认覆盖、处理中的提交在内存里保留多久（平台的确认编号默认 10 分钟有效） */
    private int pendingTtlMinutes = 30;

    /** 单次提交上限（手册默认 5000 行、2 MiB；超限不拆同一天，报错） */
    private int maxRows = 5000;

    private int maxBytes = 2 * 1024 * 1024;

    private String udsSpendDataset = "import_yata_promo_cost";

    private String udsBudgetDataset = "import_yata_promo_budget";

    /** 读 UDS 的 limit（ONLINE 单次最多 10000 行，不偏移翻页） */
    private int udsLimit = 10_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReadEnabled() { return readEnabled; }
    public void setReadEnabled(boolean readEnabled) { this.readEnabled = readEnabled; }
    public String getCollectBaseUrl() { return collectBaseUrl; }
    public void setCollectBaseUrl(String collectBaseUrl) { this.collectBaseUrl = collectBaseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Map<String, String> getSiteTokens() { return siteTokens; }
    public void setSiteTokens(Map<String, String> siteTokens) { this.siteTokens = siteTokens == null ? new LinkedHashMap<>() : siteTokens; }
    public String getCostSubmitPath() { return costSubmitPath; }
    public void setCostSubmitPath(String costSubmitPath) { this.costSubmitPath = costSubmitPath; }
    public String getBudgetSubmitPath() { return budgetSubmitPath; }
    public void setBudgetSubmitPath(String budgetSubmitPath) { this.budgetSubmitPath = budgetSubmitPath; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int v) { this.requestTimeoutSeconds = v; }
    public int getSubmitWaitSeconds() { return submitWaitSeconds; }
    public void setSubmitWaitSeconds(int v) { this.submitWaitSeconds = v; }
    public int getPendingTtlMinutes() { return pendingTtlMinutes; }
    public void setPendingTtlMinutes(int v) { this.pendingTtlMinutes = v; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    public String getUdsSpendDataset() { return udsSpendDataset; }
    public void setUdsSpendDataset(String udsSpendDataset) { this.udsSpendDataset = udsSpendDataset; }
    public String getUdsBudgetDataset() { return udsBudgetDataset; }
    public void setUdsBudgetDataset(String udsBudgetDataset) { this.udsBudgetDataset = udsBudgetDataset; }
    public int getUdsLimit() { return udsLimit; }
    public void setUdsLimit(int udsLimit) { this.udsLimit = udsLimit; }

    /** UDS 数据集 */
    public String udsDataset(BudgetKind kind)
    {
        return kind == BudgetKind.SPEND ? udsSpendDataset : udsBudgetDataset;
    }

    /** 提交接口路径 */
    public String submitPath(BudgetKind kind)
    {
        return kind == BudgetKind.SPEND ? costSubmitPath : budgetSubmitPath;
    }

    /** 金额字段：cost_amount / budget_amount */
    public static String amountField(BudgetKind kind)
    {
        return kind == BudgetKind.SPEND ? "cost_amount" : "budget_amount";
    }

    /** 站点的 token：site-tokens 里有就用，没有用默认 */
    public String tokenOf(String site)
    {
        if (site != null)
        {
            for (Map.Entry<String, String> e : siteTokens.entrySet())
            {
                if (e.getKey() != null && e.getKey().trim().equalsIgnoreCase(site.trim()) && e.getValue() != null
                    && !e.getValue().isBlank())
                {
                    return e.getValue().trim();
                }
            }
        }
        return token == null ? "" : token.trim();
    }

    /** 写入可用：开关打开、配了地址、能读已有数据（整日替换前要合并） */
    public boolean writeReady()
    {
        return enabled && readEnabled && collectBaseUrl != null && !collectBaseUrl.isBlank();
    }
}
