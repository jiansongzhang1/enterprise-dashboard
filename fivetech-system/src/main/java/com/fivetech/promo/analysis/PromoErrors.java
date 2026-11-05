package com.fivetech.promo.analysis;

/**
 * 推广分析的错误码（AjaxResult.code，HTTP 恒为 200）。
 *
 * @author fivetech
 */
public final class PromoErrors
{
    /** 时间不合法：slotFrom ≥ slotTo、超过 365 天、日期格式错 */
    public static final int BAD_TIME = 4001;

    /** 对比期不合法（含 LAST_YEAR：推广分析不支持去年同期） */
    public static final int BAD_COMPARE = 4002;

    /** 不认识的指标 / 列 / 维度编码，或该指标不支持该维度 */
    public static final int UNKNOWN_CODE = 4003;

    /** 不认识的推广商 / 推广渠道 / 终端 */
    public static final int UNKNOWN_FILTER = 4004;

    /** 导出超过 1,000,000 行 */
    public static final int TOO_MANY_ROWS = 4005;

    /** 数据平台取数失败 */
    public static final int DATA_FAILED = 5001;

    /** 导出行数上限 */
    public static final int MAX_EXPORT_ROWS = 1_000_000;

    private PromoErrors()
    {
    }
}
