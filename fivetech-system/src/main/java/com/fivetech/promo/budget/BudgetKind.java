package com.fivetech.promo.budget;

/**
 * 预算与消耗的两类数据（FY-56）：消耗（业务日报「行銷預算花費」，次日录入）、预算（可选，可录未来 90 天）。
 *
 * @author fivetech
 */
public enum BudgetKind
{
    SPEND("消耗"),

    BUDGET("預算");

    /** 模板表头第 4 列、页面文案里的名称 */
    private final String label;

    BudgetKind(String label)
    {
        this.label = label;
    }

    public String getLabel()
    {
        return label;
    }

    public BudgetKind other()
    {
        return this == SPEND ? BUDGET : SPEND;
    }

    /** SPEND / BUDGET，不区分大小写；空或认不出返回 null */
    public static BudgetKind of(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (BudgetKind k : values())
        {
            if (k.name().equalsIgnoreCase(code.trim()))
            {
                return k;
            }
        }
        return null;
    }
}
