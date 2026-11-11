package com.fivetech.web.controller.system;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.apikey.ApiKeyService;
import com.fivetech.apikey.domain.ApiKey;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.system.service.ISysUserService;

/**
 * 系统管理 · API Key（系统对接用，如数据平台发送 Lark 群消息）。只用 GET / POST。
 * <p>新建时明文只返回一次（操作日志不记返回内容）；之后只能看到前缀，丢了就作废重建。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/system/api-keys")
public class SysApiKeyController extends BaseController
{
    private final ApiKeyService apiKeys;

    private final ISysUserService userService;

    public SysApiKeyController(ApiKeyService apiKeys, ISysUserService userService)
    {
        this.apiKeys = apiKeys;
        this.userService = userService;
    }

    /** 列表：不含哈希与明文 */
    @PreAuthorize("@ss.hasPermi('system:apikey:list')")
    @GetMapping
    public AjaxResult list()
    {
        return success(apiKeys.list());
    }

    /**
     * 新建：body = { keyName, userId, allowedPaths?（逗号分隔，默认全部支持的接口）, allowedIps?, expireTime?（yyyy-MM-dd HH:mm:ss，不填永不过期）, remark? }
     * → { key, secret }，secret 只返回这一次。
     */
    @PreAuthorize("@ss.hasPermi('system:apikey:add')")
    @Log(title = "API Key", businessType = BusinessType.INSERT, isSaveResponseData = false)
    @PostMapping
    public AjaxResult create(@RequestBody ApiKey body)
    {
        SysUser user = body.getUserId() == null ? null : userService.selectUserById(body.getUserId());
        if (body.getUserId() != null && (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag())
            || UserStatus.DISABLE.getCode().equals(user.getStatus())))
        {
            throw new ServiceException("綁定的後台賬號不存在或已停用", 4001);
        }
        ApiKeyService.Created c = apiKeys.create(body, getUsername());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", c.key());
        out.put("secret", c.secret());
        return success(out);
    }

    /** 作废：立即失效，不能恢复 */
    @PreAuthorize("@ss.hasPermi('system:apikey:edit')")
    @Log(title = "API Key", businessType = BusinessType.UPDATE)
    @PostMapping("/{keyId}/revoke")
    public AjaxResult revoke(@PathVariable Long keyId)
    {
        apiKeys.revoke(keyId, getUsername());
        return success();
    }
}
