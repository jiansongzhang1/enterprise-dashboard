package com.fivetech.framework.notification;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import jakarta.mail.internet.MimeMessage;

/**
 * 统一邮件发送服务。
 */
@Service
public class EmailNotificationService
{
    private final JavaMailSender mailSender;

    private final EmailProperties properties;

    public EmailNotificationService(JavaMailSender mailSender, EmailProperties properties)
    {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * 发送邮件。
     *
     * @param recipients 收件人
     * @param subject 主题
     * @param content 正文
     * @param html 是否按 HTML 发送
     */
    public void send(List<String> recipients, String subject, String content, boolean html)
    {
        if (!properties.isEnabled())
        {
            throw new IllegalStateException("邮件通知未启用，请配置 notification.email.enabled=true");
        }
        if (!StringUtils.hasText(properties.getFrom()))
        {
            throw new IllegalStateException("邮件发件人未配置，请配置 notification.email.from");
        }

        try
        {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom());
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(content, html);
            mailSender.send(message);
        }
        catch (Exception e)
        {
            if (e instanceof MailException)
            {
                throw (MailException) e;
            }
            throw new IllegalStateException("邮件发送失败", e);
        }
    }
}
