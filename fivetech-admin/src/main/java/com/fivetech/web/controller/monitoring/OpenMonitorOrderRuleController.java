package com.fivetech.web.controller.monitoring;

import java.util.function.Supplier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.apikey.ApiKeyService;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.SiteContext;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.framework.web.service.PermissionService;
import com.fivetech.framework.web.service.SysPermissionService;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.OrderRuleOpenQuery;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.service.impl.MonitorOrderRuleService;

/**
 * 对外 · 查询订单告警规则（判定服务等系统用，设计文档 5.3）。
 *
 * <ul>
 *   <li>GET /open/monitor/order-rules：按条件查列表（分页），每条带完整详情；</li>
 *   <li>GET /open/monitor/order-rules/{ruleId}：单条，ruleId 也可以传编号（R-023）。</li>
 * </ul>
 *
 * <p>认证：请求头带 X-Api-Key（security.api-key.paths 里有 /open/monitor/order-rules/**）就按 Key 绑定的账号，
 * 否则走登录令牌。两种方式都要 monitor:rule:query，且在所查站点有角色。</p>
 *
 * <p>站点：参数 siteCode 优先（API Key 方式必填），没传时用请求头 X-Site-Code，都没有为默认站点。
 * 权限按所查站点计算（LoginUser 的权限随 SiteContext 现算），所以不用 @PreAuthorize（它按请求头的站点算），
 * 而是切到所查站点后再判断。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/open/monitor/order-rules")
public class OpenMonitorOrderRuleController extends BaseController
{
    static final String PERMISSION = "monitor:rule:query";

    private final MonitorOrderRuleService service;

    private final DashboardProperties properties;

    private final PermissionService permission;

    private final SysPermissionService sysPermission;

    public OpenMonitorOrderRuleController(MonitorOrderRuleService service, DashboardProperties properties,
            PermissionService permission, SysPermissionService sysPermission)
    {
        this.service = service;
        this.properties = properties;
        this.permission = permission;
        this.sysPermission = sysPermission;
    }

    /** 列表：{ rows, total, pageNum, pageSize, maxVersion }，筛选参数见 {@link OrderRuleOpenQuery} */
    @GetMapping
    public AjaxResult list(OrderRuleOpenQuery query, HttpServletRequest request)
    {
        String site = resolveSite(query.getSiteCode(), request);
        return inSite(site, () -> success(service.list(new MonitorScope(site, getUserId()), query)));
    }

    /** 单条：ruleId 为规则 ID 或编号（R-023） */
    @GetMapping("/{ruleId}")
    public AjaxResult detail(@PathVariable String ruleId, @RequestParam(required = false) String siteCode,
            HttpServletRequest request)
    {
        String site = resolveSite(siteCode, request);
        return inSite(site, () -> success(service.get(new MonitorScope(site, getUserId()), ruleId)));
    }

    /** 在所查站点下判断权限并执行 */
    private AjaxResult inSite(String site, Supplier<AjaxResult> action)
    {
        return SiteContext.callWith(site, properties.udsSite(site), () -> {
            if (!permission.hasPermi(PERMISSION))
            {
                throw MonitorException.forbidden("沒有權限，請聯繫管理員授權");
            }
            return action.get();
        });
    }

    /**
     * 所查站点：参数 siteCode → 校验是已配置的站点且当前账号在该站点有角色；
     * 没传时 API Key 方式报错，令牌方式用请求头 X-Site-Code（拦截器已校验）或默认站点。
     */
    private String resolveSite(String siteCode, HttpServletRequest request)
    {
        String requested = StringUtils.trim(siteCode);
        if (StringUtils.isEmpty(requested))
        {
            if (StringUtils.isNotEmpty(request.getHeader(ApiKeyService.HEADER)))
            {
                throw MonitorFieldException.of("siteCode", "siteCode 必填（API Key 調用須指定站點）");
            }
            return properties.currentSite();
        }
        String code = properties.canonical(requested);
        LoginUser user = getLoginUser();
        if (code == null || user == null || !sysPermission.accessibleSites(user.getUser()).contains(code))
        {
            throw MonitorException.forbidden("沒有該站點的權限");
        }
        return code;
    }
}
