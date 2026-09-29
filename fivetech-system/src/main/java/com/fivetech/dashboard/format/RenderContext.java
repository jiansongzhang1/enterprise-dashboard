package com.fivetech.dashboard.format;

/**
 * 渲染上下文。同一个数在三处的要求是冲突的，必须分开。
 */
public enum RenderContext
{
    /** 指标卡：用来扫视。大数缩写成 M，金额不留小数，一眼看出量级 */
    CARD,
    /** 汇总表 / 明细表：用来核对。不缩写，金额固定两位小数，同一列格式必须一致 */
    TABLE,
    /**
     * CSV 导出：裸数值。
     * 不加千分位（逗号会撕裂字段），不加货币符号（Excel 会把整列判成文本），
     * 不加单位后缀（"8.4 min" 没法求和）。格式化的活交给 Excel。
     */
    CSV
}
