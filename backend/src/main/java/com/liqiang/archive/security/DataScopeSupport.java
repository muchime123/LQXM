package com.liqiang.archive.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 行级数据范围统一支持：为业务 SQL 追加「本人 / 本部门 / 全部 / 自定义名单」过滤条件。
 *
 * <p>两种列形态：
 * <ul>
 *   <li>{@link #appendByName}：业务表只有业务员姓名列（如 salesperson_name / b.salesperson_name_snapshot），
 *       SELF 按姓名等值过滤，DEPARTMENT 经 employees 表以姓名关联出部门后再比对；</li>
 *   <li>{@link #appendByEmployeeId}：业务表直接持有员工 ID 列且 SQL 已 JOIN employees（如提成快照的
 *       s.employee_id 与 e.department），SELF 按员工 ID 等值过滤，DEPARTMENT 按部门列等值过滤。</li>
 * </ul>
 * 所有列名参数均为代码内常量，不接收用户输入，无注入风险。
 *
 * <p>V249 起数据范围按"功能"取值：带 {@code featureCode} 的重载按
 * {@link CurrentUser#featureScopes()} 精确解析（含 CUSTOM 自定义员工/部门名单）；
 * 不带功能的重载保持历史语义（按全局 dataScope，仅供未标注功能的调用兼容）。
 */
@Component
@RequiredArgsConstructor
public class DataScopeSupport {

    private final CurrentUserService users;

    /** 兼容重载：按全局数据范围过滤。 */
    public void appendByName(StringBuilder where, List<Object> params, String nameColumn) {
        appendByName(where, params, nameColumn, null);
    }

    /** 按业务员姓名列过滤：SELF=姓名等值；DEPARTMENT=EXISTS(employees.name=列 AND 部门=?)；CUSTOM=本人/自定义名单。 */
    public void appendByName(StringBuilder where, List<Object> params, String nameColumn, String featureCode) {
        CurrentUserService.CurrentUser user = users.current();
        CurrentUserService.FeatureScope fs = featureScope(featureCode);
        String scope = fs != null ? fs.scope() : user.dataScope();
        if ("SELF".equals(scope)) {
            where.append(" AND ").append(nameColumn).append("=?");
            params.add(user.name());
        } else if ("DEPARTMENT".equals(scope)) {
            where.append(" AND EXISTS(SELECT 1 FROM employees scope_employee WHERE scope_employee.name=")
                    .append(nameColumn).append(" AND scope_employee.department=?)");
            params.add(user.department());
        } else if ("CUSTOM".equals(scope)) {
            where.append(" AND (").append(nameColumn).append("=?");
            params.add(user.name());
            List<Long> ids = fs != null ? fs.employeeIds() : List.of();
            List<String> depts = fs != null ? fs.departmentNames() : List.of();
            if (ids != null && !ids.isEmpty()) {
                where.append(" OR EXISTS(SELECT 1 FROM employees scope_employee WHERE scope_employee.name=")
                        .append(nameColumn).append(" AND scope_employee.id IN (")
                        .append(ids.stream().map(id -> "?").collect(Collectors.joining(",")))
                        .append("))");
                params.addAll(ids);
            }
            if (depts != null && !depts.isEmpty()) {
                where.append(" OR EXISTS(SELECT 1 FROM employees scope_employee WHERE scope_employee.name=")
                        .append(nameColumn).append(" AND scope_employee.department IN (")
                        .append(depts.stream().map(d -> "?").collect(Collectors.joining(",")))
                        .append("))");
                params.addAll(depts);
            }
            where.append(")");
        }
    }

    /** 兼容重载：按全局数据范围过滤。 */
    public void appendByEmployeeId(StringBuilder where, List<Object> params, String employeeColumn, String departmentColumn) {
        appendByEmployeeId(where, params, employeeColumn, departmentColumn, null);
    }

    /** 按员工 ID 列 + 部门列过滤：SELF=员工 ID 等值；DEPARTMENT=部门列等值；CUSTOM=本人或自定义名单。 */
    public void appendByEmployeeId(StringBuilder where, List<Object> params, String employeeColumn, String departmentColumn,
                                   String featureCode) {
        CurrentUserService.CurrentUser user = users.current();
        CurrentUserService.FeatureScope fs = featureScope(featureCode);
        String scope = fs != null ? fs.scope() : user.dataScope();
        if ("SELF".equals(scope)) {
            where.append(" AND ").append(employeeColumn).append("=?");
            params.add(user.employeeId());
        } else if ("DEPARTMENT".equals(scope)) {
            where.append(" AND ").append(departmentColumn).append("=?");
            params.add(user.department());
        } else if ("CUSTOM".equals(scope)) {
            where.append(" AND (").append(employeeColumn).append("=?");
            params.add(user.employeeId());
            List<Long> ids = fs != null ? fs.employeeIds() : List.of();
            List<String> depts = fs != null ? fs.departmentNames() : List.of();
            if (ids != null && !ids.isEmpty()) {
                where.append(" OR ").append(employeeColumn).append(" IN (")
                        .append(ids.stream().map(id -> "?").collect(Collectors.joining(",")))
                        .append(")");
                params.addAll(ids);
            }
            if (depts != null && !depts.isEmpty()) {
                where.append(" OR ").append(departmentColumn).append(" IN (")
                        .append(depts.stream().map(d -> "?").collect(Collectors.joining(",")))
                        .append(")");
                params.addAll(depts);
            }
            where.append(")");
        }
    }

    /** 解析功能级范围：功能有配置取其配置；否则回退全局（featureScopes 无该功能时视为全局）。 */
    private CurrentUserService.FeatureScope featureScope(String featureCode) {
        if (featureCode == null) {
            return null;
        }
        CurrentUserService.CurrentUser user = users.current();
        return user.featureScopes().get(featureCode);
    }
}
