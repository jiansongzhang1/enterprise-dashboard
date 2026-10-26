package com.fivetech.common.utils;

import java.util.function.Supplier;

/**
 * 当前请求所属站点（多站点一期）。
 *
 * <p>由 {@code SiteContextInterceptor} 按请求头 {@code X-Site-Code} 解析并校验后放入，请求结束清除。
 * 下游只认这里的站点：查询的站点编码、站点前缀权限、调用 UDS 时的站点头 X-Uds-Site 都从这里取，
 * 请求体里的 siteCode 不再采信。没有请求上下文（定时任务等）时为空，调用方回退到默认站点。</p>
 *
 * @author fivetech
 */
public final class SiteContext
{
    private static final ThreadLocal<String> CODE = new ThreadLocal<>();

    /** 该站点在 UDS 的站点标识（请求头 X-Uds-Site，如 SITE-B）；默认（已上线的老）站点为空，不发这个头 */
    private static final ThreadLocal<String> UDS_SITE = new ThreadLocal<>();

    private SiteContext()
    {
    }

    public static void set(String code, String udsSite)
    {
        CODE.set(code);
        UDS_SITE.set(udsSite);
    }

    public static String code()
    {
        return CODE.get();
    }

    public static String udsSite()
    {
        return UDS_SITE.get();
    }

    public static void clear()
    {
        CODE.remove();
        UDS_SITE.remove();
    }

    /** 临时切到指定站点执行（如按任务记录里的站点轮询导出），结束后恢复原站点 */
    public static <T> T callWith(String code, String udsSite, Supplier<T> action)
    {
        String prevCode = CODE.get();
        String prevUdsSite = UDS_SITE.get();
        set(code, udsSite);
        try
        {
            return action.get();
        }
        finally
        {
            if (prevCode == null && prevUdsSite == null)
            {
                clear();
            }
            else
            {
                set(prevCode, prevUdsSite);
            }
        }
    }
}
