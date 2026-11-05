package com.fivetech.promo.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.promo.budget.BudgetFileReader.SheetData;
import com.fivetech.promo.budget.BudgetFileReader.SheetCell;
import com.fivetech.promo.budget.BudgetFileReader.SheetRow;

/**
 * 预算与消耗导入校验（FY-56 R8、R10；文件级 F1–F7、行级 V1–V17）。纯函数：不连库、不调接口，「今天」等由调用方传入。
 *
 * <p>模板 6 列：日期｜推廣商 ID｜推廣商名稱｜推廣渠道 ID｜推廣渠道名稱｜消耗（或 預算）。表头比较忽略空格。</p>
 *
 * <p>文件级按 F1 → F7 检查，遇到第一个即整份不导入。行级每行按 日期（V1–V6）→ 推广商（V7–V9）→ 推广渠道（V10–V13）
 * → 金额为空（不导入、不算失败）→ 金额格式（V14–V16）检查，遇到第一个问题即停；V17（同一天同一渠道重复，按推广渠道 ID）
 * 在各行检查完后对有效行与金额为空的行统一判断，重复的行全部失败。F7 只看通过日期校验（V1–V6）的行。</p>
 *
 * <p>ID 按值比较（1001 与 1001.0 相同，见 {@link ConfigCodes#idKey(Object)}）；名称去首尾空格、连续空格合一、不区分大小写，
 * 以 ID 为准、名称只核对。</p>
 *
 * @author fivetech
 */
public final class BudgetImportValidator
{
    public static final int MAX_ROWS = 10_000;

    public static final int MAX_DAYS = 31;

    /** 预算最远到今天起 90 天（当天可以导入） */
    public static final int BUDGET_AHEAD_DAYS = 90;

    /** 前 5 列表头（去掉空格后比较） */
    static final String[] HEADER = { "日期", "推廣商 ID", "推廣商名稱", "推廣渠道 ID", "推廣渠道名稱" };

    static final int AMOUNT_COL = 5;

    private static final Pattern AMOUNT_HEADER = Pattern.compile("^(消耗|預算)\\s*(?:[(（]\\s*(?i:usdt)\\s*[)）])?$");

    private static final Pattern DATE_TEXT = Pattern.compile("^(\\d{4})([-/])(\\d{2})\\2(\\d{2})$");

    private static final Pattern NUMBER_TEXT = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private BudgetImportValidator()
    {
    }

    /** 校验所需的上下文 */
    public static final class Context
    {
        final BudgetKind kind;

        /** 站点时区的今天 */
        final LocalDate today;

        /** 数据起始日（早于它报 V6） */
        final LocalDate dataStart;

        final List<PromoChannelDictionary.Promoter> promoters;

        public Context(BudgetKind kind, LocalDate today, LocalDate dataStart, List<PromoChannelDictionary.Promoter> promoters)
        {
            this.kind = kind;
            this.today = today;
            this.dataStart = dataStart;
            this.promoters = promoters == null ? List.of() : promoters;
        }
    }

    /** 一条有效记录（推广商、渠道为字典里的 ID 与名称） */
    public static final class Record
    {
        private final int row;
        private final LocalDate date;
        private final String promoterId;
        private final String promoterName;
        private final String channelId;
        private final String channelName;
        private final BigDecimal amount;

        Record(int row, LocalDate date, String promoterId, String promoterName, String channelId, String channelName,
            BigDecimal amount)
        {
            this.row = row;
            this.date = date;
            this.promoterId = promoterId;
            this.promoterName = promoterName;
            this.channelId = channelId;
            this.channelName = channelName;
            this.amount = amount;
        }

        public int getRow() { return row; }
        public LocalDate getDate() { return date; }
        public String getPromoterId() { return promoterId; }
        public String getPromoterName() { return promoterName; }
        public String getChannelId() { return channelId; }
        public String getChannelName() { return channelName; }
        public BigDecimal getAmount() { return amount; }
    }

    /** 校验结果 + 有效记录 */
    public static final class Outcome
    {
        private final BudgetImportResult result;
        private final List<Record> records;

        Outcome(BudgetImportResult result, List<Record> records)
        {
            this.result = result;
            this.records = records;
        }

        public BudgetImportResult getResult() { return result; }
        public List<Record> getRecords() { return records; }
    }

    // ------------------------------------------------------------------ 入口

    public static Outcome validate(String fileName, byte[] bytes, Context ctx)
    {
        BudgetImportResult r = new BudgetImportResult();
        r.setKind(ctx.kind.name());
        r.setFileName(fileName);
        String ext = extension(fileName);
        // F1
        if (!"xlsx".equals(ext) && !"csv".equals(ext))
        {
            return reject(r, "F1", "文件格式不支持", "只支持 .xlsx、.csv，請使用模板。");
        }
        // F2
        SheetData sheet;
        try
        {
            sheet = "xlsx".equals(ext) ? BudgetFileReader.readXlsx(bytes) : BudgetFileReader.readCsv(bytes);
        }
        catch (BudgetFileReader.UnreadableException e)
        {
            return reject(r, "F2", "文件無法讀取", "文件可能已損壞，請重新導出後上傳。");
        }
        // F3 / F4：表头比较忽略空格
        String label = ctx.kind.getLabel();
        String otherLabel = ctx.kind.other().getLabel();
        List<String> header = new ArrayList<>();
        sheet.header.forEach(h -> header.add(squash(h)));
        Matcher amountHeader = header.size() > AMOUNT_COL ? AMOUNT_HEADER.matcher(header.get(AMOUNT_COL)) : null;
        if (amountHeader != null && amountHeader.matches() && otherLabel.equals(amountHeader.group(1)))
        {
            return reject(r, "F3", "文件類型不符", "表頭是「" + otherLabel + "」，當前在匯入" + label + "。");
        }
        boolean headerOk = header.size() == HEADER.length + 1 && amountHeader != null && amountHeader.matches()
            && label.equals(amountHeader.group(1));
        for (int i = 0; headerOk && i < HEADER.length; i++)
        {
            headerOk = squash(HEADER[i]).equals(header.get(i));
        }
        if (!headerOk)
        {
            return reject(r, "F4", "表頭與模板不一致",
                "第 1 行應為「" + String.join("｜", HEADER) + "｜" + label + "」，請下載新模板。");
        }
        boolean legacyUnit = !header.get(AMOUNT_COL).equals(label);
        // F5 / F6：不计整行空白的行
        List<SheetRow> rows = new ArrayList<>();
        for (SheetRow row : sheet.rows)
        {
            if (!row.isBlank())
            {
                rows.add(row);
            }
        }
        if (rows.isEmpty())
        {
            return reject(r, "F5", "沒有數據", "文件裡除了表頭沒有其他行。");
        }
        if (rows.size() > MAX_ROWS)
        {
            return reject(r, "F6", "文件太大", "單個文件最多 10,000 行，請拆分後分別匯入。");
        }

        // 行级 V1–V14
        Dictionary dict = new Dictionary(ctx.promoters);
        Map<Integer, BudgetImportResult.Failure> failures = new LinkedHashMap<>();
        List<Checked> passed = new ArrayList<>();
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (SheetRow row : rows)
        {
            Checked c = new Checked(row);
            String reason = check(c, ctx, dict);
            if (c.date != null)
            {
                minDate = minDate == null || c.date.isBefore(minDate) ? c.date : minDate;
                maxDate = maxDate == null || c.date.isAfter(maxDate) ? c.date : maxDate;
            }
            if (reason != null)
            {
                failures.put(row.line, failure(row, c.code, reason));
            }
            else
            {
                passed.add(c);
            }
        }
        // F7：有效日期（通过 V1–V6）的跨度，首尾都算
        if (minDate != null && ChronoUnit.DAYS.between(minDate, maxDate) + 1 > MAX_DAYS)
        {
            return reject(r, "F7", "日期跨度太大", "單個文件最多 31 天，請拆分後分別匯入。");
        }
        // V17：有效行与金额为空的行一起判重（按推广渠道 ID）
        Map<String, List<Checked>> groups = new LinkedHashMap<>();
        for (Checked c : passed)
        {
            groups.computeIfAbsent(c.date + "|" + ConfigCodes.idKey(c.channelId), k -> new ArrayList<>()).add(c);
        }
        List<Record> records = new ArrayList<>();
        int empty = 0;
        Set<String> channels = new LinkedHashSet<>();
        LocalDate validFrom = null;
        LocalDate validTo = null;
        for (List<Checked> g : groups.values())
        {
            if (g.size() > 1)
            {
                List<String> lines = new ArrayList<>();
                g.forEach(c -> lines.add(String.valueOf(c.row.line)));
                String reason = "同一天同一渠道重複（第 " + String.join("、", lines) + " 行）";
                g.forEach(c -> failures.put(c.row.line, failure(c.row, "V17", reason)));
                continue;
            }
            Checked c = g.get(0);
            if (c.amount == null)
            {
                empty++;
                continue;
            }
            records.add(new Record(c.row.line, c.date, c.promoterId, c.promoterName, c.channelId, c.channelName, c.amount));
            channels.add(ConfigCodes.idKey(c.channelId));
            validFrom = validFrom == null || c.date.isBefore(validFrom) ? c.date : validFrom;
            validTo = validTo == null || c.date.isAfter(validTo) ? c.date : validTo;
        }
        records.sort(Comparator.comparingInt(Record::getRow));

        List<BudgetImportResult.Failure> list = new ArrayList<>(failures.values());
        list.sort(Comparator.comparingInt(BudgetImportResult.Failure::getRow));
        r.setFailures(list);
        r.setTotal(rows.size());
        r.setValid(records.size());
        r.setFailed(list.size());
        r.setEmpty(empty);
        if (!records.isEmpty())
        {
            r.setDateFrom(validFrom.toString());
            r.setDateTo(validTo.toString());
            r.setChannelCount(channels.size());
        }
        if (legacyUnit)
        {
            r.setCurrencyNote("金額按站點默認幣種匯入，不做換算（文件表頭寫的是「" + label + " (USDT)」）。");
        }
        int valid = records.size();
        int failed = list.size();
        if (valid > 0 && failed == 0)
        {
            r.setStatus(BudgetImportResult.SUCCESS);
            r.setTitle("已匯入 " + valid + " 行");
        }
        else if (valid > 0)
        {
            r.setStatus(BudgetImportResult.PARTIAL);
            r.setTitle("已匯入 " + valid + " 行，" + failed + " 行失敗");
        }
        else if (failed > 0)
        {
            r.setStatus(BudgetImportResult.ALL_FAILED);
            r.setTitle("未匯入：沒有有效的行");
        }
        else
        {
            r.setStatus(BudgetImportResult.ALL_EMPTY);
            r.setTitle("未匯入：所有行的金額都為空");
        }
        return new Outcome(r, records);
    }

    // ------------------------------------------------------------------ 行级

    /** 一行的识别结果；date 只在通过 V1–V6 时有值（F7 用），其余字段只在整行通过时有意义 */
    private static final class Checked
    {
        final SheetRow row;
        String code;
        LocalDate date;
        String promoterId;
        String promoterName;
        String channelId;
        String channelName;
        /** null = 金额为空 */
        BigDecimal amount;

        Checked(SheetRow row)
        {
            this.row = row;
        }
    }

    /** 返回失败原因（并写 c.code）；通过返回 null */
    private static String check(Checked c, Context ctx, Dictionary dict)
    {
        // 日期 V1–V6
        SheetCell dc = c.row.cell(0);
        LocalDate date = dc.date;
        if (date == null)
        {
            String t = dc.number != null ? dc.text : dc.text.trim();
            if (t.isEmpty())
            {
                return fail(c, "V1", "日期為空");
            }
            Matcher m = DATE_TEXT.matcher(t);
            if (!m.matches())
            {
                return fail(c, "V2", "日期格式不正確");
            }
            try
            {
                date = LocalDate.parse(m.group(1) + "-" + m.group(3) + "-" + m.group(4), YMD);
            }
            catch (RuntimeException e)
            {
                return fail(c, "V3", "日期不存在");
            }
        }
        if (ctx.kind == BudgetKind.SPEND && !date.isBefore(ctx.today))
        {
            return fail(c, "V4", "消耗只能是今天以前的日期");
        }
        if (ctx.kind == BudgetKind.BUDGET && date.isAfter(ctx.today.plusDays(BUDGET_AHEAD_DAYS)))
        {
            return fail(c, "V5", "預算最遠到今天起 90 天");
        }
        if (ctx.dataStart != null && date.isBefore(ctx.dataStart))
        {
            return fail(c, "V6", "早於數據起始日");
        }
        c.date = date;

        // 推广商 V7–V9：以 ID 为准，名称只核对
        String promoterIdText = c.row.cell(1).text.trim();
        if (promoterIdText.isEmpty())
        {
            return fail(c, "V7", "推廣商 ID 為空");
        }
        PromoChannelDictionary.Promoter promoter = dict.promoter(promoterIdText);
        if (promoter == null)
        {
            return fail(c, "V8", "找不到這個推廣商 ID");
        }
        if (!PromoChannelDictionary.key(promoter.getName()).equals(PromoChannelDictionary.key(c.row.cell(2).text)))
        {
            return fail(c, "V9", "推廣商名稱與 ID 不一致（ID " + promoterIdText + " 為「" + promoter.getName() + "」）");
        }
        // 推广渠道 V10–V13
        String channelIdText = c.row.cell(3).text.trim();
        if (channelIdText.isEmpty())
        {
            return fail(c, "V10", "推廣渠道 ID 為空");
        }
        PromoChannelDictionary.Channel channel = dict.channel(channelIdText);
        if (channel == null)
        {
            return fail(c, "V11", "找不到這個推廣渠道 ID");
        }
        if (!PromoChannelDictionary.key(channel.getName()).equals(PromoChannelDictionary.key(c.row.cell(4).text)))
        {
            return fail(c, "V12", "推廣渠道名稱與 ID 不一致（ID " + channelIdText + " 為「" + channel.getName() + "」）");
        }
        if (dict.ownerOf(channelIdText) != promoter)
        {
            return fail(c, "V13", "推廣渠道不屬於這個推廣商");
        }
        c.promoterId = promoter.getId();
        c.promoterName = promoter.getName();
        c.channelId = channel.getId();
        c.channelName = channel.getName();

        // 金额：为空不导入、不算失败；V14–V16
        SheetCell ac = c.row.cell(AMOUNT_COL);
        BigDecimal amount = ac.number;
        if (amount == null)
        {
            String t = ac.text.trim();
            if (t.isEmpty())
            {
                c.amount = null;
                return null;
            }
            if (!NUMBER_TEXT.matcher(t).matches())
            {
                return fail(c, "V14", "金額不是數字");
            }
            amount = new BigDecimal(t);
        }
        if (amount.signum() < 0)
        {
            return fail(c, "V15", "金額不能小於 0");
        }
        if (amount.stripTrailingZeros().scale() > 2)
        {
            return fail(c, "V16", "金額最多 2 位小數");
        }
        c.amount = amount;
        return null;
    }

    private static String fail(Checked c, String code, String reason)
    {
        c.code = code;
        return reason;
    }

    private static BudgetImportResult.Failure failure(SheetRow row, String code, String reason)
    {
        String[] raw = new String[BudgetFileReader.COLUMNS];
        for (int i = 0; i < raw.length; i++)
        {
            raw[i] = raw(row.cell(i));
        }
        return new BudgetImportResult.Failure(row.line, code, reason, raw);
    }

    /** 表头比较用：去掉全部空白 */
    static String squash(String s)
    {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 原值照文件显示；空为 null（前端写「（空）」） */
    private static String raw(SheetCell c)
    {
        return c.isBlank() ? null : c.text;
    }

    private static Outcome reject(BudgetImportResult r, String code, String title, String message)
    {
        r.setStatus(BudgetImportResult.REJECTED);
        r.setFileCode(code);
        r.setTitle("未匯入：" + title);
        r.setFileMessage(message);
        return new Outcome(r, List.of());
    }

    static String extension(String fileName)
    {
        if (fileName == null)
        {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 字典

    /** 按 ID 查推广商、推广渠道（{@link ConfigCodes#idKey(Object)}） */
    static final class Dictionary
    {
        private final Map<String, PromoChannelDictionary.Promoter> promoters = new HashMap<>();

        private final Map<String, PromoChannelDictionary.Channel> channels = new HashMap<>();

        /** idKey(渠道) → 推广商 */
        private final Map<String, PromoChannelDictionary.Promoter> owners = new HashMap<>();

        Dictionary(List<PromoChannelDictionary.Promoter> list)
        {
            for (PromoChannelDictionary.Promoter p : list)
            {
                if (p == null || ConfigCodes.idKey(p.getId()).isEmpty())
                {
                    continue;
                }
                promoters.putIfAbsent(ConfigCodes.idKey(p.getId()), p);
                for (PromoChannelDictionary.Channel ch : p.getChannels())
                {
                    String k = ConfigCodes.idKey(ch.getId());
                    if (!k.isEmpty() && channels.putIfAbsent(k, ch) == null)
                    {
                        owners.put(k, p);
                    }
                }
            }
        }

        PromoChannelDictionary.Promoter promoter(String id)
        {
            return promoters.get(ConfigCodes.idKey(id));
        }

        PromoChannelDictionary.Channel channel(String id)
        {
            return channels.get(ConfigCodes.idKey(id));
        }

        PromoChannelDictionary.Promoter ownerOf(String channelId)
        {
            return owners.get(ConfigCodes.idKey(channelId));
        }
    }
}
