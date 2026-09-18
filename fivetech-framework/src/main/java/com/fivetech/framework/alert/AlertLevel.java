package com.fivetech.framework.alert;

/**
 * 系统错误告警级别。
 * <p>
 * 与仪表板的业务指标告警（alert_event）不同，本枚举只用于研发/运维侧的系统错误通知，
 * 不落库、不带状态机。
 *
 * @author fivetech
 */
public enum AlertLevel
{
    /** 紧急：应用不可用、依赖断连、OOM。需要立刻处理 */
    P1("紧急"),

    /** 重要：未捕获异常、后台任务失败、下游超时 */
    P2("重要"),

    /** 关注：单次业务异常、慢查询 */
    P3("关注");

    private final String label;

    AlertLevel(String label)
    {
        this.label = label;
    }

    public String getLabel()
    {
        return label;
    }
}
