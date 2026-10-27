package com.fivetech.common.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * XSS 过滤器排除 /monitor/events/handle：处理说明原样保存（含 &lt; &gt;），其他 /monitor 接口仍过滤。
 */
class XssFilterExcludeTest
{
    private static final String EXCLUDE = "/monitor/events/handle";

    private static final String NOTE = "{\"eventIds\":[91],\"note\":\"成功率<5%，持續>3次 <code>dd\\\\<code>\"}";

    @Test
    void applicationYmlExcludesHandleOnly() throws IOException
    {
        String yml = Files.readString(Path.of("../fivetech-admin/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("(?m)^xss:\\n((?:[ #].*\\n|\\n)+)").matcher(yml);
        assertTrue(block.find(), "application.yml 缺少 xss 配置");
        String xss = block.group(1);
        assertTrue(xss.contains("enabled: true"), "过滤器仍开启");
        assertTrue(Pattern.compile("(?m)^  excludes: /monitor/events/handle$").matcher(xss).find(), xss);
        assertTrue(Pattern.compile("(?m)^  urlPatterns: /system/\\*,/monitor/\\*,/tool/\\*$").matcher(xss).find(), "匹配范围不变：" + xss);
    }

    @Test
    void handleNoteIsKeptAsIs() throws Exception
    {
        assertEquals(NOTE, run("/monitor/events/handle", NOTE));
    }

    @Test
    void otherMonitorPathsAreStillCleaned() throws Exception
    {
        String body = "{\"note\":\"<script>alert(1)</script>ok\"}";
        assertEquals("{\"note\":\"alert(1)ok\"}", run("/monitor/events/unhandle", body));
        assertEquals("{\"note\":\"alert(1)ok\"}", run("/monitor/events/handle/x", body), "只排除这一个地址");
        assertEquals("{\"note\":\"alert(1)ok\"}", run("/system/notify-templates", body));
    }

    /** 走一遍过滤器，返回下游读到的请求体 */
    private static String run(String servletPath, String body) throws Exception
    {
        XssFilter filter = new XssFilter();
        filter.init(proxy(FilterConfig.class, Map.of("getInitParameter", EXCLUDE)));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(XssFilterExcludeTest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (p, m, a) -> {
                    switch (m.getName())
                    {
                        case "getServletPath":
                            return servletPath;
                        case "getMethod":
                            return "POST";
                        case "getHeader":
                            return "Content-Type".equalsIgnoreCase((String) a[0]) ? "application/json;charset=UTF-8" : null;
                        case "getContentType":
                            return "application/json;charset=UTF-8";
                        case "getHeaders":
                        case "getHeaderNames":
                            return Collections.emptyEnumeration();
                        case "getInputStream":
                            return stream(bytes);
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
        HttpServletResponse resp = proxy(HttpServletResponse.class, Map.of());
        String[] seen = new String[1];
        filter.doFilter(req, resp, (ServletRequest r, jakarta.servlet.ServletResponse s) -> seen[0] =
                new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        return seen[0];
    }

    private static ServletInputStream stream(byte[] bytes)
    {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        return new ServletInputStream()
        {
            @Override
            public int read()
            {
                return in.read();
            }

            @Override
            public boolean isFinished()
            {
                return in.available() == 0;
            }

            @Override
            public boolean isReady()
            {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l)
            {
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> answers)
    {
        return (T) Proxy.newProxyInstance(XssFilterExcludeTest.class.getClassLoader(), new Class<?>[] { type },
                (p, m, a) -> answers.containsKey(m.getName()) ? answers.get(m.getName()) : defaultValue(m.getReturnType()));
    }

    private static Object defaultValue(Class<?> t)
    {
        if (t == boolean.class)
        {
            return false;
        }
        if (t == int.class || t == long.class)
        {
            return t == int.class ? (Object) 0 : (Object) 0L;
        }
        return List.class.isAssignableFrom(t) ? Collections.emptyList() : null;
    }
}
