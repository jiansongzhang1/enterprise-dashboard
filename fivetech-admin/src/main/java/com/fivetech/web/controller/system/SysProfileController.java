package com.fivetech.web.controller.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.config.FiveTechConfig;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.utils.PasswordPolicy;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.file.FileUploadUtils;
import com.fivetech.common.utils.file.FileUtils;
import com.fivetech.common.utils.file.MimeTypeUtils;
import com.fivetech.common.utils.ip.IpUtils;
import com.fivetech.framework.notification.AccountMailService;
import com.fivetech.framework.web.service.TokenService;
import com.fivetech.system.service.ISysUserService;

/**
 * 个人信息 业务处理
 * 
 * @author fivetech
 */
@RestController
@RequestMapping("/system/user/profile")
public class SysProfileController extends BaseController
{

    @Autowired
    private ISysUserService userService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AccountMailService accountMailService;

    /**
     * 个人信息
     */
    @GetMapping
    public AjaxResult profile()
    {
        LoginUser loginUser = getLoginUser();
        SysUser user = loginUser.getUser();
        AjaxResult ajax = AjaxResult.success(user);
        ajax.put("roleGroup", userService.selectUserRoleGroup(loginUser.getUsername()));
        ajax.put("postGroup", userService.selectUserPostGroup(loginUser.getUsername()));
        ajax.put("roles", user.getRoles());
        ajax.put("permissions", loginUser.getPermissions());
        return ajax;
    }

    /**
     * 修改用户
     */
    @Log(title = "个人信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult updateProfile(@RequestBody SysUser user)
    {
        LoginUser loginUser = getLoginUser();
        SysUser currentUser = loginUser.getUser();
        currentUser.setNickName(user.getNickName());
        currentUser.setEmail(user.getEmail());
        currentUser.setPhonenumber(user.getPhonenumber());
        currentUser.setSex(user.getSex());
        if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(currentUser))
        {
            return error("修改用户'" + loginUser.getUsername() + "'失败，手机号码已存在");
        }
        if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(currentUser))
        {
            return error("修改用户'" + loginUser.getUsername() + "'失败，邮箱账号已存在");
        }
        if (userService.updateUserProfile(currentUser) > 0)
        {
            // 更新缓存用户信息
            tokenService.setLoginUser(loginUser);
            return success();
        }
        return error("修改个人信息异常，请联系管理员");
    }

    /**
     * 本人修改密码（管理后台「个人中心 → 修改密码」）。
     * 校验旧密码、密码规则（PasswordPolicy）、不能与最近 N 次密码相同；
     * 被替换的旧密码记入历史、更新 pwd_update_date，并发「密码已变更」通知（不含密码）
     */
    @Log(title = "个人信息-修改密码", businessType = BusinessType.UPDATE)
    @PutMapping("/updatePwd")
    public AjaxResult updatePwd(@RequestBody java.util.Map<String, String> params)
    {
        String oldPassword = params == null ? null : params.get("oldPassword");
        String newPassword = params == null ? null : params.get("newPassword");
        if (StringUtils.isEmpty(oldPassword) || StringUtils.isEmpty(newPassword))
        {
            return error("旧密码和新密码不能为空");
        }
        LoginUser loginUser = getLoginUser();
        Long userId = loginUser.getUserId();
        SysUser user = userService.selectUserById(userId);
        if (user == null)
        {
            return error("用户不存在");
        }
        if (!SecurityUtils.matchesPassword(oldPassword, user.getPassword()))
        {
            return error("修改密码失败，旧密码错误");
        }
        String policyError = PasswordPolicy.check(user.getUserName(), newPassword);
        if (policyError != null)
        {
            return error(policyError);
        }
        // 含当前密码：新密码与旧密码相同也在这里拦下
        if (userService.isRecentPassword(userId, newPassword))
        {
            return error("新密码不可与最近 " + userService.getPasswordHistoryCount() + " 次使用的密码相同");
        }
        SysUser update = new SysUser();
        update.setUserId(userId);
        update.setPassword(SecurityUtils.encryptPassword(newPassword));
        update.setUpdateBy(user.getUserName());
        if (userService.resetPwd(update) > 0)
        {
            // 同步当前会话里的密码与改密时间，避免本次登录态里还是旧值
            loginUser.getUser().setPwdUpdateDate(new java.util.Date());
            loginUser.getUser().setPassword(update.getPassword());
            tokenService.setLoginUser(loginUser);
            accountMailService.sendPasswordChanged(user, false, IpUtils.getIpAddr());
            return success();
        }
        return error("修改密码异常，请联系管理员");
    }

    /**
     * 头像上传
     */
    @Log(title = "用户头像", businessType = BusinessType.UPDATE)
    @PostMapping("/avatar")
    public AjaxResult avatar(@RequestParam("avatarfile") MultipartFile file) throws Exception
    {
        if (!file.isEmpty())
        {
            LoginUser loginUser = getLoginUser();
            String avatar = FileUploadUtils.upload(FiveTechConfig.getAvatarPath(), file, MimeTypeUtils.IMAGE_EXTENSION, true);
            if (userService.updateUserAvatar(loginUser.getUserId(), avatar))
            {
                String oldAvatar = loginUser.getUser().getAvatar();
                if (StringUtils.isNotEmpty(oldAvatar))
                {
                    FileUtils.deleteFile(FiveTechConfig.getProfile() + FileUtils.stripPrefix(oldAvatar));
                }
                AjaxResult ajax = AjaxResult.success();
                ajax.put("imgUrl", avatar);
                // 更新缓存用户头像
                loginUser.getUser().setAvatar(avatar);
                tokenService.setLoginUser(loginUser);
                return ajax;
            }
        }
        return error("上传图片异常，请联系管理员");
    }
}
