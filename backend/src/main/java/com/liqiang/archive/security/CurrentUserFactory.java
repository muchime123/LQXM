package com.liqiang.archive.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一构建当前用户（员工 + 角色 + 权限 + 数据范围）。
 * <p>P7 起，"我是谁"只由后端决定：登录会话（Token）或开发环境兼容 Header 解析出 employeeId 后，
 * 一律经本工厂生成 {@link CurrentUserService.CurrentUser}，业务层不再直接解析任何身份来源。
 * <p>V249 起数据范围下沉到"功能级"：{@link CurrentUser#featureScopes()} 由角色-功能授权
 * （system_role_feature_grants）聚合而来，行级过滤按功能取值；全局 {@code dataScope} 仅作
 * 历史兼容回退。
 */
@Component
@RequiredArgsConstructor
public class CurrentUserFactory {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 从 employeeId 构建正式登录用户（员工必须存在且 ACTIVE）。 */
    public CurrentUserService.CurrentUser build(Long employeeId, String loginType,
                                                Long loginAccountId, String externalUserId) {
        List<Map<String, Object>> es = jdbc.queryForList(
                "SELECT name, department, status FROM employees WHERE id=?", employeeId);
        if (es.isEmpty() || !"ACTIVE".equals(es.getFirst().get("status"))) {
            throw new SecurityException("员工身份不存在或已停用");
        }
        Map<String, Object> e = es.getFirst();
        String name = String.valueOf(e.get("name"));
        String department = (String) e.get("department");

        Set<String> roles = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT r.role_code FROM employee_roles er JOIN system_roles r ON r.id=er.role_id WHERE er.employee_id=?",
                String.class, employeeId));
        Set<String> permissions = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT p.permission_code FROM employee_roles er"
                        + " JOIN system_role_permissions rp ON rp.role_id=er.role_id"
                        + " JOIN system_permissions p ON p.id=rp.permission_id"
                        + " WHERE er.employee_id=? AND p.enabled = TRUE",
                String.class, employeeId));
        Integer level = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CASE r.data_scope WHEN 'ALL' THEN 3 WHEN 'DEPARTMENT' THEN 2 ELSE 1 END),1)"
                        + " FROM employee_roles er JOIN system_roles r ON r.id=er.role_id WHERE er.employee_id=?",
                Integer.class, employeeId);
        String scope = level != null && level == 3 ? "ALL" : level != null && level == 2 ? "DEPARTMENT" : "SELF";
        Map<String, CurrentUserService.FeatureScope> featureScopes = aggregateFeatureScopes(employeeId);
        return new CurrentUserService.CurrentUser(employeeId, name, department, roles, permissions, scope,
                false, loginType, loginAccountId, externalUserId, featureScopes);
    }

    /**
     * 聚合功能级数据范围：遍历该员工全部角色在 system_role_feature_grants 上的授权行，
     * 同一功能多个角色取范围最高级（ALL>DEPARTMENT>CUSTOM>SELF），自定义名单取并集。
     */
    private Map<String, CurrentUserService.FeatureScope> aggregateFeatureScopes(Long employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT g.feature_code, g.data_scope, g.custom_employee_ids, g.custom_department_names"
                        + " FROM employee_roles er"
                        + " JOIN system_role_feature_grants g ON g.role_id = er.role_id"
                        + " WHERE er.employee_id = ? AND g.data_scope IS NOT NULL",
                employeeId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, ScopeAccumulator> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String feature = String.valueOf(row.get("feature_code"));
            String scope = (String) row.get("data_scope");
            ScopeAccumulator a = acc.computeIfAbsent(feature, k -> new ScopeAccumulator());
            a.absorb(scope, parseIds((String) row.get("custom_employee_ids")),
                    parseNames((String) row.get("custom_department_names")));
        }
        Map<String, CurrentUserService.FeatureScope> result = new HashMap<>();
        acc.forEach((feature, a) -> result.put(feature, new CurrentUserService.FeatureScope(
                a.scope(), a.employeeIds(), a.departmentNames())));
        return result;
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> parseNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 范围聚合器：级别 ALL(4) > DEPARTMENT(3) > CUSTOM(2) > SELF(1)，自定义名单并集。 */
    private static final class ScopeAccumulator {
        private String scope = "SELF";
        private final List<Long> employeeIds = new ArrayList<>();
        private final List<String> departmentNames = new ArrayList<>();

        private static int level(String s) {
            return switch (s) {
                case "ALL" -> 4;
                case "DEPARTMENT" -> 3;
                case "CUSTOM" -> 2;
                default -> 1;
            };
        }

        void absorb(String s, List<Long> ids, List<String> depts) {
            if (level(s) > level(scope)) {
                scope = s;
            }
            if (ids != null) {
                for (Long id : ids) {
                    if (!employeeIds.contains(id)) {
                        employeeIds.add(id);
                    }
                }
            }
            if (depts != null) {
                for (String d : depts) {
                    if (d != null && !d.isBlank() && !departmentNames.contains(d)) {
                        departmentNames.add(d);
                    }
                }
            }
        }

        String scope() {
            return scope;
        }

        List<Long> employeeIds() {
            return employeeIds;
        }

        List<String> departmentNames() {
            return departmentNames;
        }
    }

    /** 未登录匿名用户（无任何权限，数据范围 SELF）。 */
    public CurrentUserService.CurrentUser anonymous() {
        return new CurrentUserService.CurrentUser(null, "未登录用户", null, Set.of(), Set.of(), "SELF",
                false, "NONE", null, null, Map.of());
    }

    /** 开发环境兼容管理员（仅当 app.security.compatibility-admin=true 时启用，生产必须关闭）。 */
    public CurrentUserService.CurrentUser compatibilityAdmin() {
        Set<String> permissions = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT permission_code FROM system_permissions WHERE enabled = TRUE", String.class));
        return new CurrentUserService.CurrentUser(null, "兼容管理员", null, Set.of("ADMIN"), permissions, "ALL",
                true, "COMPAT", null, null, Map.of());
    }
}
