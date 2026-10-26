package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;

/**
 * 卡片画什么图（监控管理一期）。GAUGE：配了刻度的指标画带注意线的门槛条；LINE：折线走势图。
 * 画图用的数值仍取卡片的 series / value，线取 alert.rule。
 *
 * @author fivetech
 */
public class MetricChartVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    public static final String GAUGE = "GAUGE";

    public static final String LINE = "LINE";

    private final String type;

    /** 刻度，只有 GAUGE 有；字符串，按展示小数位格式化，不带单位 */
    private final Scale scale;

    public MetricChartVO(String type, Scale scale)
    {
        this.type = type;
        this.scale = scale;
    }

    public String getType() { return type; }

    public Scale getScale() { return scale; }

    public static class Scale implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private final String min;
        private final String max;
        public Scale(String min, String max) { this.min = min; this.max = max; }
        public String getMin() { return min; }
        public String getMax() { return max; }
    }
}
