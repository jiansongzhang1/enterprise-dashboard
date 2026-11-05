package com.fivetech.copilot.domain;

import java.time.LocalDateTime;

/**
 * 一次 AI 调用的记录（表 copilot_usage_log）：用量对账、每日次数上限都看它。
 *
 * @author fivetech
 */
public class CopilotUsage
{
    public static final String SUCCESS = "SUCCESS";

    public static final String FAILED = "FAILED";

    /** 模型返回了，但没通过后端校验（例如出现了数据里没有的数字），结果不采用 */
    public static final String REJECTED = "REJECTED";

    private Long id;

    /** 功能：BRIEF 订阅简报摘要 / IMPORT Excel 导入（后续） */
    private String feature;

    private String siteCode;

    /** 业务 ID，如订阅 ID */
    private String refId;

    private String model;

    private String status;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer latencyMs;

    private String error;

    private String operator;

    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFeature() { return feature; }
    public void setFeature(String feature) { this.feature = feature; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
