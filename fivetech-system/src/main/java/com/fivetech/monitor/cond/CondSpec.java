package com.fivetech.monitor.cond;

import java.util.ArrayList;
import java.util.List;

/**
 * 判定用的规则设置（校验后）：条件关系、窗口、连续次数、等级、条件。规则详情、触发预览、每小时检测都用它。
 *
 * @author fivetech
 */
public class CondSpec
{
    /** ALL / ANY */
    private String matchMode = "ALL";

    private CondWindow window = CondWindow.H1;

    private int durationCount = 1;

    /** CRIT / WARN */
    private String level = "WARN";

    private List<CondCondition> conditions = new ArrayList<>();

    public boolean isAll()
    {
        return !"ANY".equals(matchMode);
    }

    /** 预览缓存的签名 */
    public String signature()
    {
        return matchMode + "|" + window.getCode() + "|" + durationCount + "|" + level + "|" + conditions;
    }

    public String getMatchMode() { return matchMode; }
    public void setMatchMode(String matchMode) { this.matchMode = matchMode; }
    public CondWindow getWindow() { return window; }
    public void setWindow(CondWindow window) { this.window = window; }
    public int getDurationCount() { return durationCount; }
    public void setDurationCount(int durationCount) { this.durationCount = durationCount; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public List<CondCondition> getConditions() { return conditions; }
    public void setConditions(List<CondCondition> conditions) { this.conditions = conditions; }
}
