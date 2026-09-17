package com.liqiang.archive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 系统管理 · 角色与权限。
 *
 * <p>V249 起角色授权改为「功能(Feature) × 数据范围 × 操作动作」模型：
 * 角色对每个功能配置数据范围（全部/本部门/本人/自定义员工部门）与勾选动作，
 * 保存时由 {@link #replaceRoleGrants} 把授权展开回写 system_role_permissions
 * （执行层 @RequirePermission 拦截零改动）。授权以 system_role_feature_grants 为准。
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    private static final String ROLE_FIELDS = "id, role_code, role_name, data_scope, built_in";

    private static final Set<String> SCOPES = Set.of("ALL", "DEPARTMENT", "CUSTOM", "SELF");

    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    // ===================== 角色 =====================

    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> roles = jdbc.queryForList(
                "SELECT " + ROLE_FIELDS + " FROM system_roles ORDER BY FIELD(role_code,'ADMIN','BOSS','SALES_MANAGER','SALESPERSON','FINANCE'),id");
        for (Map<String, Object> role : roles) {
            fillRoleDetail(role);
        }
        return roles;
    }

    public Map<String, Object> roleDetail(long roleId) {
        Map<String, Object> role = jdbc.queryForMap("SELECT " + ROLE_FIELDS + " FROM system_roles WHERE id=?", roleId);
        fillRoleDetail(role);
        return role;
    }

    private void fillRoleDetail(Map<String, Object> role) {
        Object roleId = role.get("id");
        role.put("permissionCodes", jdbc.queryForList(
                "SELECT p.permission_code FROM system_role_permissions rp JOIN system_permissions p ON p.id=rp.permission_id WHERE rp.role_id=? ORDER BY p.permission_code",
                String.class, roleId));
        role.put("featureGrants", featureGrants(((Number) roleId).longValue()));
        role.put("memberCount", jdbc.queryForObject("SELECT COUNT(*) FROM employee_roles WHERE role_id=?", Integer.class, roleId));
    }

    /** 角色-功能授权行（含自定义名单解析）。 */
    public List<Map<String, Object>> featureGrants(long roleId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT feature_code, data_scope, custom_employee_ids, custom_department_names, selected_actions"
                        + " FROM system_role_feature_grants WHERE role_id=? ORDER BY feature_code", roleId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("featureCode", row.get("feature_code"));
            g.put("dataScope", row.get("data_scope"));
            g.put("employeeIds", parseIds((String) row.get("custom_employee_ids")));
            g.put("departmentNames", parseNames((String) row.get("custom_department_names")));
            List<String> actions = new ArrayList<>();
            String selected = (String) row.get("selected_actions");
            if (selected != null && !selected.isBlank()) {
                for (String a : selected.split(",")) {
                    if (!a.isBlank()) {
                        actions.add(a.trim());
                    }
                }
            }
            g.put("actions", actions);
            result.add(g);
        }
        return result;
    }

    /** 新建角色：code 唯一；授权按功能展开。 */
    @Transactional
    public Map<String, Object> createRole(String roleCode, String roleName, List<FeatureGrantPayload> grants) {
        String code = requireCode(roleCode);
        String name = requireName(roleName);
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM system_roles WHERE role_code=?", Integer.class, code);
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("角色编码已存在：" + code);
        }
        jdbc.update("INSERT INTO system_roles(role_code, role_name, data_scope, built_in) VALUES (?,?,?,0)",
                code, name, defaultScope(grants));
        long roleId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        replaceRoleGrants(roleId, grants);
        auditLogService.log("system_role", roleId, name, "create",
                "创建角色：" + code + "，授权功能：" + describeGrants(grants));
        return roleDetail(roleId);
    }

    /** 更新角色：内置角色禁止改编码；授权整体替换。 */
    @Transactional
    public Map<String, Object> updateRole(long roleId, String roleName, List<FeatureGrantPayload> grants) {
        Map<String, Object> role = jdbc.queryForMap("SELECT " + ROLE_FIELDS + " FROM system_roles WHERE id=?", roleId);
        String name = requireName(roleName);
        jdbc.update("UPDATE system_roles SET role_name=?, data_scope=? WHERE id=?", name, defaultScope(grants), roleId);
        replaceRoleGrants(roleId, grants);
        auditLogService.log("system_role", roleId, name, "update",
                "更新角色：" + name + "，授权功能：" + describeGrants(grants));
        return roleDetail(roleId);
    }

    /** 删除自定义角色（内置角色不可删）；级联清理关联。 */
    @Transactional
    public void deleteRole(long roleId) {
        Map<String, Object> role = jdbc.queryForMap("SELECT " + ROLE_FIELDS + " FROM system_roles WHERE id=?", roleId);
        if (Boolean.TRUE.equals(role.get("built_in")) || Integer.valueOf(1).equals(role.get("built_in"))) {
            throw new IllegalArgumentException("内置角色不可删除");
        }
        jdbc.update("DELETE FROM employee_roles WHERE role_id=?", roleId);
        jdbc.update("DELETE FROM system_role_permissions WHERE role_id=?", roleId);
        jdbc.update("DELETE FROM system_role_feature_grants WHERE role_id=?", roleId);
        jdbc.update("DELETE FROM system_roles WHERE id=?", roleId);
        auditLogService.log("system_role", roleId, (String) role.get("role_name"), "delete", "删除角色：" + role.get("role_code"));
    }

    // ===================== 功能目录与权限码 =====================

    /** 全部权限（按权限码排序，含启停用状态）。 */
    public List<Map<String, Object>> permissions() {
        return jdbc.queryForList("SELECT id, permission_code, permission_name, enabled FROM system_permissions ORDER BY permission_code");
    }

    /** 启停用权限码：停用后该权限即时从所有用户权限中移除（分配记录保留，可再启用恢复）。 */
    @Transactional
    public void setPermissionEnabled(long permissionId, boolean enabled) {
        Map<String, Object> perm = jdbc.queryForMap(
                "SELECT permission_code, permission_name, enabled FROM system_permissions WHERE id=?", permissionId);
        jdbc.update("UPDATE system_permissions SET enabled=? WHERE id=?", enabled, permissionId);
        auditLogService.log("system_permission", permissionId, (String) perm.get("permission_name"),
                enabled ? "enable" : "disable",
                "权限码 " + perm.get("permission_code") + (enabled ? " 已启用" : " 已停用"));
    }

    /** 功能目录（含每个功能下的动作清单与范围变体），供权限配置 UI 渲染。 */
    public List<Map<String, Object>> features() {
        List<Map<String, Object>> feats = jdbc.queryForList(
                "SELECT id, feature_code, feature_name, category, sort_order, enabled"
                        + " FROM system_features ORDER BY sort_order, id");
        if (feats.isEmpty()) {
            return feats;
        }
        List<String> codes = feats.stream().map(f -> String.valueOf(f.get("feature_code"))).toList();
        List<Map<String, Object>> acts = jdbc.queryForList(
                "SELECT fa.feature_code, fa.action_kind, fa.permission_code, fa.variant, p.permission_name"
                        + " FROM system_feature_actions fa"
                        + " JOIN system_permissions p ON p.permission_code = fa.permission_code"
                        + " WHERE fa.feature_code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")"
                        + " ORDER BY fa.feature_code, fa.sort_order",
                codes.toArray());
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> a : acts) {
            grouped.computeIfAbsent(String.valueOf(a.get("feature_code")), k -> new ArrayList<>()).add(a);
        }
        for (Map<String, Object> f : feats) {
            f.put("actions", grouped.getOrDefault(String.valueOf(f.get("feature_code")), List.of()));
        }
        return feats;
    }

    // ===================== 授权展开（grants → role_permissions） =====================

    /** 整体替换角色-功能授权，并重算 role_permissions 物化权限。 */
    private void replaceRoleGrants(long roleId, List<FeatureGrantPayload> grants) {
        List<FeatureGrantPayload> normalized = normalizeGrants(grants);
        jdbc.update("DELETE FROM system_role_feature_grants WHERE role_id=?", roleId);
        for (FeatureGrantPayload g : normalized) {
            jdbc.update("INSERT INTO system_role_feature_grants"
                            + "(role_id, feature_code, data_scope, custom_employee_ids, custom_department_names, selected_actions)"
                            + " VALUES (?,?,?,?,?,?)",
                    roleId, g.featureCode(), g.dataScope(),
                    toJson(g.employeeIds()), toJson(g.departmentNames()), String.join(",", g.actions()));
        }
        jdbc.update("DELETE FROM system_role_permissions WHERE role_id=?", roleId);
        Set<String> inserted = new LinkedHashSet<>();
        for (FeatureGrantPayload g : normalized) {
            expandGrant(roleId, g, inserted);
        }
    }

    /** 校验并归一化授权；按功能数据范围选择 VIEW 变体码并展开为 role_permissions。 */
    private void expandGrant(long roleId, FeatureGrantPayload g, Set<String> inserted) {
        Map<String, Object> feature = jdbc.queryForMap(
                "SELECT feature_code, enabled FROM system_features WHERE feature_code=?", g.featureCode());
        if (feature == null || !Boolean.TRUE.equals(feature.get("enabled")) && !Integer.valueOf(1).equals(feature.get("enabled"))) {
            throw new IllegalArgumentException("功能不存在或已停用：" + g.featureCode());
        }
        List<Map<String, Object>> actions = jdbc.queryForList(
                "SELECT permission_code, action_kind, variant FROM system_feature_actions WHERE feature_code=? ORDER BY sort_order",
                g.featureCode());
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> a : actions) {
            byCode.put(String.valueOf(a.get("permission_code")), a);
        }
        List<Map<String, Object>> viewActions = actions.stream()
                .filter(a -> "VIEW".equals(a.get("action_kind"))).toList();
        boolean hasScopeVariants = viewActions.stream().anyMatch(a -> a.get("variant") != null);

        String scope = g.dataScope();
        final String effectiveScope;
        if (scope == null && hasScopeVariants) {
            effectiveScope = "SELF";
        } else {
            effectiveScope = scope;
        }
        if (effectiveScope != null && !SCOPES.contains(effectiveScope)) {
            throw new IllegalArgumentException("数据范围不合法：" + effectiveScope);
        }
        boolean viewChecked = g.actions().contains("VIEW");
        if (viewChecked) {
            if (hasScopeVariants) {
                String want = switch (effectiveScope) {
                    case "ALL" -> "ALL";
                    case "DEPARTMENT" -> "DEPARTMENT";
                    default -> "SELF"; // SELF 与 CUSTOM（自定义名单经行级过滤）均授 read.self
                };
                String code = viewActions.stream()
                        .filter(a -> want.equals(a.get("variant")))
                        .map(a -> String.valueOf(a.get("permission_code")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "功能 " + g.featureCode() + " 不支持数据范围 " + scope));
                insertPermission(roleId, code, inserted);
            } else {
                // 无范围变体的查看（如 material_inventory.read）：直接授予该查看码
                if (viewActions.size() == 1 && viewActions.getFirst().get("variant") == null) {
                    insertPermission(roleId, String.valueOf(viewActions.getFirst().get("permission_code")), inserted);
                }
            }
        }
        for (String action : g.actions()) {
            if ("VIEW".equals(action)) {
                continue;
            }
            Map<String, Object> row = byCode.get(action);
            if (row == null) {
                throw new IllegalArgumentException("功能 " + g.featureCode() + " 不含动作：" + action);
            }
            insertPermission(roleId, action, inserted);
        }
    }

    private void insertPermission(long roleId, String permissionCode, Set<String> inserted) {
        if (!inserted.add(permissionCode)) {
            return;
        }
        jdbc.update("INSERT INTO system_role_permissions(role_id, permission_id)"
                + " SELECT ?, id FROM system_permissions WHERE permission_code=?", roleId, permissionCode);
    }

    /** 空 actions 视作忽略该功能授权；剔除重复功能。 */
    private List<FeatureGrantPayload> normalizeGrants(List<FeatureGrantPayload> grants) {
        List<FeatureGrantPayload> result = new ArrayList<>();
        if (grants == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (FeatureGrantPayload g : grants) {
            if (g == null || g.featureCode() == null || g.featureCode().isBlank()) {
                continue;
            }
            String code = g.featureCode().trim();
            List<String> actions = g.actions() == null ? List.of()
                    : g.actions().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
            if (actions.isEmpty()) {
                continue;
            }
            if (!seen.add(code)) {
                continue;
            }
            List<Long> ids = g.employeeIds() == null ? List.of()
                    : g.employeeIds().stream().filter(Objects::nonNull).distinct().toList();
            List<String> depts = g.departmentNames() == null ? List.of()
                    : g.departmentNames().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
            String scope = g.dataScope() == null ? null : g.dataScope().trim().toUpperCase();
            result.add(new FeatureGrantPayload(code, scope, ids, depts, actions));
        }
        return result;
    }

    /** 角色默认数据范围（system_roles.data_scope 兼容列）：取所有授权中范围最高级。 */
    private String defaultScope(List<FeatureGrantPayload> grants) {
        String best = "SELF";
        for (FeatureGrantPayload g : normalizeGrants(grants)) {
            String s = g.dataScope();
            if (s == null) {
                continue;
            }
            if (level(s) > level(best)) {
                best = s;
            }
        }
        return best;
    }

    private static int level(String s) {
        return switch (s) {
            case "ALL" -> 4;
            case "DEPARTMENT" -> 3;
            case "CUSTOM" -> 2;
            default -> 1;
        };
    }

    // ===================== 角色成员管理 =====================

    public List<Map<String, Object>> roleMembers(long roleId) {
        return jdbc.queryForList(
                "SELECT e.id, e.employee_no, e.name, e.department, e.status FROM employee_roles er JOIN employees e ON e.id=er.employee_id WHERE er.role_id=? ORDER BY e.status='ACTIVE' DESC, e.name",
                roleId);
    }

    /** 批量把员工加入角色（已拥有则跳过）。 */
    @Transactional
    public Map<String, Object> addRoleMembers(long roleId, List<Long> employeeIds) {
        Map<String, Object> role = jdbc.queryForMap("SELECT " + ROLE_FIELDS + " FROM system_roles WHERE id=?", roleId);
        StringBuilder names = new StringBuilder();
        int added = 0;
        if (employeeIds != null) {
            for (Long empId : employeeIds.stream().filter(Objects::nonNull).distinct().toList()) {
                Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM employee_roles WHERE employee_id=? AND role_id=?", Integer.class, empId, roleId);
                if (exists == null || exists == 0) {
                    jdbc.update("INSERT INTO employee_roles(employee_id, role_id) VALUES (?,?)", empId, roleId);
                    added++;
                    names.append(jdbc.queryForObject("SELECT name FROM employees WHERE id=?", String.class, empId)).append("、");
                }
            }
        }
        if (added > 0) {
            auditLogService.log("system_role", roleId, (String) role.get("role_name"), "add-members", "新增成员：" + names.toString().replaceAll("、$", ""));
        }
        return roleDetail(roleId);
    }

    /** 从角色移除成员（业务员强制保留 SALESPERSON）。 */
    @Transactional
    public Map<String, Object> removeRoleMember(long roleId, long employeeId) {
        Map<String, Object> role = jdbc.queryForMap("SELECT " + ROLE_FIELDS + " FROM system_roles WHERE id=?", roleId);
        if ("SALESPERSON".equals(role.get("role_code"))) {
            Map<String, Object> emp = jdbc.queryForMap("SELECT is_salesperson FROM employees WHERE id=?", employeeId);
            if (Boolean.TRUE.equals(emp.get("is_salesperson")) || Integer.valueOf(1).equals(emp.get("is_salesperson"))) {
                throw new IllegalArgumentException("该员工是业务员，销售角色不可移除");
            }
        }
        jdbc.update("DELETE FROM employee_roles WHERE employee_id=? AND role_id=?", employeeId, roleId);
        String name = jdbc.queryForObject("SELECT name FROM employees WHERE id=?", String.class, employeeId);
        auditLogService.log("system_role", roleId, (String) role.get("role_name"), "remove-member", "移除成员：" + name);
        return roleDetail(roleId);
    }

    // ===================== 员工角色分配（原有） =====================

    public Map<String, Object> employeeRoles(long employeeId) {
        Map<String, Object> employee = jdbc.queryForMap("SELECT id,employee_no,name,department,is_salesperson,status FROM employees WHERE id=?", employeeId);
        List<String> codes = jdbc.queryForList("SELECT r.role_code FROM employee_roles er JOIN system_roles r ON r.id=er.role_id WHERE er.employee_id=? ORDER BY r.id", String.class, employeeId);
        return Map.of("employee", employee, "roleCodes", codes);
    }

    @Transactional
    public Map<String, Object> setEmployeeRoles(long employeeId, List<String> requested) {
        Map<String, Object> employee = jdbc.queryForMap("SELECT id,is_salesperson FROM employees WHERE id=?", employeeId);
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (requested != null) requested.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(codes::add);
        List<String> valid = codes.isEmpty() ? List.of() : jdbc.queryForList("SELECT role_code FROM system_roles WHERE role_code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")", String.class, codes.toArray());
        if (valid.size() != codes.size()) {
            throw new IllegalArgumentException("包含不存在的系统角色");
        }
        if (Boolean.TRUE.equals(employee.get("is_salesperson")) || Integer.valueOf(1).equals(employee.get("is_salesperson"))) {
            codes.add("SALESPERSON");
        }
        jdbc.update("DELETE FROM employee_roles WHERE employee_id=?", employeeId);
        for (String code : codes) {
            jdbc.update("INSERT INTO employee_roles(employee_id,role_id) SELECT ?,id FROM system_roles WHERE role_code=?", employeeId, code);
        }
        auditLogService.log("employee", employeeId, jdbc.queryForObject("SELECT name FROM employees WHERE id=?", String.class, employeeId),
                "assign-roles", "分配角色：" + (codes.isEmpty() ? "无" : String.join(",", codes)));
        return employeeRoles(employeeId);
    }

    // ===================== 内部工具 =====================

    public record FeatureGrantPayload(String featureCode, String dataScope,
                                       List<Long> employeeIds, List<String> departmentNames,
                                       List<String> actions) {
    }

    private String toJson(List<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
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
        } catch (Exception e) {
            return List.of();
        }
    }

    private String describeGrants(List<FeatureGrantPayload> grants) {
        List<String> parts = new ArrayList<>();
        for (FeatureGrantPayload g : normalizeGrants(grants)) {
            String scope = g.dataScope() == null ? "" : "[" + g.dataScope() + "]";
            parts.add(g.featureCode() + scope + "(" + String.join(",", g.actions()) + ")");
        }
        return parts.isEmpty() ? "无" : String.join("；", parts);
    }

    private String requireCode(String roleCode) {
        String code = roleCode == null ? "" : roleCode.trim().toUpperCase();
        if (code.isEmpty() || !code.matches("[A-Z][A-Z0-9_]{1,49}")) {
            throw new IllegalArgumentException("角色编码需为 2-50 位大写字母/数字/下划线");
        }
        return code;
    }

    private String requireName(String roleName) {
        String name = roleName == null ? "" : roleName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new IllegalArgumentException("角色名称不能为空且不超过 100 字");
        }
        return name;
    }
}
