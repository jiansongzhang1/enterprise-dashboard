package com.fivetech.framework.interceptor;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.alibaba.fastjson2.JSON;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.utils.ServletUtils;
import com.fivetech.common.utils.StringUtils;

/**
 * 仪表板接口（/dashboard/**）IP 白名单。
 * <p>
 * 配置（{@code dashboard.ip-whitelist.*}）：
 * <ul>
 *   <li>{@code enabled}：总开关，默认 false</li>
 *   <li>{@code allowed}：逗号分隔，支持单个 IP（IPv4 / IPv6）和 CIDR 网段，如 {@code 1.2.3.4,10.0.0.0/8}</li>
 *   <li>{@code client-ip-header}：取客户端 IP 的请求头，如 CloudFront 的 {@code CloudFront-Viewer-Address}（值为 ip:port）；
 *       为空时用 X-Forwarded-For</li>
 *   <li>{@code trusted-proxy-hops}：本服务前面有几层会追加 X-Forwarded-For 的可信代理（只有 CloudFront = 1，
 *       CloudFront + Nginx = 2）</li>
 * </ul>
 * <p>
 * <b>为什么不用 IpUtils.getIpAddr</b>：它取 X-Forwarded-For 最左边的值，那是客户端自己可以随便填的，
 * 白名单靠它等于没有。这里从右往左数，跳过可信代理追加的部分，取到的才是代理真正看到的来源 IP。
 *
 * @author fivetech
 */
@Component
public class DashboardIpWhitelistInterceptor implements HandlerInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(DashboardIpWhitelistInterceptor.class);

    /** 只接受 IP 字面量，避免 InetAddress.getByName 对主机名做 DNS 解析 */
    private static final java.util.regex.Pattern IP_LITERAL =
        java.util.regex.Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F.]*:[0-9a-fA-F:.]*)$");

    @Value("${dashboard.ip-whitelist.enabled:false}")
    private boolean enabled;

    @Value("${dashboard.ip-whitelist.allowed:}")
    private String allowed;

    @Value("${dashboard.ip-whitelist.client-ip-header:}")
    private String clientIpHeader;

    @Value("${dashboard.ip-whitelist.trusted-proxy-hops:1}")
    private int trustedProxyHops;

    private volatile List<byte[][]> rules;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
    {
        if (!enabled)
        {
            return true;
        }
        String ip = clientIp(request);
        if (ip != null && isAllowed(ip))
        {
            return true;
        }
        log.warn("[ip-whitelist] 拒绝访问 ip={} uri={} xff={}", ip, request.getRequestURI(),
            request.getHeader("X-Forwarded-For"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error(403, "当前网络（" + ip + "）不在访问白名单内")));
        return false;
    }

    /** 取客户端真实 IP */
    String clientIp(HttpServletRequest request)
    {
        if (StringUtils.isNotEmpty(clientIpHeader))
        {
            String v = request.getHeader(clientIpHeader);
            if (StringUtils.isNotEmpty(v))
            {
                return stripPort(v.trim());
            }
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotEmpty(xff) && trustedProxyHops > 0)
        {
            String[] parts = xff.split(",");
            // 最右边 trustedProxyHops 个是可信代理追加的；最右第 hops 个就是最外层代理看到的来源 IP
            int idx = parts.length - trustedProxyHops;
            if (idx >= 0)
            {
                return parts[idx].trim();
            }
            // 层数比配置少：说明请求没走完整代理链（如直连源站），按最左边兜底不可信，直接用连接地址
        }
        return request.getRemoteAddr();
    }

    /** CloudFront-Viewer-Address 形如 1.2.3.4:5678 或 [2001:db8::1]:5678 */
    private static String stripPort(String v)
    {
        if (v.startsWith("["))
        {
            int end = v.indexOf(']');
            return end > 0 ? v.substring(1, end) : v;
        }
        int colon = v.lastIndexOf(':');
        // IPv4:port 只有一个冒号；裸 IPv6 有多个冒号，不处理
        return colon > 0 && v.indexOf(':') == colon ? v.substring(0, colon) : v;
    }

    boolean isAllowed(String ip)
    {
        byte[] addr = parse(ip);
        if (addr == null)
        {
            return false;
        }
        for (byte[][] rule : rules())
        {
            byte[] net = rule[0];
            int prefix = rule[1][0] & 0xFF;
            if (net.length == addr.length && matches(net, addr, prefix))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] net, byte[] addr, int prefix)
    {
        int full = prefix / 8;
        for (int i = 0; i < full; i++)
        {
            if (net[i] != addr[i])
            {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0)
        {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (net[full] & mask) == (addr[full] & mask);
    }

    /** 解析白名单配置：每条为 {网络地址, {前缀长度}}；非法条目记警告后忽略 */
    private List<byte[][]> rules()
    {
        List<byte[][]> r = rules;
        if (r != null)
        {
            return r;
        }
        r = new ArrayList<>();
        for (String item : StringUtils.isEmpty(allowed) ? new String[0] : allowed.split(","))
        {
            String s = item.trim();
            if (s.isEmpty())
            {
                continue;
            }
            String host = s;
            Integer prefix = null;
            int slash = s.indexOf('/');
            if (slash > 0)
            {
                host = s.substring(0, slash);
                try
                {
                    prefix = Integer.parseInt(s.substring(slash + 1));
                }
                catch (NumberFormatException e)
                {
                    log.warn("[ip-whitelist] 忽略非法条目 {}", s);
                    continue;
                }
            }
            byte[] net = parse(host);
            if (net == null || (prefix != null && (prefix < 0 || prefix > net.length * 8)))
            {
                log.warn("[ip-whitelist] 忽略非法条目 {}", s);
                continue;
            }
            r.add(new byte[][] { net, { (byte) (prefix == null ? net.length * 8 : prefix) } });
        }
        if (enabled && r.isEmpty())
        {
            log.warn("[ip-whitelist] 已开启但白名单为空，所有 /dashboard 请求都会被拒绝");
        }
        rules = r;
        return r;
    }

    private static byte[] parse(String ip)
    {
        if (ip == null || !IP_LITERAL.matcher(ip).matches())
        {
            return null;
        }
        try
        {
            return InetAddress.getByName(ip).getAddress();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
