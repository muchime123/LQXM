package com.liqiang.archive.service;

import com.liqiang.archive.dto.EmployeeDtos.*;
import com.liqiang.archive.entity.Employee;
import com.liqiang.archive.entity.EmployeeExternalMapping;
import com.liqiang.archive.entity.LoginAccount;
import com.liqiang.archive.repository.EmployeeExternalMappingRepository;
import com.liqiang.archive.repository.EmployeeRepository;
import com.liqiang.archive.repository.LoginAccountRepository;
import com.liqiang.archive.util.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final LoginAccountRepository loginAccountRepository;
    private final EmployeeExternalMappingRepository mappingRepository;
    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;
    private final DepartmentService departmentService;
    private final H3EmployeeIdentityService h3EmployeeIdentityService;
    private final DefaultLoginAccountService defaultLoginAccountService;

    // ==================== 枚举转换（中文 ↔ DB 英文） ====================

    private static String toDbStatus(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim()) {
            case "在职" -> "ACTIVE";
            case "离职" -> "RESIGNED";
            case "停薪留职" -> "ON_LEAVE";
            default -> value.trim();
        };
    }

    private static String toDbGender(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim()) {
            case "男" -> "MALE";
            case "女" -> "FEMALE";
            case "其他" -> "OTHER";
            default -> value.trim();
        };
    }

    private static String toDbSourceSystem(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim()) {
            case "氚云" -> "H3YUN";
            case "钉钉" -> "DINGTALK";
            case "系统账号" -> "SYSTEM";
            default -> value.trim();
        };
    }

    /** 身份证号归一化：空值原样透传（null 不覆盖 / 空串视为清除），否则去空格、小写 x 转大写并校验 18 位。 */
    private static String normalizeIdCardNumber(String value) {
        if (value == null || value.isBlank()) return value;
        String cleaned = value.trim().toUpperCase();
        if (!cleaned.matches("\\d{17}[0-9X]")) {
            throw new IllegalArgumentException("身份证号格式不正确：应为 18 位（17 位数字 + 1 位数字或 X）");
        }
        return cleaned;
    }

    /** DB 英文 → 前端中文展示（列表/详情/映射返回统一走这个）。 */
    public static String toLabel(String dbValue) {
        if (dbValue == null) return null;
        return switch (dbValue) {
            case "ACTIVE" -> "在职";
            case "RESIGNED" -> "离职";
            case "ON_LEAVE" -> "停薪留职";
            case "MALE" -> "男";
            case "FEMALE" -> "女";
            case "OTHER" -> "其他";
            case "H3YUN" -> "氚云";
            case "DINGTALK" -> "钉钉";
            case "SYSTEM" -> "系统账号";
            default -> dbValue;
        };
    }

    public static final Function<String, String> TRANSLATOR = EmployeeService::toLabel;

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Page<EmployeeSummary> search(String department, String status, Boolean isSalesperson,
                                        String accountStatus, String keyword, Pageable pageable) {
        Page<Employee> page = employeeRepository.search(
                StringUtils.blankToNull(department),
                toDbStatus(status),
                isSalesperson,
                StringUtils.blankToNull(accountStatus),
                StringUtils.blankToNull(keyword),
                pageable);
        List<Long> employeeIds = page.getContent().stream().map(Employee::getId).toList();
        Map<Long, LoginAccount> accounts = employeeIds.isEmpty()
                ? Map.of()
                : loginAccountRepository.findByEmployeeIdIn(employeeIds).stream()
                        .collect(Collectors.toMap(LoginAccount::getEmployeeId, Function.identity()));
        return page.map(e -> EmployeeSummary.from(e, accounts.get(e.getId()), EmployeeService::toLabel));
    }

    @Transactional(readOnly = true)
    public EmployeeDetail get(Long id) {
        Employee e = employeeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("员工不存在: " + id));
        List<MappingSummary> mappings = mappingRepository
                .findByEmployeeIdOrderBySourceSystemAscIsPrimaryDescIdAsc(id)
                .stream()
                .map(m -> MappingSummary.from(m, EmployeeService::toLabel))
                .toList();
        return EmployeeDetail.from(e, mappings, EmployeeService::toLabel);
    }

    @Transactional(readOnly = true)
    public FilterOptions filters() {
        List<String> departments = employeeRepository.findDepartments();
        List<String> statuses = List.of("在职", "离职", "停薪留职");
        List<String> sourceSystems = List.of("氚云", "钉钉", "系统账号");
        long total = employeeRepository.count();
        long active = employeeRepository.countByStatus("ACTIVE");
        long sales = employeeRepository.countByIsSalespersonTrueAndStatus("ACTIVE");
        return new FilterOptions(departments, departmentService.companyOptions(), statuses, sourceSystems, total, active, sales);
    }

    // ==================== 增删改 ====================

    @Transactional
    public EmployeeSummary create(EmployeePayload payload) {
        if (StringUtils.blankToNull(payload.name()) == null) {
            throw new IllegalArgumentException("员工姓名不能为空");
        }
        Employee e = new Employee();
        if (StringUtils.blankToNull(payload.employeeNo()) != null) {
            e.setEmployeeNo(payload.employeeNo().trim());
        } else {
            e.setEmployeeNo(nextEmployeeNo());
        }
        applyPayloadToEntity(e, payload, "后台");
        Employee saved = employeeRepository.save(e);
        saved.setDepartmentId(departmentService.syncEmployeeOrganization(saved.getId(), payload.departmentId(),
                saved.getDepartment(), payload.companyId(), "INTERNAL"));
        syncSalespersonRole(saved);
        defaultLoginAccountService.synchronize(saved);
        auditLogService.log("Employee", saved.getId(), saved.getName(),
                "EMP_CREATE", "创建员工档案：" + saved.getName());
        return summary(saved);
    }

    @Transactional
    public EmployeeSummary update(Long id, EmployeePayload payload) {
        Employee e = employeeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("员工不存在: " + id));
        applyPayloadToEntity(e, payload, "后台");
        Employee saved = employeeRepository.save(e);
        saved.setDepartmentId(departmentService.syncEmployeeOrganization(saved.getId(), payload.departmentId(),
                saved.getDepartment(), payload.companyId(), "INTERNAL"));
        syncSalespersonRole(saved);
        defaultLoginAccountService.synchronize(saved);
        auditLogService.log("Employee", id, e.getName(),
                "EMP_UPDATE", "更新员工档案：" + e.getName());
        return summary(saved);
    }

    private EmployeeSummary summary(Employee employee) {
        LoginAccount account = loginAccountRepository.findByEmployeeId(employee.getId()).orElse(null);
        return EmployeeSummary.from(employee, account, EmployeeService::toLabel);
    }

    private void syncSalespersonRole(Employee employee) {
        if (Boolean.TRUE.equals(employee.getIsSalesperson()) && "ACTIVE".equals(employee.getStatus())) {
            jdbc.update("INSERT IGNORE INTO employee_roles(employee_id,role_id) SELECT ?,id FROM system_roles WHERE role_code='SALESPERSON'", employee.getId());
        } else {
            jdbc.update("DELETE er FROM employee_roles er JOIN system_roles r ON r.id=er.role_id WHERE er.employee_id=? AND r.role_code='SALESPERSON'", employee.getId());
        }
    }

    /** 无业务引用时物理删除；存在业务引用时安全转为离职并停用账号。 */
    @Transactional
    public EmployeeDeleteResult delete(Long id) {
        Employee e = employeeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("员工不存在: " + id));
        List<String> referenceTables = businessReferenceTables(id);
        if (!referenceTables.isEmpty()) {
            e.setStatus("RESIGNED");
            e.setIsSalesperson(false);
            if (e.getLeaveDate() == null) e.setLeaveDate(LocalDate.now());
            e.setUpdatedBy("后台");
            Employee saved = employeeRepository.save(e);
            syncSalespersonRole(saved);
            defaultLoginAccountService.synchronize(saved);
            auditLogService.log("Employee", id, saved.getName(),
                    "EMP_RETIRE", "员工存在业务引用，已转为离职并停用登录账号：" + saved.getName());
            return new EmployeeDeleteResult("RETIRED", "员工已有业务记录，已转为离职并停用登录账号", referenceTables);
        }
        String name = e.getName();
        jdbc.update("DELETE FROM auth_sessions WHERE employee_id=?", id);
        jdbc.update("DELETE FROM login_accounts WHERE employee_id=?", id);
        jdbc.update("DELETE FROM employee_roles WHERE employee_id=?", id);
        mappingRepository.deleteAllByEmployeeId(id);
        employeeRepository.delete(e);
        auditLogService.log("Employee", id, name,
                "EMP_DELETE", "删除员工档案：" + name);
        return new EmployeeDeleteResult("DELETED", "员工档案已彻底删除", List.of());
    }

    private List<String> businessReferenceTables(Long employeeId) {
        Set<String> administrativeTables = Set.of(
                "employees", "auth_sessions", "login_accounts", "employee_roles",
                "employee_external_mappings", "employee_department_assignments");
        List<EmployeeReference> columns = jdbc.query("""
                SELECT TABLE_NAME, COLUMN_NAME
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                """, (rs, rowNum) -> new EmployeeReference(rs.getString(1), rs.getString(2)));
        Set<String> referenced = new LinkedHashSet<>();
        for (EmployeeReference column : columns) {
            if (administrativeTables.contains(column.tableName())) continue;
            if (!("employee_id".equals(column.columnName()) || column.columnName().endsWith("_employee_id"))) continue;
            if (!column.tableName().matches("[A-Za-z0-9_]+") || !column.columnName().matches("[A-Za-z0-9_]+")) continue;
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `" + column.tableName() + "` WHERE `" + column.columnName() + "` = ?",
                    Long.class, employeeId);
            if (count != null && count > 0) referenced.add(column.tableName());
        }
        return List.copyOf(referenced);
    }

    private record EmployeeReference(String tableName, String columnName) {}

    private String nextEmployeeNo() {
        Long maxSeq = employeeRepository.findMaxEmployeeLQSequence();
        long seq = maxSeq == null ? 0 : maxSeq;
        return "LQ" + String.format("%03d", seq + 1);
    }

    private void applyPayloadToEntity(Employee e, EmployeePayload payload, String operator) {
        EmployeePayload converted = new EmployeePayload(
                payload.employeeNo(),
                payload.name(),
                toDbGender(payload.gender()),
                payload.department(),
                payload.departmentId(),
                payload.companyId(),
                payload.position(),
                payload.phone(),
                normalizeIdCardNumber(payload.idCardNumber()),
                payload.email(),
                payload.entryDate(),
                payload.leaveDate(),
                toDbStatus(payload.status()),
                payload.isSalesperson(),
                payload.remark()
        );
        converted.applyTo(e);
        // 组织归属允许显式清空；前端员工表单始终提交这两个字段。
        e.setDepartment(StringUtils.blankToNull(payload.department()));
        e.setDepartmentId(payload.departmentId());
        e.setCompanyId(payload.companyId());
        if (e.getCreatedBy() == null) e.setCreatedBy(operator);
        e.setUpdatedBy(operator);
    }

    // ==================== 外部身份映射 ====================

    @Transactional(readOnly = true)
    public List<MappingSummary> listMappings(Long employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new EntityNotFoundException("员工不存在: " + employeeId);
        }
        return mappingRepository.findByEmployeeIdOrderBySourceSystemAscIsPrimaryDescIdAsc(employeeId)
                .stream()
                .map(m -> MappingSummary.from(m, EmployeeService::toLabel))
                .toList();
    }

    @Transactional
    public MappingSummary addMapping(Long employeeId, MappingPayload payload) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new EntityNotFoundException("员工不存在: " + employeeId);
        }
        String sourceSystem = toDbSourceSystem(payload.sourceSystem());
        if (sourceSystem == null || payload.externalUserId() == null || payload.externalUserId().isBlank()) {
            throw new IllegalArgumentException("来源系统和外部用户ID不能为空");
        }
        mappingRepository.findBySourceSystemAndExternalUserId(sourceSystem, payload.externalUserId().trim())
                .ifPresent(m -> {
                    throw new IllegalArgumentException("外部账号已被员工 id=" + m.getEmployeeId() + " 绑定");
                });
        EmployeeExternalMapping m = new EmployeeExternalMapping();
        m.setEmployeeId(employeeId);
        MappingPayload converted = new MappingPayload(
                sourceSystem,
                payload.externalUserId().trim(),
                payload.externalName(),
                payload.externalDepartment(),
                payload.isPrimary() != null ? payload.isPrimary() : Boolean.FALSE,
                payload.remark()
        );
        converted.applyTo(m);
        m.setMappedAt(LocalDateTime.now());
        m.setMappedBy("后台");
        EmployeeExternalMapping saved = mappingRepository.saveAndFlush(m);
        if ("H3YUN".equals(saved.getSourceSystem())) h3EmployeeIdentityService.backfillUnassigned("员工名册新增映射");
        auditLogService.log("Employee", employeeId, null,
                "EMP_MAPPING_ADD", "新增身份映射：" + toLabel(sourceSystem) + " / " + m.getExternalUserId());
        return MappingSummary.from(saved, EmployeeService::toLabel);
    }

    @Transactional
    public MappingSummary updateMapping(Long employeeId, Long mappingId, MappingPayload payload) {
        EmployeeExternalMapping m = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new EntityNotFoundException("映射不存在: " + mappingId));
        if (!m.getEmployeeId().equals(employeeId)) {
            throw new IllegalArgumentException("映射不属于该员工");
        }
        String newSource = payload.sourceSystem() != null ? toDbSourceSystem(payload.sourceSystem()) : m.getSourceSystem();
        String newExternal = payload.externalUserId() != null ? payload.externalUserId().trim() : m.getExternalUserId();
        // 唯一性冲突检查：新(source,external) 组合不能被他人占用
        final String sourceFinal = newSource;
        final String externalFinal = newExternal;
        mappingRepository.findBySourceSystemAndExternalUserId(sourceFinal, externalFinal)
                .ifPresent(existing -> {
                    if (!existing.getId().equals(mappingId)) {
                        throw new IllegalArgumentException("外部账号已被员工 id=" + existing.getEmployeeId() + " 绑定");
                    }
                });
        MappingPayload converted = new MappingPayload(
                newSource, newExternal,
                payload.externalName(),
                payload.externalDepartment(),
                payload.isPrimary() == null ? m.getIsPrimary() : payload.isPrimary(),
                payload.remark()
        );
        converted.applyTo(m);
        m.setMappedBy("后台");
        EmployeeExternalMapping saved = mappingRepository.saveAndFlush(m);
        if ("H3YUN".equals(saved.getSourceSystem())) h3EmployeeIdentityService.backfillUnassigned("员工名册更新映射");
        auditLogService.log("Employee", employeeId, null,
                "EMP_MAPPING_UPDATE", "更新身份映射 id=" + mappingId);
        return MappingSummary.from(saved, EmployeeService::toLabel);
    }

    @Transactional
    public void removeMapping(Long employeeId, Long mappingId) {
        EmployeeExternalMapping m = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new EntityNotFoundException("映射不存在: " + mappingId));
        if (!m.getEmployeeId().equals(employeeId)) {
            throw new IllegalArgumentException("映射不属于该员工");
        }
        mappingRepository.delete(m);
        auditLogService.log("Employee", employeeId, null,
                "EMP_MAPPING_DELETE", "删除身份映射 id=" + mappingId);
    }
}
