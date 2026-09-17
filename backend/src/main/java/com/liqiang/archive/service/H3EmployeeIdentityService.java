package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 出库只有业务员姓名时，复用已确认的氚云通讯录映射；不匹配内部同名员工。 */
@Service
@RequiredArgsConstructor
public class H3EmployeeIdentityService {
    private final JdbcTemplate jdbc;
    private final SourceIdentityService identities;
    private final SalesOutboundCommissionService commissions;
    private final AuditLogService audit;

    public Long resolveName(String name) {
        if (name == null || name.isBlank()) return null;
        Long unified = identities.resolvedTarget("EMPLOYEE", null, name);
        if (unified != null) return unified;
        List<Long> ids = jdbc.query("""
            SELECT DISTINCT employee_id FROM employee_external_mappings
            WHERE source_system='H3YUN' AND TRIM(external_name)=?
            """, (rs, n) -> rs.getLong(1), name.trim());
        // 同一姓名指向不同员工时必须人工解决，不取第一条。
        return uniqueEmployee(ids);
    }

    static Long uniqueEmployee(List<Long> ids) {
        List<Long> unique = ids.stream().distinct().toList();
        return unique.size() == 1 ? unique.getFirst() : null;
    }

    /** 通讯录整个批次落库后调用，避免仅导入一半时将同名身份误判为唯一。 */
    @Transactional
    public int backfillUnassigned(String operator) {
        List<Map<String, Object>> mappings = jdbc.queryForList("""
            SELECT TRIM(external_name) source_name, MIN(employee_id) employee_id
            FROM employee_external_mappings WHERE source_system='H3YUN'
              AND external_name IS NOT NULL AND TRIM(external_name)<>''
            GROUP BY TRIM(external_name) HAVING COUNT(DISTINCT employee_id)=1
            """);
        int total = 0;
        for (Map<String, Object> mapping : mappings) {
            Long employeeId = ((Number) mapping.get("employee_id")).longValue();
            int updated = jdbc.update("""
                UPDATE sales_outbounds o SET o.employee_id=?
                WHERE o.external_source='H3YUN' AND o.employee_id IS NULL
                  AND TRIM(o.salesperson_name)=?
                  AND NOT EXISTS(SELECT 1 FROM sales_outbound_commissions c
                                 WHERE c.sales_outbound_id=o.id AND c.status='SETTLED')
                """, employeeId, mapping.get("source_name"));
            if (updated > 0) commissions.recalculateByEmployee(employeeId);
            total += updated;
        }
        audit.log("Employee", null, "氚云身份回填", "H3_IDENTITY_BACKFILL",
                "已回填未绑定出库 " + total + " 张；保留已有归属及已结算订单。操作人：" + operator);
        return total;
    }
}
