package com.fivetech.web.controller.dashboard;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * CSV 下载的响应组装。
 * <p>
 * 浏览器要弹出「下载」而不是把内容显示在页面里，靠的是两件事：
 * <ol>
 *   <li>{@code Content-Disposition: attachment}；</li>
 *   <li>中文文件名必须用 RFC 5987 的 {@code filename*=UTF-8''...} 形式，
 *       同时保留一个纯 ASCII 的 {@code filename=} 兜底给老浏览器。</li>
 * </ol>
 * 另外 {@code Content-Disposition} 默认对前端 JS 不可见，必须在 CORS 里
 * 显式暴露，否则前端拿不到文件名（已在 ResourcesConfig 中配置）。
 *
 * @author fivetech
 */
final class DashboardExportSupport
{
    /** 文本类型显式带 charset，否则 Excel 可能忽略 BOM 之外的提示 */
    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private DashboardExportSupport()
    {
    }

    static ResponseEntity<StreamingResponseBody> csv(String fileName, StreamingResponseBody body)
    {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CSV);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + asciiFallback(fileName) + "\"; filename*=UTF-8''" + encoded);
        // 两个都给，前端无论用哪套都拿得到文件名
        headers.set("download-filename", encoded);
        // 导出内容随筛选条件变化，不允许任何中间层缓存
        headers.setCacheControl("no-store, no-cache, must-revalidate");
        headers.setPragma("no-cache");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * 纯 ASCII 兜底文件名：把非 ASCII 字符换成下划线，保留扩展名
     */
    private static String asciiFallback(String fileName)
    {
        StringBuilder sb = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++)
        {
            char c = fileName.charAt(i);
            sb.append(c < 128 && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }
}
