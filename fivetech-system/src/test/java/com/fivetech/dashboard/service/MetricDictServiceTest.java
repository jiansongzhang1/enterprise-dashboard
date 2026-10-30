package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.mapper.MetricDictMapper;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;

/** 系统管理 · 指标管理：口径与取数说明、名称 / 分组 / 格式（已接入也能改，改后重载 MetricRegistry）、仅登记指标的增删改、与线上配置对照（不连数据库） */
class MetricDictServiceTest
{
    private final Map<String, MetricDictItem> rows = new LinkedHashMap<>();

    private final List<String> calls = new ArrayList<>();

    private final List<Map<String, Object>> pageRefs = new ArrayList<>();

    private final List<Map<String, Object>> ruleCounts = new ArrayList<>();

    private int events;

    private MetricDictItem lastWrite;

    private MetricDictService service;

    /** 记录重载次数；reloadFails 时模拟配置校验不通过 */
    private int reloads;

    private boolean reloadFails;

    private int ruleCountForFormat;

    private static MetricDictItem row(String code, String status, String hour, String amount, String baseline)
    {
        MetricDictItem it = new MetricDictItem();
        it.setMetricCode(code);
        it.setMetricName(code + "名");
        it.setGroupCode("eg1");
        it.setStatus(status);
        it.setUdsHourField(hour);
        it.setUdsAmountField(amount);
        it.setUdsBaselineField(baseline);
        it.setValueFormat("INT");
        it.setDirection("up");
        it.setUpdateFrequency("hour");
        it.setCalcType("NONE");
        return it;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @BeforeEach
    void setUp()
    {
        rows.put("reg", row("reg", "0", "reg", "reg", "reg_baseline"));
        rows.put("dep", row("dep", "0", "dep", "dep", "dep_baseline"));
        rows.put("wd", row("wd", "0", "wd", "wd", "wd_baseline_OLD"));     // 基准列与配置不一致
        MetricDictItem net = row("net", "0", null, null, null);
        net.setCalcType("SUBTRACT");
        net.setLeftCode("dep");
        net.setRightCode("wd");
        net.setMinSampleKey("dOk");
        rows.put("net", net);
        rows.put("dOk", row("dOk", "0", "dep_ok", "dep_ok", null));
        rows.put("newOne", row("newOne", "1", null, null, null));         // 仅登记、无引用
        rows.put("newRef", row("newRef", "1", null, null, null));         // 仅登记、但被页面引用
        pageRefs.add(map("metricCode", "reg", "pageCode", "OVERVIEW"));
        pageRefs.add(map("metricCode", "newRef", "pageCode", "SUMMARY"));
        ruleCounts.add(map("metricCode", "dep", "cnt", 2L));

        MetricDictMapper mapper = (MetricDictMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { MetricDictMapper.class }, (proxy, method, args) -> {
                    calls.add(method.getName());
                    switch (method.getName())
                    {
                        case "selectAll":
                            List<MetricDictItem> copy = new ArrayList<>();
                            for (MetricDictItem it : rows.values())
                            {
                                MetricDictItem c = row(it.getMetricCode(), it.getStatus(), it.getUdsHourField(),
                                        it.getUdsAmountField(), it.getUdsBaselineField());
                                c.setLeftCode(it.getLeftCode());
                                c.setRightCode(it.getRightCode());
                                c.setMinSampleKey(it.getMinSampleKey());
                                c.setCalcType(it.getCalcType());
                                copy.add(c);
                            }
                            return copy;
                        case "selectByCode":
                            return rows.get((String) args[0]);
                        case "countCodeIgnoreCase":
                            return (int) rows.keySet().stream().filter(k -> k.equalsIgnoreCase((String) args[0])).count();
                        case "selectGroups":
                            List<Map<String, Object>> g = new ArrayList<>();
                            g.add(map("groupCode", "eg1", "groupName", "获客与转化"));
                            return g;
                        case "selectPageRefs":
                            return pageRefs;
                        case "selectRuleCounts":
                            return ruleCounts;
                        case "countEvents":
                            return events;
                        case "countRules":
                            return ruleCountForFormat;
                        case "insertRegistered":
                        case "updateDictionary":
                        case "updateRegisteredBasic":
                        case "updateLiveBasic":
                            lastWrite = (MetricDictItem) args[0];
                            return 1;
                        case "deleteRegistered":
                            rows.remove((String) args[0]);
                            return 1;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });

        UdsProperties uds = new UdsProperties();
        UdsProperties.Overview ov = new UdsProperties.Overview();
        ov.setHour("overview_hour");
        ov.setDay("overview_day");
        ov.setAmount("overview_amount");
        Map<String, List<String>> sub = new LinkedHashMap<>();
        sub.put("net", List.of("dep", "wd"));
        ov.setSubtract(sub);
        Map<String, List<String>> div = new LinkedHashMap<>();
        div.put("dT", List.of("dep_time_sum", "dep_time_count"));
        ov.setDivide(div);
        uds.setOverview(ov);
        Map<String, UdsProperties.Dataset> ds = new LinkedHashMap<>();
        ds.put("overview_hour", dataset("dashboard_overview_hour_v2", "reg", "reg", "dep", "dep", "wd", "wd", "dOk", "dep_ok"));
        ds.put("overview_day", dataset("dashboard_overview_day_v2"));
        ds.put("overview_amount", dataset("dashboard_overview_amount_v2", "reg", "reg", "dep", "dep", "wd", "wd", "dOk", "dep_ok"));
        uds.setDatasets(ds);
        MonitorProperties monitor = new MonitorProperties();
        Map<String, String> base = new LinkedHashMap<>();
        base.put("reg", "reg_baseline");
        base.put("dep", "dep_baseline");
        base.put("wd", "wd_baseline");
        monitor.getEngine().setBaselineFields(base);
        MetricRegistry registry = new MetricRegistry(null, null)
        {
            @Override
            public synchronized void reload()
            {
                reloads++;
                if (reloadFails)
                {
                    throw new IllegalStateException("分组 egX 未启用");
                }
            }
        };
        service = new MetricDictService(mapper, uds, monitor, registry);
    }

    private static UdsProperties.Dataset dataset(String name, String... kv)
    {
        UdsProperties.Dataset d = new UdsProperties.Dataset();
        d.setName(name);
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        d.setMetrics(m);
        return d;
    }

    @SuppressWarnings("unchecked")
    private List<MetricDictItem> list(String keyword, String group, String status)
    {
        return (List<MetricDictItem>) service.list(keyword, group, status).get("rows");
    }

    private MetricDictItem find(String code)
    {
        for (MetricDictItem it : list(null, null, null))
        {
            if (it.getMetricCode().equals(code))
            {
                return it;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 查询与对照

    @Test
    void listComparesWithLiveConfig()
    {
        assertTrue(find("reg").getConfigMismatch().isEmpty(), "与 yml 一致");
        assertEquals(List.of("跌幅基準列"), find("wd").getConfigMismatch());
        assertEquals("wd_baseline", find("wd").getConfig().get("udsBaselineField"));
        assertEquals("dep − wd", find("net").getConfig().get("assemble"), "后端组装的指标显示组装方式");
        assertTrue(find("net").getConfigMismatch().isEmpty(), "组装指标没有 UDS 字段，表里也为空");
        // 平均到账时间：后端相除组装（2026-10-29），不再登记 dT_seconds
        assertEquals("sum(dep_time_sum) ÷ sum(dep_time_count)", service.liveConfig("dT").get("assemble"));
        assertEquals(null, service.liveConfig("dT").get("udsHourField"));
        Map<String, Object> out = service.list(null, null, null);
        assertEquals("dashboard_overview_hour_v2", ((Map<?, ?>) out.get("datasets")).get("udsHourField"));
    }

    @Test
    void referencesAndDeletable()
    {
        assertEquals(List.of("OVERVIEW"), find("reg").getPages());
        assertEquals(2, find("dep").getRuleCount());
        assertEquals(List.of("net"), find("dep").getUsedBy(), "被 net 当被减数");
        assertEquals(List.of("net"), find("dOk").getUsedBy(), "被 net 当最小样本量");
        assertFalse(find("reg").getDeletable(), "已接入的不能删");
        assertTrue(find("newOne").getDeletable());
        assertFalse(find("newRef").getDeletable(), "仅登记但被页面引用");
    }

    @Test
    void listFilters()
    {
        assertEquals(2, list(null, null, "1").size(), "只看仅登记");
        assertEquals(1, list("WD", null, null).size(), "关键字不区分大小写");
        assertEquals(0, list(null, "eg9", null).size());
    }

    // ------------------------------------------------------------------ 新增

    @Test
    void createRegistersWithDefaults()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricCode(" betU ");
        body.setMetricName(" 投注人数 ");
        body.setGroupCode("eg1");
        body.setRemark("口径：有投注的去重会员数");
        body.setUdsHourField("bet_users_hour");
        assertEquals("betU", service.create(body, "admin"));
        assertTrue(calls.contains("insertRegistered"));
        assertEquals("投注人数", lastWrite.getMetricName());
        assertEquals("INT", lastWrite.getValueFormat());
        assertEquals("up", lastWrite.getDirection());
        assertEquals("hour", lastWrite.getUpdateFrequency());
        assertEquals("admin", lastWrite.getCreateBy());
    }

    @Test
    void createValidates()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricCode("REG");
        body.setMetricName("x");
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.create(body, "admin"));
        assertTrue(e.getFields().containsKey("metricCode"), "编码不区分大小写重复");

        MetricDictItem bad = new MetricDictItem();
        bad.setMetricCode("1abc");
        bad.setGroupCode("eg9");
        bad.setValueFormat("BAR");
        bad.setUdsHourField("drop table");
        bad.setSourceField("f");
        e = assertThrows(MonitorFieldException.class, () -> service.create(bad, "admin"));
        for (String f : new String[] { "metricCode", "metricName", "groupCode", "valueFormat", "udsHourField", "sourceTable" })
        {
            assertTrue(e.getFields().containsKey(f), f);
        }
        assertFalse(calls.contains("insertRegistered"));
    }

    // ------------------------------------------------------------------ 修改

    @Test
    void liveMetricDictionaryChanges()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricName("reg名");            // 与原值相同（前端会带上整行）
        body.setRemark("口径：新的说明");
        body.setUdsBaselineField("reg_baseline2");
        service.update("reg", body, "ops");
        assertTrue(calls.contains("updateDictionary"));
        assertTrue(calls.contains("updateLiveBasic"), "已接入的指标同时写名称 / 分组 / 格式");
        assertFalse(calls.contains("updateRegisteredBasic"));
        assertEquals("口径：新的说明", lastWrite.getRemark());
        assertEquals("ops", lastWrite.getUpdateBy());
        assertEquals("reg", lastWrite.getUdsHourField(), "没传的沿用原值");
        assertEquals("reg_baseline2", lastWrite.getUdsBaselineField());
        assertEquals(1, reloads, "保存后重载指标配置");

        MetricDictItem clear = new MetricDictItem();
        clear.setUdsAmountField("");
        service.update("reg", clear, "ops");
        assertNull(lastWrite.getUdsAmountField(), "传空串表示清空");
        assertEquals("reg", lastWrite.getUdsHourField());
    }

    @Test
    void liveMetricNameGroupFormatEditable()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricName("  註冊人數（新）  ");
        body.setMetricNameEn("Registrations");
        body.setGroupCode("eg1");
        body.setValueFormat("PCT");
        service.update("reg", body, "ops");
        assertTrue(calls.contains("updateLiveBasic"));
        assertEquals("註冊人數（新）", lastWrite.getMetricName(), "名称去掉首尾空白后原样保存（不转换）");
        assertEquals("Registrations", lastWrite.getMetricNameEn());
        assertEquals("PCT", lastWrite.getValueFormat(), "没有告警规则：格式可以改");
        assertEquals("eg1", lastWrite.getGroupCode());
        assertEquals(1, reloads);
        assertTrue(calls.contains("countRules"), "改了格式要查告警规则");
    }

    @Test
    void liveMetricDirectionAndFrequencyStillLocked()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricName("改名");
        body.setDirection("down");
        body.setUpdateFrequency("day");
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update("reg", body, "ops"));
        assertTrue(e.getFields().containsKey("direction"));
        assertTrue(e.getFields().containsKey("updateFrequency"));
        assertFalse(e.getFields().containsKey("metricName"), "名称可以改");
        assertFalse(calls.contains("updateDictionary"));
        assertEquals(0, reloads);
    }

    @Test
    void liveMetricFormatLockedWhenRulesExist()
    {
        ruleCountForFormat = 2;
        MetricDictItem body = new MetricDictItem();
        body.setValueFormat("MONEY");
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update("dep", body, "ops"));
        assertTrue(e.getFields().get("valueFormat").contains("2 條告警規則"), e.getFields().toString());
        assertFalse(calls.contains("updateLiveBasic"));

        // 格式没变：有规则也能改名称
        calls.clear();
        MetricDictItem rename = new MetricDictItem();
        rename.setMetricName("存款總額");
        rename.setValueFormat("INT");
        service.update("dep", rename, "ops");
        assertTrue(calls.contains("updateLiveBasic"));
        assertFalse(calls.contains("countRules"), "格式没变不查规则");
        assertEquals("存款總額", lastWrite.getMetricName());
    }

    @Test
    void liveMetricReloadFailureIs400()
    {
        reloadFails = true;
        MetricDictItem body = new MetricDictItem();
        body.setMetricName("新名");
        MonitorException e = assertThrows(MonitorException.class, () -> service.update("reg", body, "ops"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("分组 egX 未启用"), e.getMessage());
    }

    @Test
    void liveMetricBasicValidation()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricName("   ");
        body.setValueFormat("XX");
        body.setGroupCode("nope");
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update("reg", body, "ops"));
        assertTrue(e.getFields().containsKey("metricName"));
        assertTrue(e.getFields().containsKey("valueFormat"));
        assertTrue(e.getFields().containsKey("groupCode"));
        assertEquals(0, reloads);
    }

    @Test
    void registeredMetricBasicChanges()
    {
        MetricDictItem body = new MetricDictItem();
        body.setMetricName("新名字");
        body.setValueFormat("MONEY");
        service.update("newOne", body, "ops");
        assertTrue(calls.contains("updateRegisteredBasic"));
        assertEquals(0, reloads, "仅登记的指标不在运营总览、指标汇总里，不用重载");
        assertEquals("新名字", lastWrite.getMetricName());
        assertEquals("MONEY", lastWrite.getValueFormat());
        assertEquals("eg1", lastWrite.getGroupCode(), "没传的沿用原值");
    }

    @Test
    void updateValidatesAndNotFound()
    {
        MetricDictItem body = new MetricDictItem();
        body.setRemark("x".repeat(501));
        MonitorFieldException e = assertThrows(MonitorFieldException.class, () -> service.update("reg", body, "ops"));
        assertTrue(e.getFields().containsKey("remark"));
        MonitorException nf = assertThrows(MonitorException.class, () -> service.update("nope", new MetricDictItem(), "ops"));
        assertEquals(404, nf.getCode());
    }

    // ------------------------------------------------------------------ 删除

    @Test
    void deleteOnlyUnreferencedRegistered()
    {
        MonitorException live = assertThrows(MonitorException.class, () -> service.delete("reg"));
        assertEquals(400, live.getCode());
        MonitorException ref = assertThrows(MonitorException.class, () -> service.delete("newRef"));
        assertTrue(ref.getMessage().contains("SUMMARY"), ref.getMessage());
        events = 3;
        MonitorException ev = assertThrows(MonitorException.class, () -> service.delete("newOne"));
        assertTrue(ev.getMessage().contains("3 條異常事件"), ev.getMessage());
        events = 0;
        service.delete("newOne");
        assertFalse(rows.containsKey("newOne"));
    }
}
