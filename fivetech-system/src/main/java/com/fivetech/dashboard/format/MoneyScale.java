package com.fivetech.dashboard.format;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额统一保留两位小数（四舍五入）后返回给前端。null 原样返回，不当成 0。
 *
 * @author fivetech
 */
public final class MoneyScale
{
    public static final int SCALE = 2;

    private MoneyScale()
    {
    }

    public static BigDecimal of(BigDecimal v)
    {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 百分数保留 1 位小数：注册渠道、排行榜、留存与 LTV 的页面展示 */
    public static BigDecimal pct1(BigDecimal v)
    {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }

    /** 「15.43%」这类百分数文本转成 1 位小数文本「15.4%」；解析不了的原样返回 */
    public static String pct1Text(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        try
        {
            String n = text.endsWith("%") ? text.substring(0, text.length() - 1) : text;
            return new BigDecimal(n.trim()).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
        }
        catch (NumberFormatException e)
        {
            return text;
        }
    }

    /** 百分数保留 2 位小数：服务层与导出精度 */
    public static BigDecimal pct2(BigDecimal v)
    {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 金额取整（四舍五入，不带小数）：分析区表格（注册渠道、排行榜、留存与 LTV）的页面展示 */
    public static BigDecimal integer(BigDecimal v)
    {
        return v == null ? null : v.setScale(0, RoundingMode.HALF_UP);
    }
}
