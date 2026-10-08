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

    /** 金额取整（四舍五入，不带小数）：排行榜、留存与 LTV 用 */
    public static BigDecimal integer(BigDecimal v)
    {
        return v == null ? null : v.setScale(0, RoundingMode.HALF_UP);
    }
}
