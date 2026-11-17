package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 条件规则字段目录的一项，来自指标字典（page_scope = MONITOR_COND）。
 *
 * <ul>
 *   <li>取数：{@code udsField}（uds_hour_field）；比率字段（ext.ratio）多小时按分子分母合计重算，百分比格式（PCT）× 100；</li>
 *   <li>聚合：可加字段多小时求和；不可加且不是比率的（如活跃人数）取各小时平均；快照字段不看窗口，取检测时最近一小时的值；</li>
 *   <li>样本（ext.sample）：最小样本量的口径，窗口内求和（快照取最近一小时）。</li>
 * </ul>
 *
 * @author fivetech
 */
public class CondField
{
    private final String code;

    private final String metricCode;

    private final String name;

    private final String group;

    private final String valueFormat;

    private final Integer decimals;

    private final String udsField;

    private final List<String> dims;

    private final boolean snapshot;

    private final String defaultHow;

    private final String defaultOp;

    private final String numerator;

    private final String denominator;

    private final BigDecimal ratioScale;

    private final boolean additive;

    private final String sampleKey;

    private final String sampleName;

    private final String sampleUnit;

    private final String sampleField;

    private final String caliber;

    private final String absUnit;

    private final int sortNo;

    @SuppressWarnings("unchecked")
    public CondField(String metricCode, String name, String valueFormat, Integer decimals, String udsField, String remark,
            int sortNo, String groupCode, Map<String, Object> ext)
    {
        this.metricCode = metricCode;
        this.name = name;
        this.valueFormat = valueFormat == null ? "INT" : valueFormat.toUpperCase(Locale.ROOT);
        this.decimals = decimals;
        this.udsField = udsField;
        this.caliber = remark;
        this.sortNo = sortNo;
        this.code = str(ext.get("apiCode"), metricCode);
        String g = str(ext.get("group"), null);
        this.group = g != null ? g : groupOf(groupCode);
        List<String> d = new ArrayList<>();
        if (ext.get("dims") instanceof List<?> l)
        {
            l.forEach(o -> d.add(String.valueOf(o)));
        }
        this.dims = List.copyOf(d);
        this.snapshot = Boolean.TRUE.equals(ext.get("snapshot"));
        this.defaultHow = str(ext.get("defaultHow"), snapshot ? "CUR" : "BASE");
        this.defaultOp = str(ext.get("defaultOp"), "CUR".equals(defaultHow) ? "GT" : "CHG");
        Map<String, Object> ratio = ext.get("ratio") instanceof Map<?, ?> r ? (Map<String, Object>) r : null;
        this.numerator = ratio == null ? null : str(ratio.get("numerator"), null);
        this.denominator = ratio == null ? null : str(ratio.get("denominator"), null);
        Object scale = ratio == null ? null : ratio.get("scale");
        this.ratioScale = scale instanceof Number n ? new BigDecimal(n.toString())
            : ("PCT".equals(this.valueFormat) ? BigDecimal.valueOf(100) : BigDecimal.ONE);
        Object add = ext.get("additive");
        this.additive = add instanceof Boolean b ? b : ratio == null && !"PCT".equals(this.valueFormat);
        Map<String, Object> sample = ext.get("sample") instanceof Map<?, ?> s ? (Map<String, Object>) s : null;
        this.sampleKey = sample == null ? null : str(sample.get("key"), null);
        this.sampleName = sample == null ? null : str(sample.get("name"), null);
        this.sampleUnit = sample == null ? null : str(sample.get("unit"), null);
        this.sampleField = sample == null ? null : str(sample.get("field"), null);
        String abs = str(ext.get("absUnit"), null);
        this.absUnit = abs != null ? abs : ("PCT".equals(this.valueFormat) ? "pt" : null);
    }

    /** 指标分组 → 条件规则字段分组（user / fund / game） */
    static String groupOf(String groupCode)
    {
        String g = groupCode == null ? "" : groupCode.toLowerCase(Locale.ROOT);
        if (g.contains("game") || g.contains("bet"))
        {
            return "game";
        }
        if (g.contains("user") || g.contains("acq") || g.contains("reg"))
        {
            return "user";
        }
        return "fund";
    }

    private static String str(Object o, String fallback)
    {
        if (o == null)
        {
            return fallback;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? fallback : s;
    }

    public boolean isRatio()
    {
        return numerator != null && denominator != null;
    }

    public boolean isPercent()
    {
        return "PCT".equals(valueFormat);
    }

    /** 这个字段在 UDS 上要取的列（值或分子分母 + 样本） */
    public List<String> udsColumns()
    {
        List<String> out = new ArrayList<>();
        if (isRatio())
        {
            out.add(numerator);
            out.add(denominator);
        }
        else if (udsField != null)
        {
            out.add(udsField);
        }
        if (sampleField != null && !out.contains(sampleField))
        {
            out.add(sampleField);
        }
        return out;
    }

    public String getCode() { return code; }
    public String getMetricCode() { return metricCode; }
    public String getName() { return name; }
    public String getGroup() { return group; }
    public String getValueFormat() { return valueFormat; }
    public Integer getDecimals() { return decimals; }
    public String getUdsField() { return udsField; }
    public List<String> getDims() { return dims; }
    public boolean isSnapshot() { return snapshot; }
    public String getDefaultHow() { return defaultHow; }
    public String getDefaultOp() { return defaultOp; }
    public String getNumerator() { return numerator; }
    public String getDenominator() { return denominator; }
    public BigDecimal getRatioScale() { return ratioScale; }
    public boolean isAdditive() { return additive; }
    public String getSampleKey() { return sampleKey; }
    public String getSampleName() { return sampleName; }
    public String getSampleUnit() { return sampleUnit; }
    public String getSampleField() { return sampleField; }
    public String getCaliber() { return caliber; }
    public String getAbsUnit() { return absUnit; }
    public int getSortNo() { return sortNo; }
}
