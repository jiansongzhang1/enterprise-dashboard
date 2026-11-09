package com.fivetech.dashboard.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.view.domain.SaveViewRequest;
import com.fivetech.dashboard.view.domain.UserView;
import com.fivetech.dashboard.view.domain.ViewPreset;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.domain.ViewSlotItem;
import com.fivetech.monitor.exception.MonitorFieldException;
import tools.jackson.databind.json.JsonMapper;

/** 个人视图的合成与保存校验：设计方案「合成算法与边界」表里的每一行 */
class UserViewResolverTest
{
    // ------------------------------------------------------------------ 夹具

    static ViewSlot slot(String code, int min, Integer max, boolean adjacent)
    {
        ViewSlot s = new ViewSlot();
        s.setSlotCode(code);
        s.setPageCode("PROMO");
        s.setName("核心指標");
        s.setKind("METRICS");
        s.setMinItems(min);
        s.setMaxItems(max);
        s.setGroupAdjacent(adjacent);
        s.setStatus("0");
        return s;
    }

    /** 核心指标卡：a b c d 四项，默认 a b */
    static SlotDef cards()
    {
        List<ViewSlotItem> items = List.of(
            new ViewSlotItem("cards", "a", null, "inv", "投放", 10, 10),
            new ViewSlotItem("cards", "b", null, "inv", "投放", 20, 20),
            new ViewSlotItem("cards", "c", null, "flow", "流量轉化", 30, null),
            new ViewSlotItem("cards", "d", null, "flow", "流量轉化", 40, null));
        return new SlotDef(slot("cards", 1, 3, false), items, List.of(), List.of());
    }

    /** 表格列：两组 x(x1 x2) y(y1 y2)，同组相邻；預置組合 standard（默认）= x1 y1、daily = y1 y2 */
    static SlotDef columns()
    {
        ViewSlot s = slot("cols", 1, null, true);
        s.setName("表格列");
        List<ViewSlotItem> items = List.of(
            new ViewSlotItem("cols", "x1", "X1", "x", "X", 10, null),
            new ViewSlotItem("cols", "x2", "X2", "x", "X", 20, null),
            new ViewSlotItem("cols", "y1", "Y1", "y", "Y", 30, null),
            new ViewSlotItem("cols", "y2", "Y2", "y", "Y", 40, null));
        List<ViewPreset> presets = List.of(new ViewPreset("cols", "standard", "標準", "x1,y1", true, 10),
            new ViewPreset("cols", "daily", "投放日報", "y1,y2", false, 20));
        return new SlotDef(s, items, presets, List.of());
    }

    /** 行维度：agent channel date，1–2 层，agent 须在 channel 之前 */
    static SlotDef rows()
    {
        ViewSlot s = slot("rows", 1, 2, false);
        s.setName("行維度");
        List<ViewSlotItem> items = List.of(
            new ViewSlotItem("rows", "agent", "推廣商", "dim", "維度", 10, 10),
            new ViewSlotItem("rows", "channel", "推廣渠道", "dim", "維度", 20, 20),
            new ViewSlotItem("rows", "date", "日期", "dim", "維度", 30, null));
        return new SlotDef(s, items, List.of(), List.of(new SlotDef.OrderRule("agent", "channel", "推廣渠道須在推廣商之下")));
    }

    static Map<String, ViewItemSource.ItemInfo> infos(String... codes)
    {
        Map<String, ViewItemSource.ItemInfo> m = new LinkedHashMap<>();
        for (String c : codes)
        {
            m.put(c, ViewItemSource.ItemInfo.of("名稱" + c));
        }
        return m;
    }

    static UserViewResolver.Stored custom(String... items)
    {
        return new UserViewResolver.Stored(UserView.CUSTOM, null, List.of(items), List.of(items), null);
    }

    static SaveViewRequest req(String mode, String preset, List<String> items, Map<String, Object> attrs)
    {
        SaveViewRequest r = new SaveViewRequest();
        r.setMode(mode);
        r.setPresetCode(preset);
        r.setItems(items);
        r.setAttrs(attrs);
        return r;
    }

    // ------------------------------------------------------------------ 合成

    @Test
    void noRecordUsesDefaultsAndLabelsFromSource()
    {
        SlotView v = UserViewResolver.resolve(cards(), infos("a", "b", "c", "d"), null, null);
        assertEquals(List.of("a", "b"), v.getItems());
        assertEquals(List.of("a", "b"), v.getDefaultItems());
        assertNull(v.getDefaultPresetCode());
        assertEquals("名稱a", v.getCandidates().get(0).getLabel());
        assertTrue(v.getSkipped().isEmpty());
        assertTrue(v.getNotices().isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) v.toMap().get("candidates");
        assertFalse(cands.get(0).containsKey("reason"));
    }

    @Test
    void metricDisabledIsSkippedSilentlyAndComesBack()
    {
        // 管理员停用 c（来源不再返回 c）：卡片少一张、不进 skipped；重新启用后自动回来
        SlotView off = UserViewResolver.resolve(cards(), infos("a", "b", "d"), null, custom("c", "d"));
        assertEquals(List.of("d"), off.getItems());
        assertTrue(off.getSkipped().isEmpty());
        assertEquals(3, off.getCandidates().size());
        SlotView on = UserViewResolver.resolve(cards(), infos("a", "b", "c", "d"), null, custom("c", "d"));
        assertEquals(List.of("c", "d"), on.getItems());
    }

    @Test
    void siteUnavailableGoesToSkippedAndGreyedOut()
    {
        Map<String, ViewItemSource.ItemInfo> in = infos("a", "b", "d");
        in.put("c", ViewItemSource.ItemInfo.unavailable("名稱c", "本站不提供"));
        SlotView v = UserViewResolver.resolve(cards(), in, null, custom("c", "d"));
        assertEquals(List.of("d"), v.getItems());
        assertEquals(List.of("c"), v.getSkipped());
        SlotView.Candidate c = v.getCandidates().get(2);
        assertFalse(c.isAvailable());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) v.toMap().get("candidates");
        assertEquals("本站不提供", cands.get(2).get("reason"));
    }

    @Test
    void lessThanMinFallsBackToDefaultWithNotice()
    {
        // 只选了一个已下线的指标
        SlotView v = UserViewResolver.resolve(cards(), infos("a", "b", "d"), null, custom("c"));
        assertEquals(List.of("a", "b"), v.getItems());
        assertEquals(1, v.getNotices().size());
        assertEquals(UserViewResolver.NOTICE_FALLBACK, v.getNotices().get(0).get("code"));
    }

    @Test
    void moreThanMaxIsTruncatedAndDuplicatesRemoved()
    {
        SlotView v = UserViewResolver.resolve(cards(), infos("a", "b", "c", "d"), null, custom("d", "d", "c", "b", "a"));
        assertEquals(List.of("d", "c", "b"), v.getItems());
    }

    @Test
    void newMetricIsNotAddedToCustomView()
    {
        SlotView v = UserViewResolver.resolve(cards(), infos("a", "b", "c", "d"), null, custom("c"));
        assertEquals(List.of("c"), v.getItems());
        assertEquals(4, v.getCandidates().size());
    }

    @Test
    void presetFollowsChangesAndDeletedPresetFallsBackToDefault()
    {
        UserViewResolver.Stored daily = new UserViewResolver.Stored(UserView.PRESET, "daily", null, null, null);
        assertEquals(List.of("y1", "y2"), UserViewResolver.resolve(columns(), null, null, daily).getItems());
        UserViewResolver.Stored gone = new UserViewResolver.Stored(UserView.PRESET, "gone", null, null, null);
        SlotView v = UserViewResolver.resolve(columns(), null, null, gone);
        assertEquals(List.of("x1", "y1"), v.getItems());
        assertEquals("standard", v.getDefaultPresetCode());
        assertTrue(v.getNotices().isEmpty());
    }

    @Test
    void siteDefaultsWinOverPresetDefault()
    {
        SlotView v = UserViewResolver.resolve(columns(), null, List.of("y2", "x2", "zz"), null);
        // 站点默认去掉不认识的项，并按同组相邻整理
        assertEquals(List.of("y2", "x2"), v.getDefaultItems());
        assertNull(v.getDefaultPresetCode());
    }

    @Test
    void storedViewIsArrangedByGroupsAndRules()
    {
        SlotView cols = UserViewResolver.resolve(columns(), null, null, custom("x1", "y1", "x2"));
        assertEquals(List.of("x1", "x2", "y1"), cols.getItems());
        SlotView r = UserViewResolver.resolve(rows(), null, null, custom("channel", "agent"));
        assertEquals(List.of("agent", "channel"), r.getItems());
    }

    @Test
    void lastCustomAndAttrsAreReturned()
    {
        Map<String, Object> attrs = Map.of("spark", true);
        UserViewResolver.Stored s = new UserViewResolver.Stored(UserView.PRESET, "daily", null, List.of("x2", "y2"), attrs);
        SlotView v = UserViewResolver.resolve(columns(), null, null, s);
        assertEquals(List.of("x2", "y2"), v.getLastCustom());
        assertEquals(true, v.getAttrs().get("spark"));
        assertNull(UserViewResolver.resolve(columns(), null, null, null).getLastCustom());
    }

    // ------------------------------------------------------------------ 保存

    @Test
    void customEqualToDefaultIsStoredAsDefault()
    {
        SlotView cur = UserViewResolver.resolve(columns(), null, null, null);
        UserViewResolver.Stored s = UserViewResolver.normalize(cur, req("CUSTOM", null, List.of("x1", "y1"), null), null, 0);
        assertEquals(UserView.DEFAULT, s.getMode());
        assertTrue(s.empty());
    }

    @Test
    void customEqualToPresetIsStoredAsPreset()
    {
        SlotView cur = UserViewResolver.resolve(columns(), null, null, null);
        UserViewResolver.Stored s = UserViewResolver.normalize(cur, req("CUSTOM", null, List.of("y1", "y2"), null), null, 0);
        assertEquals(UserView.PRESET, s.getMode());
        assertEquals("daily", s.getPresetCode());
        assertNull(s.getItems());
    }

    @Test
    void customIsStoredWithLastCustomAndKeptAfterSwitchingToPreset()
    {
        SlotView cur = UserViewResolver.resolve(columns(), null, null, null);
        UserViewResolver.Stored s = UserViewResolver.normalize(cur, req("CUSTOM", null, List.of("x2", "y2"), null), null, 0);
        assertEquals(UserView.CUSTOM, s.getMode());
        assertEquals(List.of("x2", "y2"), s.getItems());
        assertEquals(List.of("x2", "y2"), s.getLastCustom());
        UserViewResolver.Stored p = UserViewResolver.normalize(cur, req("PRESET", "daily", null, null), s, 0);
        assertEquals(UserView.PRESET, p.getMode());
        assertEquals(List.of("x2", "y2"), p.getLastCustom());
        // 选「標準」（就是默认）存成 DEFAULT，最近一次自定义仍保留 → 不删记录
        UserViewResolver.Stored d = UserViewResolver.normalize(cur, req("PRESET", "standard", null, null), p, 0);
        assertEquals(UserView.DEFAULT, d.getMode());
        assertFalse(d.empty());
    }

    @Test
    void attrsOnlyKeepsItemsAndReplacesAttrsWhole()
    {
        SlotView cur = UserViewResolver.resolve(cards(), infos("a", "b", "c", "d"), null, null);
        UserViewResolver.Stored old = new UserViewResolver.Stored(UserView.CUSTOM, null, List.of("c"), List.of("c"),
            Map.of("selected", "c", "x", 1));
        UserViewResolver.Stored s = UserViewResolver.normalize(cur, req(null, null, null, Map.of("selected", "a")), old, 20);
        assertEquals(UserView.CUSTOM, s.getMode());
        assertEquals(List.of("c"), s.getItems());
        assertEquals(Map.of("selected", "a"), s.getAttrs());
        // 没有记录时只存小状态：DEFAULT + attrs；attrs 清空后可以删记录
        UserViewResolver.Stored only = UserViewResolver.normalize(cur, req(null, null, null, Map.of("selected", "a")), null, 20);
        assertEquals(UserView.DEFAULT, only.getMode());
        assertFalse(only.empty());
        assertTrue(UserViewResolver.normalize(cur, req(null, null, null, Map.of()), only, 2).empty());
    }

    @Test
    void validationErrorsPointAtFields()
    {
        SlotView cards = UserViewResolver.resolve(cards(), infos("a", "b", "c", "d"), null, null);
        assertEquals("核心指標最多選 3 個", field(cards, req("CUSTOM", null, List.of("a", "b", "c", "d"), null), "items"));
        assertEquals("核心指標至少選 1 個", field(cards, req("CUSTOM", null, List.of(), null), "items"));
        assertEquals("有重複的項：a", field(cards, req("CUSTOM", null, List.of("a", "a"), null), "items"));
        assertEquals("不認識的項：zz", field(cards, req("CUSTOM", null, List.of("zz"), null), "items"));
        Map<String, ViewItemSource.ItemInfo> in = infos("a", "b", "d");
        in.put("c", ViewItemSource.ItemInfo.unavailable("名稱c", "本站不提供"));
        SlotView greyed = UserViewResolver.resolve(cards(), in, null, null);
        assertEquals("本站不可用：名稱c", field(greyed, req("CUSTOM", null, List.of("c"), null), "items"));

        SlotView rows = UserViewResolver.resolve(rows(), null, null, null);
        assertEquals("推廣渠道須在推廣商之下", field(rows, req("CUSTOM", null, List.of("channel", "agent"), null), "items"));
        // 只选推广渠道、不选推广商是允许的
        assertEquals(UserView.CUSTOM, UserViewResolver.normalize(rows, req("CUSTOM", null, List.of("channel", "date"), null), null, 0)
            .getMode());

        SlotView cols = UserViewResolver.resolve(columns(), null, null, null);
        assertEquals("同一組的列需要相鄰", field(cols, req("CUSTOM", null, List.of("x1", "y1", "x2"), null), "items"));
        assertTrue(field(cols, req("PRESET", "nope", null, null), "presetCode").startsWith("預置組合不存在"));
        assertEquals("attrs 不能超過 4 KB", field(cols, req(null, null, null, Map.of("k", "v")), "attrs", 5000));
        assertTrue(field(cols, req("WHAT", null, null, null), "mode").startsWith("mode 只能是"));
        assertTrue(field(cols, req(null, null, null, null), "mode").startsWith("mode 與 attrs"));
    }

    /** 推广分析表格列不要求同组相邻（PRD 没有这条规则）：跨组穿插的顺序可以保存，自定义按原顺序下发 */
    @Test
    void columnsWithoutGroupAdjacentKeepUserOrder()
    {
        SlotDef def = columns();
        def.getSlot().setGroupAdjacent(false);
        SlotView cols = UserViewResolver.resolve(def, null, null, null);
        assertEquals(false, cols.isGroupAdjacent());
        UserViewResolver.Stored saved = UserViewResolver.normalize(cols, req("CUSTOM", null, List.of("x1", "y1", "x2"), null), null, 10);
        assertEquals(UserView.CUSTOM, saved.getMode());
        SlotView mine = UserViewResolver.resolve(def, null, null, custom("y2", "x1", "y1", "x2"));
        assertEquals(List.of("y2", "x1", "y1", "x2"), mine.getItems());
    }

    /** 上线脚本里推广分析表格列的配置位：group_adjacent = FALSE（⑦ 初始数据 + ⑧ 补改） */
    @Test
    void releaseSqlTurnsOffGroupAdjacentForPromoColumns() throws Exception
    {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../sql/release_next_postgresql.sql"),
            java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\('promo\\.channel\\.columns', 'PROMO', '表格列',\\s+'COLUMNS',\\s+1, NULL, (\\w+),").matcher(sql);
        assertTrue(m.find(), "⑦ 里找不到 promo.channel.columns 的配置位");
        assertEquals("FALSE", m.group(1));
        // 补改必须同时刷新 update_time，否则视图定义按版本（行数 + max(update_time)）判断不出变化、不会热加载
        assertTrue(sql.contains("UPDATE dashboard_view_slot SET group_adjacent = FALSE, update_time = localtimestamp\n"
            + " WHERE slot_code = 'promo.channel.columns';"));
    }

    private static String field(SlotView v, SaveViewRequest r, String name)
    {
        return field(v, r, name, 10);
    }

    private static String field(SlotView v, SaveViewRequest r, String name, int bytes)
    {
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> UserViewResolver.normalize(v, r, null, bytes));
        assertEquals(400, e.getCode());
        assertTrue(e.getFields().containsKey(name), "fields 应含 " + name + "：" + e.getFields());
        return e.getFields().get(name);
    }

    // ------------------------------------------------------------------ 定义快照

    @Test
    void definitionsSkipDisabledAndParseRules()
    {
        ViewDefinitions defs = new ViewDefinitions(null, JsonMapper.builder().build(), 0);
        ViewSlot on = slot("rows", 1, 4, false);
        on.setRules("[{\"before\":\"agent\",\"after\":\"channel\",\"message\":\"m\"},{\"before\":\"x\"}]");
        ViewSlot off = slot("off", 1, null, false);
        off.setStatus("1");
        ViewSlot bad = slot("bad", 1, null, false);
        bad.setRules("not json");
        ViewSlotItem disabled = new ViewSlotItem("rows", "date", null, "dim", "維度", 5, null);
        disabled.setStatus("1");
        List<ViewSlotItem> items = new ArrayList<>(List.of(new ViewSlotItem("rows", "channel", null, "dim", "維度", 20, null),
            new ViewSlotItem("rows", "agent", null, "dim", "維度", 10, null), disabled));
        Map<String, SlotDef> m = defs.build(List.of(on, off, bad), items, List.of());
        assertEquals(List.of("rows", "bad"), new ArrayList<>(m.keySet()));
        SlotDef rows = m.get("rows");
        assertEquals(1, rows.getRules().size());
        assertEquals("m", rows.getRules().get(0).getMessage());
        assertEquals("agent", rows.getItems().get(0).getItemCode());
        assertEquals(2, rows.getItems().size());
        assertTrue(m.get("bad").getRules().isEmpty());
    }
}
