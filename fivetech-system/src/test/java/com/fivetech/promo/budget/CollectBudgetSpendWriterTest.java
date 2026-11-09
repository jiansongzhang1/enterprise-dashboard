package com.fivetech.promo.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 预算 / 消耗同步写入：合并、请求体、200 / 202 / 409 确认 / 失败（不调真实接口） */
class CollectBudgetSpendWriterTest
{
    static final ObjectMapper OM = JsonMapper.builder().build();
    static final LocalDate D1 = LocalDate.of(2026, 12, 1);
    static final LocalDate D2 = LocalDate.of(2026, 12, 2);

    /** 按顺序返回预置响应，记录每次的 key 与请求体 */
    static final class FakeApi implements CollectSubmitApi
    {
        final List<Response> answers = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<BudgetKind> kinds = new ArrayList<>();

        public Response submit(String site, BudgetKind kind, String idempotencyKey, byte[] body)
        {
            keys.add(idempotencyKey);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            kinds.add(kind);
            return answers.remove(0);
        }
    }

    static CollectSubmitApi.Response resp(int status, String json, Integer retryAfter)
    {
        return new CollectSubmitApi.Response(status, json == null ? null : OM.readTree(json), retryAfter, "r1", status == 0 ? "timeout" : null);
    }

    static final class FakeReader extends BudgetDataReader
    {
        List<Row> rows = new ArrayList<>();
        RuntimeException error;

        FakeReader() { super(null, new BudgetImportProperties()); }

        @Override
        public List<Row> rows(BudgetKind kind, LocalDate from, LocalDate to)
        {
            if (error != null)
            {
                throw error;
            }
            return rows;
        }
    }

    static BudgetImportProperties props()
    {
        BudgetImportProperties p = new BudgetImportProperties();
        p.setEnabled(true);
        p.setCollectBaseUrl("http://collect");
        p.setToken("t");
        p.setSubmitWaitSeconds(60);
        return p;
    }

    static BudgetImportValidator.Record rec(int line, LocalDate d, String pid, String cid, String cname, String amount)
    {
        return new BudgetImportValidator.Record(line, d, pid, "推廣商" + pid, cid, cname, new BigDecimal(amount));
    }

    static FakeReader udsDay1()
    {
        FakeReader r = new FakeReader();
        r.rows.add(new BudgetDataReader.Row(D1, "1001", "hada", "2001", "DSP-hada", new BigDecimal("50.5")));
        r.rows.add(new BudgetDataReader.Row(D1, "1001", "hada", "2002", "meta-hada", new BigDecimal("80")));
        r.rows.add(new BudgetDataReader.Row(D1, "9", "舊推廣商", "0077", "字典外", new BigDecimal("1.2345")));
        r.rows.add(new BudgetDataReader.Row(LocalDate.of(2026, 11, 30), "1001", "hada", "2001", "DSP-hada", new BigDecimal("9")));
        return r;
    }

    static CollectBudgetSpendWriter writer(BudgetImportProperties p, FakeApi api, FakeReader reader)
    {
        CollectBudgetSpendWriter w = new CollectBudgetSpendWriter(p, api, reader, OM);
        w.sleeper = ms -> { };
        return w;
    }

    static final String OK = "{\"import_id\":\"imp1\",\"status\":\"SUCCEEDED\",\"message\":\"导入成功\",\"row_count\":4,"
        + "\"dates\":[\"2026-12-01\",\"2026-12-02\"],\"published_revision\":101}";

    @Test
    void mergeKeepsOtherChannelsOverridesSameChannelAndTracksFileLines()
    {
        List<Integer> lines = new ArrayList<>();
        List<Map<String, Object>> m = CollectBudgetSpendWriter.merge(BudgetKind.SPEND, Set.of(D1, D2), udsDay1().rows,
            List.of(rec(5, D1, "1001", "2002", "meta-hada", "120"), rec(7, D2, "1002", "2003", "TG-jason", "0")), lines);
        assertEquals(4, m.size());                                                  // 11-30 不在本次日期
        assertEquals("2026-12-01|1001|2001|50.5", key(m.get(0)));                   // 同一天别的渠道带上
        assertEquals("2026-12-01|1001|2002|120", key(m.get(1)));                    // 同渠道被覆盖
        assertEquals("2026-12-01|9|0077|1.2345", key(m.get(2)));                    // 字典外的渠道也带上，前导零不动
        assertEquals("2026-12-02|1002|2003|0", key(m.get(3)));
        assertEquals(java.util.Arrays.asList(null, 5, null, 7), lines);
    }

    private static String key(Map<String, Object> r)
    {
        return r.get("biz_date") + "|" + r.get("promoter_id") + "|" + r.get("channel_id") + "|" + r.get("cost_amount");
    }

    @Test
    void succeedsWithMergedFullDaysAndIdempotencyKey()
    {
        FakeApi api = new FakeApi();
        api.answers.add(resp(200, OK, null));
        BudgetWriteOutcome o = writer(props(), api, udsDay1()).write("SITE-IN", BudgetKind.SPEND,
            List.of(rec(2, D1, "1001", "2002", "meta-hada", "120"), rec(3, D2, "1002", "2003", "TG-jason", "30.5")), "admin");
        assertEquals(BudgetWriteOutcome.SUCCEEDED, o.getStatus());
        assertTrue(o.isWritten());
        assertEquals(101L, o.getPublishedRevision());
        assertEquals("imp1", o.getImportId());
        assertEquals(List.of("2026-12-01", "2026-12-02"), o.getDates());
        JsonNode body = OM.readTree(api.bodies.get(0));
        assertFalse(body.has("confirmation_id"));
        assertEquals(4, body.get("rows").size());
        assertEquals("120", body.get("rows").get(1).get("cost_amount").asString());
        assertTrue(body.get("rows").get(1).get("cost_amount").isString());     // 金额是字符串，不是 JSON 数字
        assertTrue(api.bodies.get(0).startsWith("{\"rows\":[{\"biz_date\":\"2026-12-01\",\"promoter_id\":\"1001\""));
        assertNotNull(api.keys.get(0));
        assertEquals(BudgetKind.SPEND, api.kinds.get(0));
    }

    @Test
    void processingIsResentWithSameKeyAndBodyUntilSucceeded()
    {
        FakeApi api = new FakeApi();
        api.answers.add(resp(202, "{\"import_id\":\"imp1\",\"status\":\"PROCESSING\"}", 2));
        api.answers.add(resp(503, "{\"code\":\"busy\"}", null));
        api.answers.add(resp(0, null, null));
        api.answers.add(resp(200, OK, null));
        BudgetWriteOutcome o = writer(props(), api, new FakeReader()).write("SITE-IN", BudgetKind.BUDGET,
            List.of(rec(2, D1, "1001", "2001", "DSP-hada", "500")), "admin");
        assertEquals(BudgetWriteOutcome.SUCCEEDED, o.getStatus());
        assertEquals(4, api.keys.size());
        assertEquals(1, api.keys.stream().distinct().count());
        assertEquals(1, api.bodies.stream().distinct().count());
        assertTrue(api.bodies.get(0).contains("\"budget_amount\":\"500\""));
    }

    @Test
    void overwriteNeedsConfirmationThenResendsSameRowsWithConfirmationId()
    {
        FakeApi api = new FakeApi();
        api.answers.add(resp(409, "{\"code\":\"overwrite_confirmation_required\",\"confirmation_id\":\"c1\",\"row_count\":3,"
            + "\"dates\":[\"2026-12-01\"],\"overwrite_dates\":[\"2026-12-01\"]}", null));
        api.answers.add(resp(200, OK, null));
        CollectBudgetSpendWriter w = writer(props(), api, udsDay1());
        BudgetWriteOutcome o = w.write("SITE-IN", BudgetKind.SPEND, List.of(rec(2, D1, "1001", "2002", "meta-hada", "120")), "admin");
        assertEquals(BudgetWriteOutcome.CONFIRM_REQUIRED, o.getStatus());
        assertFalse(o.isWritten());
        assertEquals(List.of("2026-12-01"), o.getOverwriteDates());
        assertEquals(3, o.getSubmitRows());
        assertTrue(o.getMessage().contains("整日替換"), o.getMessage());
        assertNotNull(o.getToken());

        assertEquals(BudgetWriteOutcome.FAILED, w.resume(o.getToken(), true, "SITE-BR", "admin").getStatus());   // 换了站点不行
        BudgetWriteOutcome done = w.resume(o.getToken(), true, "SITE-IN", "admin");
        assertEquals(BudgetWriteOutcome.SUCCEEDED, done.getStatus());
        assertEquals(api.keys.get(0), api.keys.get(1));
        JsonNode first = OM.readTree(api.bodies.get(0));
        JsonNode second = OM.readTree(api.bodies.get(1));
        assertEquals("c1", second.get("confirmation_id").asString());
        assertEquals(first.get("rows"), second.get("rows"));
        assertTrue(api.bodies.get(1).endsWith(api.bodies.get(0).substring("{\"rows\":".length())));
        // 用过的 token 失效
        assertEquals(BudgetWriteOutcome.FAILED, w.resume(o.getToken(), true, "SITE-IN", "admin").getStatus());
    }

    @Test
    void cancelOverwriteWritesNothing()
    {
        FakeApi api = new FakeApi();
        api.answers.add(resp(409, "{\"code\":\"overwrite_confirmation_required\",\"confirmation_id\":\"c1\",\"overwrite_dates\":[\"2026-12-01\"]}", null));
        CollectBudgetSpendWriter w = writer(props(), api, new FakeReader());
        BudgetWriteOutcome o = w.write("SITE-IN", BudgetKind.SPEND, List.of(rec(2, D1, "1001", "2001", "x", "1")), "admin");
        assertEquals(BudgetWriteOutcome.CANCELLED, w.resume(o.getToken(), false, "SITE-IN", "admin").getStatus());
        assertEquals(1, api.keys.size());
    }

    @Test
    void stillProcessingAfterWaitReturnsTokenToAskAgain()
    {
        FakeApi api = new FakeApi();
        BudgetImportProperties p = props();
        p.setSubmitWaitSeconds(0);
        api.answers.add(resp(202, "{\"status\":\"PROCESSING\"}", 2));
        CollectBudgetSpendWriter w = writer(p, api, new FakeReader());
        BudgetWriteOutcome o = w.write("SITE-IN", BudgetKind.SPEND, List.of(rec(2, D1, "1001", "2001", "x", "1")), "admin");
        assertEquals(BudgetWriteOutcome.PROCESSING, o.getStatus());
        assertTrue(o.getMessage().contains("重新查詢結果"));
        api.answers.add(resp(200, OK, null));
        assertEquals(BudgetWriteOutcome.SUCCEEDED, w.resume(o.getToken(), false, "SITE-IN", "admin").getStatus());
        assertEquals(api.keys.get(0), api.keys.get(1));
        assertEquals(api.bodies.get(0), api.bodies.get(1));
    }

    @Test
    void rowValidationFailureMapsToFileLine()
    {
        FakeApi api = new FakeApi();
        api.answers.add(resp(400, "{\"status\":\"FAILED\",\"code\":\"row_validation_failed\",\"invalid_rows\":1,"
            + "\"errors\":[{\"row\":2,\"field\":\"cost_amount\",\"code\":\"invalid_row_type\"}]}", null));
        BudgetWriteOutcome o = writer(props(), api, udsDay1()).write("SITE-IN", BudgetKind.SPEND,
            List.of(rec(9, D1, "1001", "2002", "meta-hada", "120")), "admin");
        assertEquals(BudgetWriteOutcome.FAILED, o.getStatus());
        assertEquals("row_validation_failed", o.getErrorCode());
        assertTrue(o.getMessage().contains("文件第 9 行 2026-12-01 meta-hada cost_amount：invalid_row_type"), o.getMessage());
    }

    @Test
    void staleBaseAndAuthFailuresAreNotRetried()
    {
        FakeApi api = new FakeApi();
        api.answers.add(resp(409, "{\"status\":\"FAILED\",\"code\":\"stale_base\"}", null));
        BudgetWriteOutcome o = writer(props(), api, new FakeReader()).write("SITE-IN", BudgetKind.BUDGET,
            List.of(rec(2, D1, "1001", "2001", "x", "1")), "admin");
        assertEquals(BudgetWriteOutcome.FAILED, o.getStatus());
        assertTrue(o.getMessage().contains("另一次上傳更新過"));
        assertEquals(1, api.keys.size());

        FakeApi api2 = new FakeApi();
        api2.answers.add(resp(401, "{\"code\":\"unauthorized\"}", null));
        BudgetWriteOutcome o2 = writer(props(), api2, new FakeReader()).write("SITE-IN", BudgetKind.BUDGET,
            List.of(rec(2, D1, "1001", "2001", "x", "1")), "admin");
        assertTrue(o2.getMessage().contains("憑證或權限"));
    }

    @Test
    void readFailureOrDisabledWritesNothing()
    {
        FakeApi api = new FakeApi();
        FakeReader r = new FakeReader();
        r.error = new RuntimeException("UDS 超时");
        BudgetWriteOutcome o = writer(props(), api, r).write("SITE-IN", BudgetKind.SPEND, List.of(rec(2, D1, "1", "2", "x", "1")), "admin");
        assertEquals(BudgetWriteOutcome.FAILED, o.getStatus());
        assertTrue(o.getMessage().contains("丟失其他推廣渠道"));
        assertTrue(api.keys.isEmpty());

        BudgetImportProperties p = props();
        p.setEnabled(false);
        assertEquals(BudgetWriteOutcome.DISABLED, writer(p, api, new FakeReader()).write("SITE-IN", BudgetKind.SPEND,
            List.of(rec(2, D1, "1", "2", "x", "1")), "admin").getStatus());
        assertNull(CollectSubmitClient.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT"));
        assertEquals(2, CollectSubmitClient.retryAfter("2"));
    }
}
