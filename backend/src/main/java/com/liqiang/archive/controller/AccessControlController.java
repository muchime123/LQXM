package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.AccessControlService;
import com.liqiang.archive.service.AccessControlService.FeatureGrantPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 系统管理 · 角色与权限（V249：按「功能 × 数据范围 × 操作」授权，含员工角色分配）。 */
@RestController
@RequestMapping("/api/access-control")
@RequiredArgsConstructor
@RequirePermission("system.employee.manage")
public class AccessControlController {

    private final AccessControlService service;

    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return service.roles();
    }

    /** 功能目录（含每功能的动作清单），权限配置页渲染依据。 */
    @GetMapping("/features")
    public List<Map<String, Object>> features() {
        return service.features();
    }

    @GetMapping("/permissions")
    public List<Map<String, Object>> permissions() {
        return service.permissions();
    }

    /** 启停用权限码（停用=即时下线，启用=恢复）。 */
    @PutMapping("/permissions/{id}/enabled")
    public Map<String, Object> setPermissionEnabled(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        service.setPermissionEnabled(id, enabled);
        return Map.of("enabled", enabled);
    }

    @GetMapping("/roles/{id}")
    public Map<String, Object> roleDetail(@PathVariable long id) {
        return service.roleDetail(id);
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(@RequestBody RolePayload body) {
        return service.createRole(body.roleCode(), body.roleName(), body.grants());
    }

    @PutMapping("/roles/{id}")
    public Map<String, Object> updateRole(@PathVariable long id, @RequestBody RolePayload body) {
        return service.updateRole(id, body.roleName(), body.grants());
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> deleteRole(@PathVariable long id) {
        service.deleteRole(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/employees/{id}/roles")
    public Map<String, Object> employeeRoles(@PathVariable long id) {
        return service.employeeRoles(id);
    }

    @PutMapping("/employees/{id}/roles")
    public Map<String, Object> setRoles(@PathVariable long id, @RequestBody RolePayload body) {
        return service.setEmployeeRoles(id, body.roleCodes());
    }

    // ===================== 角色成员管理 =====================

    @GetMapping("/roles/{id}/members")
    public List<Map<String, Object>> roleMembers(@PathVariable long id) {
        return service.roleMembers(id);
    }

    @PostMapping("/roles/{id}/members")
    public Map<String, Object> addMembers(@PathVariable long id, @RequestBody MemberPayload body) {
        return service.addRoleMembers(id, body.employeeIds());
    }

    @DeleteMapping("/roles/{id}/members/{employeeId}")
    public Map<String, Object> removeMember(@PathVariable long id, @PathVariable long employeeId) {
        return service.removeRoleMember(id, employeeId);
    }

    /** 角色载荷：grants 为功能授权列表；roleCodes 仅供员工角色分配端点使用。 */
    public record RolePayload(String roleCode, String roleName,
                              List<FeatureGrantPayload> grants, List<String> roleCodes) {
    }

    public record MemberPayload(List<Long> employeeIds) {
    }
}
