package com.liqiang.archive.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;

/**
 * 接口访问拦截器，负责「强制认证 + 注解授权」两层控制：
 * <ul>
 *   <li>标注 {@link PublicApi} 的接口（类级或方法级）：匿名与登录用户均可访问，直接放行；</li>
 *   <li>其余接口：未登录（匿名）一律返回 401；登录用户按 {@link RequirePermission} 校验，未命中返回 403；</li>
 *   <li>标注 {@link LoginOnly} 的接口（类级或方法级）：仅要求登录、不校验具体权限；</li>
 *   <li>未声明任何权限注解的接口：读方法（GET/HEAD/OPTIONS）灰度期放行，写方法（POST/PUT/DELETE/PATCH）一律拒绝，
 *       强制要求显式声明 {@link RequirePermission} 或 {@link LoginOnly}，杜绝"无主写接口"。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final CurrentUserService users;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod m)) {
            return true; // 静态资源等非 HandlerMethod 直接放行
        }
        // 1) 显式标注公开的接口：匿名与登录用户均可访问
        if (m.hasMethodAnnotation(PublicApi.class) || m.getBeanType().getAnnotation(PublicApi.class) != null) {
            return true;
        }
        // 2) 强制认证：匿名访问非公开接口一律拒绝（401）
        CurrentUserService.CurrentUser user = users.current();
        if (!user.isAuthenticated()) {
            throw new NotAuthenticatedException("请先登录");
        }
        // 3) 仅要求登录、不校验权限（全员写操作：退出登录/上传/借用归还/个人工作台）
        if (m.hasMethodAnnotation(LoginOnly.class) || m.getBeanType().getAnnotation(LoginOnly.class) != null) {
            return true;
        }
        // 4) 注解授权
        RequirePermission rule = m.getMethodAnnotation(RequirePermission.class);
        if (rule == null) {
            rule = m.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (rule == null) {
            // 灰度策略：未声明权限的读方法放行；未声明权限的写方法一律拒绝，防止越权写操作
            if (isReadMethod(req.getMethod())) {
                return true;
            }
            throw new SecurityException("接口未声明权限注解，已拒绝此写操作，请联系管理员补充 @RequirePermission 或 @LoginOnly");
        }
        Set<String> owned = user.permissions();
        boolean ok = rule.any()
                ? Arrays.stream(rule.value()).anyMatch(owned::contains)
                : Arrays.stream(rule.value()).allMatch(owned::contains);
        if (!ok) {
            throw new SecurityException("没有执行此操作的权限");
        }
        return true;
    }

    private static boolean isReadMethod(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }

}
