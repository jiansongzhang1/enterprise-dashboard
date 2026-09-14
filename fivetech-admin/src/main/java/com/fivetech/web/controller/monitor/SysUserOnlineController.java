package com.fivetech.web.controller.monitor;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.core.domain.model.OnlineUser;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.framework.web.service.TokenService;

/** 在线用户及会话管理。 */
@RestController
@RequestMapping("/monitor/online")
public class SysUserOnlineController
{
    @Autowired
    private TokenService tokenService;

    @PreAuthorize("@ss.hasPermi('monitor:online:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return AjaxResult.success(tokenService.listOnlineUsers());
    }

    /** 当前登录用户心跳，不需要单独菜单权限。 */
    @PostMapping("/heartbeat")
    public AjaxResult heartbeat()
    {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        tokenService.refreshOnlineSession(loginUser);
        return AjaxResult.success();
    }

    @Log(title = "在线用户", businessType = BusinessType.FORCE)
    @PreAuthorize("@ss.hasPermi('monitor:online:forceLogout')")
    @DeleteMapping("/{tokenId}")
    public AjaxResult forceLogout(@PathVariable String tokenId)
    {
        if (StringUtils.isEmpty(tokenId))
        {
            return AjaxResult.error("会话标识不能为空");
        }
        tokenService.forceLogout(tokenId);
        return AjaxResult.success();
    }

    @Log(title = "在线用户", businessType = BusinessType.FORCE)
    @PreAuthorize("@ss.hasPermi('monitor:online:forceLogout')")
    @PostMapping("/batchLogout")
    public AjaxResult batchLogout(@RequestBody String[] tokenIds)
    {
        if (tokenIds != null)
        {
            for (String tokenId : tokenIds)
            {
                tokenService.forceLogout(tokenId);
            }
        }
        return AjaxResult.success();
    }
}
