package com.fivetech.dashboard.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.fivetech.dashboard.config.DashboardProperties;

/**
 * 指标数值格式化。
 *
 * <p>格式化层<b>不改变数值大小</b>——除了 CARD 上下文的百万缩写，而那是纯展示行为。
 * 任何 ×100 / ÷100 都必须在计算层完成，理由见 {@link ValueFormat} 的量纲约定。</p>
 */
@Component
public class MetricValueFormatter
{
    /** 空值统一显示为破折号。不是 0，也不是空字符串——「没有数据」和「数据是 0」是两回事 */
    public static final String DASH = "—";

    /**
     * 百万缩写的下界。取 99,950,000 而不是 100,000,000：
     * 否则 99,999,999 会被 ROUND_HALF_UP 印成 100.00M，读起来像九位数。
     */
    private static final BigDecimal ABBR_NO_DECIMAL = new BigDecimal("99950000");

    private static final BigDecimal ABBR_MIN = new BigDecimal("1000000");

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final DashboardProperties properties;

    public MetricValueFormatter(DashboardProperties properties)
    {
        this.properties = properties;
    }

    /**
     * @param value    指标值，可为 null
     * @param format   语义单位
     * @param decimals 覆盖小数位，null 用 format 的默认值
     * @param ctx      渲染上下文
     */
    public String format(BigDecimal value, ValueFormat format, Integer decimals, RenderContext ctx)
    {
        if (value == null)
        {
            return ctx == RenderContext.CSV ? "" : DASH;
        }
        ValueFormat f = format == null ? ValueFormat.INT : format;

        // CSV：裸数值，交给 Excel 去格式化
        if (ctx == RenderContext.CSV)
        {
            return value.stripTrailingZeros().toPlainString();
        }

        boolean negative = value.signum() < 0;
        BigDecimal abs = value.abs();

        // CARD 上的大数缩写，只对计数和金额生效
        if (ctx == RenderContext.CARD && f.abbreviable() && abs.compareTo(ABBR_MIN) >= 0)
        {
            boolean noDecimal = abs.compareTo(ABBR_NO_DECIMAL) >= 0;
            BigDecimal m = abs.divide(MILLION, noDecimal ? 0 : 2, RoundingMode.HALF_UP);
            String body = group(m, noDecimal ? 0 : 2) + "M";
            return f == ValueFormat.MONEY ? currency().apply(body, negative) : (negative ? "−" : "") + body;
        }

        int dp = resolveDecimals(f, decimals, ctx);
        String body = group(abs.setScale(dp, RoundingMode.HALF_UP), dp);

        if (f == ValueFormat.MONEY)
        {
            return currency().apply(body, negative);
        }
        return (negative ? "−" : "") + f.prefix() + body + f.suffix();
    }

    /**
     * 变化量。PCT 类走「百分点」，其余走「相对变化率」——单位不同，不能共用一套。
     *
     * @param current 当期值
     * @param prev    对比期值
     * @return 形如 {@code +1.52pt} / {@code −9.6%}；对比期缺失或为 0 时返回 {@link #DASH}
     */
    public String formatDelta(BigDecimal current, BigDecimal prev, ValueFormat format)
    {
        if (current == null || prev == null)
        {
            return DASH;
        }
        ValueFormat f = format == null ? ValueFormat.INT : format;
        if (f.deltaInPoints())
        {
            BigDecimal d = current.subtract(prev).setScale(2, RoundingMode.HALF_UP);
            return sign(d) + d.abs().toPlainString() + "pt";
        }
        if (prev.signum() == 0)
        {
            // 对比期为 0 时变化率是无穷大，显示破折号而不是 ∞ 或 100%
            return DASH;
        }
        BigDecimal r = current.subtract(prev)
                .divide(prev.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        return sign(r) + r.abs().toPlainString() + "%";
    }

    private static String sign(BigDecimal d)
    {
        return d.signum() < 0 ? "−" : "+";
    }

    private int resolveDecimals(ValueFormat f, Integer decimals, RenderContext ctx)
    {
        // 表格是拿来核对的：金额一律两位小数，同一列不能有的带小数有的不带
        if (ctx == RenderContext.TABLE && f == ValueFormat.MONEY)
        {
            return 2;
        }
        return decimals != null ? decimals : f.defaultDecimals();
    }

    private Currency currency()
    {
        return Currency.of(properties.getCurrency());
    }

    private static String group(BigDecimal v, int decimals)
    {
        StringBuilder p = new StringBuilder("#,##0");
        if (decimals > 0)
        {
            p.append('.');
            for (int i = 0; i < decimals; i++)
            {
                p.append('0');
            }
        }
        DecimalFormat df = new DecimalFormat(p.toString(), DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(v);
    }
}
