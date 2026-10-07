package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出任务状态（GET /dashboard/export/tasks/{jobId}）。
 * terminal=false 继续轮询；terminal=true 停止轮询，urls 非空就下载，否则展示 message。
 *
 * @author fivetech
 */
public class ExportTaskStatusVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 任务编号 */
    private String jobId;

    /** 是否终态（完成 / 失败 / 已取消） */
    private boolean terminal;

    /** 提示文案 */
    private String message;

    /** 下载链接，仅完成时有值，1 小时有效；文件超过 1 GB 会拆成多个 */
    private List<String> urls = new ArrayList<>();

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<String> getUrls() { return urls; }
    public void setUrls(List<String> urls) { this.urls = urls; }
}
