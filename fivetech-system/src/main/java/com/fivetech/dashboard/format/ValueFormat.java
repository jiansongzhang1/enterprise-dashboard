package com.fivetech.dashboard.format;

/**
 * 指标数值的语义单位。
 *
 * <p>只回答「这个数是什么」，不回答「长什么样」——后者由 {@link MetricValueFormatter}
 * 按渲染上下文决定。两者分开是因为同一个指标在卡片、表格、CSV 里必须长得不一样。</p>
 *
 * <p><b>量纲约定</b>：入库值即展示数值。PCT 存 15.82 而不是 0.1582，
 * 这样 dashboard_metric_card 的 alert_min / alert_max 才能和指标值直接比较，
 * 不需要任何一方做换算——换算迟早会有人忘。</p>
 */
public enum ValueFormat
{
    /** 计数，单位「个」 */
    INT(0, "", ""),
    /** 金额，站点币种主单位（元，不是分）。符号由站点币种决定，不在指标上配 */
    MONEY(0, "", ""),
    /** 百分比，入库已 ×100 */
    PCT(2, "", "%"),
    /** 时长，单位分钟 */
    MIN(1, "", " min"),
    /** 倍数 */
    MULTIPLE(2, "", "×");

    private final int defaultDecimals;
    private final String prefix;
    private final String suffix;

    ValueFormat(int defaultDecimals, String prefix, String suffix)
    {
        this.defaultDecimals = defaultDecimals;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    public int defaultDecimals()
    {
        return defaultDecimals;
    }

    public String prefix()
    {
        return prefix;
    }

    public String suffix()
    {
        return suffix;
    }

    /** 大数是否可以缩写成 M。只有计数和金额可以；8.4 min 缩成 M 毫无意义 */
    public boolean abbreviable()
    {
        return this == INT || this == MONEY;
    }

    /**
     * 变化量的单位。百分比类指标的变化是「百分点」而不是「百分比」：
     * 15.82% → 14.30% 是 −1.52pt，不是 −9.6%。混了会让运营以为跌了 9.6%。
     */
    public boolean deltaInPoints()
    {
        return this == PCT;
    }

    public static ValueFormat of(String code)
    {
        if (code == null)
        {
            return INT;
        }
        try
        {
            return valueOf(code.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return INT;
        }
    }
}
