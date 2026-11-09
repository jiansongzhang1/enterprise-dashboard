package com.fivetech.dashboard.subscription.snapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 本机无头 Chromium：一个浏览器进程（DevTools 协议，见 {@link Cdp}），每张截图一个独立的浏览器上下文（cookie、localStorage
 * 互不相干，截完整个丢掉）。不是线程安全的：调用方保证同一时间只截一张。
 *
 * <p>一张截图：写 cookie / localStorage → 打开页面 → 等就绪（加载完、网络空闲、就绪元素出现、没有加载中的标志）→
 * 隐藏导航等 → 撑开视口到整页高度再等一轮 → 截图。</p>
 *
 * @author fivetech
 */
final class HeadlessChrome implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(HeadlessChrome.class);

    private static final Pattern DEVTOOLS = Pattern.compile("DevTools listening on (ws://\\S+)");

    /** 截图失败（页面打不开、登录失效、超时等），message 给人看 */
    static final class SnapshotException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        SnapshotException(String message)
        {
            super(message);
        }

        SnapshotException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /** 一张截图的参数 */
    static final class Shot
    {
        String url;
        String cookieName;
        String cookieValue;
        Map<String, String> localStorage = new LinkedHashMap<>();
        int width = 1280;
        int height = 900;
        int maxHeight = 6000;
        double scale = 1.5;
        String captureSelector;
        String readySelector;
        String busySelector;
        String hideSelector;
        String loginKeyword;
        int idleMillis = 800;
        int settleMillis = 1500;
        Duration timeout = Duration.ofSeconds(45);
    }

    private final String executable;

    private final List<String> extraArgs;

    private final ObjectMapper json;

    private Process process;

    private Cdp cdp;

    private Path userDataDir;

    private int shots;

    private boolean broken;

    HeadlessChrome(String executable, List<String> extraArgs, ObjectMapper json)
    {
        this.executable = executable;
        this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        this.json = json;
    }

    int getShots()
    {
        return shots;
    }

    boolean isUsable()
    {
        return !broken && process != null && process.isAlive() && cdp != null && cdp.isOpen();
    }

    /** 截一张；浏览器没起或坏了先（重新）启动 */
    byte[] shoot(Shot s)
    {
        if (!isUsable())
        {
            close();
            start(s.timeout);
        }
        try
        {
            byte[] png = doShoot(s);
            shots++;
            return png;
        }
        catch (SnapshotException e)
        {
            // 页面级问题（登录失效、页面打不开、等待超时）浏览器本身没坏；DevTools 调用出错或连接断了下次重启
            if (e.getCause() instanceof Cdp.CdpException || cdp == null || !cdp.isOpen())
            {
                broken = true;
            }
            throw e;
        }
        catch (RuntimeException e)
        {
            broken = true;
            throw new SnapshotException("截圖失敗：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 启动与关闭

    private void start(Duration timeout)
    {
        try
        {
            userDataDir = Files.createTempDirectory("fivetech-chrome-");
        }
        catch (IOException e)
        {
            throw new SnapshotException("建立瀏覽器臨時目錄失敗：" + e.getMessage(), e);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("--headless=new");
        cmd.add("--disable-gpu");
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--hide-scrollbars");
        cmd.add("--mute-audio");
        cmd.add("--disable-extensions");
        cmd.add("--disable-background-networking");
        cmd.add("--disable-dev-shm-usage");
        cmd.add("--disable-features=Translate,MediaRouter");
        cmd.add("--remote-debugging-address=127.0.0.1");
        cmd.add("--remote-debugging-port=0");
        cmd.add("--user-data-dir=" + userDataDir);
        cmd.addAll(extraArgs);
        cmd.add("about:blank");
        try
        {
            process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        }
        catch (IOException e)
        {
            broken = true;
            throw new SnapshotException("啟動瀏覽器失敗（" + executable + "）：" + e.getMessage(), e);
        }
        String ws = waitDevtools(process, timeout);
        cdp = Cdp.connect(ws, json, Duration.ofSeconds(10));
        shots = 0;
        broken = false;
        log.info("[snapshot] 浏览器已启动 pid={} {}", process.pid(), executable);
    }

    /** 从 stderr 读出 DevTools 地址；之后继续把 stderr 读空（否则缓冲区满了浏览器会卡住） */
    private static String waitDevtools(Process p, Duration timeout)
    {
        CompletableFuture<String> found = new CompletableFuture<>();
        StringBuilder head = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = r.readLine()) != null)
                {
                    if (!found.isDone())
                    {
                        Matcher m = DEVTOOLS.matcher(line);
                        if (m.find())
                        {
                            found.complete(m.group(1));
                        }
                        else if (head.length() < 2000)
                        {
                            head.append(line).append('\n');
                        }
                    }
                }
            }
            catch (IOException ignored)
            {
                // 进程结束
            }
            found.completeExceptionally(new SnapshotException("瀏覽器已退出：" + head.toString().trim()));
        }, "chrome-stderr");
        t.setDaemon(true);
        t.start();
        try
        {
            return found.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            p.destroyForcibly();
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new SnapshotException("瀏覽器啟動失敗：" + (c.getMessage() == null ? c.toString() : c.getMessage()), c);
        }
    }

    @Override
    public void close()
    {
        if (cdp != null)
        {
            try
            {
                cdp.call("Browser.close", Map.of(), null, Duration.ofSeconds(3));
            }
            catch (RuntimeException ignored)
            {
                // 关不掉就直接杀进程
            }
            cdp.close();
            cdp = null;
        }
        if (process != null)
        {
            try
            {
                if (!process.waitFor(3, TimeUnit.SECONDS))
                {
                    process.destroyForcibly();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
        deleteQuietly(userDataDir);
        userDataDir = null;
    }

    private static void deleteQuietly(Path dir)
    {
        if (dir == null)
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // 临时目录，删不掉也无妨
                }
            });
        }
        catch (IOException ignored)
        {
            // 同上
        }
    }

    // ------------------------------------------------------------------ 截一张

    private byte[] doShoot(Shot s)
    {
        long deadline = System.currentTimeMillis() + s.timeout.toMillis();
        Duration step = Duration.ofSeconds(Math.max(5, Math.min(20, s.timeout.toSeconds())));
        String context = cdp.call("Target.createBrowserContext", Map.of("disposeOnDetach", true), null, step)
            .path("browserContextId").asString();
        String session = null;
        try
        {
            String target = cdp.call("Target.createTarget", Map.of("url", "about:blank", "browserContextId", context), null, step)
                .path("targetId").asString();
            session = cdp.call("Target.attachToTarget", Map.of("targetId", target, "flatten", true), null, step)
                .path("sessionId").asString();

            // 网络与加载状态
            Set<String> inflight = ConcurrentHashMap.newKeySet();
            AtomicLong lastNet = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean loaded = new AtomicBoolean(false);
            List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
            cdp.on(session, ev -> {
                String m = ev.path("method").asString("");
                JsonNode p = ev.path("params");
                switch (m)
                {
                    case "Network.requestWillBeSent":
                        inflight.add(p.path("requestId").asString(""));
                        lastNet.set(System.currentTimeMillis());
                        break;
                    case "Network.loadingFinished":
                    case "Network.loadingFailed":
                        inflight.remove(p.path("requestId").asString(""));
                        lastNet.set(System.currentTimeMillis());
                        break;
                    case "Page.loadEventFired":
                        loaded.set(true);
                        break;
                    case "Runtime.exceptionThrown":
                        if (errors.size() < 5)
                        {
                            errors.add(p.path("exceptionDetails").path("exception").path("description")
                                .asString(p.path("exceptionDetails").path("text").asString("")));
                        }
                        break;
                    default:
                        break;
                }
            });
            cdp.call("Page.enable", Map.of(), session, step);
            cdp.call("Network.enable", Map.of(), session, step);
            cdp.call("Runtime.enable", Map.of(), session, step);
            metrics(session, s.width, s.height, s.scale, step);
            if (notBlank(s.cookieName) && notBlank(s.cookieValue))
            {
                Map<String, Object> cookie = new LinkedHashMap<>();
                cookie.put("name", s.cookieName);
                cookie.put("value", s.cookieValue);
                cookie.put("url", s.url);
                cookie.put("path", "/");
                cookie.put("secure", s.url.startsWith("https://"));
                JsonNode ok = cdp.call("Network.setCookie", cookie, session, step);
                if (!ok.path("success").asBoolean(true))
                {
                    throw new SnapshotException("寫入登錄 cookie 失敗");
                }
            }
            cdp.call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", preludeScript(s)), session, step);

            JsonNode nav = cdp.call("Page.navigate", Map.of("url", s.url), session, step);
            if (notBlank(nav.path("errorText").asString("")))
            {
                throw new SnapshotException("頁面打不開：" + nav.path("errorText").asString());
            }
            waitReady(s, session, loaded, inflight, lastNet, deadline, step);
            checkLogin(s, session, step);

            // 撑开视口到整页（或要截的元素）高度：懒加载的区块会在这时加载，再等一轮
            double[] clip = clip(s, session, step);
            int fullHeight = (int) Math.ceil(clip[1] + clip[3]);
            if (fullHeight > s.height)
            {
                metrics(session, s.width, Math.min(fullHeight, s.maxHeight), s.scale, step);
                lastNet.set(System.currentTimeMillis());
                waitReady(s, session, loaded, inflight, lastNet, deadline, step);
                clip = clip(s, session, step);
            }
            sleep(Math.min(s.settleMillis, Math.max(0, deadline - System.currentTimeMillis())));
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("x", clip[0]);
            region.put("y", clip[1]);
            region.put("width", clip[2]);
            region.put("height", Math.min(clip[3], s.maxHeight));
            region.put("scale", 1);
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("format", "png");
            shot.put("captureBeyondViewport", true);
            shot.put("clip", region);
            String data = cdp.call("Page.captureScreenshot", shot, session, Duration.ofSeconds(30)).path("data").asString("");
            if (data.isEmpty())
            {
                throw new SnapshotException("瀏覽器沒有返回截圖");
            }
            if (!errors.isEmpty())
            {
                log.info("[snapshot] 页面有脚本错误（已截图）：{}", errors);
            }
            return Base64.getDecoder().decode(data);
        }
        catch (Cdp.CdpException e)
        {
            throw new SnapshotException("截圖失敗：" + e.getMessage(), e);
        }
        finally
        {
            cdp.off(session);
            try
            {
                cdp.call("Target.disposeBrowserContext", Map.of("browserContextId", context), null, Duration.ofSeconds(5));
            }
            catch (RuntimeException ignored)
            {
                broken = true;
            }
        }
    }

    private void metrics(String session, int width, int height, double scale, Duration step)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", width);
        m.put("height", Math.max(100, height));
        m.put("deviceScaleFactor", scale);
        m.put("mobile", false);
        cdp.call("Emulation.setDeviceMetricsOverride", m, session, step);
    }

    /** 等页面就绪：load 事件、网络空闲 idleMillis、就绪元素出现、加载中的标志都消失 */
    private void waitReady(Shot s, String session, AtomicBoolean loaded, Set<String> inflight, AtomicLong lastNet,
        long deadline, Duration step)
    {
        String check = "(() => {"
            + "const ready = " + (notBlank(s.readySelector) ? "!!document.querySelector(" + js(s.readySelector) + ")" : "true") + ";"
            + "const busy = " + (notBlank(s.busySelector)
                ? "Array.from(document.querySelectorAll(" + js(s.busySelector) + ")).some(e => e.offsetParent !== null || getComputedStyle(e).position === 'fixed')"
                : "false") + ";"
            + "return document.readyState === 'complete' && ready && !busy; })()";
        while (true)
        {
            long now = System.currentTimeMillis();
            boolean idle = inflight.isEmpty() && now - lastNet.get() >= s.idleMillis;
            if (loaded.get())
            {
                // 跳到登录页就不用再等就绪元素了
                checkLogin(s, session, step);
                if (idle && evalBool(check, session, step))
                {
                    return;
                }
            }
            if (now > deadline)
            {
                String why = !loaded.get() ? "頁面沒有載入完成" : !inflight.isEmpty() ? "還有 " + inflight.size() + " 個請求沒有返回"
                    : notBlank(s.readySelector) ? "沒有出現就緒元素 " + s.readySelector + "，或仍在載入中" : "頁面仍在載入中";
                throw new SnapshotException("等待頁面超時（" + s.timeout.toSeconds() + " 秒）：" + why);
            }
            sleep(200);
        }
    }

    private void checkLogin(Shot s, String session, Duration step)
    {
        if (!notBlank(s.loginKeyword))
        {
            return;
        }
        String href = eval("location.href", session, step).asString("");
        if (href.toLowerCase(java.util.Locale.ROOT).contains(s.loginKeyword.toLowerCase(java.util.Locale.ROOT)))
        {
            throw new SnapshotException("跳到了登錄頁（截圖帳號的登錄態無效或沒有權限）：" + href);
        }
    }

    /** 截图区域 [x, y, width, height]（文档坐标）：指定元素的位置，或整页 */
    private double[] clip(Shot s, String session, Duration step)
    {
        String expr;
        if (notBlank(s.captureSelector))
        {
            expr = "(() => { const e = document.querySelector(" + js(s.captureSelector) + ");"
                + " if (!e) return null; const r = e.getBoundingClientRect();"
                + " return [r.left + scrollX, r.top + scrollY, r.width, r.height]; })()";
        }
        else
        {
            expr = "[0, 0, document.documentElement.clientWidth || innerWidth,"
                + " Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)]";
        }
        JsonNode v = eval(expr, session, step);
        if (v == null || !v.isArray() || v.size() < 4)
        {
            throw new SnapshotException("頁面上找不到要截圖的區域 " + s.captureSelector);
        }
        double w = Math.max(1, v.get(2).asDouble());
        double h = Math.max(1, v.get(3).asDouble());
        return new double[] { Math.max(0, v.get(0).asDouble()), Math.max(0, v.get(1).asDouble()), w, h };
    }

    private boolean evalBool(String expr, String session, Duration step)
    {
        JsonNode v = eval(expr, session, step);
        return v != null && v.asBoolean(false);
    }

    private JsonNode eval(String expr, String session, Duration step)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("expression", expr);
        p.put("returnByValue", true);
        JsonNode r = cdp.call("Runtime.evaluate", p, session, step);
        if (r.has("exceptionDetails"))
        {
            return null;
        }
        return r.path("result").path("value");
    }

    /**
     * 每个新文档最先执行的脚本：写 localStorage（about:blank 上会抛错，忽略），DOM 就绪后插入隐藏元素、关掉 CSS 动画的样式。
     * 包内可见供测试。
     */
    String preludeScript(Shot s)
    {
        StringBuilder css = new StringBuilder("*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}");
        if (notBlank(s.hideSelector))
        {
            css.append(s.hideSelector).append("{display:none!important}");
        }
        return "(() => {"
            + "try { const kv = " + json.valueToTree(s.localStorage).toString() + ";"
            + " for (const k in kv) { localStorage.setItem(k, kv[k]); } } catch (e) {}"
            + "const css = " + js(css.toString()) + ";"
            + "const add = () => { const st = document.createElement('style'); st.textContent = css;"
            + " (document.head || document.documentElement).appendChild(st); };"
            + "if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', add); } else { add(); }"
            + "})();";
    }

    /** JS 字符串字面量 */
    private String js(String s)
    {
        return json.valueToTree(s).toString();
    }

    private static boolean notBlank(String s)
    {
        return s != null && !s.isBlank();
    }

    private static void sleep(long ms)
    {
        if (ms <= 0)
        {
            return;
        }
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new SnapshotException("截圖被中斷");
        }
    }
}
