package com.liqiang.archive.controller;

import com.liqiang.archive.security.AuthService;
import com.liqiang.archive.security.LoginOnly;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P7 正式身份认证入口。
 * <ul>
 *   <li>登录方式：POST /login/local（员工手机号账号）</li>
 *   <li>POST /bootstrap：系统无任何登录账号时的首次引导（仅超管初始化时使用一次）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService users;

    @Value("${app.security.allow-employee-header:false}")
    private boolean allowEmployeeHeader;

    public record LoginPayload(String username, String password) {
    }

    public record BootstrapPayload(Long employeeId) {
    }

    public record AccountEnabledPayload(boolean enabled) {
    }

    /** 员工手机号账号登录。 */
    @PublicApi
    @PostMapping("/login/local")
    public AuthService.LoginResult loginLocal(@RequestBody LoginPayload body, HttpServletRequest req) {
        return authService.loginLocal(body.username(), body.password(), clientIp(req), req.getHeader("User-Agent"));
    }

    /** 退出登录：撤销当前会话。 */
    @LoginOnly
    @PostMapping("/logout")
    public void logout(HttpServletRequest req) {
        authService.logout(bearerToken(req));
    }

    /** 当前登录用户。 */
    @GetMapping("/me")
    public CurrentUserService.CurrentUser me() {
        return users.current();
    }

    /** 登录页配置：各登录方式可用性与前端所需参数。 */
    @PublicApi
    @GetMapping("/login-config")
    public Map<String, Object> loginConfig() {
        return Map.of(
                "localLoginEnabled", true,
                "allowEmployeeHeader", allowEmployeeHeader);
    }

    /** 是否尚未创建任何登录账号（登录页据此显示首次引导表单）。 */
    @PublicApi
    @GetMapping("/bootstrap-required")
    public Map<String, Object> bootstrapRequired() {
        return Map.of("bootstrapRequired", authService.bootstrapRequired());
    }

    /**
     * 登录页公开员工列表（无需 Token）。
     * 仅在系统首次引导或开发兼容模式开启时返回数据，生产环境常态下为空。
     */
    @PublicApi
    @GetMapping("/public-employees")
    public List<Map<String, Object>> publicEmployees() {
        return authService.publicEmployees();
    }

    /** 首次引导：仅当系统没有任何登录账号时可用（初始化第一个管理员账号）。 */
    @PublicApi
    @PostMapping("/bootstrap")
    public AuthService.LoginResult bootstrap(@RequestBody BootstrapPayload body, HttpServletRequest req) {
        return authService.bootstrap(body.employeeId(),
                clientIp(req), req.getHeader("User-Agent"));
    }

    /** 登录账号列表。 */
    @GetMapping("/accounts")
    @RequirePermission("system.employee.manage")
    public List<Map<String, Object>> accounts() {
        return authService.listAccounts();
    }

    @GetMapping("/accounts/employee/{employeeId}")
    @RequirePermission("system.employee.manage")
    public Optional<Map<String, Object>> accountOfEmployee(@PathVariable Long employeeId) {
        return Optional.ofNullable(authService.accountOfEmployee(employeeId));
    }

    /** 重置密码（会撤销该员工全部现有会话）。 */
    @PostMapping("/accounts/{id}/reset-password")
    @RequirePermission("system.employee.manage")
    public void resetPassword(@PathVariable Long id) {
        authService.resetPassword(id);
    }

    /** 启用或停用员工登录账号。 */
    @PutMapping("/accounts/{id}/enabled")
    @RequirePermission("system.employee.manage")
    public void setAccountEnabled(@PathVariable Long id, @RequestBody AccountEnabledPayload body) {
        authService.setAccountEnabled(id, body.enabled());
    }

    /** 删除本地登录账号（会撤销该员工全部现有会话）。 */
    @DeleteMapping("/accounts/{id}")
    @RequirePermission("system.employee.manage")
    public void deleteAccount(@PathVariable Long id) {
        authService.deleteAccount(id);
    }

    private static String bearerToken(HttpServletRequest req) {
        String authorization = req.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
