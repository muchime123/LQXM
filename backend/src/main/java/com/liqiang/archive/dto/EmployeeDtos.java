package com.liqiang.archive.dto;

import com.liqiang.archive.entity.Employee;
import com.liqiang.archive.entity.EmployeeExternalMapping;
import com.liqiang.archive.entity.LoginAccount;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * P5.1 统一员工体系 DTO（内联 record）。
 * <p>
 * 枚举翻译约定（Service 层统一注入 translator，中文 ↔ DB 英文）：
 * <ul>
 *     <li>status：ACTIVE 在职 / RESIGNED 离职 / ON_LEAVE 停薪留职</li>
 *     <li>gender：MALE 男 / FEMALE 女 / OTHER 其他</li>
 *     <li>sourceSystem：H3YUN 氚云 / DINGTALK 钉钉 / SYSTEM 系统账号</li>
 * </ul>
 */
public class EmployeeDtos {

    // ==================== 筛选下拉选项 ====================

    public record FilterOptions(
            List<String> departments,
            List<CompanyOption> companies,
            List<String> statuses,
            List<String> sourceSystems,
            long totalCount,
            long activeCount,
            long salespersonCount
    ) {
    }

    public record CompanyOption(Long id, String name, Long parentId, String parentName) {}

    public record EmployeeDeleteResult(String action, String message, List<String> referenceTables) {}

    // ==================== 列表摘要 ====================

    public record EmployeeSummary(
            Long id,
            String employeeNo,
            String name,
            String gender,
            String department,
            Long departmentId,
            Long companyId,
            String position,
            String phone,
            String status,
            Boolean isSalesperson,
            LocalDate entryDate,
            LocalDateTime createdAt,
            String accountStatus
    ) {
        public static EmployeeSummary from(Employee e, LoginAccount account, Function<String, String> translate) {
            return new EmployeeSummary(
                    e.getId(),
                    e.getEmployeeNo(),
                    e.getName(),
                    translate.apply(e.getGender()),
                    e.getDepartment(),
                    e.getDepartmentId(),
                    e.getCompanyId(),
                    e.getPosition(),
                    e.getPhone(),
                    translate.apply(e.getStatus()),
                    e.getIsSalesperson(),
                    e.getEntryDate(),
                    e.getCreatedAt(),
                    accountStatus(e, account)
            );
        }

        private static String accountStatus(Employee employee, LoginAccount account) {
            if (employee.getPhone() == null || employee.getPhone().isBlank()) return "MISSING_PHONE";
            if (account == null) return "MISSING_ACCOUNT";
            if (!Boolean.TRUE.equals(account.getEnabled())) return "DISABLED";
            return "NORMAL";
        }
    }

    // ==================== 详情（含映射列表） ====================

    public record EmployeeDetail(
            Long id,
            String employeeNo,
            String name,
            String gender,
            String department,
            Long departmentId,
            Long companyId,
            String position,
            String phone,
            String idCardNumber,
            String email,
            LocalDate entryDate,
            LocalDate leaveDate,
            String status,
            Boolean isSalesperson,
            String remark,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<MappingSummary> mappings
    ) {
        public static EmployeeDetail from(Employee e, List<MappingSummary> mappings, Function<String, String> translate) {
            return new EmployeeDetail(
                    e.getId(),
                    e.getEmployeeNo(),
                    e.getName(),
                    translate.apply(e.getGender()),
                    e.getDepartment(),
                    e.getDepartmentId(),
                    e.getCompanyId(),
                    e.getPosition(),
                    e.getPhone(),
                    e.getIdCardNumber(),
                    e.getEmail(),
                    e.getEntryDate(),
                    e.getLeaveDate(),
                    translate.apply(e.getStatus()),
                    e.getIsSalesperson(),
                    e.getRemark(),
                    e.getCreatedBy(),
                    e.getUpdatedBy(),
                    e.getCreatedAt(),
                    e.getUpdatedAt(),
                    mappings
            );
        }
    }

    // ==================== 创建/更新入参 ====================

    public record EmployeePayload(
            String employeeNo,
            String name,
            String gender,
            String department,
            Long departmentId,
            Long companyId,
            String position,
            @NotBlank(message = "手机号不能为空，将作为员工登录账号") String phone,
            String idCardNumber,
            String email,
            LocalDate entryDate,
            LocalDate leaveDate,
            String status,
            Boolean isSalesperson,
            String remark
    ) {
        /** 将 Payload 写入 Entity（仅覆盖非空字段）。枚举字段调用方须提前用 translator 转 DB 英文。 */
        public void applyTo(Employee e) {
            if (employeeNo != null) e.setEmployeeNo(employeeNo.isBlank() ? null : employeeNo);
            if (name != null) e.setName(name);
            if (gender != null) e.setGender(gender.isBlank() ? null : gender);
            if (department != null) e.setDepartment(department.isBlank() ? null : department);
            if (departmentId != null) e.setDepartmentId(departmentId);
            if (companyId != null) e.setCompanyId(companyId);
            if (position != null) e.setPosition(position.isBlank() ? null : position);
            if (phone != null) e.setPhone(phone.isBlank() ? null : phone);
            if (idCardNumber != null) e.setIdCardNumber(idCardNumber.isBlank() ? null : idCardNumber);
            if (email != null) e.setEmail(email.isBlank() ? null : email);
            if (entryDate != null) e.setEntryDate(entryDate);
            if (leaveDate != null) e.setLeaveDate(leaveDate);
            if (status != null) e.setStatus(status);
            if (isSalesperson != null) e.setIsSalesperson(isSalesperson);
            if (remark != null) e.setRemark(remark.isBlank() ? null : remark);
        }
    }

    // ==================== 外部身份映射 ====================

    public record MappingSummary(
            Long id,
            Long employeeId,
            String sourceSystem,
            String externalUserId,
            String externalName,
            String externalDepartment,
            String externalStatus,
            String externalPosition,
            String externalRoles,
            Boolean isPrimary,
            String remark,
            LocalDateTime mappedAt
    ) {
        public static MappingSummary from(EmployeeExternalMapping m, Function<String, String> translate) {
            return new MappingSummary(
                    m.getId(),
                    m.getEmployeeId(),
                    translate.apply(m.getSourceSystem()),
                    m.getExternalUserId(),
                    m.getExternalName(),
                    m.getExternalDepartment(),
                    m.getExternalStatus(),
                    m.getExternalPosition(),
                    m.getExternalRoles(),
                    m.getIsPrimary(),
                    m.getRemark(),
                    m.getMappedAt()
            );
        }
    }

    public record MappingPayload(
            String sourceSystem,
            String externalUserId,
            String externalName,
            String externalDepartment,
            Boolean isPrimary,
            String remark
    ) {
        /** 写入 Entity（枚举字段调用方转 DB 英文）。 */
        public void applyTo(EmployeeExternalMapping m) {
            if (sourceSystem != null) m.setSourceSystem(sourceSystem);
            if (externalUserId != null) m.setExternalUserId(externalUserId);
            if (externalName != null) m.setExternalName(externalName.isBlank() ? null : externalName);
            if (externalDepartment != null) m.setExternalDepartment(externalDepartment.isBlank() ? null : externalDepartment);
            if (isPrimary != null) m.setIsPrimary(isPrimary);
            if (remark != null) m.setRemark(remark.isBlank() ? null : remark);
        }
    }

}
