package com.fivetech.framework.interceptor;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fivetech.common.utils.StringUtils;

/**
 * 一份 IP 白名单：逗号分隔，支持单个 IP（IPv4 / IPv6）和 CIDR 网段，如 {@code 1.2.3.4,10.0.0.0/8,2001:db8::/32}。
 * <p>
 * 构造时一次解析好，之后只读、线程安全。非法条目记警告后忽略，不会让整份名单失效。
 * 管理接口白名单与 Dashboard 白名单共用这一个实现。
 *
 * @author fivetech
 */
public final class IpWhitelist
{
    private static final Logger log = LoggerFactory.getLogger(IpWhitelist.class);

    /** 只接受 IP 字面量，避免 InetAddress.getByName 对主机名做 DNS 解析 */
    private static final Pattern IP_LITERAL =
        Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F.]*:[0-9a-fA-F:.]*)$");

    /** 每条规则：{网络地址, {前缀长度}} */
    private final List<byte[][]> rules;

    private IpWhitelist(List<byte[][]> rules)
    {
        this.rules = Collections.unmodifiableList(rules);
    }

    /**
     * @param name 名单名称，只用于日志
     * @param spec 逗号分隔的 IP / CIDR，可为空
     */
    public static IpWhitelist parse(String name, String spec)
    {
        List<byte[][]> r = new ArrayList<>();
        for (String item : StringUtils.isEmpty(spec) ? new String[0] : spec.split(","))
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
                    log.warn("[ip-whitelist] {} 忽略非法条目 {}", name, s);
                    continue;
                }
            }
            byte[] net = parseIp(host);
            if (net == null || (prefix != null && (prefix < 0 || prefix > net.length * 8)))
            {
                log.warn("[ip-whitelist] {} 忽略非法条目 {}", name, s);
                continue;
            }
            r.add(new byte[][] { net, { (byte) (prefix == null ? net.length * 8 : prefix) } });
        }
        return new IpWhitelist(r);
    }

    public boolean isEmpty()
    {
        return rules.isEmpty();
    }

    public int size()
    {
        return rules.size();
    }

    /** ip 是否落在名单里；ip 不是合法的 IP 字面量时返回 false */
    public boolean contains(String ip)
    {
        byte[] addr = parseIp(ip);
        if (addr == null)
        {
            return false;
        }
        for (byte[][] rule : rules)
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

    static byte[] parseIp(String ip)
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
