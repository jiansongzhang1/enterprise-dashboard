package com.fivetech.web.controller.system;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.RateLimiter;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.LimitType;
import com.fivetech.framework.web.service.SysAccountActivationService;

/**
 * 账号启用（邮件链接）——匿名可访问。
 *
 * <p>凭证放在请求体里而不是 URL 路径，避免出现在网关访问日志中。
 * 两个接口都按 IP 限流，防止暴力猜测链接。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/account/activation")
public class SysAccountActivationController
{
    @Autowired
    private SysAccountActivationService activationService;

    /**
     * 打开启用链接时校验，并返回只读的账号名和到期时间。
     */
    @RateLimiter(key = "rate_limit:activation:inspect:", time = 60, count = 30, limitType = LimitType.IP)
    @PostMapping("/inspect")
    public AjaxResult inspect(@RequestBody Map<String, String> body)
    {
        return AjaxResult.success(activationService.inspect(body == null ? null : body.get("token")));
    }

    /**
     * 设置密码并启用账号。
     */
    @RateLimiter(key = "rate_limit:activation:complete:", time = 60, count = 10, limitType = LimitType.IP)
    @PostMapping("/complete")
    public AjaxResult complete(@RequestBody Map<String, String> body)
    {
        if (body == null)
        {
            return AjaxResult.success(activationService.complete(null, null, null));
        }
        return AjaxResult.success(activationService.complete(body.get("token"), body.get("password"),
                body.get("confirmPassword")));
    }
}
