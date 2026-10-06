package com.fivetech.web.controller.dashboard;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 文件下载的响应组装（仪表板导出统一为 XLSX）。
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
    private DashboardExportSupport()
    {
    }

    /** xlsx 的 MIME，写错的话 Safari 会把文件存成 .zip */
    private static final MediaType XLSX = MediaType
        .parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /**
     * 整份生成完再返回的下载响应。
     * <p>用 byte[] 而不是流：全量生成完再写第一个响应字节（明细用 SXSSF 流式写临时文件，内存可控），
     * 生成途中失败时还能回一个正常的 JSON 错误体；边生成边 flush 的话响应头已经发出去，
     * 前端只会拿到一个打不开的半截文件。</p>
     */
    static ResponseEntity<byte[]> xlsx(String fileName, byte[] body)
    {
        HttpHeaders headers = fileHeaders(fileName, XLSX);
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static HttpHeaders fileHeaders(String fileName, MediaType type)
    {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + asciiFallback(fileName) + "\"; filename*=UTF-8''" + encoded);
        headers.set("download-filename", encoded);
        headers.setCacheControl("no-store, no-cache, must-revalidate");
        headers.setPragma("no-cache");
        return headers;
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
