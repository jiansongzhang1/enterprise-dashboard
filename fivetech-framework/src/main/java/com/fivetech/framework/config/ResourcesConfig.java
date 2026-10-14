package com.fivetech.framework.config;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.fivetech.common.utils.TraceIdUtils;
import com.fivetech.common.config.FiveTechConfig;
import com.fivetech.common.constant.Constants;
import com.fivetech.framework.interceptor.AdminIpWhitelistInterceptor;
import com.fivetech.framework.interceptor.RepeatSubmitInterceptor;

/**
 * 通用配置
 * 
 * @author fivetech
 */
@Configuration
public class ResourcesConfig implements WebMvcConfigurer
{
    @Autowired
    private RepeatSubmitInterceptor repeatSubmitInterceptor;

    @Autowired
    private AdminIpWhitelistInterceptor adminIpWhitelistInterceptor;

    /** 不受管理接口 IP 白名单限制的路径（Ant 风格，逗号分隔），见 security.admin-ip-whitelist.exclude-paths */
    @Value("${security.admin-ip-whitelist.exclude-paths:}")
    private String adminIpWhitelistExcludePaths;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry)
    {
        /** 本地文件上传路径 */
        registry.addResourceHandler(Constants.RESOURCE_PREFIX + "/**")
                .addResourceLocations("file:" + FiveTechConfig.getProfile() + "/");

        /** swagger配置 */
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springfox-swagger-ui/")
                .setCacheControl(CacheControl.maxAge(5, TimeUnit.HOURS).cachePublic());
    }

    /**
     * 自定义拦截规则
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(repeatSubmitInterceptor).addPathPatterns("/**");
        // 内部管理接口 IP 白名单：拦全部，只放过 exclude-paths（仪表板、登录与账号自助等）。
        // 总开关 security.admin-ip-whitelist.enabled，关闭时拦截器直接放行
        String[] excludes = Arrays.stream(adminIpWhitelistExcludePaths.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        registry.addInterceptor(adminIpWhitelistInterceptor).addPathPatterns("/**").excludePathPatterns(excludes);
    }

    /**
     * 跨域配置
     */
    @Bean
    public CorsFilter corsFilter()
    {
        CorsConfiguration config = new CorsConfiguration();
        // 设置访问源地址
        config.addAllowedOriginPattern("*");
        // 设置访问源请求头
        config.addAllowedHeader("*");
        // 设置访问源请求方法
        config.addAllowedMethod("*");
        // 暴露链路追踪响应头，前端才能读到 X-Trace-Id
        config.addExposedHeader(TraceIdUtils.TRACE_HEADER);
        // 暴露下载响应头，否则前端拿不到 CSV 的文件名
        config.addExposedHeader("Content-Disposition");
        config.addExposedHeader("download-filename");
        // 有效期 1800秒
        config.setMaxAge(1800L);
        // 添加映射路径，拦截一切请求
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        // 返回新的CorsFilter
        return new CorsFilter(source);
    }
}