package com.liqiang.archive.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P7 统一身份上下文：业务代码一律通过 {@link #current()} 获取"我是谁"。
 *
 * <p>身份解析优先级：
 * <ol>
 *   <li><b>Bearer Token</b>（正式登录会话，主身份依据）——生产唯一可信来源；</li>
 *   <li><b>X-Employee-Id</b>（仅开发环境降级，{@code app.security.allow-employee-header=true} 时生效）；
 *   从"身份依据"降级为开发便利，生产环境必须关闭；</li>
 *   <li>匿名：无任何权限（{@code app.security.compatibility-admin=true} 时返回开发兼容管理员，生产必须关闭）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private static final String ATTR = CurrentUserService.class.getName() + ".CURRENT_USER";

    private final HttpServletRequest request;
    private final AuthService authService;
    private final CurrentUserFactory factory;
    private final JdbcTemplate jdbc;

    @Value("${app.security.compatibility-admin:false}")
    private boolean compatibilityAdmin;

    @Value("${app.security.allow-employee-header:true}")
    private boolean allowEmployeeHeader;

    /** 当前登录用户（请求内缓存，避免同一次请求重复解析/查库）。 */
    public CurrentUser current() {
        Object cached = request.getAttribute(ATTR);
        if (cached instanceof CurrentUser cu) {
            return cu;
        }
        CurrentUser user = resolve();
        request.setAttribute(ATTR, user);
        return user;
    }

    public boolean can(String permission) {
        return current().permissions().contains(permission);
    }

    /** 业务员姓名是否属于当前用户可访问范围（本人/本部门/全部/自定义）。 */
    public boolean canAccessSalesperson(String name) {
        return canAccessSalesperson(name, null);
    }

    /** 按功能取数据范围后判断业务员姓名是否可访问。featureCode 为空时回退到全局数据范围。 */
    public boolean canAccessSalesperson(String name, String featureCode) {
        CurrentUser u = current();
        if (u.employeeId() == null) {
            return false;
        }
        String scope = scopeOf(featureCode);
        if ("ALL".equals(scope)) {
            return true;
        }
        if (name == null || name.isBlank()) {
            return true;
        }
        if (name.equals(u.name())) {
            return true;
        }
        if ("DEPARTMENT".equals(scope)) {
            if (u.department() == null) {
                return false;
            }
            List<String> names = jdbc.queryForList(
                    "SELECT name FROM employees WHERE department=? AND status='ACTIVE'", String.class, u.department());
            return names.contains(name);
        }
        if ("CUSTOM".equals(scope)) {
            FeatureScope fs = u.featureScopes().get(featureCode);
            if (fs != null && !activeNamesOf(fs.employeeIds()).isEmpty()) {
                List<String> names = activeNamesOf(fs.employeeIds());
                if (names.contains(name)) {
                    return true;
                }
            }
            if (fs != null && fs.departmentNames() != null && !fs.departmentNames().isEmpty()) {
                List<String> names = jdbc.queryForList(
                        "SELECT name FROM employees WHERE department IN ("
                                + fs.departmentNames().stream().map(d -> "?").collect(Collectors.joining(","))
                                + ") AND status='ACTIVE'", String.class, fs.departmentNames().toArray());
                return names.contains(name);
            }
        }
        return false;
    }

    /** 员工 ID 是否属于当前用户可访问范围（本人/本部门/全部/自定义）。 */
    public boolean canAccessEmployee(Long employeeId) {
        return canAccessEmployee(employeeId, null);
    }

    /** 按功能取数据范围后判断员工 ID 是否可访问。featureCode 为空时回退到全局数据范围。 */
    public boolean canAccessEmployee(Long employeeId, String featureCode) {
        CurrentUser u = current();
        if (u.employeeId() == null) {
            return false;
        }
        String scope = scopeOf(featureCode);
        if ("ALL".equals(scope)) {
            return true;
        }
        if (employeeId == null) {
            return true;
        }
        if (employeeId.equals(u.employeeId())) {
            return true;
        }
        if ("DEPARTMENT".equals(scope)) {
            if (u.department() == null) {
                return false;
            }
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM employees WHERE department=? AND status='ACTIVE'", Long.class, u.department());
            return ids.contains(employeeId);
        }
        if ("CUSTOM".equals(scope)) {
            FeatureScope fs = u.featureScopes().get(featureCode);
            if (fs != null && fs.employeeIds() != null && fs.employeeIds().contains(employeeId)) {
                return true;
            }
            if (fs != null && fs.departmentNames() != null && !fs.departmentNames().isEmpty()) {
                List<Long> ids = jdbc.queryForList(
                        "SELECT id FROM employees WHERE department IN ("
                                + fs.departmentNames().stream().map(d -> "?").collect(Collectors.joining(","))
                                + ") AND status='ACTIVE'", Long.class, fs.departmentNames().toArray());
                return ids.contains(employeeId);
            }
        }
        return false;
    }

    /** 取当前用户在某功能上的数据范围；功能无配置或未标注时回退到全局 dataScope。 */
    public String scopeOf(String featureCode) {
        CurrentUser u = current();
        if (featureCode != null) {
            FeatureScope fs = u.featureScopes().get(featureCode);
            if (fs != null) {
                return fs.scope();
            }
        }
        return u.dataScope();
    }

    /** 自定义名单（员工 ID）对应的在职员工姓名。 */
    public List<String> activeNamesOf(List<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT name FROM employees WHERE id IN ("
                        + employeeIds.stream().map(id -> "?").collect(Collectors.joining(","))
                        + ") AND status='ACTIVE'", String.class, employeeIds.toArray());
    }

    private CurrentUser resolve() {
        // 1) 正式登录会话（Bearer Token）
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            var session = authService.validateSession(token);
            if (session == null) {
                throw new NotAuthenticatedException("登录已失效，请重新登录");
            }
            return factory.build(session.getEmployeeId(), session.getLoginType(), null, session.getExternalUserId());
        }
        // 2) 开发环境降级：X-Employee-Id（生产必须关闭）
        if (allowEmployeeHeader) {
            String raw = request.getHeader("X-Employee-Id");
            if (raw != null && !raw.isBlank()) {
                long id;
                try {
                    id = Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    throw new SecurityException("员工身份格式无效");
                }
                return factory.build(id, "COMPAT", null, null);
            }
        }
        // 3) 未登录
        if (compatibilityAdmin) {
            return factory.compatibilityAdmin();
        }
        return factory.anonymous();
    }

    /**
     * 当前登录用户统一模型。业务代码、权限判断、数据范围全部由此确定。
     *
     * @param employeeId     系统员工 ID（未登录为 null）
     * @param loginType      LOCAL / DINGTALK / COMPAT / NONE
     * @param loginAccountId 本地登录账号 ID（LOCAL 登录时为非空）
     * @param externalUserId 钉钉 userId 等外部身份（DINGTALK 登录时为非空）
     * @param featureScopes  功能级数据范围（V249 起按功能区分：全部/本部门/本人/自定义名单）
     */
    public record CurrentUser(
            Long employeeId,
            String name,
            String department,
            Set<String> roles,
            Set<String> permissions,
            String dataScope,
            boolean compatibilityMode,
            String loginType,
            Long loginAccountId,
            String externalUserId,
            Map<String, FeatureScope> featureScopes) {

        public boolean isAuthenticated() {
            return employeeId != null;
        }
    }

    /**
     * 功能级数据范围：scope 取值 ALL / DEPARTMENT / CUSTOM / SELF；
     * employeeIds 与 departmentNames 仅当 scope=CUSTOM 时携带自定义名单。
     */
    public record FeatureScope(String scope, List<Long> employeeIds, List<String> departmentNames) {

        public static FeatureScope of(String scope) {
            return new FeatureScope(scope, Collections.emptyList(), Collections.emptyList());
        }
    }
}
