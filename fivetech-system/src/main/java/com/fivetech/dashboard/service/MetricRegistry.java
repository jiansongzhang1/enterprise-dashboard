package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.MetricDefinition;

/**
 * 指标注册表（内存版）。
 * <p>
 * 这是接口入参的<b>白名单</b>：前端只能传 code，服务端据此决定查哪张表哪个列。
 * 白名单之外的 code 一律丢弃，杜绝把表名列名变成外部输入。
 * <p>
 * 口径来源为前端原型的 metric defs，后续迁移到 {@code metric_definition} 表，
 * 届时本类改为从库加载即可，调用方无需改动。
 *
 * @author fivetech
 */
@Component
public class MetricRegistry
{
    private static final String ATOM = "ATOM";

    private static final String DERIVED = "DERIVED";

    private final Map<String, MetricDefinition> definitions = new LinkedHashMap<>();

    /** 未指定列时的核心指标集 */
    private final List<String> coreCodes = new ArrayList<>();

    public MetricRegistry()
    {
        // g1 转化指标
        put(new MetricDefinition("reg", "注册人数", "g1", ATOM, "INT", "SUM", null), true);
        put(new MetricDefinition("ftd", "首存人数", "g1", ATOM, "INT", "SUM", null), true);
        put(new MetricDefinition("ftdr", "首存转化率", "g1", DERIVED, "PCT", "FORMULA", "ftd / reg * 100"), true);

        // g2 付费指标
        put(new MetricDefinition("dep", "存款总额", "g2", ATOM, "MONEY", "SUM", null), true);
        put(new MetricDefinition("wd", "提款总额", "g2", ATOM, "MONEY", "SUM", null), true);
        put(new MetricDefinition("net", "存提差", "g2", DERIVED, "MONEY", "FORMULA", "dep - wd"), true);
        put(new MetricDefinition("arppu", "ARPPU", "g2", DERIVED, "MONEY1", "FORMULA", "dep / depU"), false);
        put(new MetricDefinition("ftdA", "首存ARPPU", "g2", DERIVED, "MONEY1", "FORMULA", "ftdAmt / ftd"), false);
        put(new MetricDefinition("dOkR", "存款成功率", "g2", DERIVED, "PCT", "FORMULA", "dOk / dTry * 100"), true);
        // 平均耗时跨时间片必须按笔数加权，绝不可算术平均
        put(new MetricDefinition("dT", "平均到帐时间", "g2", ATOM, "MIN", "WEIGHTED_AVG", null), true);
        put(new MetricDefinition("wOkR", "出款成功率", "g2", DERIVED, "PCT", "FORMULA", "wOk / wTry * 100"), false);
        put(new MetricDefinition("wT", "平均出款时间", "g2", ATOM, "MIN", "WEIGHTED_AVG", null), false);

        // g3 营收指标
        put(new MetricDefinition("bonus", "发放赠金总额", "g3", ATOM, "MONEY", "SUM", null), false);
        put(new MetricDefinition("bonusR", "赠金比", "g3", DERIVED, "PCT", "FORMULA", "bonus / bet * 100"), false);
        put(new MetricDefinition("bet", "投注总额", "g3", ATOM, "MONEY", "SUM", null), true);
        put(new MetricDefinition("ggr", "GGR", "g3", DERIVED, "MONEY", "FORMULA", "bet - pay"), true);
        put(new MetricDefinition("killR", "平均杀率", "g3", DERIVED, "PCT", "FORMULA", "ggr / bet * 100"), false);

        // g4 运营指标
        // 去重人数：跨时间片不可相加，区间合计必须由数据侧重算
        put(new MetricDefinition("active", "活跃人数", "g4", ATOM, "INT", "DISTINCT", null), true);
        put(new MetricDefinition("login", "登录人数", "g4", ATOM, "INT", "DISTINCT", null), false);
        put(new MetricDefinition("ngr", "NGR", "g4", DERIVED, "MONEY", "FORMULA", "ggr - bonus"), true);
        put(new MetricDefinition("turnX", "流水倍数", "g4", DERIVED, "X", "FORMULA", "bet / dep"), false);

        // 仅出现在汇总表、不上指标墙的原子量
        put(new MetricDefinition("depU", "存款人数", "g2", ATOM, "INT", "DISTINCT", null), false);
        put(new MetricDefinition("ftdAmt", "首存总金额", "g1", ATOM, "MONEY", "SUM", null), false);
        put(new MetricDefinition("pay", "派彩金额", "g3", ATOM, "MONEY", "SUM", null), false);
        put(new MetricDefinition("dOk", "存款成功笔数", "g2", ATOM, "INT", "SUM", null), false);
        put(new MetricDefinition("dTry", "存款尝试笔数", "g2", ATOM, "INT", "SUM", null), false);
        put(new MetricDefinition("wOk", "出款成功笔数", "g2", ATOM, "INT", "SUM", null), false);
        put(new MetricDefinition("wTry", "出款尝试笔数", "g2", ATOM, "INT", "SUM", null), false);
    }

    private void put(MetricDefinition definition, boolean core)
    {
        definitions.put(definition.getCode(), definition);
        if (core)
        {
            coreCodes.add(definition.getCode());
        }
    }

    public MetricDefinition get(String code)
    {
        return code == null ? null : definitions.get(code);
    }

    public boolean contains(String code)
    {
        return code != null && definitions.containsKey(code);
    }

    public Collection<MetricDefinition> all()
    {
        return definitions.values();
    }

    public List<String> coreCodes()
    {
        return new ArrayList<>(coreCodes);
    }

    /**
     * 过滤出合法的指标编码，并按注册表顺序排列。
     * <p>
     * 未知编码直接丢弃而不是报错：前端版本可能比后端旧，
     * 少一列比整个页面 500 要好。
     *
     * @param requested 请求的编码
     * @param allowed 数据权限允许的编码，为空表示不限
     */
    public List<String> resolveColumns(List<String> requested, Collection<String> allowed)
    {
        List<String> source = (requested == null || requested.isEmpty()) ? coreCodes() : requested;
        List<String> result = new ArrayList<>();
        for (MetricDefinition definition : definitions.values())
        {
            String code = definition.getCode();
            if (!source.contains(code))
            {
                continue;
            }
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(code))
            {
                continue;   // 无权限的指标不返回、不占位
            }
            result.add(code);
        }
        return result.isEmpty() ? filterAllowed(coreCodes(), allowed) : result;
    }

    private List<String> filterAllowed(List<String> codes, Collection<String> allowed)
    {
        if (allowed == null || allowed.isEmpty())
        {
            return codes;
        }
        List<String> result = new ArrayList<>();
        for (String code : codes)
        {
            if (allowed.contains(code))
            {
                result.add(code);
            }
        }
        return result;
    }

    /**
     * 校验排序列：必须是 time 或白名单内的指标编码
     */
    public String resolveSortColumn(String sortColumn, List<String> columns)
    {
        if (StringUtils.isEmpty(sortColumn) || "time".equalsIgnoreCase(sortColumn))
        {
            return "time";
        }
        return columns.contains(sortColumn) ? sortColumn : "time";
    }
}
