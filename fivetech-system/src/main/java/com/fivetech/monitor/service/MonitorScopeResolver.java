package com.fivetech.monitor.service;

import org.springframework.stereotype.Component;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 在 Controller 入口生成 {@link MonitorScope}：站点 + 当前 userId。
 *
 * <p>一期不做功能级权限，也不按负责人限制数据范围（2026-10-20 按原型调整），所以这里只解析站点。</p>
 *
 * @author fivetech
 */
@Component
public class MonitorScopeResolver
{
    private final MonitorSiteResolver siteResolver;

    public MonitorScopeResolver(MonitorSiteResolver siteResolver)
    {
        this.siteResolver = siteResolver;
    }

    public MonitorScope resolve()
    {
        return new MonitorScope(siteResolver.currentSite(), SecurityUtils.getUserId());
    }
}
