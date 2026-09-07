package com.fivetech.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import com.fivetech.common.constant.Constants;
import java.util.List;
import java.util.Locale;

/**
 * 资源文件配置加载
 * 
 * @author fivetech
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer
{
    @Bean
    public LocaleResolver localeResolver()
    {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Constants.DEFAULT_LOCALE);
        // Keep the locale tags aligned with sys_menu_i18n: zh-CN, zh-TW and en-US.
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.US, Locale.ENGLISH));
        return resolver;
    }
}
