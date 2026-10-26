package com.fivetech.framework.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;

/**
 * 取客户端真实 IP，供 IP 白名单使用（管理接口白名单与 Dashboard 白名单共用同一套取法）。
 * <p>
 * 配置沿用 {@code security.admin-ip-whitelist.*} 下的两项（它们描述的是部署的代理链，与哪份名单无关）：
 * <ul>
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
public class ClientIpResolver
{
    private final String clientIpHeader;

    private final int trustedProxyHops;

    public ClientIpResolver(@Value("${security.admin-ip-whitelist.client-ip-header:}") String clientIpHeader,
            @Value("${security.admin-ip-whitelist.trusted-proxy-hops:1}") int trustedProxyHops)
    {
        this.clientIpHeader = clientIpHeader;
        this.trustedProxyHops = trustedProxyHops;
    }

    public String resolve(HttpServletRequest request)
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
    static String stripPort(String v)
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
}
