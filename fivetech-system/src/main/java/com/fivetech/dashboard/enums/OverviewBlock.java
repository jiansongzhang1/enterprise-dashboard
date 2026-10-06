package com.fivetech.dashboard.enums;

/**
 * 运营总览的内容分块。
 *
 * <p>目前只有指标墙一块；保留分块结构，后续新增区块时接口不用改形状。</p>
 *
 * @author fivetech
 */
public enum OverviewBlock
{
    /** 指标卡 */
    METRICS;

    public static OverviewBlock of(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (OverviewBlock block : values())
        {
            if (block.name().equalsIgnoreCase(code.trim()))
            {
                return block;
            }
        }
        return null;
    }
}
