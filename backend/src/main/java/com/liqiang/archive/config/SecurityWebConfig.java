package com.liqiang.archive.config;
import com.liqiang.archive.security.PermissionInterceptor;import lombok.RequiredArgsConstructor;import org.springframework.context.annotation.Configuration;import org.springframework.web.servlet.config.annotation.*;
@Configuration @RequiredArgsConstructor public class SecurityWebConfig implements WebMvcConfigurer{private final PermissionInterceptor interceptor;@Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(interceptor).addPathPatterns("/api/**");}}
