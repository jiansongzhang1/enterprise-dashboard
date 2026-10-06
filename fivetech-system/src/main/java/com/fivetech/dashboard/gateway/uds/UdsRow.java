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

    /**
     * 列名小写 → 原始列名。上游表定义的大小写不一定和我们配置里写的一致
     * （ads_kpi_summary_1h 里是 GGR / bonusAmount / India_DDHH），
     * 精确匹配取不到值时不会报错，只会安静地返回 null，指标就此变空。
     */
    private final Map<String, String> lowerIndex;

    public UdsRow(Map<String, Object> cells)
    {
        this.cells = cells == null ? java.util.Map.of() : cells;
        Map<String, String> index = new java.util.HashMap<>(this.cells.size() * 2);
        for (String key : this.cells.keySet())
        {
            if (key != null)
            {
                // 先到先得：真出现同名不同大小写的两列，保留先出现的那个，
                // 并在调用侧靠 cells() 能看出全貌
                index.putIfAbsent(key.toLowerCase(java.util.Locale.ROOT), key);
            }
        }
        this.lowerIndex = index;
    }

    /**
     * 按列名取原始值。<b>先精确匹配，取不到再忽略大小写匹配一次</b>。
     */
    public Object raw(String column)
    {
        if (column == null)
        {
            return null;
        }
        Object exact = cells.get(column);
        if (exact != null || cells.containsKey(column))
        {
            return exact;
        }
        String actual = lowerIndex.get(column.toLowerCase(java.util.Locale.ROOT));
        return actual == null ? null : cells.get(actual);
    }

    /** 该列是否存在（不区分大小写） */
    public boolean has(String column)
    {
        return column != null
            && (cells.containsKey(column)
                || lowerIndex.containsKey(column.toLowerCase(java.util.Locale.ROOT)));
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
