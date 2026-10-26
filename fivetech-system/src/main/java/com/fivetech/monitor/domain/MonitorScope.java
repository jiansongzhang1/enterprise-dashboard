package com.fivetech.monitor.domain;

/**
 * 监控模块的数据范围：站点 + 当前用户。
 *
 * <p>由 {@code MonitorScopeResolver} 在 Controller 入口统一生成，所有监控 Mapper 的查询都必须接收它
 * （参数名固定为 {@code scope}），并引用同一个 SQL 片段 {@code monitorScopeFilter}：{@code site_code = #{scope.siteCode}}。</p>
 *
 * <p>2026-10-20 起按原型调整：站点内有页面权限的人看得到、改得了全部规则和事件，不再按负责人过滤；
 * 负责人只用于展示和「我负责的」筛选。以后要加别的数据范围，只改 scope 和那一个 SQL 片段。</p>
 *
 * <p>单条操作（详情、修改、启停、删除、标记处理）先用带 scope 的查询取出记录，取不到就返回 404。</p>
 *
 * @author fivetech
 */
public final class MonitorScope
{
    private final String siteCode;

    private final Long userId;

    public MonitorScope(String siteCode, Long userId)
    {
        this.siteCode = siteCode;
        this.userId = userId;
    }

    public String getSiteCode()
    {
        return siteCode;
    }

    /** 当前用户：「我负责的」筛选、操作人 */
    public Long getUserId()
    {
        return userId;
    }

    @Override
    public String toString()
    {
        return "MonitorScope{site=" + siteCode + ", userId=" + userId + "}";
    }
}
