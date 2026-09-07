package com.fivetech.web.controller.system;

import java.util.Date;
import java.util.List;
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
        loginService.confirmBind(bindBody.getUsername(), bindBody.getPassword(), bindBody.getCode());
        return AjaxResult.success(MessageUtils.message("user.ga.bind.success"));
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
        // 角色集合
        Set<String> roles = permissionService.getRolePermission(user);
        // 权限集合
        Set<String> permissions = permissionService.getMenuPermission(user);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("user", user);
        ajax.put("roles", roles);
        ajax.put("permissions", permissions);
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
