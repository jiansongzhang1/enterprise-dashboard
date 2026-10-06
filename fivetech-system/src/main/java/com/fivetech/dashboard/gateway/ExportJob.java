package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据平台异步导出作业的状态快照。
 * <p>
 * 状态机：PENDING → RUNNING → DONE / FAILED / CANCELLED，后三个是终态。
 * DONE 时 {@link #files} 里每个文件都带一个现签的预签名下载链接（1 小时有效），
 * 链接过期后再查一次作业即可拿到新链接；文件本身保留 7 天（{@link #expiresAt}）。
 *
 * @author fivetech
 */
public class ExportJob implements Serializable
{
    private static final long serialVersionUID = 1L;

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private String jobId;

    private String status;

    /** 命中 7 天内同一份导出时为 true，此时 jobId 是旧作业 */
    private boolean deduplicated;

    private Long rowCount;

    private Long bytes;

    private List<ExportFile> files = new ArrayList<>();

    /** 文件过期时间（桶生命周期删除） */
    private String expiresAt;

    private String errorCode;

    private String errorMessage;

    private String createdAt;

    private String startedAt;

    private String finishedAt;

    /** 预估代价等级（S/M/L…），提交时返回 */
    private String costTier;

    private Long scanRowsEst;

    public boolean isTerminal()
    {
        return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    /** 单个导出文件。超过 1 GB 会切成多个，调用方必须按数组处理 */
    public static class ExportFile implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private String url;

        private String key;

        private Long bytes;

        private Long rows;

        private String urlExpiresAt;

        /** 建议的下载文件名（本系统生成，带正确扩展名；多文件时带 _part 序号） */
        private String fileName;

        public String getFileName()
        {
            return fileName;
        }

        public void setFileName(String fileName)
        {
            this.fileName = fileName;
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Long getBytes() { return bytes; }
        public void setBytes(Long bytes) { this.bytes = bytes; }
        public Long getRows() { return rows; }
        public void setRows(Long rows) { this.rows = rows; }
        public String getUrlExpiresAt() { return urlExpiresAt; }
        public void setUrlExpiresAt(String urlExpiresAt) { this.urlExpiresAt = urlExpiresAt; }
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isDeduplicated() { return deduplicated; }
    public void setDeduplicated(boolean deduplicated) { this.deduplicated = deduplicated; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Long getBytes() { return bytes; }
    public void setBytes(Long bytes) { this.bytes = bytes; }
    public List<ExportFile> getFiles() { return files; }
    public void setFiles(List<ExportFile> files) { this.files = files; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public String getCostTier() { return costTier; }
    public void setCostTier(String costTier) { this.costTier = costTier; }
    public Long getScanRowsEst() { return scanRowsEst; }
    public void setScanRowsEst(Long scanRowsEst) { this.scanRowsEst = scanRowsEst; }
}
