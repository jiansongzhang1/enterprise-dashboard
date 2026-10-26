package com.fivetech.monitor.engine;

/**
 * 告警到研发值班（系统错误通知通道）。监控模块在 system 里，ErrorAlertService 在 framework，
 * 这里只定义接口，由 framework 提供实现；没有实现时调用方只写日志。
 *
 * @author fivetech
 */
public interface MonitorOpsAlerter
{
    /**
     * 提交一条值班告警（异步、去重限流，不阻塞调用方）。
     *
     * @param title  标题，例如「監控告警發送失敗：Lark 應用憑證無效」
     * @param detail 详细原因（不能带 token、secret）
     */
    void alert(String title, String detail);
}
