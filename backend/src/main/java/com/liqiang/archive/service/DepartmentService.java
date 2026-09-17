package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.liqiang.archive.dto.EmployeeDtos.CompanyOption;

/** 统一部门主数据服务；名称快照只用于解析，业务关系统一保存 department_id。 */
@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final JdbcTemplate jdbc;

    @Transactional
    public Long resolveOrCreateByName(String name, String sourceSystem) {
        if (!StringUtils.hasText(name)) return null;
        String clean = name.trim();
        List<Long> ids = jdbc.query("SELECT id FROM departments WHERE name=? AND status='ACTIVE' LIMIT 2",
                (rs, n) -> rs.getLong(1), clean);
        if (!ids.isEmpty()) return ids.getFirst();
        jdbc.update("INSERT IGNORE INTO departments(name,source_system) VALUES(?,?)",
                clean, StringUtils.hasText(sourceSystem) ? sourceSystem : "INTERNAL");
        return jdbc.queryForObject("SELECT id FROM departments WHERE name=?", Long.class, clean);
    }

    public Long resolveCompanyByName(String name) {
        if (!StringUtils.hasText(name)) return null;
        List<Long> ids = jdbc.query("SELECT id FROM organization_companies WHERE name=? AND status='ACTIVE' LIMIT 1",
                (rs, n) -> rs.getLong(1), name.trim());
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public List<CompanyOption> companyOptions() {
        return jdbc.query("""
                SELECT c.id,c.name,c.parent_id,p.name parent_name
                  FROM organization_companies c
                  LEFT JOIN organization_companies p ON p.id=c.parent_id
                 WHERE c.status='ACTIVE'
                 ORDER BY c.parent_id IS NOT NULL,c.name
                """, (rs, n) -> new CompanyOption(rs.getLong("id"), rs.getString("name"),
                (Long) rs.getObject("parent_id"), rs.getString("parent_name")));
    }

    public List<Map<String,Object>> listCompanies() {
        return jdbc.query("""
                SELECT c.id,c.company_code,c.name,c.parent_id,p.name parent_name,c.status,
                       (SELECT COUNT(*) FROM departments d WHERE d.company_id=c.id AND d.status='ACTIVE') department_count,
                       (SELECT COUNT(*) FROM employees e WHERE e.company_id=c.id AND e.status='ACTIVE') active_employee_count
                  FROM organization_companies c
                  LEFT JOIN organization_companies p ON p.id=c.parent_id
                 ORDER BY c.parent_id IS NOT NULL,c.name
                """, this::row);
    }

    /** 部门解析结果：departmentId + 本次是否新建了真实外部映射 / 是否用真实 ID 替换了 NAME# 过渡映射。
     *  <p>供同步摘要统计「本次新增/升级部门映射」使用。
     */
    public record ResolveResult(Long departmentId, boolean externalMappingCreated, boolean nameMappingUpgraded) {
    }

    /** 按真实外部部门 ID 解析/创建统一部门，并维护外部映射。
     *  <p>优先按 (source_system, external_department_id) 命中已绑定部门；命中时顺带把钉钉侧部门改名同步到
     *  departments.name 与员工当前部门名称（历史费用快照不受影响）。未命中则按名称解析/创建部门，写入真实
     *  外部 ID 映射，并清理该部门名下遗留的 NAME#MD5 过渡映射——实现「钉钉临时名称映射」逐步升级为真实 deptId。
     *  @param externalDeptId 外部真实部门 ID（如钉钉 originatorDeptId），可空：空时回落名称解析
     */
    @Transactional
    public ResolveResult resolveOrCreateByExternalId(String externalDeptId, String name, String sourceSystem) {
        if (StringUtils.hasText(externalDeptId)) {
            List<Long> bound = jdbc.query(
                    "SELECT department_id FROM department_external_mappings WHERE source_system=? AND external_department_id=? LIMIT 1",
                    (rs, n) -> rs.getLong(1), sourceSystem, externalDeptId.trim());
            if (!bound.isEmpty()) {
                if (StringUtils.hasText(name)) {
                    String clean = name.trim();
                    jdbc.update("UPDATE departments SET name=?,updated_at=NOW() WHERE id=? AND name<>?",
                            clean, bound.getFirst(), clean);
                    jdbc.update("UPDATE employees SET department=?,updated_at=NOW() WHERE department_id=? AND department<>?",
                            clean, bound.getFirst(), clean);
                }
                return new ResolveResult(bound.getFirst(), false, false);
            }
        }
        Long departmentId = resolveOrCreateByName(name, sourceSystem);
        boolean created = false;
        boolean upgraded = false;
        if (departmentId != null && StringUtils.hasText(externalDeptId)) {
            jdbc.update("""
                    INSERT INTO department_external_mappings(source_system,external_department_id,external_name,department_id)
                    VALUES(?,?,?,?)
                    ON DUPLICATE KEY UPDATE external_name=VALUES(external_name),department_id=VALUES(department_id)
                    """, sourceSystem, externalDeptId.trim(),
                    StringUtils.hasText(name) ? name.trim() : null, departmentId);
            created = true;
            upgraded = jdbc.update(
                    "DELETE FROM department_external_mappings WHERE source_system=? AND department_id=? AND external_department_id LIKE 'NAME#%'",
                    sourceSystem, departmentId) > 0;
        }
        return new ResolveResult(departmentId, created, upgraded);
    }

    @Transactional
    public Long syncEmployeeDepartment(Long employeeId, String departmentName, String sourceSystem) {
        if (employeeId == null) return resolveOrCreateByName(departmentName, sourceSystem);
        Long departmentId = resolveOrCreateByName(departmentName, sourceSystem);
        if (departmentId == null) return null;
        jdbc.update("UPDATE employees SET department_id=?,department=COALESCE(NULLIF(?,''),department),updated_at=NOW() WHERE id=?",
                departmentId, departmentName, employeeId);
        jdbc.update("UPDATE employee_department_assignments SET assignment_status='INACTIVE',effective_to=COALESCE(effective_to,CURRENT_DATE) " +
                "WHERE employee_id=? AND is_primary=1 AND assignment_status='ACTIVE' AND department_id<>?", employeeId, departmentId);
        jdbc.update("""
                INSERT INTO employee_department_assignments
                  (employee_id,department_id,is_primary,effective_from,assignment_status,source_system)
                SELECT ?,?,1,CURRENT_DATE,'ACTIVE',?
                WHERE NOT EXISTS(SELECT 1 FROM employee_department_assignments
                                  WHERE employee_id=? AND department_id=? AND is_primary=1 AND assignment_status='ACTIVE')
                """, employeeId, departmentId, sourceSystem, employeeId, departmentId);
        return departmentId;
    }

    @Transactional
    public Long syncEmployeeOrganization(Long employeeId, Long departmentId, String departmentName,
                                         Long companyId, String sourceSystem) {
        if (employeeId == null) return departmentId;
        Long resolvedDepartmentId = departmentId;
        if (resolvedDepartmentId == null && StringUtils.hasText(departmentName)) {
            resolvedDepartmentId = resolveOrCreateByName(departmentName, sourceSystem);
        }
        String resolvedName = departmentName;
        if (resolvedDepartmentId != null) {
            List<String> names = jdbc.queryForList("SELECT name FROM departments WHERE id=?", String.class, resolvedDepartmentId);
            if (names.isEmpty()) throw new IllegalArgumentException("工作部门不存在：" + resolvedDepartmentId);
            resolvedName = names.getFirst();
        }
        if (companyId != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM organization_companies WHERE id=? AND status='ACTIVE'", Long.class, companyId) == 0) {
            throw new IllegalArgumentException("所属公司不存在或已停用：" + companyId);
        }
        jdbc.update("UPDATE employees SET department_id=?,department=?,company_id=?,updated_at=NOW() WHERE id=?",
                resolvedDepartmentId, resolvedName, companyId, employeeId);
        if (resolvedDepartmentId == null) {
            jdbc.update("UPDATE employee_department_assignments SET assignment_status='INACTIVE',effective_to=COALESCE(effective_to,CURRENT_DATE) " +
                    "WHERE employee_id=? AND is_primary=1 AND assignment_status='ACTIVE'", employeeId);
            return null;
        }
        jdbc.update("UPDATE employee_department_assignments SET assignment_status='INACTIVE',effective_to=COALESCE(effective_to,CURRENT_DATE) " +
                "WHERE employee_id=? AND is_primary=1 AND assignment_status='ACTIVE' AND department_id<>?", employeeId, resolvedDepartmentId);
        jdbc.update("""
                INSERT INTO employee_department_assignments
                  (employee_id,department_id,is_primary,effective_from,assignment_status,source_system)
                SELECT ?,?,1,CURRENT_DATE,'ACTIVE',?
                WHERE NOT EXISTS(SELECT 1 FROM employee_department_assignments
                                  WHERE employee_id=? AND department_id=? AND is_primary=1 AND assignment_status='ACTIVE')
                """, employeeId, resolvedDepartmentId, sourceSystem, employeeId, resolvedDepartmentId);
        return resolvedDepartmentId;
    }

    public List<Map<String,Object>> list() {
        return jdbc.query("""
                SELECT d.id,d.department_code,d.name,d.parent_id,p.name parent_name,d.company_id,c.name company_name,
                       d.ownership_status,d.unit_type,d.level_no,d.status,d.source_system,
                       (SELECT COUNT(*) FROM employees e WHERE e.department_id=d.id AND e.status='ACTIVE') active_employee_count,
                       (SELECT COUNT(*) FROM expense_claims c WHERE c.department_id=d.id) expense_claim_count,
                       (SELECT COALESCE(SUM(CASE WHEN c.approval_status='APPROVED' THEN COALESCE(c.approved_amount,c.total_amount,0) ELSE 0 END),0)
                          FROM expense_claims c WHERE c.department_id=d.id) approved_expense_amount
                  FROM departments d
                  LEFT JOIN departments p ON p.id=d.parent_id
                  LEFT JOIN organization_companies c ON c.id=d.company_id
                 WHERE d.unit_type<>'LEGACY_ORGANIZATION'
                 ORDER BY d.status,d.level_no,d.name
                """, this::row);
    }

    public Map<String,Object> quality() {
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("departmentCount", jdbc.queryForObject("SELECT COUNT(*) FROM departments WHERE status='ACTIVE'", Long.class));
        r.put("employeesWithoutDepartment", jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE status='ACTIVE' AND department_id IS NULL", Long.class));
        r.put("claimsWithoutDepartment", jdbc.queryForObject("SELECT COUNT(*) FROM expense_claims WHERE department_id IS NULL", Long.class));
        r.put("nameOnlyDingtalkMappings", jdbc.queryForObject("SELECT COUNT(*) FROM department_external_mappings WHERE source_system='DINGTALK' AND external_department_id LIKE 'NAME#%'", Long.class));
        r.put("departmentsWithoutCompany", jdbc.queryForObject("SELECT COUNT(*) FROM departments WHERE status='ACTIVE' AND unit_type<>'LEGACY_ORGANIZATION' AND company_id IS NULL", Long.class));
        r.put("organizationConflicts", jdbc.queryForObject("SELECT COUNT(*) FROM departments WHERE status='ACTIVE' AND ownership_status='CONFLICT'", Long.class));
        return r;
    }

    @Transactional
    public Map<String,Object> save(Long id, String code, String name, Long parentId, Long companyId,
                                  String ownershipStatus, String unitType, String status) {
        if (!StringUtils.hasText(name)) throw new IllegalArgumentException("部门名称不能为空");
        if (id == null) {
            jdbc.update("""
                    INSERT INTO departments(department_code,name,parent_id,company_id,ownership_status,unit_type,level_no,status,source_system)
                    VALUES(?,?,?,?,?,?,COALESCE((SELECT level_no+1 FROM departments p WHERE p.id=?),1),?,'INTERNAL')
                    """, blank(code), name.trim(), parentId, companyId, normalizeOwnership(companyId, ownershipStatus),
                    normalizeUnitType(unitType), parentId, normalizeStatus(status));
            id = jdbc.queryForObject("SELECT id FROM departments WHERE name=?", Long.class, name.trim());
        } else {
            if (parentId != null && parentId.equals(id)) throw new IllegalArgumentException("上级部门不能是自身");
            jdbc.update("""
                    UPDATE departments SET department_code=?,name=?,parent_id=?,company_id=?,ownership_status=?,unit_type=?,
                           level_no=COALESCE((SELECT p.level_no+1 FROM (SELECT id,level_no FROM departments) p WHERE p.id=?),1),status=?
                     WHERE id=?
                    """, blank(code), name.trim(), parentId, companyId, normalizeOwnership(companyId, ownershipStatus),
                    normalizeUnitType(unitType), parentId, normalizeStatus(status), id);
            jdbc.update("UPDATE employees SET department=? WHERE department_id=?", name.trim(), id);
        }
        return jdbc.query("SELECT * FROM departments WHERE id=?", this::row, id).getFirst();
    }

    @Transactional
    public Map<String,Object> saveCompany(Long id, String code, String name, Long parentId, String status) {
        if (!StringUtils.hasText(name)) throw new IllegalArgumentException("公司名称不能为空");
        if (id == null) {
            jdbc.update("INSERT INTO organization_companies(company_code,name,parent_id,status) VALUES(?,?,?,?)",
                    blank(code), name.trim(), parentId, normalizeStatus(status));
            id = jdbc.queryForObject("SELECT id FROM organization_companies WHERE name=?", Long.class, name.trim());
        } else {
            if (parentId != null && parentId.equals(id)) throw new IllegalArgumentException("上级公司不能是自身");
            jdbc.update("UPDATE organization_companies SET company_code=?,name=?,parent_id=?,status=? WHERE id=?",
                    blank(code), name.trim(), parentId, normalizeStatus(status), id);
        }
        Long savedId = id;
        return listCompanies().stream().filter(row -> savedId.equals(((Number) row.get("id")).longValue())).findFirst().orElseThrow();
    }

    private static String normalizeStatus(String status) { return "INACTIVE".equalsIgnoreCase(status) ? "INACTIVE" : "ACTIVE"; }
    private static String normalizeOwnership(Long companyId, String status) {
        if ("CONFLICT".equalsIgnoreCase(status)) return "CONFLICT";
        return companyId == null ? "UNMAPPED" : "CONFIRMED";
    }
    private static String normalizeUnitType(String value) {
        return "WORKSHOP".equalsIgnoreCase(value) ? "WORKSHOP" : "DEPARTMENT";
    }
    private static String blank(String v) { return StringUtils.hasText(v) ? v.trim() : null; }
    private Map<String,Object> row(ResultSet rs,int n)throws SQLException {
        ResultSetMetaData md=rs.getMetaData(); Map<String,Object> map=new LinkedHashMap<>();
        for(int i=1;i<=md.getColumnCount();i++) map.put(toCamel(md.getColumnLabel(i)),rs.getObject(i));
        return map;
    }
    private String toCamel(String s){StringBuilder b=new StringBuilder();boolean upper=false;for(char c:s.toLowerCase().toCharArray()){if(c=='_')upper=true;else{b.append(upper?Character.toUpperCase(c):c);upper=false;}}return b.toString();}
}
