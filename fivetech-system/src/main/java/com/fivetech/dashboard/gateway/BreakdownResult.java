package com.fivetech.dashboard.gateway;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拆解查询的结果：每行是「维度编码 → 文本值」+「指标编码 → 数值」。
 * <p>
 * 三种状态必须区分开，前端提示也不同：
 * <ul>
 *   <li>{@code configured=false}：数据集还没对接（TODO 阶段），板块显示「数据待接入」；</li>
 *   <li>{@code ready=false}：已对接但整个区间都在上游水位之上，显示「数据尚未就绪」；</li>
 *   <li>两者都为 true 但 rows 为空：区间内确实没有数据。</li>
 * </ul>
 *
 * @author fivetech
 */
public class BreakdownResult implements Serializable
{
    private static final long serialVersionUID = 1L;

    private boolean configured;

    private boolean ready = true;

    private List<Row> rows = new ArrayList<>();

    /** 上游自报的水位 */
    private LocalDateTime watermark;

    /**
     * 全量分母（同数据集、同时间、dimensions=[] 的查询），key 为指标编码。
     * 未配置 withTotal 时为空，调用方需自行兜底
     */
    private Map<String, BigDecimal> totals = new LinkedHashMap<>();

    public Map<String, BigDecimal> getTotals()
    {
        return totals;
    }

    public BigDecimal total(String code)
    {
        return totals.get(code);
    }

    public static BreakdownResult notConfigured()
    {
        BreakdownResult r = new BreakdownResult();
        r.configured = false;
        r.ready = false;
        return r;
    }

    public static BreakdownResult notReady()
    {
        BreakdownResult r = new BreakdownResult();
        r.configured = true;
        r.ready = false;
        return r;
    }

    public boolean isConfigured()
    {
        return configured;
    }

    public void setConfigured(boolean configured)
    {
        this.configured = configured;
    }

    public boolean isReady()
    {
        return ready;
    }

    public void setReady(boolean ready)
    {
        this.ready = ready;
    }

    public List<Row> getRows()
    {
        return rows;
    }

    public void setRows(List<Row> rows)
    {
        this.rows = rows;
    }

    public LocalDateTime getWatermark()
    {
        return watermark;
    }

    public void setWatermark(LocalDateTime watermark)
    {
        this.watermark = watermark;
    }

    /** 一行拆解结果 */
    public static class Row implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private final Map<String, String> dims = new LinkedHashMap<>();

        private final Map<String, BigDecimal> values = new LinkedHashMap<>();

        public Map<String, String> getDims()
        {
            return dims;
        }

        public Map<String, BigDecimal> getValues()
        {
            return values;
        }

        public String dim(String code)
        {
            return dims.get(code);
        }

        /** 无数据为 null，不是 0 */
        public BigDecimal num(String code)
        {
            return values.get(code);
        }
    }
}
