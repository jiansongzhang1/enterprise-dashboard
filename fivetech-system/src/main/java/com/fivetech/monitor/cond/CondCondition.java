package com.fivetech.monitor.cond;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个条件（校验后）：字段 + 范围 + 取值方式 + 比较 + 门槛 + 最小样本量。存 monitor_rule_condition 一行。
 *
 * @author fivetech
 */
public class CondCondition
{
    /** 1 起 */
    private int index;

    private String field;

    /** 维度 → 一个值（ch 为 g:推广商ID 或 c:推广商ID|渠道ID），空 = 不限范围 */
    private Map<String, String> scope = new LinkedHashMap<>();

    /** CUR / BASE / YD */
    private String how;

    /** CUR：GT GE LT LE OUT；BASE / YD：UP DOWN CHG */
    private String op;

    /** 变化类：PCT / ABS；当前值为 null */
    private String unit;

    /** 门槛；OUT 为下限 */
    private BigDecimal low;

    /** OUT 的上限；其他为 null */
    private BigDecimal high;

    private BigDecimal minSample;

    public boolean isChange()
    {
        return "BASE".equals(how) || "YD".equals(how);
    }

    /** 接口里的 threshold：数字，OUT 为 [下限, 上限] */
    public Object threshold()
    {
        return "OUT".equals(op) ? List.of(low, high) : low;
    }

    /** 签名用（预览缓存） */
    @Override
    public String toString()
    {
        return field + scope + how + op + unit + low + high + minSample;
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public Map<String, String> getScope() { return scope; }
    public void setScope(Map<String, String> scope) { this.scope = scope == null ? new LinkedHashMap<>() : scope; }
    public String getHow() { return how; }
    public void setHow(String how) { this.how = how; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }
    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }
    public BigDecimal getMinSample() { return minSample; }
    public void setMinSample(BigDecimal minSample) { this.minSample = minSample; }
}
