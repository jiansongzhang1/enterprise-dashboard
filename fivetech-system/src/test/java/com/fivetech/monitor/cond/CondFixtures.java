package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fivetech.dashboard.domain.MetricDictItem;

/** 条件规则测试共用：字段目录（与 release_next ㉓ 的 MONITOR_COND 行一致的几项）、序列构造 */
final class CondFixtures
{
    private CondFixtures()
    {
    }

    static MetricDictItem item(String metricCode, String name, String fmt, String udsField, String extJson)
    {
        MetricDictItem it = new MetricDictItem();
        it.setMetricCode(metricCode);
        it.setMetricName(name);
        it.setValueFormat(fmt);
        it.setUdsHourField(udsField);
        it.setStatus("0");
        it.setPageScope("MONITOR_COND");
        it.setExtJson(extJson);
        it.setSortNo(1);
        return it;
    }

    static List<MetricDictItem> rows()
    {
        return List.of(
            item("cdReg", "註冊人數", "INT", "reg_cnt", "{\"apiCode\":\"reg\",\"group\":\"user\",\"dims\":[\"ch\",\"term\"],\"defaultHow\":\"BASE\",\"defaultOp\":\"UP\"}"),
            item("cdFtdr", "首存轉化率", "PCT", null, "{\"apiCode\":\"ftdr\",\"group\":\"user\",\"dims\":[\"ch\",\"term\"],\"defaultHow\":\"CUR\",\"defaultOp\":\"LT\","
                + "\"ratio\":{\"numerator\":\"ftd_cnt\",\"denominator\":\"reg_cnt\"},\"sample\":{\"key\":\"reg\",\"name\":\"註冊人數\",\"unit\":\"人\",\"field\":\"reg_cnt\"}}"),
            item("cdDep", "存款總額", "MONEY", "dep_amount", "{\"apiCode\":\"dep\",\"group\":\"fund\",\"dims\":[\"ch\",\"pay\",\"term\"]}"),
            item("cdActive", "活躍人數", "INT", "active_cnt", "{\"apiCode\":\"active\",\"group\":\"user\",\"additive\":false,\"dims\":[\"ch\",\"term\"]}"),
            item("cdPendN", "待審核筆數", "INT", "w_pend_cnt", "{\"apiCode\":\"wPendN\",\"group\":\"fund\",\"snapshot\":true,\"defaultHow\":\"CUR\",\"defaultOp\":\"GT\"}"));
    }

    static CondCatalog catalog()
    {
        CondProperties p = new CondProperties();
        p.setCatalogCacheSeconds(0);
        return CondCatalog.of(p, CondFixtures::rows);
    }

    static Map<String, BigDecimal> vals(Object... kv)
    {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], new BigDecimal(String.valueOf(kv[i + 1])));
        }
        return m;
    }

    /** 从 from 起 hours 小时，每小时同样的值 */
    static CondSeries flat(LocalDateTime from, int hours, Object... kv)
    {
        CondSeries s = new CondSeries(from);
        for (int i = 0; i < hours; i++)
        {
            s.put(from.plusHours(i), vals(kv));
        }
        return s;
    }
}
