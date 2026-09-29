package com.fivetech.framework.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 账号通知邮件模板（繁體中文）。
 *
 * <p>纯函数，不依赖 Spring，便于单独测试。所有插入正文的变量都做 HTML 转义。
 * 任何模板都不包含密码。</p>
 *
 * @author fivetech
 */
public final class AccountMailTemplates
{
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AccountMailTemplates()
    {
    }

    /** 渲染后的邮件 */
    public static final class Mail
    {
        private final String subject;

        private final String html;

        Mail(String subject, String html)
        {
            this.subject = subject;
            this.html = html;
        }

        public String getSubject()
        {
            return subject;
        }

        public String getHtml()
        {
            return html;
        }
    }

    /**
     * 启用帐号：一次性链接，员工自己设置密码。
     */
    public static Mail activation(String platform, String displayName, String userName,
            String activateUrl, Date expireTime, ZoneId zone)
    {
        String p = escape(platform);
        String url = escape(activateUrl);
        StringBuilder body = new StringBuilder(2048);
        body.append(greeting(displayName, userName));
        body.append("<p>管理員已為您開通 ").append(p).append(" 帳號，請點擊下方按鈕設定密碼，完成帳號啟用。</p>");
        body.append(table(row("登入帳號", escape(userName))));
        body.append("<p style=\"margin:24px 0;\"><a href=\"").append(url).append("\" ")
            .append("style=\"display:inline-block;padding:10px 24px;background:#5b5bd6;color:#ffffff;")
            .append("text-decoration:none;border-radius:6px;font-weight:600;\">設定密碼並啟用帳號</a></p>");
        body.append("<p>連結有效期限至 <strong>").append(escape(formatTime(expireTime, zone)))
            .append("</strong>，僅能使用一次。逾期或已失效時，請聯繫管理員重新寄送。</p>");
        body.append("<p>設定密碼後，請至登入頁「首次綁定」分頁完成 Google Authenticator 或 Microsoft Authenticator 綁定，之後每次登入需輸入 6 位數驗證碼。</p>");
        body.append("<p style=\"color:#646a73;\">如按鈕無法點擊，請複製以下網址至瀏覽器開啟：<br>")
            .append("<span style=\"word-break:break-all;\">").append(url).append("</span></p>");
        body.append(warning("非本人操作請聯繫管理員。此郵件含有您專屬的啟用連結，請勿轉寄。"));
        return new Mail("【" + safeSubject(platform) + "】請啟用您的帳號", wrap(body));
    }

    /**
     * 密碼已變更：只通知，不含密碼。
     *
     * @param byAdmin true 表示管理員重設；false 表示本人修改
     * @param ip 本人修改時的來源 IP，可為空
     */
    public static Mail passwordChanged(String platform, String displayName, String userName,
            boolean byAdmin, String ip, Date changeTime, ZoneId zone)
    {
        StringBuilder body = new StringBuilder(1536);
        body.append(greeting(displayName, userName));
        body.append("<p>您的 ").append(escape(platform)).append(" 登入密碼已變更。</p>");
        StringBuilder rows = new StringBuilder();
        rows.append(row("登入帳號", escape(userName)));
        rows.append(row("變更時間", escape(formatTime(changeTime, zone))));
        rows.append(row("變更方式", byAdmin ? "由管理員重設" : "由您本人修改"));
        if (!byAdmin && hasText(ip))
        {
            rows.append(row("來源 IP", escape(ip)));
        }
        body.append(table(rows.toString()));
        if (byAdmin)
        {
            body.append("<p>新密碼請向管理員確認。為保障帳號安全，本郵件不包含任何密碼。</p>");
        }
        body.append(warning("若非您本人操作或您不知情，請立即聯繫管理員。"));
        return new Mail("【" + safeSubject(platform) + "】您的登入密碼已變更", wrap(body));
    }

    /**
     * 帳號已停用。
     */
    public static Mail accountDisabled(String platform, String displayName, String userName,
            Date time, ZoneId zone)
    {
        StringBuilder body = new StringBuilder(1024);
        body.append(greeting(displayName, userName));
        body.append("<p>您的 ").append(escape(platform)).append(" 帳號已由管理員停用，停用期間無法登入。</p>");
        body.append(table(row("登入帳號", escape(userName)) + row("停用時間", escape(formatTime(time, zone)))));
        body.append(warning("如有疑問，請聯繫管理員。"));
        return new Mail("【" + safeSubject(platform) + "】您的帳號已停用", wrap(body));
    }

    /**
     * 帳號已重新啟用。
     */
    public static Mail accountEnabled(String platform, String displayName, String userName,
            Date time, ZoneId zone, String loginUrl)
    {
        StringBuilder body = new StringBuilder(1024);
        body.append(greeting(displayName, userName));
        body.append("<p>您的 ").append(escape(platform)).append(" 帳號已由管理員重新啟用，現在可以正常登入。</p>");
        StringBuilder rows = new StringBuilder();
        rows.append(row("登入帳號", escape(userName)));
        rows.append(row("啟用時間", escape(formatTime(time, zone))));
        if (hasText(loginUrl))
        {
            String url = escape(loginUrl);
            rows.append(row("登入地址", "<a href=\"" + url + "\">" + url + "</a>"));
        }
        body.append(table(rows.toString()));
        body.append(warning("若您未預期此變更，請聯繫管理員。"));
        return new Mail("【" + safeSubject(platform) + "】您的帳號已重新啟用", wrap(body));
    }

    /**
     * 格式化为“2026-10-01 14:30（UTC+09:00）”，让不同 BU 的收件人都能看懂具体时刻。
     */
    static String formatTime(Date time, ZoneId zone)
    {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        ZonedDateTime t = Instant.ofEpochMilli((time == null ? new Date() : time).getTime()).atZone(z);
        String offset = t.getOffset().getId();
        return t.format(TIME_FORMAT) + "（UTC" + ("Z".equals(offset) ? "+00:00" : offset) + "）";
    }

    private static String greeting(String displayName, String userName)
    {
        String name = hasText(displayName) ? displayName : userName;
        return "<p>" + escape(name) + " 您好：</p>";
    }

    private static String table(String rows)
    {
        return "<table style=\"border-collapse:collapse;margin:16px 0;\">" + rows + "</table>";
    }

    private static String row(String label, String valueHtml)
    {
        return "<tr>"
                + "<td style=\"padding:6px 16px 6px 0;color:#646a73;white-space:nowrap;\">" + label + "</td>"
                + "<td style=\"padding:6px 0;font-weight:600;\">" + valueHtml + "</td>"
                + "</tr>";
    }

    private static String warning(String text)
    {
        return "<p style=\"color:#d4380d;\">" + text + "</p>";
    }

    private static String wrap(StringBuilder body)
    {
        return "<div style=\"font-family:-apple-system,'Segoe UI','PingFang TC','Microsoft JhengHei',Roboto,Arial,sans-serif;"
                + "font-size:14px;line-height:1.7;color:#1f2329;max-width:560px;\">"
                + body
                + "<p style=\"color:#8a8f8d;font-size:12px;margin-top:24px;\">本郵件由系統自動發送，請勿直接回覆。</p>"
                + "</div>";
    }

    /** 主题不是 HTML，但要去掉换行，防止邮件头注入 */
    private static String safeSubject(String text)
    {
        return text == null ? "" : text.replaceAll("[\\r\\n]", " ").trim();
    }

    private static boolean hasText(String s)
    {
        return s != null && !s.trim().isEmpty();
    }

    static String escape(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
