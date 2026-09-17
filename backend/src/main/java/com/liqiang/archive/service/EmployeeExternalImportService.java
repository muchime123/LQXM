package com.liqiang.archive.service;

import com.liqiang.archive.dto.EmployeeExternalImportDtos.ApplyExternalImportResult;
import com.liqiang.archive.dto.EmployeeExternalImportDtos.ApplyIdentityItem;
import com.liqiang.archive.dto.EmployeeExternalImportDtos.ExternalIdentityItem;
import com.liqiang.archive.dto.EmployeeExternalImportDtos.ExternalImportPreview;
import com.liqiang.archive.entity.CompanyTeamMember;
import com.liqiang.archive.entity.Employee;
import com.liqiang.archive.entity.EmployeeExternalMapping;
import com.liqiang.archive.repository.CompanyTeamMemberRepository;
import com.liqiang.archive.repository.EmployeeExternalMappingRepository;
import com.liqiang.archive.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 员工名册 · 外部身份导入映射（氚云/钉钉通讯录导出文件）。
 * <p>
 * 背景（P5.1 统一员工体系 + V176 事实表员工归属）：
 * <ul>
 *     <li>钉钉链路：报销单按 dingtalk_user_id → employee_external_mappings(DINGTALK) 精确反查，无映射时按姓名兜底；</li>
 *     <li>氚云链路：事实表 salesperson_id → company_team_members.employee_id 桥接，无桥接时按姓名兜底。</li>
 * </ul>
 * 两链路目前依赖「本地员工姓名唯一」才能兜底一致。本服务将钉钉/氚云通讯录导出的
 * <b>真实外部用户ID</b>（钉钉 UserID / 氚云 ObjectId）批量写入映射，使两链路全部升级为
 * 精确映射，从根上消除同名/重名导致的两链路归属不一致风险。
 * <p>
 * 数据流：上传两个导出文件 → {@link #parse} 解析+自动建议匹配（不落库）
 * → 前端人工确认 → {@link #apply} 事务落库（映射 + 团队桥 + 事实表回填 + 员工档案回填/新建）。
 *
 * @since V186
 */
@Service
@RequiredArgsConstructor
public class EmployeeExternalImportService {

    /** 氚云组织架构导出来源标识。 */
    public static final String SOURCE_H3YUN = "H3YUN";
    /** 钉钉在职员工导出来源标识。 */
    public static final String SOURCE_DINGTALK = "DINGTALK";
    /** 公司内部员工名单，只补充实际工作部门，不代表外部登录身份。 */
    public static final String SOURCE_EMPLOYEE_ROSTER = "EMPLOYEE_ROSTER";

    private static final int MAX_HEADER_SCAN_ROWS = 10;
    private static final int MAX_ROWS_PER_FILE = 5000;

    private final EmployeeRepository employeeRepository;
    private final EmployeeExternalMappingRepository mappingRepository;
    private final CompanyTeamMemberRepository teamMemberRepository;
    private final DepartmentService departmentService;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;
    private final H3EmployeeIdentityService h3EmployeeIdentityService;
    private final DefaultLoginAccountService defaultLoginAccountService;

    // ==================== 解析 + 匹配预览 ====================

    /**
     * 解析氚云/钉钉通讯录导出文件，对每条身份给出本地员工匹配建议。
     * 不写库，仅供前端预览确认。
     *
     * @param hyFile 氚云组织结构导出（.xlsx，含「成员ID」列）
     * @param ddFile 钉钉在职员工导出（.xls/.xlsx，含「员工UserID」列）
     */
    @Transactional(readOnly = true)
    public ExternalImportPreview parse(MultipartFile hyFile, MultipartFile ddFile, MultipartFile rosterFile) {
        List<ExternalIdentityItem> items = new ArrayList<>();
        if (hyFile != null && !hyFile.isEmpty()) {
            items.addAll(parseWorkbook(hyFile, SOURCE_H3YUN));
        }
        if (ddFile != null && !ddFile.isEmpty()) {
            items.addAll(parseWorkbook(ddFile, SOURCE_DINGTALK));
        }
        if (rosterFile != null && !rosterFile.isEmpty()) {
            items.addAll(parseWorkbook(rosterFile, SOURCE_EMPLOYEE_ROSTER));
        }
        int hyCount = (int) items.stream().filter(i -> SOURCE_H3YUN.equals(i.source())).count();
        int ddCount = (int) items.stream().filter(i -> SOURCE_DINGTALK.equals(i.source())).count();
        int rosterCount = (int) items.stream().filter(i -> SOURCE_EMPLOYEE_ROSTER.equals(i.source())).count();
        return new ExternalImportPreview(hyCount, ddCount, rosterCount, items);
    }

    private List<ExternalIdentityItem> parseWorkbook(MultipartFile file, String expectedSource) {
        List<ExternalIdentityItem> result = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                throw new IllegalArgumentException("文件没有可读的 Sheet: " + file.getOriginalFilename());
            }
            // 1. 扫描表头行，识别来源与列位置
            Map<String, Integer> header = new HashMap<>();
            int headerRowIdx = -1;
            DataFormatter fmt = new DataFormatter();
            for (int r = 0; r < Math.min(sheet.getLastRowNum() + 1, MAX_HEADER_SCAN_ROWS); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Integer> candidate = new HashMap<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    String text = cell == null ? "" : fmt.formatCellValue(cell).trim().replaceFirst("^\\*", "");
                    if (!text.isEmpty()) candidate.put(text, c);
                }
                if (candidate.containsKey("姓名") && (candidate.containsKey("成员ID") || candidate.containsKey("员工UserID")
                        || (candidate.containsKey("手机号码") && candidate.containsKey("部门")))) {
                    header = candidate;
                    headerRowIdx = r;
                    break;
                }
            }
            if (headerRowIdx < 0) {
                throw new IllegalArgumentException("未识别到员工表头（需为氚云组织、钉钉员工或内部员工名单）: " + file.getOriginalFilename());
            }
            boolean isHy = header.containsKey("成员ID");
            boolean isDingtalk = header.containsKey("员工UserID");
            String source = isHy ? SOURCE_H3YUN : isDingtalk ? SOURCE_DINGTALK : SOURCE_EMPLOYEE_ROSTER;
            if (!expectedSource.equals(source)) {
                throw new IllegalArgumentException("文件内容与所选来源不匹配（" + expectedSource + "）: " + file.getOriginalFilename());
            }
            Integer nameCol = header.get("姓名");
            Integer idCol = isHy ? header.get("成员ID") : isDingtalk ? header.get("员工UserID") : null;
            Integer phoneCol = header.get(isDingtalk || isHy ? "手机号" : "手机号码");
            // 钉钉新版导出顶级组织表头为"1级部门"，旧版为"一级部门"；顶级组织节点决定公司/车间归属。
            Integer deptCol = isHy ? header.get("所属部门")
                    : isDingtalk ? header.get(topOrgColumnKey(header)) : header.get("部门");
            Integer posCol = header.get("职位");
            Integer roleCol = isHy ? header.get("角色") : null;
            Integer statusCol = header.get(isHy ? "授权状态" : "员工状态");

            // 2. 逐行读取数据
            int rows = Math.min(sheet.getLastRowNum() + 1, headerRowIdx + MAX_ROWS_PER_FILE + 1);
            for (int r = headerRowIdx + 1; r < rows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String name = cellText(row, nameCol, fmt);
                String phone = cellText(row, phoneCol, fmt);
                String externalUserId = idCol == null ? (!phone.isBlank() ? "PHONE#" + phone : "NAME#" + name) : cellText(row, idCol, fmt);
                if (name.isEmpty() || externalUserId.isEmpty()) continue;
                String externalDepartment = cellText(row, deptCol, fmt);
                String pos = cellText(row, posCol, fmt);
                String roles = cellText(row, roleCol, fmt);
                String status = cellText(row, statusCol, fmt);
                String companyName = companyNameOf(source, externalDepartment);
                String department = departmentNameOf(source, externalDepartment);
                result.add(buildItem(source, externalUserId, name, phone, companyName,
                        externalDepartment, department, pos, roles, status));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("读取通讯录文件失败: " + file.getOriginalFilename(), e);
        }
        return result;
    }

    private static String cellText(Row row, Integer col, DataFormatter fmt) {
        if (col == null) return "";
        Cell cell = row.getCell(col);
        return cell == null ? "" : fmt.formatCellValue(cell).trim();
    }

    /** 钉钉导出顶级组织列：新版表头为“1级部门”，旧版为“一级部门”，个别导出配置下仅有“部门”。 */
    private static String topOrgColumnKey(Map<String, Integer> header) {
        if (header.containsKey("1级部门")) return "1级部门";
        if (header.containsKey("一级部门")) return "一级部门";
        return "部门";
    }

    /** 组织路径根节点：兼容 “A/B” 与 “A--B” 两种分隔，无分隔时返回整段。 */
    private static String rootSegment(String path) {
        String clean = path.trim();
        int cut = firstSeparator(clean);
        return cut < 0 ? clean : clean.substring(0, cut).trim();
    }

    /** 根节点之后的剩余文本（含分隔符）；无更深层级时为空。 */
    private static String restAfterRoot(String path) {
        String clean = path.trim();
        int cut = firstSeparator(clean);
        return cut < 0 ? "" : clean.substring(cut).trim();
    }

    private static int firstSeparator(String text) {
        int slash = text.indexOf('/');
        int dash = text.indexOf("--");
        if (slash >= 0 && (dash < 0 || slash < dash)) return slash;
        return dash;
    }

    /**
     * 从外部组织文本识别公司主体（V263 口径：公司主体与工作部门分离，子公司只作公司层级）。
     * <p>氚云按旧口径取路径末节；钉钉取顶级组织节点——若该节点为“…有限公司”（总公司或子公司）则归对应公司主体，
     * 否则（如“利强生产车间”）视为车间/部门，公司留空交由员工档案确认。
     */
    private static String companyNameOf(String source, String externalDepartment) {
        if (externalDepartment == null || externalDepartment.isBlank()) return "";
        String clean = externalDepartment.trim();
        if (SOURCE_H3YUN.equals(source)) {
            String[] parts = clean.split("--");
            return parts[parts.length - 1].trim();
        }
        String root = rootSegment(clean);
        return root.endsWith("有限公司") ? root : "";
    }

    /**
     * 从外部组织文本识别实际工作部门。
     * <p>氚云公司路径不写入工作部门；钉钉顶级组织即公司且无更深层级时为空，
     * 非公司顶级节点（如“利强生产车间”）整体作为部门，带层级路径时取末段。
     */
    private static String departmentNameOf(String source, String externalDepartment) {
        if (externalDepartment == null || externalDepartment.isBlank()) return "";
        if (SOURCE_H3YUN.equals(source)) return "";
        String clean = externalDepartment.trim();
        String root = rootSegment(clean);
        if (root.endsWith("有限公司") && restAfterRoot(clean).isBlank()) return "";
        int slash = clean.lastIndexOf('/');
        int dash = clean.lastIndexOf("--");
        int cut = Math.max(slash, dash);
        if (cut < 0) return clean;
        return clean.substring(cut + (cut == dash ? 2 : 1)).trim();
    }

    private ExternalIdentityItem buildItem(String source, String externalUserId, String name,
                                           String phone, String companyName, String externalDepartment,
                                           String department, String pos, String roles, String status) {
        // 已有精确映射 → 直接给出，不重复建议
        Optional<Long> existing = mappingRepository.findEmployeeIdBySourceAndExternalUser(source, externalUserId);
        if (existing.isPresent()) {
            Long empId = existing.get();
            String empName = employeeRepository.findById(empId).map(Employee::getName).orElse(null);
            return new ExternalIdentityItem(source, externalUserId, name, phone, companyName, externalDepartment,
                    department, pos, roles, status,
                    empId, empName, "MAPPED", true);
        }
        Suggestion s = suggest(name, phone);
        return new ExternalIdentityItem(source, externalUserId, name, phone, companyName, externalDepartment,
                department, pos, roles, status,
                s.employeeId, s.employeeName, s.confidence, false);
    }

    /** 姓名/手机号 → 本地员工建议匹配。 */
    private Suggestion suggest(String name, String phone) {
        if (name == null || name.isBlank()) {
            return new Suggestion(null, null, "NO_NAME");
        }
        if (phone != null && !phone.isBlank()) {
            List<Employee> activeByPhone = employeeRepository.findByPhone(phone).stream()
                    .filter(e -> "ACTIVE".equals(e.getStatus())).toList();
            if (activeByPhone.size() == 1) {
                Employee match = activeByPhone.getFirst();
                return name.trim().equals(match.getName())
                        ? new Suggestion(match.getId(), match.getName(), "HIGH_PHONE")
                        : new Suggestion(null, match.getName(), "PHONE_NAME_CONFLICT");
            }
            if (activeByPhone.size() > 1) return new Suggestion(null, null, "AMBIGUOUS_PHONE");
        }
        List<Employee> byName = employeeRepository.findByName(name.trim());
        List<Employee> activeByName = byName.stream().filter(e -> "ACTIVE".equals(e.getStatus())).toList();
        if (!activeByName.isEmpty()) {
            if (activeByName.size() == 1) {
                return new Suggestion(activeByName.get(0).getId(), activeByName.get(0).getName(), "HIGH");
            }
            if (phone != null && !phone.isBlank()) {
                Optional<Employee> byPhone = activeByName.stream()
                        .filter(e -> phone.equals(e.getPhone())).findFirst();
                if (byPhone.isPresent()) {
                    return new Suggestion(byPhone.get().getId(), byPhone.get().getName(), "HIGH_PHONE");
                }
            }
            return new Suggestion(null, null, "AMBIGUOUS");
        }
        return new Suggestion(null, null, "UNMATCHED");
    }

    private record Suggestion(Long employeeId, String employeeName, String confidence) {
    }

    // ==================== 事务应用 ====================

    /**
     * 应用导入映射：写外部映射 + 团队桥接 + 回填事实表 + 回填/新建员工档案。
     *
     * @param items    前端确认后的绑定项
     * @param operator 操作人
     */
    @Transactional
    public ApplyExternalImportResult apply(List<ApplyIdentityItem> items, String operator) {
        String op = operator == null || operator.isBlank() ? "EXTERNAL_IMPORT" : operator;
        int mapped = 0;
        int created = 0;
        int skipped = 0;
        // 不使用姓名缓存跨身份复用员工；歧义必须由管理员明确选择。
        for (ApplyIdentityItem item : items) {
            if (item.externalUserId() == null || item.externalUserId().isBlank()) {
                skipped++;
                continue;
            }
            Employee emp;
            if (item.employeeId() != null) {
                emp = employeeRepository.findById(item.employeeId()).orElse(null);
                if (emp == null) {
                    skipped++;
                    continue;
                }
            } else if (item.createIfMissing() && item.name() != null && !item.name().isBlank()) {
                {
                    // 库中已存在同名在职员工 → 复用而不新建（防止历史重复建档后再次导入仍重复）
                    List<Employee> actives = employeeRepository.findByName(item.name()).stream()
                            .filter(e -> "ACTIVE".equals(e.getStatus())).toList();
                    if (actives.isEmpty()) {
                        emp = createEmployee(item, op);
                        created++;
                    } else {
                        // 多个同名时优先手机号精确匹配，避免绑错
                        List<Employee> candidates = isEmpty(item.phone()) ? actives : actives.stream()
                                .filter(e -> item.phone().equals(e.getPhone())).toList();
                        if (candidates.size() != 1) {
                            throw new IllegalArgumentException("员工身份不唯一，请明确选择内部员工：" + item.name());
                        }
                        emp = candidates.getFirst();
                    }
                }
            } else {
                skipped++;
                continue;
            }
            bindIdentity(item, emp, op);
            mapped++;
        }
        mappingRepository.flush();
        teamMemberRepository.flush();
        int backfilled = h3EmployeeIdentityService.backfillUnassigned(op);
        return new ApplyExternalImportResult(mapped, created, skipped, backfilled);
    }

    private void bindIdentity(ApplyIdentityItem item, Employee emp, String op) {
        if (SOURCE_EMPLOYEE_ROSTER.equals(item.source())) {
            if (isEmpty(emp.getPhone()) && !isEmpty(item.phone())) emp.setPhone(item.phone());
            emp.setDepartment(item.department());
            emp.setUpdatedBy(op);
            employeeRepository.save(emp);
            emp.setDepartmentId(departmentService.syncEmployeeOrganization(emp.getId(), null,
                    item.department(), emp.getCompanyId(), SOURCE_EMPLOYEE_ROSTER));
            auditLogService.log("Employee", emp.getId(), emp.getName(), "EMP_ROSTER_DEPARTMENT",
                    "员工名单确认工作部门：" + (item.department() == null ? "未设置" : item.department()));
            return;
        }
        // 1. upsert 外部映射（唯一索引 uk_eem_source_external）
        EmployeeExternalMapping mapping = mappingRepository
                .findBySourceSystemAndExternalUserId(item.source(), item.externalUserId())
                .orElseGet(EmployeeExternalMapping::new);
        boolean isNew = mapping.getId() == null;
        if (!isNew && !emp.getId().equals(mapping.getEmployeeId())) {
            throw new IllegalArgumentException("该外部身份已绑定其他员工，请先核实归属：" + item.externalUserId());
        }
        mapping.setEmployeeId(emp.getId());
        mapping.setSourceSystem(item.source());
        mapping.setExternalUserId(item.externalUserId());
        mapping.setExternalName(item.name());
        mapping.setExternalDepartment(item.externalDepartment());
        mapping.setExternalStatus(item.status());
        mapping.setExternalPosition(item.position());
        mapping.setExternalRoles(item.externalRoles());
        mapping.setLastSeenAt(java.time.LocalDateTime.now());
        mapping.setIsPrimary(Boolean.FALSE);
        mapping.setMappedBy(op);
        if (isNew) {
            mapping.setRemark("外部通讯录导入映射");
        }
        mappingRepository.save(mapping);

        if (SOURCE_H3YUN.equals(item.source())) {
            // 2a. upsert 团队桥接（company_team_members，按外部ID→姓名→新建）
            CompanyTeamMember tm = teamMemberRepository.findByExternalUserId(item.externalUserId()).orElse(null);
            if (tm == null) {
                List<CompanyTeamMember> byName = teamMemberRepository.findByName(item.name());
                tm = byName.size() == 1 && isEmpty(byName.getFirst().getExternalUserId())
                        && (byName.getFirst().getEmployeeId() == null || emp.getId().equals(byName.getFirst().getEmployeeId()))
                        ? byName.getFirst() : null;
            }
            if (tm == null) {
                tm = new CompanyTeamMember();
                tm.setName(item.name());
                tm.setPosition(item.position());
                tm.setPhone(item.phone());
                tm.setIsCore(Boolean.FALSE);
                tm.setSortOrder(999);
            }
            tm.setEmployeeId(emp.getId());
            tm.setExternalUserId(item.externalUserId());
            if (item.externalDepartment() != null && !item.externalDepartment().isBlank()) {
                tm.setDepartment(item.externalDepartment());
            }
            teamMemberRepository.save(tm);
            // 3a. 回填四张事实表 + 客户业务员关系（salesperson_id = company_team_members.id）
            backfillBySalespersonId("production_orders", emp.getId(), tm.getId());
            // 出库统一在全批通讯录落库后按已确认映射回填，旧 salesperson_id 不再决定归属。
            backfillBySalespersonId("receipts", emp.getId(), tm.getId());
            backfillBySalespersonId("customer_salesperson_relations", emp.getId(), tm.getId());
        } else if (SOURCE_DINGTALK.equals(item.source())) {
            // 2b. 回填钉钉报销单归属
            jdbc.update("UPDATE expense_claims SET employee_id=? WHERE dingtalk_user_id=? AND employee_id IS NULL",
                    emp.getId(), item.externalUserId());
        }

        // 4. 回填员工档案空字段（仅当本地为空且外部有值，避免覆盖人工维护）
        boolean changed = false;
        if (isEmpty(emp.getPhone()) && !isEmpty(item.phone())) {
            emp.setPhone(item.phone());
            changed = true;
        }
        if (isEmpty(emp.getDepartment()) && !isEmpty(item.department())) {
            emp.setDepartment(item.department());
            changed = true;
        }
        if (isEmpty(emp.getPosition()) && !isEmpty(item.position())) {
            emp.setPosition(item.position());
            changed = true;
        }
        Long companyId = departmentService.resolveCompanyByName(item.companyName());
        if (emp.getCompanyId() == null && companyId != null) {
            emp.setCompanyId(companyId);
            changed = true;
        }
        if (changed) {
            emp.setUpdatedBy(op);
            employeeRepository.save(emp);
            emp.setDepartmentId(departmentService.syncEmployeeOrganization(emp.getId(), emp.getDepartmentId(),
                    emp.getDepartment(), emp.getCompanyId(), "EXTERNAL_IMPORT"));
            defaultLoginAccountService.synchronize(emp);
        }
    }

    private void backfillBySalespersonId(String table, Long employeeId, Long salespersonId) {
        jdbc.update("UPDATE " + table + " SET employee_id=? WHERE salesperson_id=? AND employee_id IS NULL",
                employeeId, salespersonId);
    }

    private Employee createEmployee(ApplyIdentityItem item, String op) {
        Employee e = new Employee();
        e.setName(item.name());
        e.setPhone(item.phone());
        e.setDepartment(item.department());
        e.setCompanyId(departmentService.resolveCompanyByName(item.companyName()));
        e.setPosition(item.position());
        e.setStatus("ACTIVE");
        // 外部职位/氚云角色不得直接授予内部业务员身份或系统权限。
        e.setIsSalesperson(Boolean.FALSE);
        e.setEmployeeNo(nextEmployeeNo());
        e.setCreatedBy(op);
        e.setUpdatedBy(op);
        Employee saved = employeeRepository.save(e);
        saved.setDepartmentId(departmentService.syncEmployeeOrganization(saved.getId(), null, saved.getDepartment(),
                saved.getCompanyId(), "EXTERNAL_IMPORT"));
        syncSalespersonRole(saved);
        defaultLoginAccountService.synchronize(saved);
        auditLogService.log("Employee", saved.getId(), saved.getName(),
                "EMP_CREATE", "外部通讯录导入新建员工：" + saved.getName());
        return saved;
    }

    private void syncSalespersonRole(Employee employee) {
        if (Boolean.TRUE.equals(employee.getIsSalesperson()) && "ACTIVE".equals(employee.getStatus())) {
            jdbc.update("INSERT IGNORE INTO employee_roles(employee_id,role_id) SELECT ?,id FROM system_roles WHERE role_code='SALESPERSON'",
                    employee.getId());
        } else {
            jdbc.update("DELETE er FROM employee_roles er JOIN system_roles r ON r.id=er.role_id WHERE er.employee_id=? AND r.role_code='SALESPERSON'",
                    employee.getId());
        }
    }

    private String nextEmployeeNo() {
        Long maxSeq = employeeRepository.findMaxEmployeeLQSequence();
        long seq = maxSeq == null ? 0 : maxSeq;
        return "LQ" + String.format("%03d", seq + 1);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }
}
