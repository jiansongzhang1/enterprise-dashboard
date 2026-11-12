package com.fivetech.promo.budget;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 预算与消耗同步写入采集平台（《市场预算、消耗：后端端到端联调手册》2026-11-06）。
 *
 * <h3>为什么先合并</h3>
 * 「上传成功会替换该类型、该租户、目标日期的全部数据」。FY-56 的规则是同一「日期 × 推广渠道」覆盖、金额为空的行保留原值，
 * 所以提交前先从 UDS 读出这些日期已发布的行，用本次有效行覆盖同一推广渠道 ID，其余（含渠道字典外的渠道）原样带上，整日提交。
 *
 * <h3>结果处理（手册第 3 节）</h3>
 * <ul>
 *   <li>200 SUCCEEDED：写入成功；</li>
 *   <li>202 PROCESSING / 429 / 503 / 5xx / 没响应：按 Retry-After（没有时 2、4、8… 秒，封顶 30 秒，加抖动）用同一 URL、同一 key、
 *       同一请求体重发，最多等 submit-wait-seconds；还没结果返回 PROCESSING，用户点「重新查詢結果」再原样发；</li>
 *   <li>409 overwrite_confirmation_required / confirmation_expired / confirmation_changed：没有导入，把整日替换的日期给用户确认，
 *       确认后同一 key、同一 rows 加上 confirmation_id 再发；不自动同意；</li>
 *   <li>409 idempotency_conflict / stale_base、400 row_validation_failed、422、401、403、404、413、415：失败，不重试。</li>
 * </ul>
 * 待确认、处理中的提交（key、请求体）放在内存里，按 pending-ttl-minutes 过期；服务重启后需重新上传文件（新的一次上传）。
 *
 * @author fivetech
 */
@Component
public class CollectBudgetSpendWriter implements BudgetSpendWriter
{
    private static final Logger log = LoggerFactory.getLogger(CollectBudgetSpendWriter.class);

    private final BudgetImportProperties props;

    private final CollectSubmitApi api;

    private final BudgetDataReader reader;

    private final ObjectMapper objectMapper;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** 测试里换成不睡眠 */
    Sleeper sleeper = ms -> Thread.sleep(ms);

    interface Sleeper
    {
        void sleep(long ms) throws InterruptedException;
    }

    /** 一次上传：key、请求体（rows 部分）在第一次发送前定下，重试不变 */
    static final class Pending
    {
        final String token = UUID.randomUUID().toString().replace("-", "");
        final String site;
        final BudgetKind kind;
        final String key = UUID.randomUUID().toString();
        final String rowsJson;
        final List<Map<String, Object>> rows;
        /** rows[i] 来自文件第几行；null = 平台原有数据（合并带上的） */
        final List<Integer> lines;
        final String operator;
        final long createdAt = System.currentTimeMillis();
        /** 平台最近给的确认编号（等用户确认） */
        volatile String offeredConfirmation;
        /** 最近一次实际发出去的确认编号（处理中重发时原样带上） */
        volatile String sentConfirmation;
        volatile String state;
        /** 文件里的日期（判断是否跨年） */
        Set<LocalDate> fileDates = Set.of();
        /** 文件中的「日期 × 推广渠道」已有值的日期（确认页覆盖提示用；为空不提示） */
        Set<LocalDate> overwrittenDates = Set.of();

        Pending(String site, BudgetKind kind, String rowsJson, List<Map<String, Object>> rows, List<Integer> lines, String operator)
        {
            this.site = site;
            this.kind = kind;
            this.rowsJson = rowsJson;
            this.rows = rows;
            this.lines = lines;
            this.operator = operator;
        }
    }

    public CollectBudgetSpendWriter(BudgetImportProperties props, CollectSubmitApi api, BudgetDataReader reader,
        ObjectMapper objectMapper)
    {
        this.props = props;
        this.api = api;
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isEnabled()
    {
        return props.writeReady();
    }

    @Override
    public BudgetWriteOutcome write(String siteCode, BudgetKind kind, List<BudgetImportValidator.Record> records, String operator)
    {
        if (!isEnabled())
        {
            return BudgetWriteOutcome.of(BudgetWriteOutcome.DISABLED, "寫入數據平台尚未開啟，本次只做了校驗，沒有寫入數據。");
        }
        evictExpired();
        TreeSet<LocalDate> dates = new TreeSet<>();
        records.forEach(r -> dates.add(r.getDate()));
        List<BudgetDataReader.Row> existing;
        try
        {
            existing = reader.rows(kind, dates.first(), dates.last());
        }
        catch (RuntimeException e)
        {
            log.warn("[promo-budget] 读取已有{}失败 site={} {}~{}：{}", kind.getLabel(), siteCode, dates.first(), dates.last(), e.getMessage());
            return BudgetWriteOutcome.of(BudgetWriteOutcome.FAILED, "讀取這些日期已有的" + kind.getLabel()
                + "失敗，為免整日替換時丟失其他推廣渠道的數據，本次沒有寫入；請稍後重新上傳。（" + e.getMessage() + "）");
        }
        List<Integer> lines = new ArrayList<>();
        List<Map<String, Object>> rows = merge(kind, dates, existing, records, lines);
        if (rows.size() > props.getMaxRows())
        {
            return BudgetWriteOutcome.of(BudgetWriteOutcome.FAILED, "合併後共 " + rows.size() + " 行，超過數據平台單次上限 "
                + props.getMaxRows() + " 行，本次沒有寫入；請縮短日期範圍分次匯入。");
        }
        String rowsJson = objectMapper.writeValueAsString(rows);
        if (rowsJson.getBytes(StandardCharsets.UTF_8).length + 64 > props.getMaxBytes())
        {
            return BudgetWriteOutcome.of(BudgetWriteOutcome.FAILED, "提交內容超過數據平台單次上限，本次沒有寫入；請縮短日期範圍分次匯入。");
        }
        Pending p = new Pending(siteCode, kind, rowsJson, rows, lines, operator);
        p.fileDates = dates;
        p.overwrittenDates = overwrittenDates(dates, existing, records);
        log.info("[promo-budget] 提交{} site={} key={} 日期 {}~{} 有效 {} 行，合并后 {} 行 operator={}", kind.getLabel(), siteCode, p.key,
            dates.first(), dates.last(), records.size(), rows.size(), operator);
        return send(p, null);
    }

    @Override
    public BudgetWriteOutcome resume(String token, boolean confirm, String siteCode, String operator)
    {
        evictExpired();
        Pending p = token == null ? null : pending.get(token);
        if (p == null || !p.site.equalsIgnoreCase(siteCode))
        {
            return BudgetWriteOutcome.of(BudgetWriteOutcome.FAILED, "這次匯入已失效（超過 " + props.getPendingTtlMinutes()
                + " 分鐘或服務已重啟），沒有寫入；請重新上傳文件。");
        }
        if (BudgetWriteOutcome.CONFIRM_REQUIRED.equals(p.state))
        {
            if (!confirm)
            {
                pending.remove(p.token);
                log.info("[promo-budget] {} 取消覆盖 key={} operator={}", p.kind.getLabel(), p.key, operator);
                return BudgetWriteOutcome.of(BudgetWriteOutcome.CANCELLED, "已取消，沒有寫入。");
            }
            log.info("[promo-budget] {} 确认覆盖 key={} confirmation={} operator={}", p.kind.getLabel(), p.key, p.offeredConfirmation, operator);
            return send(p, p.offeredConfirmation);
        }
        return send(p, p.sentConfirmation);
    }

    // ------------------------------------------------------------------ 发送与结果

    private BudgetWriteOutcome send(Pending p, String confirmationId)
    {
        p.sentConfirmation = confirmationId;
        byte[] body = body(p.rowsJson, confirmationId).getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + Math.max(0, props.getSubmitWaitSeconds()) * 1000L;
        int attempt = 0;
        while (true)
        {
            CollectSubmitApi.Response r = api.submit(p.site, p.kind, p.key, body);
            int s = r.getStatus();
            String code = r.text("code");
            if (s == 200)
            {
                pending.remove(p.token);
                if (!"SUCCEEDED".equalsIgnoreCase(r.text("status")))
                {
                    return failed(r, "數據平台返回 " + r.text("status") + "：" + nvl(r.text("message")) + "，本次沒有寫入。");
                }
                return succeeded(p, r);
            }
            if (s == 409 && ("overwrite_confirmation_required".equals(code) || "confirmation_expired".equals(code)
                || "confirmation_changed".equals(code)))
            {
                return confirmRequired(p, r, code);
            }
            boolean retry = s == 202 || s == 0 || s == 429 || s >= 500;
            if (!retry)
            {
                pending.remove(p.token);
                return failed(r, failureMessage(p, r));
            }
            long wait = waitMs(s == 202 && r.getRetryAfterSeconds() == null ? Integer.valueOf(2) : r.getRetryAfterSeconds(), attempt++);
            if (System.currentTimeMillis() + wait > deadline)
            {
                p.state = BudgetWriteOutcome.PROCESSING;
                pending.put(p.token, p);
                BudgetWriteOutcome o = BudgetWriteOutcome.of(BudgetWriteOutcome.PROCESSING, s == 202
                    ? "數據平台已接收，仍在導入，結果未確定。請稍後點「重新查詢結果」，不要重新上傳同一文件。"
                    : "數據平台暫時沒有回應（" + (s == 0 ? nvl(r.getError()) : "HTTP " + s) + "），結果未確定。請稍後點「重新查詢結果」，不要重新上傳同一文件。");
                o.setToken(p.token);
                o.setSubmitRows(p.rows.size());
                o.setRequestId(r.getRequestId());
                return o;
            }
            sleep(wait);
        }
    }

    private BudgetWriteOutcome succeeded(Pending p, CollectSubmitApi.Response r)
    {
        BudgetWriteOutcome o = BudgetWriteOutcome.of(BudgetWriteOutcome.SUCCEEDED, null);
        o.setImportId(r.text("import_id"));
        JsonNode rev = r.getBody() == null ? null : r.getBody().get("published_revision");
        o.setPublishedRevision(rev != null && rev.isNumber() ? rev.asLong() : null);
        JsonNode rc = r.getBody() == null ? null : r.getBody().get("row_count");
        o.setSubmitRows(rc != null && rc.isNumber() ? rc.asInt() : p.rows.size());
        o.setDates(strings(r.getBody(), "dates"));
        o.setRequestId(r.getRequestId());
        o.setMessage("已寫入數據平台：" + String.join("、", o.getDates()) + " 共 " + o.getSubmitRows() + " 行（整日替換）。");
        log.info("[promo-budget] {} 写入成功 key={} import={} revision={} rows={}", p.kind.getLabel(), p.key, o.getImportId(),
            o.getPublishedRevision(), o.getSubmitRows());
        return o;
    }

    private BudgetWriteOutcome confirmRequired(Pending p, CollectSubmitApi.Response r, String code)
    {
        String cid = r.text("confirmation_id");
        if (cid == null || cid.isBlank())
        {
            pending.remove(p.token);
            return failed(r, "數據平台要求確認覆蓋，但沒有返回確認編號，本次沒有寫入。");
        }
        p.offeredConfirmation = cid;
        p.state = BudgetWriteOutcome.CONFIRM_REQUIRED;
        pending.put(p.token, p);
        BudgetWriteOutcome o = BudgetWriteOutcome.of(BudgetWriteOutcome.CONFIRM_REQUIRED, null);
        o.setToken(p.token);
        o.setErrorCode(code);
        o.setOverwriteDates(strings(r.getBody(), "overwrite_dates"));
        o.setDates(strings(r.getBody(), "dates"));
        JsonNode rc = r.getBody() == null ? null : r.getBody().get("row_count");
        o.setSubmitRows(rc != null && rc.isNumber() ? rc.asInt() : p.rows.size());
        o.setRequestId(r.getRequestId());
        // 确认页覆盖提示（OPT-14）：只在文件中的「日期 × 推广渠道」已有值时提示；都没有已有值时不提示，仍要确认
        String again = "overwrite_confirmation_required".equals(code) ? "" : "覆蓋範圍有變化或確認已過期，請重新確認。";
        String notice = overwriteNotice(p.kind, p.overwrittenDates, p.fileDates);
        String message = again + (notice == null ? "" : notice);
        o.setMessage(message.isEmpty() ? null : message);
        return o;
    }

    /** 文件中的「日期 × 推广渠道」在数据平台已有值的日期（与 {@link #merge} 同一个键） */
    static Set<LocalDate> overwrittenDates(Set<LocalDate> dates, List<BudgetDataReader.Row> existing, List<BudgetImportValidator.Record> records)
    {
        Set<String> had = new java.util.HashSet<>();
        for (BudgetDataReader.Row r : existing)
        {
            if (r.getDate() != null && dates.contains(r.getDate()) && !blank(r.getChannelId()) && !blank(r.getPromoterId())
                && r.getAmount() != null)
            {
                had.add(r.getDate() + "|" + r.getChannelId());
            }
        }
        TreeSet<LocalDate> out = new TreeSet<>();
        for (BudgetImportValidator.Record r : records)
        {
            if (had.contains(r.getDate() + "|" + r.getChannelId()))
            {
                out.add(r.getDate());
            }
        }
        return out;
    }

    /**
     * 确认页覆盖提示（OPT-14）：「11-05 – 11-11 已有消耗。確認後，文件中的推廣渠道按本次金額覆蓋，其他推廣渠道保持不變。」
     * 连续日期合并为一段，多段用「、」；日期写 MM-DD，文件跨年时写完整日期。没有已有值返回 null。
     */
    static String overwriteNotice(BudgetKind kind, Set<LocalDate> overwritten, Set<LocalDate> fileDates)
    {
        if (overwritten == null || overwritten.isEmpty())
        {
            return null;
        }
        boolean crossYear = (fileDates == null || fileDates.isEmpty() ? overwritten : fileDates).stream()
            .map(LocalDate::getYear).distinct().count() > 1;
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern(crossYear ? "yyyy-MM-dd" : "MM-dd");
        List<String> ranges = new ArrayList<>();
        LocalDate start = null;
        LocalDate prev = null;
        for (LocalDate d : new TreeSet<>(overwritten))
        {
            if (prev != null && !d.equals(prev.plusDays(1)))
            {
                ranges.add(range(start, prev, f));
                start = null;
            }
            if (start == null)
            {
                start = d;
            }
            prev = d;
        }
        ranges.add(range(start, prev, f));
        return String.join("、", ranges) + " 已有" + kind.getLabel() + "。確認後，文件中的推廣渠道按本次金額覆蓋，其他推廣渠道保持不變。";
    }

    private static String range(LocalDate from, LocalDate to, java.time.format.DateTimeFormatter f)
    {
        return from.equals(to) ? from.format(f) : from.format(f) + " – " + to.format(f);
    }

    private static BudgetWriteOutcome failed(CollectSubmitApi.Response r, String message)
    {
        BudgetWriteOutcome o = BudgetWriteOutcome.of(BudgetWriteOutcome.FAILED, message);
        o.setErrorCode(r.text("code"));
        o.setImportId(r.text("import_id"));
        o.setRequestId(r.getRequestId());
        return o;
    }

    /** 失败时给用户看的话（繁体）。包内可见供测试 */
    String failureMessage(Pending p, CollectSubmitApi.Response r)
    {
        int s = r.getStatus();
        String code = nvl(r.text("code"));
        if (s == 401 || s == 403)
        {
            return "數據平台拒絕寫入（憑證或權限），本次沒有寫入，請聯繫管理員。";
        }
        if (s == 404)
        {
            return "數據平台找不到提交接口（" + code + "），本次沒有寫入，請聯繫管理員核對配置。";
        }
        if (s == 413 || s == 415)
        {
            return "提交內容超過數據平台限制或格式不被接受（HTTP " + s + "），本次沒有寫入；請縮短日期範圍分次匯入。";
        }
        switch (code)
        {
            case "row_validation_failed":
                return rowErrors(p, r.getBody());
            case "stale_base":
                return "匯入期間這些日期的" + p.kind.getLabel() + "被另一次上傳更新過，本次沒有覆蓋；請刷新表格核對後再決定是否重新上傳。";
            case "idempotency_conflict":
                return "提交內容與數據平台記錄的同一次上傳不一致，本次沒有寫入，請重新上傳文件。";
            default:
                return "數據平台返回錯誤（HTTP " + s + (code.isEmpty() ? "" : "，" + code) + "）：" + nvl(r.text("message")) + "；本次沒有寫入。";
        }
    }

    /** row_validation_failed：errors[].row 从 1 起，对应提交的 rows；对回文件行号（合并带上的原有数据另说明） */
    private String rowErrors(Pending p, JsonNode body)
    {
        JsonNode errors = body == null ? null : body.get("errors");
        if (errors == null || !errors.isArray() || errors.isEmpty())
        {
            return "數據平台拒收了部分行，本次沒有寫入。";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode e : errors)
        {
            int idx = e.path("row").asInt(0) - 1;
            String field = e.get("field") == null || e.get("field").isNull() ? null : e.get("field").asString();
            String code = e.get("code") == null ? "" : e.get("code").asString();
            String where = "第 " + (idx + 1) + " 筆";
            if (idx >= 0 && idx < p.rows.size())
            {
                Map<String, Object> row = p.rows.get(idx);
                Integer line = p.lines.get(idx);
                String name = row.get("channel_name") == null ? String.valueOf(row.get("channel_id")) : String.valueOf(row.get("channel_name"));
                where = (line == null ? "平台原有數據 " : "文件第 " + line + " 行 ") + row.get("biz_date") + " " + name;
            }
            parts.add(where + (field == null ? "" : " " + field) + "：" + code);
            if (parts.size() >= 5)
            {
                break;
            }
        }
        JsonNode invalid = body.get("invalid_rows");
        String more = invalid != null && invalid.isNumber() && invalid.asInt() > parts.size() ? " 等共 " + invalid.asInt() + " 行" : "";
        return "數據平台拒收（" + String.join("；", parts) + more + "），本次沒有寫入。";
    }

    // ------------------------------------------------------------------ 合并

    /**
     * 这些日期已发布的行，被本次同一「日期 × 推广渠道 ID」的行覆盖；其余照旧带上（含渠道字典外的渠道）。
     * 按日期、推广商 ID、推广渠道 ID 排序；lines 同步填每行来自文件第几行（原有数据为 null）。包内可见供测试。
     */
    static List<Map<String, Object>> merge(BudgetKind kind, Set<LocalDate> dates, List<BudgetDataReader.Row> existing,
        List<BudgetImportValidator.Record> records, List<Integer> lines)
    {
        Map<String, Object[]> byKey = new LinkedHashMap<>();
        for (BudgetDataReader.Row r : existing)
        {
            if (r.getDate() != null && dates.contains(r.getDate()) && !blank(r.getChannelId()) && !blank(r.getPromoterId())
                && r.getAmount() != null)
            {
                byKey.put(r.getDate() + "|" + r.getChannelId(), new Object[] {
                    row(kind, r.getDate(), r.getPromoterId(), r.getPromoterName(), r.getChannelId(), r.getChannelName(), r.getAmount()), null });
            }
        }
        for (BudgetImportValidator.Record r : records)
        {
            byKey.put(r.getDate() + "|" + r.getChannelId(), new Object[] {
                row(kind, r.getDate(), r.getPromoterId(), r.getPromoterName(), r.getChannelId(), r.getChannelName(), r.getAmount()), r.getRow() });
        }
        List<Object[]> list = new ArrayList<>(byKey.values());
        list.sort((x, y) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) x[0];
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) y[0];
            int c = String.valueOf(a.get("biz_date")).compareTo(String.valueOf(b.get("biz_date")));
            if (c == 0)
            {
                c = String.valueOf(a.get("promoter_id")).compareTo(String.valueOf(b.get("promoter_id")));
            }
            if (c == 0)
            {
                c = String.valueOf(a.get("channel_id")).compareTo(String.valueOf(b.get("channel_id")));
            }
            return c;
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] o : list)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o[0];
            out.add(m);
            lines.add((Integer) o[1]);
        }
        return out;
    }

    /** 提交行：ID 原样字符串（保留前导零）；名称可为 null；金额为十进制字符串 */
    static Map<String, Object> row(BudgetKind kind, LocalDate date, String promoterId, String promoterName, String channelId,
        String channelName, BigDecimal amount)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("biz_date", date.toString());
        m.put("promoter_id", promoterId);
        m.put("promoter_name", promoterName);
        m.put("channel_id", channelId);
        m.put("channel_name", channelName);
        m.put(BudgetImportProperties.amountField(kind), BudgetDataReader.amountText(amount));
        return m;
    }

    /** 请求体：确认时只在前面加 confirmation_id，rows 原样 */
    String body(String rowsJson, String confirmationId)
    {
        return confirmationId == null ? "{\"rows\":" + rowsJson + "}"
            : "{\"confirmation_id\":" + objectMapper.writeValueAsString(confirmationId) + ",\"rows\":" + rowsJson + "}";
    }

    // ------------------------------------------------------------------ 工具

    /** 等待：至少 Retry-After；没有时 2、4、8… 秒，封顶 30 秒，加抖动 */
    static long waitMs(Integer retryAfterSeconds, int attempt)
    {
        if (retryAfterSeconds != null)
        {
            return retryAfterSeconds * 1000L + ThreadLocalRandom.current().nextLong(0, 300);
        }
        long base = Math.min(30_000L, 2000L << Math.min(attempt, 4));
        return base + ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 4));
    }

    private void sleep(long ms)
    {
        try
        {
            sleeper.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void evictExpired()
    {
        long limit = System.currentTimeMillis() - Math.max(1, props.getPendingTtlMinutes()) * 60_000L;
        pending.values().removeIf(p -> p.createdAt < limit);
    }

    private static List<String> strings(JsonNode body, String field)
    {
        List<String> out = new ArrayList<>();
        JsonNode a = body == null ? null : body.get(field);
        if (a != null && a.isArray())
        {
            a.forEach(n -> out.add(n.asString()));
        }
        return out;
    }

    private static boolean blank(String s)
    {
        return s == null || s.isBlank();
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }
}
