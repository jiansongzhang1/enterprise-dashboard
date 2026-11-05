package com.fivetech.promo.analysis;

/**
 * 推广分析导出的文件：文件名、内容、类型（xlsx，生成失败时为 csv）、摘要（前端拼完成提示用）。
 *
 * @author fivetech
 */
public final class PromoFile
{
    private final String fileName;

    private final byte[] content;

    private final boolean csv;

    /** 如 days=3&channels=12&rows=40；前端拼「已匯出 {文件名}（{N} 天 × {M} 個推廣渠道）」 */
    private final String summary;

    PromoFile(String fileName, byte[] content, boolean csv, String summary)
    {
        this.fileName = fileName;
        this.content = content;
        this.csv = csv;
        this.summary = summary;
    }

    public String getFileName() { return fileName; }

    public byte[] getContent() { return content; }

    public boolean isCsv() { return csv; }

    public String getSummary() { return summary; }
}
