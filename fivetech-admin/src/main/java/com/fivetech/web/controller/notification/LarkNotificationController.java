package com.fivetech.web.controller.notification;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.framework.notification.LarkNotificationService;

/** Lark 通知接口。 */
@RestController
@RequestMapping("/notification/lark")
public class LarkNotificationController extends BaseController
{
    private final LarkNotificationService larkService;

    public LarkNotificationController(LarkNotificationService larkService)
    {
        this.larkService = larkService;
    }

    @PreAuthorize("@ss.hasPermi('notification:lark:send')")
    @Log(title = "Lark通知", businessType = BusinessType.OTHER)
    @PostMapping("/send")
    public AjaxResult send(@Validated @RequestBody LarkSendRequest request)
    {
        larkService.sendText(request.getReceiveId(), request.getReceiveIdType(), request.getMessage());
        return success("Lark消息发送成功");
    }
}
