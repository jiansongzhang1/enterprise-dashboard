package com.fivetech.promo.budget;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入结果（FY-56 R10 结果页）。
 *
 * <ul>
 *   <li>status：SUCCESS 全部成功 / PARTIAL 部分失败 / ALL_FAILED 全部失败 / ALL_EMPTY 全部为空 / REJECTED 整份不导入（F1–F7）。</li>
 *   <li>total = valid + failed + empty；total 为表头以外、非空白的行。</li>
 *   <li>dateFrom / dateTo / channelCount 只算有效行；有效 0 行时为 null / 0（结果页只显示文件名）。</li>
 *   <li>currencyNote：表头金额列写的是「消耗 (USDT)」这类旧模板时的提示；REJECTED 时为 null。</li>
 *   <li>failures：按行号排列；date / promoter / channel / amount 为文件原值，空为 null（前端显示「（空）」）。</li>
 *   <li>written：有效行是否已写入。数据存放在 UDS 侧，写入暂未接入，目前恒为 false（writeNote 说明）。</li>
 * </ul>
 *
 * @author fivetech
 */
public class BudgetImportResult
{
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String ALL_FAILED = "ALL_FAILED";
    public static final String ALL_EMPTY = "ALL_EMPTY";
    public static final String REJECTED = "REJECTED";

    private String kind;
    private String fileName;
    private String status;
    private String title;

    /** REJECTED 时的文件级编号（F1–F7）与说明文字 */
    private String fileCode;
    private String fileMessage;

    private int total;
    private int valid;
    private int failed;
    private int empty;

    private String dateFrom;
    private String dateTo;
    private int channelCount;

    private String currencyNote;

    private List<Failure> failures = new ArrayList<>();

    private boolean written;
    private String writeNote;

    /** 失败的行：文件原值照写，空为 null（前端写「（空）」） */
    public static class Failure
    {
        private int row;
        private String code;
        private String reason;
        private String date;
        private String promoterId;
        private String promoterName;
        private String channelId;
        private String channelName;
        private String amount;

        public Failure()
        {
        }

        Failure(int row, String code, String reason, String[] raw)
        {
            this.row = row;
            this.code = code;
            this.reason = reason;
            this.date = raw[0];
            this.promoterId = raw[1];
            this.promoterName = raw[2];
            this.channelId = raw[3];
            this.channelName = raw[4];
            this.amount = raw[5];
        }

        public int getRow() { return row; }
        public String getCode() { return code; }
        public String getReason() { return reason; }
        public String getDate() { return date; }
        public String getPromoterId() { return promoterId; }
        public String getPromoterName() { return promoterName; }
        public String getChannelId() { return channelId; }
        public String getChannelName() { return channelName; }
        public String getAmount() { return amount; }
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFileCode() { return fileCode; }
    public void setFileCode(String fileCode) { this.fileCode = fileCode; }
    public String getFileMessage() { return fileMessage; }
    public void setFileMessage(String fileMessage) { this.fileMessage = fileMessage; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getValid() { return valid; }
    public void setValid(int valid) { this.valid = valid; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public int getEmpty() { return empty; }
    public void setEmpty(int empty) { this.empty = empty; }
    public String getDateFrom() { return dateFrom; }
    public void setDateFrom(String dateFrom) { this.dateFrom = dateFrom; }
    public String getDateTo() { return dateTo; }
    public void setDateTo(String dateTo) { this.dateTo = dateTo; }
    public int getChannelCount() { return channelCount; }
    public void setChannelCount(int channelCount) { this.channelCount = channelCount; }
    public String getCurrencyNote() { return currencyNote; }
    public void setCurrencyNote(String currencyNote) { this.currencyNote = currencyNote; }
    public List<Failure> getFailures() { return failures; }
    public void setFailures(List<Failure> failures) { this.failures = failures; }
    public boolean isWritten() { return written; }
    public void setWritten(boolean written) { this.written = written; }
    public String getWriteNote() { return writeNote; }
    public void setWriteNote(String writeNote) { this.writeNote = writeNote; }
}
