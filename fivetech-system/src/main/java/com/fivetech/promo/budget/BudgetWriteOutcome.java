package com.fivetech.promo.budget;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次写入数据平台的结果（导入接口与「確認覆蓋 / 重新查詢結果」接口共用）。
 *
 * <ul>
 *   <li>SUCCEEDED：已写入（importId、publishedRevision、submitRows、dates）；</li>
 *   <li>CONFIRM_REQUIRED：这些日期已有数据，整日替换前要用户确认（overwriteDates、submitRows、token）；</li>
 *   <li>PROCESSING：平台仍在处理或暂时没响应，结果未确定（token：用同一 key 原样再发查结果，不要重新上传）；</li>
 *   <li>FAILED：没有写入（message 是原因）；CANCELLED：用户取消覆盖；DISABLED：写入未开启。</li>
 * </ul>
 *
 * @author fivetech
 */
public class BudgetWriteOutcome
{
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String CONFIRM_REQUIRED = "CONFIRM_REQUIRED";
    public static final String PROCESSING = "PROCESSING";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String DISABLED = "DISABLED";

    private String status;
    private String message;
    private String token;
    private List<String> dates = new ArrayList<>();
    private List<String> overwriteDates = new ArrayList<>();
    private Integer submitRows;
    private String importId;
    private Long publishedRevision;
    private String errorCode;
    private String requestId;

    public static BudgetWriteOutcome of(String status, String message)
    {
        BudgetWriteOutcome o = new BudgetWriteOutcome();
        o.status = status;
        o.message = message;
        return o;
    }

    public boolean isWritten()
    {
        return SUCCEEDED.equals(status);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public List<String> getDates() { return dates; }
    public void setDates(List<String> dates) { this.dates = dates == null ? new ArrayList<>() : dates; }
    public List<String> getOverwriteDates() { return overwriteDates; }
    public void setOverwriteDates(List<String> overwriteDates) { this.overwriteDates = overwriteDates == null ? new ArrayList<>() : overwriteDates; }
    public Integer getSubmitRows() { return submitRows; }
    public void setSubmitRows(Integer submitRows) { this.submitRows = submitRows; }
    public String getImportId() { return importId; }
    public void setImportId(String importId) { this.importId = importId; }
    public Long getPublishedRevision() { return publishedRevision; }
    public void setPublishedRevision(Long publishedRevision) { this.publishedRevision = publishedRevision; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
