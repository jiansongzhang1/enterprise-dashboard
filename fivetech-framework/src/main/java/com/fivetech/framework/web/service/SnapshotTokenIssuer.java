package com.fivetech.framework.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.dashboard.subscription.snapshot.SnapshotTokens;
import com.fivetech.system.service.ISysUserService;

/**
 * 订阅截图的临时令牌：按账号名直接在进程内签发（{@link TokenService#createTemporaryToken}），不经过登录接口、密码和 GA；
 * 权限按账号当前的角色与菜单加载，和正常登录一致。到期自动失效、不续期、不进在线用户；截完由调用方立即作废。
 *
 * @author fivetech
 */
@Component
public class SnapshotTokenIssuer implements SnapshotTokens
{
    private static final Logger log = LoggerFactory.getLogger(SnapshotTokenIssuer.class);

    static final String PURPOSE = "訂閱截圖";

    private final TokenService tokenService;

    private final ISysUserService userService;

    private final SysPermissionService permissionService;

    public SnapshotTokenIssuer(TokenService tokenService, ISysUserService userService, SysPermissionService permissionService)
    {
        this.tokenService = tokenService;
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public Issued issue(String username, int ttlSeconds)
    {
        if (username == null || username.isBlank())
        {
            throw new IllegalStateException("未配置截圖帳號（DASHBOARD_SNAPSHOT_USER）");
        }
        SysUser user = userService.selectUserByUserName(username.trim());
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            throw new IllegalStateException("截圖帳號不存在：" + username);
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            throw new IllegalStateException("截圖帳號已停用：" + username);
        }
        LoginUser loginUser = new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
        String jwt = tokenService.createTemporaryToken(loginUser, ttlSeconds, PURPOSE);
        log.info("[snapshot] 签发截图令牌 user={} ttl={}s", username, ttlSeconds);
        return new Issued(jwt, loginUser.getToken(), user.getUserId());
    }

    @Override
    public void revoke(Issued token)
    {
        if (token != null && token.getUuid() != null)
        {
            tokenService.delLoginUser(token.getUuid());
        }
    }
}
