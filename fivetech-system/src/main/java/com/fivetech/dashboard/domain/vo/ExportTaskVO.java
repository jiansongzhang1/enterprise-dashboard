package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import com.fivetech.dashboard.gateway.ExportJob;

/**
 * 异步导出任务：本系统记录的归属信息 + 数据平台的作业快照。
 *
 * @author fivetech
 */
public class ExportTaskVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 作业编号（即数据平台的 jobId） */
    private String jobId;

    /** member / deposit / withdraw / bet / benchmark */
    private String kind;

    /** 报表名称，如「投注明细」 */
    private String label;

    /** 本系统受理时间 */
    private String submittedAt;

    /** 建议文件名（预签名链接跨域，浏览器实际保存名以对象存储 key 为准） */
    private String suggestedFileName;

    /** 数据平台作业状态；列表接口里为 null（列表不逐个查询平台） */
    private ExportJob job;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
    public String getSuggestedFileName() { return suggestedFileName; }
    public void setSuggestedFileName(String suggestedFileName) { this.suggestedFileName = suggestedFileName; }
    public ExportJob getJob() { return job; }
    public void setJob(ExportJob job) { this.job = job; }
}
