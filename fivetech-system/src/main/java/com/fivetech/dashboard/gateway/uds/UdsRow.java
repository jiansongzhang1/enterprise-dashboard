package com.fivetech.dashboard.gateway.uds;

import java.math.BigDecimal;
import java.util.Map;

/**
 * UDS 查询返回的一行，列名 → 值。
 * <p>
 * 统一成 Map 是为了把「响应结构」和「业务映射」解耦：
 * 解析层只负责把各种形态的 JSON 拍平成行，映射层只认列名。
 *
 * @author fivetech
 */
public class UdsRow
{
    private final Map<String, Object> cells;

    public UdsRow(Map<String, Object> cells)
    {
        this.cells = cells;
    }

    public Object raw(String column)
    {
        return column == null ? null : cells.get(column);
    }

    public String str(String column)
    {
        Object v = raw(column);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 取数值。<b>拿不到或无法解析一律返回 null，绝不返回 0</b> ——
     * 无数据和 0 在业务上不是一回事。
     */
    public BigDecimal num(String column)
    {
        Object v = raw(column);
        if (v == null)
        {
            return null;
        }
        if (v instanceof BigDecimal)
        {
            return (BigDecimal) v;
        }
        if (v instanceof Number)
        {
            return new BigDecimal(v.toString());
        }
        String text = String.valueOf(v).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "-".equals(text))
        {
            return null;
        }
        try
        {
            return new BigDecimal(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    public Integer intVal(String column)
    {
        BigDecimal v = num(column);
        return v == null ? null : v.intValue();
    }

    public Map<String, Object> cells()
    {
        return cells;
    }
}
