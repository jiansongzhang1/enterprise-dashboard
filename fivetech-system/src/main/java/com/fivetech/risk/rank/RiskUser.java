package com.fivetech.risk.rank;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个用户在所选时间内的汇总：列编码 → 值（数值为 BigDecimal，文本 / 时间为 String）。
 *
 * @author fivetech
 */
public final class RiskUser
{
    private final String userId;

    private final Map<String, Object> values = new LinkedHashMap<>();

    public RiskUser(String userId)
    {
        this.userId = userId;
    }

    public String getUserId()
    {
        return userId;
    }

    public Map<String, Object> getValues()
    {
        return values;
    }

    public Object get(String code)
    {
        return values.get(code);
    }

    public BigDecimal num(String code)
    {
        Object v = values.get(code);
        return v instanceof BigDecimal b ? b : null;
    }

    public String text(String code)
    {
        Object v = values.get(code);
        return v == null ? null : String.valueOf(v);
    }

    public void put(String code, Object value)
    {
        if (value == null)
        {
            values.remove(code);
        }
        else
        {
            values.put(code, value);
        }
    }

    /** 账号（维度 account） */
    public String getAccount()
    {
        return text(RiskAggregator.ACCOUNT);
    }
}
