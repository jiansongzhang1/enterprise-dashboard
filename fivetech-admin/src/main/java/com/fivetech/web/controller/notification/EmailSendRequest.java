package com.fivetech.web.controller.notification;

import java.util.List;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 邮件发送请求。
 */
public class EmailSendRequest
{
    @NotEmpty(message = "收件人不能为空")
    @Size(max = 50, message = "收件人最多支持50个")
    private List<@Email(message = "收件人邮箱格式不正确") String> to;

    @NotBlank(message = "邮件主题不能为空")
    @Size(max = 200, message = "邮件主题不能超过200个字符")
    private String subject;

    @NotBlank(message = "邮件正文不能为空")
    @Size(max = 100000, message = "邮件正文不能超过100000个字符")
    private String content;

    private boolean html = true;

    public List<String> getTo()
    {
        return to;
    }

    public void setTo(List<String> to)
    {
        this.to = to;
    }

    public String getSubject()
    {
        return subject;
    }

    public void setSubject(String subject)
    {
        this.subject = subject;
    }

    public String getContent()
    {
        return content;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public boolean isHtml()
    {
        return html;
    }

    public void setHtml(boolean html)
    {
        this.html = html;
    }
}
