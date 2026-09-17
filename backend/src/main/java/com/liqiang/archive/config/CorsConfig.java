package com.liqiang.archive.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> originPatterns = new ArrayList<>();
        // 用户显式配置的 origin（精确匹配）
        for (String origin : corsProperties.getAllowedOrigins()) {
            if (origin != null && !origin.isBlank()) {
                originPatterns.add(origin);
            }
        }
        // LAN 通用模式：任何 192.168.*.*、10.*.*.*、172.16-31.*.*、localhost 的任意端口
        originPatterns.add("http://localhost:*");
        originPatterns.add("http://127.0.0.1:*");
        originPatterns.add("http://10.*:*");
        originPatterns.add("http://192.168.*:*");
        originPatterns.add("http://172.16.*:*");
        originPatterns.add("http://172.17.*:*");
        originPatterns.add("http://172.18.*:*");
        originPatterns.add("http://172.19.*:*");
        originPatterns.add("http://172.2*:*");
        originPatterns.add("http://172.30.*:*");
        originPatterns.add("http://172.31.*:*");

        configuration.setAllowedOriginPatterns(originPatterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "Content-Type", "Content-Length"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}
