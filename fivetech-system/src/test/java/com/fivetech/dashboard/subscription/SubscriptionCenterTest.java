package com.fivetech.dashboard.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.exception.MonitorFieldException;

/** 站点页面 · 订阅管理：发送历史的时间范围与行、「異常」的原因 */
class SubscriptionCenterTest
{
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @Test
    void historyWindows()
    {
        LocalDateTime[] d7 = SubscriptionCenter.historyWindow(null, null, null, TODAY);   // 默认近 7 天
        assertEquals(LocalDateTime.of(2026, 11, 7, 0, 0), d7[0]);
        assertEquals(LocalDateTime.of(2026, 11, 14, 0, 0), d7[1]);
        assertEquals(LocalDateTime.of(2026, 11, 12, 0, 0), SubscriptionCenter.historyWindow("yesterday", null, null, TODAY)[0]);
        assertNull(SubscriptionCenter.historyWindow("all", null, null, TODAY)[0]);
        LocalDateTime[] c = SubscriptionCenter.historyWindow("custom", "2026-11-01", "2026-11-03", TODAY);
        assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0), c[1]);
        // 自訂最多 90 天、最晚到今天
        assertThrows(MonitorFieldException.class, () -> SubscriptionCenter.historyWindow("custom", "2026-08-01", "2026-11-13", TODAY));
        assertThrows(MonitorFieldException.class, () -> SubscriptionCenter.historyWindow("custom", "2026-11-10", "2026-11-14", TODAY));
        assertThrows(MonitorFieldException.class, () -> SubscriptionCenter.historyWindow("month", null, null, TODAY));
    }

    @Test
    void historyRowUsesReasonTable()
    {
        SubscriptionHistoryRow r = new SubscriptionHistoryRow();
        r.setTriggerType("MANUAL");
        r.setOperator("zhang@yata.demo");
        r.setSentAt(LocalDateTime.of(2026, 11, 12, 15, 20, 31));
        r.setResult("FAIL");
        r.setReasonCode("timeout");
        r.setDeleted(true);
        Map<String, Object> m = SubscriptionCenter.historyRow(r);
        assertEquals("2026-11-12 15:20", m.get("sentAt"));
        assertEquals(true, m.get("manual"));
        assertEquals("zhang@yata.demo", m.get("operator"));
        assertEquals("失敗", m.get("resultText"));
        assertEquals("Lark 回應逾時", m.get("reasonText"));
        assertTrue(((String) m.get("reasonNote")).contains("已自動重試 2 次"));
        assertEquals(true, m.get("deleted"));
        // 定时发送不显示操作人；成功没有原因
        r.setTriggerType("SCHEDULE");
        r.setResult("OK");
        r.setReasonCode(null);
        m = SubscriptionCenter.historyRow(r);
        assertNull(m.get("operator"));
        assertNull(m.get("reasonText"));
    }

    @Test
    void abnormalReasons()
    {
        DashboardSubscription s = new DashboardSubscription();
        s.setOwnerUserId(5L);
        SubscriptionCenter.Context ctx = new SubscriptionCenter.Context();
        ctx.botConfigured = true;
        DashboardSubscriptionRun last = new DashboardSubscriptionRun();
        last.setResult("FAIL");
        last.setReasonCode("shot");
        // 群部分失效 + 负责人无效；群失效时不重复写最近一次失败
        List<String> r = SubscriptionCenter.reasons(s, ctx, List.of("市場活動群"), 1, null, last);
        assertEquals(List.of("接收群「市場活動群」失效，其餘照常發送", "負責人的帳號已停用或沒有本站「訂閱管理」權限，請轉交"), r);
        // 群正常：写最近一次失败的原因短语
        r = SubscriptionCenter.reasons(s, ctx, List.of(), 2, "li@yata.demo", last);
        assertEquals(List.of("最近一次發送失敗：截圖生成失敗"), r);
        // 凭证失效（上次已跳過）
        last.setResult("SKIP");
        r = SubscriptionCenter.reasons(s, ctx, List.of(), 2, "li@yata.demo", last);
        assertEquals(List.of("Lark 機器人憑證失效，暫停發送；恢復後自動發送"), r);
        // 没有机器人、群全部失效
        ctx.botConfigured = false;
        r = SubscriptionCenter.reasons(s, ctx, List.of("A"), 0, "li@yata.demo", null);
        assertEquals(List.of("本站尚未接入 Lark 機器人，不會發送", "接收群全部失效，不會發送"), r);
    }
}
