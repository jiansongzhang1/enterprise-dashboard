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
     * 帳號已停用（停用時未使用的設定連結同時作廢）。
     */
    public static Mail accountDisabled(String platform, String displayName, String userName,
            Date time, ZoneId zone)
    {
        String p = escape(platform);
        StringBuilder body = new StringBuilder(1024);
        body.append("<p>您好，</p>");
        body.append("<p>您的帳號已於 ").append(escape(formatTime(time, zone))).append(" 停用，目前無法登入 ")
            .append(p).append("，先前寄出的設定連結也已失效。</p>");
        body.append(accountLine(userName));
        body.append("<p>如有疑問，請聯繫管理員。</p>");
        return new Mail("【" + safeSubject(platform) + "】帳號已停用", wrap(body));
    }

    /**
     * 驗證器已重設：管理員清空驗證器密鑰，員工用目前密碼登入後重新綁定。
     *
     * @param loginUrl 登入網址，未配置 portal-url 時為空，不顯示該行
     */
    public static Mail gaReset(String platform, String userName, Date time, ZoneId zone, String loginUrl)
    {
        String p = escape(platform);
        StringBuilder body = new StringBuilder(1536);
        body.append("<p>您好，</p>");
        body.append("<p>管理員已於 ").append(escape(formatTime(time, zone))).append(" 重設您的驗證器，原驗證器已失效。</p>");
        body.append(accountLine(userName));
        body.append("<p>請使用此帳號與目前的密碼登入 ").append(p).append("，系統會引導您綁定新的驗證器，完成後即可正常使用。</p>");
        if (hasText(loginUrl))
        {
            String url = escape(loginUrl);
            body.append("<p>登入網址：<a href=\"").append(url).append("\">").append(url).append("</a></p>");
        }
        body.append("<ul style=\"padding-left:18px;color:#646a73;\">")
            .append("<li>如您忘記密碼，請聯繫管理員改為重設密碼與驗證器。</li>")
            .append("<li>如非本人申請，請立即聯繫管理員。</li>")
            .append("</ul>");
        return new Mail("【" + safeSubject(platform) + "】驗證器已重設", wrap(body));
    }

    /**
     * 重設密碼與驗證器：一次性連結，員工依序設定新密碼、綁定新的驗證器。
     */
    public static Mail resetPasswordAndGa(String platform, String userName, String setupUrl,
            Date expireTime, ZoneId zone)
    {
        String url = escape(setupUrl);
        StringBuilder body = new StringBuilder(2048);
        body.append("<p>您好，</p>");
        body.append("<p>管理員已重設您的密碼與驗證器，原密碼與原驗證器均已失效。請點擊下方按鈕，依序設定新密碼並綁定新的驗證器。</p>");
        body.append(accountLine(userName));
        body.append("<p style=\"margin:24px 0;\"><a href=\"").append(url).append("\" ")
            .append("style=\"display:inline-block;padding:10px 24px;background:#5b5bd6;color:#ffffff;")
            .append("text-decoration:none;border-radius:6px;font-weight:600;\">開始設定</a></p>");
        body.append("<p style=\"color:#646a73;\">若按鈕無法點擊，請複製以下網址至瀏覽器開啟：<br>")
            .append("<span style=\"word-break:break-all;\">").append(url).append("</span></p>");
        body.append("<p>此連結將於 <strong>").append(escape(formatTime(expireTime, zone)))
            .append("</strong> 失效，且僅能使用一次。</p>");
        body.append("<ul style=\"padding-left:18px;color:#646a73;\">")
            .append("<li>如非本人申請，請立即聯繫管理員。</li>")
            .append("<li>請勿轉寄此郵件。</li>")
            .append("</ul>");
        return new Mail(safeSubject(platform) + "重設密碼與驗證器", wrap(body));
    }

    /**
     * 帳號已啟用：員工首次透過啟用連結設定密碼、完成啟用後系統自動發送。
     */
    public static Mail accountActivated(String platform, String userName, Date time, ZoneId zone)
    {
        StringBuilder body = new StringBuilder(1024);
        body.append("<p>您好，</p>");
        body.append("<p>您的帳號已於 ").append(escape(formatTime(time, zone))).append(" 完成啟用。</p>");
        body.append(accountLine(userName));
        body.append(warning("如非本人操作，請立即聯繫管理員。"));
        return new Mail("【" + safeSubject(platform) + "】帳號已啟用", wrap(body));
    }

    /**
     * 帳號已暫時鎖定：密碼正確但驗證碼連續輸入錯誤達上限。
     */
    public static Mail codeLocked(String platform, String userName, Date lockTime, Date unlockTime, ZoneId zone)
    {
        StringBuilder body = new StringBuilder(1024);
        body.append("<p>您好，</p>");
        body.append("<p>您的帳號於 ").append(escape(formatTime(lockTime, zone)))
            .append(" 因多次輸入錯誤的驗證碼，已暫時鎖定至 ").append(escape(formatTime(unlockTime, zone))).append("。</p>");
        body.append(accountLine(userName));
        body.append(warning("這通常表示有人已知道您的密碼並嘗試登入。如非本人操作，請立即聯繫管理員重設密碼。"));
        return new Mail(safeSubject(platform) + "帳號已暫時鎖定", wrap(body));
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

    private static String accountLine(String userName)
    {
        return "<p>帳號：<strong>" + escape(userName) + "</strong></p>";
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
