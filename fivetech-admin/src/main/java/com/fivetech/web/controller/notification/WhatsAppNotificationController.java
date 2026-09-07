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
import com.fivetech.framework.notification.WhatsAppNotificationService;

/**
 * WhatsApp 通知接口。
 */
@RestController
@RequestMapping("/notification/whatsapp")
public class WhatsAppNotificationController extends BaseController
{
    private final WhatsAppNotificationService whatsAppService;

    public WhatsAppNotificationController(WhatsAppNotificationService whatsAppService)
    {
        this.whatsAppService = whatsAppService;
    }

    @PreAuthorize("@ss.hasPermi('notification:whatsapp:send')")
    @Log(title = "WhatsApp通知", businessType = BusinessType.OTHER)
    @PostMapping("/send")
    public AjaxResult send(@Validated @RequestBody WhatsAppSendRequest request)
    {
        whatsAppService.sendText(request.getTo(), request.getMessage());
        return success("WhatsApp消息发送成功");
    }
}
