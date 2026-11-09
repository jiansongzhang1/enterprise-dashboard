package com.fivetech.dashboard.subscription.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真浏览器截本地假页面：cookie 与 localStorage 写进去、等加载中的标志消失、隐藏导航、跳到登录页报错、等待超时报错。
 * 需要本机有 Chromium：系统属性 / 环境变量 CHROME_PATH 指向可执行文件，没有就跳过。
 */
class HeadlessChromeTest
{
    static String chrome()
    {
        String p = System.getProperty("CHROME_PATH", System.getenv("CHROME_PATH"));
        return p != null && Files.isExecutable(Path.of(p)) ? p : null;
    }

    static HttpServer server()
    {
        try
        {
            HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/zh-TW/overview", ex -> {
                String cookie = String.valueOf(ex.getRequestHeaders().getFirst("Cookie"));
                if (!cookie.contains("Admin-Token=jwt-ok"))
                {
                    ex.getResponseHeaders().add("Location", "/zh-TW/login");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                String html = "<html><body style='margin:0'><nav id=nav>導航</nav><div id=app><h1>運營總覽</h1>"
                    + "<div class=el-loading-mask style='position:fixed;inset:0'>載入中</div></div><script>"
                    + "setTimeout(() => fetch('/api').then(r => r.text()).then(t => {"
                    + " const c = document.createElement('div'); c.id = 'cards'; c.style.height = '1600px';"
                    + " c.textContent = t + ' ' + localStorage.getItem('site') + ' ' + location.search; document.getElementById('app').appendChild(c);"
                    + " document.querySelector('.el-loading-mask').remove(); }), 300);</script></body></html>";
                byte[] b = html.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            srv.createContext("/zh-TW/login", ex -> {
                byte[] b = "login".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            srv.createContext("/api", ex -> {
                byte[] b = "註冊人數 1,234".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            srv.start();
            return srv;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    static HeadlessChrome.Shot shot(String base, String cookie)
    {
        HeadlessChrome.Shot s = new HeadlessChrome.Shot();
        s.url = base + "?range=today&gran=hour&cmp=previous-period";
        s.cookieName = "Admin-Token";
        s.cookieValue = cookie;
        s.localStorage.put("site", "SITE-IN");
        s.readySelector = "#cards";
        s.busySelector = ".el-loading-mask";
        s.hideSelector = "#nav";
        s.loginKeyword = "login";
        s.scale = 1;
        s.timeout = Duration.ofSeconds(20);
        return s;
    }

    @Test
    void shootsLoggedInPageAndReportsLoginAndTimeout()
    {
        String path = chrome();
        Assumptions.assumeTrue(path != null, "沒有 CHROME_PATH，跳過真瀏覽器測試");
        HttpServer srv = server();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/zh-TW/overview";
        HeadlessChrome hc = new HeadlessChrome(path, List.of("--no-sandbox"), JsonMapper.builder().build());
        try
        {
            byte[] png = hc.shoot(shot(base, "jwt-ok"));
            assertTrue(png.length > 1000);
            assertEquals((byte) 0x89, png[0]);
            assertEquals('P', png[1]);
            // 整页：高度超过首屏 900
            int height = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
            assertTrue(height > 1600, "高度 " + height);
            HeadlessChrome.SnapshotException login = assertThrows(HeadlessChrome.SnapshotException.class,
                () -> hc.shoot(shot(base, "bad")));
            assertTrue(login.getMessage().contains("登錄頁"), login.getMessage());
            HeadlessChrome.Shot slow = shot(base, "jwt-ok");
            slow.readySelector = "#never";
            slow.timeout = Duration.ofSeconds(5);
            HeadlessChrome.SnapshotException timeout = assertThrows(HeadlessChrome.SnapshotException.class, () -> hc.shoot(slow));
            assertTrue(timeout.getMessage().contains("等待頁面超時"), timeout.getMessage());
            // 页面级失败后浏览器还能用
            assertTrue(hc.isUsable());
            assertTrue(hc.shoot(shot(base, "jwt-ok")).length > 1000);
            assertEquals(2, hc.getShots());
        }
        finally
        {
            hc.close();
            srv.stop(0);
        }
    }
}
