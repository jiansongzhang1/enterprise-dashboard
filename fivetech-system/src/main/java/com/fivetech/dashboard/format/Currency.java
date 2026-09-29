package com.fivetech.dashboard.format;

/**
 * 币种字典。符号的位置是变的：₹1,126,164 是前缀，1,126,164 USDT 是后缀，
 * 所以不能在指标表上放一个 prefix 列了事。
 */
public enum Currency
{
    INR("₹", true),
    USD("US$", true),
    USDT("USDT", false);

    private final String symbol;
    private final boolean prefixed;

    Currency(String symbol, boolean prefixed)
    {
        this.symbol = symbol;
        this.prefixed = prefixed;
    }

    public String apply(String number, boolean negative)
    {
        String sign = negative ? "−" : "";
        return prefixed ? sign + symbol + number : sign + number + " " + symbol;
    }

    public static Currency of(String code)
    {
        if (code == null)
        {
            return INR;
        }
        try
        {
            return valueOf(code.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return INR;
        }
    }
}
