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
import com.fivetech.framework.notification.EmailNotificationService;

/**
 * 邮件通知接口。
 */
@RestController
@RequestMapping("/notification/email")
public class EmailNotificationController extends BaseController
{
    private final EmailNotificationService emailService;

    public EmailNotificationController(EmailNotificationService emailService)
    {
        this.emailService = emailService;
    }

    @PreAuthorize("@ss.hasPermi('notification:email:send')")
    @Log(title = "邮件通知", businessType = BusinessType.OTHER)
    @PostMapping("/send")
    public AjaxResult send(@Validated @RequestBody EmailSendRequest request)
    {
        emailService.send(request.getTo(), request.getSubject(), request.getContent(), request.isHtml());
        return success("邮件发送成功");
    }
}
