package com.fivetech.web.controller.system;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.constant.Constants;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.entity.SysMenu;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.GaBindBody;
import com.fivetech.common.core.domain.model.GaBindInfo;
import com.fivetech.common.core.domain.model.LoginBody;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.core.text.Convert;
import com.fivetech.common.utils.DateUtils;
import com.fivetech.common.utils.MessageUtils;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.framework.web.service.SysLoginService;
import com.fivetech.framework.web.service.SysPermissionService;
import com.fivetech.system.service.ISysConfigService;
import com.fivetech.system.service.ISysMenuService;

/**
 * 登录验证
 * 
 * @author fivetech
 */
@RestController
public class SysLoginController
{
    @Autowired
    private SysLoginService loginService;

    @Autowired
    private ISysMenuService menuService;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private com.fivetech.framework.web.service.TokenService tokenService;

    @Autowired
    private com.fivetech.dashboard.config.DashboardProperties dashboardProperties;

    @Autowired
    private ISysConfigService configService;

    /**
     * 登录方法
     * 
     * @param loginBody 登录信息
     * @return 结果
     */
    @PostMapping("/login")
    public AjaxResult login(@RequestBody LoginBody loginBody)
    {
        AjaxResult ajax = AjaxResult.success();
        // 生成令牌
        String token = loginService.login(loginBody.getUsername(), loginBody.getPassword(), loginBody.getCode());
        ajax.put(Constants.TOKEN, token);
        return ajax;
    }

    /**
     * 两步登录 · 第一步：帐号 + 密码。通过后返回一次性 ticket（默认 5 分钟有效）
     *
     * @param loginBody username、password
     * @return data: ticket、expireSeconds
     */
    @PostMapping("/login/password")
    public AjaxResult loginPassword(@RequestBody LoginBody loginBody)
    {
        Map<String, Object> data = loginService.loginPassword(loginBody.getUsername(), loginBody.getPassword());
        if (Boolean.TRUE.equals(data.get("bindRequired")))
        {
            // 未绑定验证器：code = 4012（不是错误），前端转到首次绑定流程
            return new AjaxResult(SysLoginService.GA_NOT_BOUND, MessageUtils.message("user.ga.bind.pending"), data);
        }
        return AjaxResult.success(data);
    }

    /**
     * 两步登录 · 第二步：ticket + 6 位验证码。通过后返回 token；
     * ticket 已失效时 code = 4011，前端回到第一步
     *
     * @param body ticket、code
     * @return token
     */
    @PostMapping("/login/verify")
    public AjaxResult loginVerify(@RequestBody Map<String, String> body)
    {
        AjaxResult ajax = AjaxResult.success();
        ajax.put(Constants.TOKEN, loginService.loginVerify(body.get("ticket"), body.get("code")));
        return ajax;
    }

    /**
     * 获取 Google Authenticator 绑定信息
     *
     * @param bindBody 绑定请求
     * @return 绑定信息
     */
    @PostMapping("/ga/bind/start")
    public AjaxResult gaBindStart(@RequestBody GaBindBody bindBody)
    {
        GaBindInfo bindInfo = loginService.createBindInfo(bindBody.getUsername(), bindBody.getPassword());
        return AjaxResult.success(bindInfo);
    }

    /**
     * 确认 Google Authenticator 绑定
     *
     * @param bindBody 绑定请求
     * @return 结果
     */
    @PostMapping("/ga/bind/confirm")
    public AjaxResult gaBindConfirm(@RequestBody GaBindBody bindBody)
    {
        // 绑定成功即登录：返回 token，前端不用再走两步登录
        String token = loginService.confirmBind(bindBody.getUsername(), bindBody.getPassword(), bindBody.getCode());
        AjaxResult ajax = AjaxResult.success(MessageUtils.message("user.ga.bind.success"));
        ajax.put(Constants.TOKEN, token);
        return ajax;
    }

    /**
     * 获取用户信息
     * 
     * @return 用户信息
     */
    @GetMapping("getInfo")
    public AjaxResult getInfo()
    {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser user = loginUser.getUser();
        // 重新加载各角色的权限（角色授权变更后不用重新登录即可生效），并写回登录缓存
        loginUser.setPermissions(permissionService.getMenuPermission(user));
        tokenService.setLoginUser(loginUser);
        // 多站点：角色与权限只取当前站点（请求头 X-Site-Code）的角色 + 通用角色。
        // 前端切换站点后带新的 X-Site-Code 重新请求 getInfo / getRouters 即可拿到该站点的权限
        Set<String> roles = permissionService.getRolePermissionForSite(user);
        Set<String> permissions = loginUser.getPermissions();
        AjaxResult ajax = AjaxResult.success();
        ajax.put("user", user);
        ajax.put("roles", roles);
        ajax.put("permissions", permissions);
        ajax.put("currentSite", dashboardProperties.currentSite());
        java.util.List<java.util.Map<String, String>> sites = new java.util.ArrayList<>();
        for (String code : permissionService.accessibleSites(user))
        {
            // timezone：站点时区（IANA 时区 ID，如 Asia/Kolkata），前端按它显示站点时间；
            // currency / currencySymbol：站点币种代码（如 INR、CNY）与符号（如 ₹、¥），站点管理里设置，没设置用 dashboard.currency
            sites.add(java.util.Map.of("code", code, "name", dashboardProperties.siteName(code),
                    "timezone", dashboardProperties.siteTimezone(code),
                    "currency", dashboardProperties.siteCurrency(code),
                    "currencySymbol", dashboardProperties.siteCurrencySymbol(code)));
        }
        ajax.put("sites", sites);
        ajax.put("pwdChrtype", getSysAccountChrtype());
        ajax.put("isDefaultModifyPwd", initPasswordIsModify(user.getPwdUpdateDate()));
        ajax.put("isPasswordExpired", passwordIsExpiration(user.getPwdUpdateDate()));
        return ajax;
    }

    /**
     * 获取路由信息
     * 
     * @return 路由信息
     */
    @GetMapping("getRouters")
    public AjaxResult getRouters()
    {
        Long userId = SecurityUtils.getUserId();
        List<SysMenu> menus = menuService.selectMenuTreeByUserId(userId);
        return AjaxResult.success(menuService.buildMenus(menus));
    }

    // 获取用户密码自定义配置规则
    public String getSysAccountChrtype()
    {
        return Convert.toStr(configService.selectConfigByKey("sys.account.chrtype"), "0");
    }

    // 检查初始密码是否提醒修改
    public boolean initPasswordIsModify(Date pwdUpdateDate)
    {
        Integer initPasswordModify = Convert.toInt(configService.selectConfigByKey("sys.account.initPasswordModify"));
        return initPasswordModify != null && initPasswordModify == 1 && pwdUpdateDate == null;
    }

    // 检查密码是否过期
    public boolean passwordIsExpiration(Date pwdUpdateDate)
    {
        Integer passwordValidateDays = Convert.toInt(configService.selectConfigByKey("sys.account.passwordValidateDays"));
        if (passwordValidateDays != null && passwordValidateDays > 0)
        {
            if (StringUtils.isNull(pwdUpdateDate))
            {
                // 如果从未修改过初始密码，直接提醒过期
                return true;
            }
            Date nowDate = DateUtils.getNowDate();
            return DateUtils.differentDaysByMillisecond(nowDate, pwdUpdateDate) > passwordValidateDays;
        }
        return false;
    }
}
