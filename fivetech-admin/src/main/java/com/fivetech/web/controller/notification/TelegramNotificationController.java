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
import com.fivetech.framework.notification.TelegramNotificationService;

/**
 * Telegram 通知接口。
 */
@RestController
@RequestMapping("/notification/telegram")
public class TelegramNotificationController extends BaseController
{
    private final TelegramNotificationService telegramService;

    public TelegramNotificationController(TelegramNotificationService telegramService)
    {
        this.telegramService = telegramService;
    }

    @PreAuthorize("@ss.hasPermi('notification:telegram:send')")
    @Log(title = "Telegram通知", businessType = BusinessType.OTHER)
    @PostMapping("/send")
    public AjaxResult send(@Validated @RequestBody TelegramSendRequest request)
    {
        telegramService.send(request.getChatId(), request.getMessage(), request.getParseMode());
        return success("Telegram消息发送成功");
    }
}
