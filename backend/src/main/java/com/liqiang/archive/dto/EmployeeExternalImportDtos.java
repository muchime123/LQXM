package com.liqiang.archive.dto;

import java.util.List;

/**
 * 员工名册 · 外部身份导入映射（氚云/钉钉通讯录导出文件）DTO。
 *
 * @since V186
 */
public final class EmployeeExternalImportDtos {

    private EmployeeExternalImportDtos() {
    }

    /**
     * 外部身份解析+匹配结果项。
     *
     * @param source               来源系统：H3YUN / DINGTALK
     * @param externalUserId       外部用户ID：氚云成员ID(ObjectId) / 钉钉员工UserID
     * @param name                 姓名
     * @param phone                手机号（氚云导出通常为空）
     * @param department           所属部门
     * @param companyName          从外部组织节点识别出的公司主体
     * @param department           实际工作部门；氚云公司路径不会写入该字段
     * @param position             外部职位（不再使用氚云角色代替）
     * @param externalRoles        氚云应用角色，仅作来源快照
     * @param status               外部状态（氚云：已启用/已停用；钉钉：员工状态）
     * @param suggestedEmployeeId  系统建议匹配的员工ID（可为 null）
     * @param suggestedEmployeeName 建议匹配的员工姓名
     * @param confidence           置信度：HIGH / HIGH_PHONE / MEDIUM / AMBIGUOUS / UNMATCHED / NO_NAME
     * @param hasMapping           该外部身份是否已存在精确映射
     */
    public record ExternalIdentityItem(
            String source,
            String externalUserId,
            String name,
            String phone,
            String companyName,
            String externalDepartment,
            String department,
            String position,
            String externalRoles,
            String status,
            Long suggestedEmployeeId,
            String suggestedEmployeeName,
            String confidence,
            boolean hasMapping
    ) {
    }

    /**
     * 解析预览结果。
     *
     * @param hyCount   氚云身份数
     * @param ddCount   钉钉身份数
     * @param items     全部身份（含匹配建议）
     */
    public record ExternalImportPreview(
            int hyCount,
            int ddCount,
            int rosterCount,
            List<ExternalIdentityItem> items
    ) {
    }

    /**
     * 应用请求项（前端可人工调整 employeeId 与是否新建）。
     *
     * @param source           来源系统：H3YUN / DINGTALK
     * @param externalUserId   外部用户ID
     * @param name             姓名
     * @param phone            手机号
     * @param department       部门
     * @param position         职位
     * @param employeeId       绑定到本地员工ID；为 null 且 createIfMissing=true 时新建
     * @param createIfMissing  未匹配时是否新建员工档案
     */
    public record ApplyIdentityItem(
            String source,
            String externalUserId,
            String name,
            String phone,
            String companyName,
            String externalDepartment,
            String department,
            String position,
            String externalRoles,
            String status,
            Long employeeId,
            boolean createIfMissing
    ) {
    }

    /**
     * 应用结果统计。
     */
    public record ApplyExternalImportResult(
            int mappedCount,
            int createdEmployeeCount,
            int skippedCount,
            int backfilledOutboundCount
    ) {
    }
}
