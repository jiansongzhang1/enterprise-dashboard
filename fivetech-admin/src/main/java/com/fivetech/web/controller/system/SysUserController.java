package com.fivetech.web.controller.system;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.entity.SysDept;
import com.fivetech.common.core.domain.entity.SysRole;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.page.TableDataInfo;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.common.enums.UserTypeEnum;
import com.fivetech.common.utils.PasswordPolicy;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.poi.ExcelUtil;
import com.fivetech.framework.notification.AccountMailService;
import com.fivetech.framework.web.service.SysAccountActivationService;
import com.fivetech.framework.web.service.SysPasswordService;
import com.fivetech.framework.web.service.TokenService;
import com.fivetech.system.service.ISysDeptService;
import com.fivetech.system.service.ISysPostService;
import com.fivetech.system.service.ISysRoleService;
import com.fivetech.system.service.ISysUserService;

/**
 * 用户信息
 * 
 * @author fivetech
 */
@RestController
@RequestMapping("/system/user")
public class SysUserController extends BaseController
{
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysDeptService deptService;

    @Autowired
    private ISysPostService postService;

    @Autowired
    private AccountMailService accountMailService;

    @Autowired
    private SysAccountActivationService activationService;

    @Autowired
    private SysPasswordService passwordService;

    @Autowired
    private TokenService tokenService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 新增用户方式：管理员直接指定用户名和密码 */
    private static final String CREATE_MODE_ADMIN = "ADMIN";

    /**
     * 获取用户列表
     */
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysUser user)
    {
        startPage();
        List<SysUser> list = userService.selectUserList(user);
        // 登录锁定状态在 Redis 里，按当前页逐个标记，前端据此显示「解锁」按钮
        for (SysUser u : list)
        {
            u.setLocked(passwordService.isLocked(u.getUserName()));
        }
        return getDataTable(list);
    }

    @Log(title = "用户管理", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('system:user:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysUser user)
    {
        List<SysUser> list = userService.selectUserList(user);
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        util.exportExcel(response, list, "用户数据");
    }

    @Log(title = "用户管理", businessType = BusinessType.IMPORT)
    @PreAuthorize("@ss.hasPermi('system:user:import')")
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception
    {
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        List<SysUser> userList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = userService.importUser(userList, updateSupport, operName);
        return success(message);
    }

    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response)
    {
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        util.importTemplateExcel(response, "用户数据");
    }

    /**
     * 根据用户编号获取详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:user:query')")
    @GetMapping(value = { "/", "/{userId}" })
    public AjaxResult getInfo(@PathVariable(value = "userId", required = false) Long userId)
    {
        AjaxResult ajax = AjaxResult.success();
        if (StringUtils.isNotNull(userId))
        {
            userService.checkUserDataScope(userId);
            SysUser sysUser = userService.selectUserById(userId);
            ajax.put(AjaxResult.DATA_TAG, sysUser);
            ajax.put("postIds", postService.selectPostListByUserId(userId));
            ajax.put("roleIds", sysUser.getRoles().stream().map(SysRole::getRoleId).collect(Collectors.toList()));
        }
        List<SysRole> roles = roleService.selectRoleAll();
        boolean targetIsAdmin = StringUtils.isNull(userId) ? SecurityUtils.isAdmin() : userService.selectUserById(userId).isAdmin();
        ajax.put("roles", targetIsAdmin ? roles : roles.stream().filter(r -> !r.isAdmin()).collect(Collectors.toList()));
        ajax.put("posts", postService.selectPostAll());
        return ajax;
    }

    /**
     * 新增用户
     */
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    @Log(title = "用户管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysUser user)
    {
        deptService.checkDeptDataScope(user.getDeptId());
        roleService.checkRoleDataScope(user.getRoleIds());
        if (!userService.checkUserNameUnique(user))
        {
            return error("新增用户'" + user.getUserName() + "'失败，登录账号已存在");
        }
        else if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return error("新增用户'" + user.getUserName() + "'失败，手机号码已存在");
        }
        else if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return error("新增用户'" + user.getUserName() + "'失败，邮箱账号已存在");
        }
        user.setCreateBy(getUsername());
        // 只有超级管理员可以创建超级管理员，普通管理员提交的值一律降级为普通用户。
        if (UserTypeEnum.SUPER_ADMIN.getCode().equals(user.getUserType()) && !SecurityUtils.isAdmin())
        {
            return error("只有超级管理员可以创建超级管理员用户");
        }
        if (!UserTypeEnum.SUPER_ADMIN.getCode().equals(user.getUserType()))
        {
            user.setUserType(UserTypeEnum.NORMAL.getCode());
        }
        if (StringUtils.isNotEmpty(user.getEmail()))
        {
            user.setEmail(user.getEmail().trim());
        }
        // 方式二：管理员直接指定用户名和密码，邮箱选填，不发启用邮件，账号创建即启用
        if (CREATE_MODE_ADMIN.equalsIgnoreCase(StringUtils.trim(user.getCreateMode())))
        {
            if (StringUtils.isEmpty(user.getPassword()))
            {
                return error("新增用户'" + user.getUserName() + "'失败，请设置登录密码");
            }
            String policyError = PasswordPolicy.check(user.getUserName(), user.getPassword());
            if (policyError != null)
            {
                return error(policyError);
            }
            user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
            user.setPwdUpdateDate(new java.util.Date());
            user.setActivateTime(new java.util.Date());
            return toAjax(userService.insertUser(user));
        }
        // 方式一（默认）：通过用户邮箱新增，邮箱必填
        if (!accountMailService.isActivationEnabled())
        {
            return error("新增用户'" + user.getUserName() + "'失败，启用邮件功能未开启（notification.email.account.activation-enabled），请选择「管理员设置密码」方式");
        }
        // 员工通过邮件里的一次性链接自己设置密码，管理员不分配、不接触密码
        if (!AccountMailService.isDeliverable(user.getEmail()))
        {
            return error("新增用户'" + user.getUserName() + "'失败，请填写有效的邮箱，用于接收启用链接");
        }
        if (!accountMailService.canSendActivation())
        {
            return error("新增用户'" + user.getUserName() + "'失败，未开启邮件发送或未配置前台地址 notification.email.account.portal-url");
        }
        // 启用前写入一个无人知道的随机密码，账号在员工设置密码前无法登录
        user.setPassword(SecurityUtils.encryptPassword(unusablePassword()));
        user.setPwdUpdateDate(null);
        user.setActivateTime(null);
        int rows = userService.insertUser(user);
        if (rows > 0)
        {
            try
            {
                activationService.issueActivation(user, getUsername());
            }
            catch (Exception e)
            {
                // 账号已建好，不回滚；管理员可在列表里重发启用邮件
                logger.error("启用链接生成失败：账号={}", user.getUserName(), e);
                return success("账号已创建，但启用邮件未能发送，请稍后在用户列表中重发启用邮件");
            }
        }
        return toAjax(rows);
    }

    /**
     * 修改用户
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        deptService.checkDeptDataScope(user.getDeptId());
        roleService.checkRoleDataScope(user.getRoleIds());
        if (!userService.checkUserNameUnique(user))
        {
            return error("修改用户'" + user.getUserName() + "'失败，登录账号已存在");
        }
        else if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return error("修改用户'" + user.getUserName() + "'失败，手机号码已存在");
        }
        else if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return error("修改用户'" + user.getUserName() + "'失败，邮箱账号已存在");
        }
        // 非超级管理员不能通过修改请求提升用户身份；保持数据库中的原身份。
        SysUser existingUser = userService.selectUserById(user.getUserId());
        if (!SecurityUtils.isAdmin())
        {
            user.setUserType(existingUser.getUserType());
        }
        else if (!UserTypeEnum.SUPER_ADMIN.getCode().equals(user.getUserType()))
        {
            user.setUserType(UserTypeEnum.NORMAL.getCode());
        }
        user.setUpdateBy(getUsername());
        return toAjax(userService.updateUser(user));
    }

    /**
     * 删除用户
     */
    @PreAuthorize("@ss.hasPermi('system:user:remove')")
    @Log(title = "用户管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{userIds}")
    public AjaxResult remove(@PathVariable Long[] userIds)
    {
        if (ArrayUtils.contains(userIds, getUserId()))
        {
            return error("当前用户不能删除");
        }
        return toAjax(userService.deleteUserByIds(userIds));
    }

    /**
     * 重置密码
     */
    @PreAuthorize("@ss.hasPermi('system:user:resetPwd')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @PostMapping("/resetPwd")
    public AjaxResult resetPwd(@RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        // 请求体只有 userId / password，帐号名从库里取，用于「密码不可包含帐号」校验
        SysUser target = userService.selectUserById(user.getUserId());
        if (target == null)
        {
            return error("用户不存在");
        }
        String policyError = PasswordPolicy.check(target.getUserName(), user.getPassword());
        if (policyError != null)
        {
            return error(policyError);
        }
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        user.setUpdateBy(getUsername());
        int rows = userService.resetPwd(user);
        if (rows > 0)
        {
            // 旧密码登录的会话全部失效，并清除错误计数，用户可以用新密码立即登录
            tokenService.forceLogoutByUserId(user.getUserId());
            passwordService.clearLoginRecordCache(target.getUserName());
            // 只通知“密码已变更”，邮件中不含新密码
            accountMailService.sendPasswordChanged(userService.selectUserById(user.getUserId()), true, null);
        }
        return toAjax(rows);
    }

    /**
     * 重置 Google Authenticator：清空密钥、状态改为待绑定，并踢出该用户全部会话。
     * 用户下次登录需在登录页「绑定」重新扫码（绑定时生成新密钥），旧验证器立即失效。
     */
    @PreAuthorize("@ss.hasPermi('system:user:resetGa')")
    @Log(title = "用户管理-重置验证器", businessType = BusinessType.UPDATE)
    @PostMapping("/resetGa/{userId}")
    public AjaxResult resetGa(@PathVariable("userId") Long userId)
    {
        SysUser target = new SysUser();
        target.setUserId(userId);
        userService.checkUserAllowed(target);
        userService.checkUserDataScope(userId);
        SysUser user = userService.selectUserById(userId);
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            return error("用户不存在");
        }
        int rows = userService.resetGaSecret(userId, getUsername());
        if (rows > 0)
        {
            tokenService.forceLogoutByUserId(userId);
            passwordService.clearLoginRecordCache(user.getUserName());
            accountMailService.sendGaReset(user);
        }
        return toAjax(rows);
    }

    /**
     * 重设密码与验证器：原密码、原验证器立即失效并踢出全部会话，给员工发一次性链接，
     * 员工依序设置新密码、在登录页绑定新验证器。仅限已启用、未停用、邮箱有效的账号。
     */
    @PreAuthorize("@ss.hasPermi('system:user:resetPwdGa')")
    @Log(title = "用户管理-重设密码与验证器", businessType = BusinessType.UPDATE)
    @PostMapping("/resetPwdGa/{userId}")
    public AjaxResult resetPwdGa(@PathVariable("userId") Long userId)
    {
        SysUser target = new SysUser();
        target.setUserId(userId);
        userService.checkUserAllowed(target);
        userService.checkUserDataScope(userId);
        SysUser user = userService.selectUserById(userId);
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            return error("用户不存在");
        }
        if (user.getActivateTime() == null)
        {
            return error("该账号尚未启用，请使用「重发启用邮件」");
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            return error("该账号已停用，请先启用账号");
        }
        if (!AccountMailService.isDeliverable(user.getEmail()))
        {
            return error("该账号没有有效的邮箱，请先修改邮箱");
        }
        // 先确认邮件能发出，避免改了密码却收不到设置链接
        if (!accountMailService.canSendActivation())
        {
            return error("未开启邮件发送或未配置前台地址 notification.email.account.portal-url");
        }
        activationService.resetPasswordAndGa(user, SecurityUtils.encryptPassword(unusablePassword()), getUsername());
        return success();
    }

    /**
     * 重发启用邮件：仅限待启用账号，旧链接立即作废
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @PostMapping("/resendActivation/{userId}")
    public AjaxResult resendActivation(@PathVariable("userId") Long userId)
    {
        SysUser target = new SysUser();
        target.setUserId(userId);
        userService.checkUserAllowed(target);
        userService.checkUserDataScope(userId);
        SysUser user = userService.selectUserById(userId);
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            return error("用户不存在");
        }
        if (!accountMailService.isActivationEnabled())
        {
            return error("启用邮件功能未开启（notification.email.account.activation-enabled），请通过「重置密码」设置初始密码");
        }
        if (user.getActivateTime() != null)
        {
            return error("该账号已启用，无需重发启用邮件");
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            return error("该账号已停用，请先启用账号再重发");
        }
        if (!AccountMailService.isDeliverable(user.getEmail()))
        {
            return error("该账号没有有效的邮箱，请先修改邮箱");
        }
        if (!accountMailService.canSendActivation())
        {
            return error("未开启邮件发送或未配置前台地址 notification.email.account.portal-url");
        }
        activationService.issueActivation(user, getUsername());
        return success();
    }

    /**
     * 解锁登录：清除该账号的密码错误、验证码错误计数（Redis），立即可以重新登录
     */
    @PreAuthorize("@ss.hasPermi('system:user:unlock')")
    @Log(title = "用户管理-账号解锁", businessType = BusinessType.UPDATE)
    @PostMapping("/unlock/{userId}")
    public AjaxResult unlock(@PathVariable("userId") Long userId)
    {
        userService.checkUserDataScope(userId);
        SysUser user = userService.selectUserById(userId);
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            return error("用户不存在");
        }
        passwordService.clearLoginRecordCache(user.getUserName());
        return success();
    }

    /**
     * 状态修改
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        user.setUpdateBy(getUsername());
        SysUser before = userService.selectUserById(user.getUserId());
        int rows = userService.updateUserStatus(user);
        if (rows > 0 && before != null && !StringUtils.equals(before.getStatus(), user.getStatus()))
        {
            if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
            {
                // 停用时作废未使用的启用链接；会话在下一次请求时由 JwtAuthenticationTokenFilter 踢出
                activationService.revokeLinks(before.getUserId());
                accountMailService.sendAccountDisabled(before);
            }
            else if (UserStatus.OK.getCode().equals(user.getStatus()) && before.getActivateTime() != null)
            {
                // 待启用账号重新启用时不发通知：旧链接已作废，由管理员重发启用邮件
                accountMailService.sendAccountEnabled(before);
            }
        }
        return toAjax(rows);
    }

    /**
     * 根据用户编号获取授权角色
     */
    @PreAuthorize("@ss.hasPermi('system:user:query')")
    @GetMapping("/authRole/{userId}")
    public AjaxResult authRole(@PathVariable("userId") Long userId)
    {
        AjaxResult ajax = AjaxResult.success();
        SysUser user = userService.selectUserById(userId);
        List<SysRole> roles = roleService.selectRolesByUserId(userId);
        ajax.put("user", user);
        ajax.put("roles", user.isAdmin() ? roles : roles.stream().filter(r -> !r.isAdmin()).collect(Collectors.toList()));
        return ajax;
    }

    /**
     * 用户授权角色
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.GRANT)
    @PutMapping("/authRole")
    public AjaxResult insertAuthRole(Long userId, Long[] roleIds)
    {
        userService.checkUserDataScope(userId);
        roleService.checkRoleDataScope(roleIds);
        userService.insertUserAuth(userId, roleIds);
        return success();
    }

    /** 生成一个无人知道的随机密码，用于待启用账号 */
    private static String unusablePassword()
    {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 获取部门树列表
     */
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/deptTree")
    public AjaxResult deptTree(SysDept dept)
    {
        return success(deptService.selectDeptTreeList(dept));
    }
}
