package com.liqiang.archive.security;

import com.liqiang.archive.config.SecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动期权限码校验器：扫描所有 {@link RestController} 上 {@link RequirePermission} 引用的权限码，
 * 与 {@code system_permissions} 表比对，发现"代码已引用但表未注册"的权限码时：
 * <ul>
 *   <li>FAIL（默认）：抛出异常阻止启动，强制开发者先补充 Flyway 迁移注册权限码；</li>
 *   <li>WARN：仅打印告警日志，不阻断启动（灰度期可临时降级）。</li>
 * </ul>
 * 防止权限码漂移（代码引用 vs 数据库定义不一致）进入生产环境。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionCodeValidator implements ApplicationRunner {

    private final ApplicationContext context;
    private final JdbcTemplate jdbcTemplate;
    private final SecurityProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> referenced = collectReferencedCodes();
        Set<String> registered = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT permission_code FROM system_permissions", String.class));
        referenced.removeAll(registered);
        if (referenced.isEmpty()) {
            log.debug("权限码校验通过：@RequirePermission 引用的 {} 个权限码均已注册", referenced.size());
            return;
        }
        String message = "@RequirePermission 引用了未注册的权限码 " + referenced
                + "；请先添加 Flyway 迁移脚本将权限码写入 system_permissions，并分配给相应角色";
        if (properties.getPermissionCodeValidation() == SecurityProperties.PermissionCodeValidationMode.FAIL) {
            throw new IllegalStateException(message);
        }
        log.warn("[安全告警] {}", message);
    }

    private Set<String> collectReferencedCodes() {
        Set<String> codes = new TreeSet<>();
        for (String beanName : context.getBeanNamesForAnnotation(RestController.class)) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            RequirePermission typeRule = type.getAnnotation(RequirePermission.class);
            if (typeRule != null) {
                addAll(codes, typeRule.value());
            }
            for (Method method : type.getDeclaredMethods()) {
                RequirePermission methodRule = method.getAnnotation(RequirePermission.class);
                if (methodRule != null) {
                    addAll(codes, methodRule.value());
                }
            }
        }
        return codes;
    }

    private static void addAll(Set<String> target, String[] values) {
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    target.add(v.trim());
                }
            }
        }
    }
}
