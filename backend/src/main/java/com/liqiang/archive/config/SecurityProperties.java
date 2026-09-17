package com.liqiang.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P7 身份认证安全开关。
 */
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** 是否允许通过 X-Employee-Id / X-User-Name 请求头声明身份（仅开发环境）。 */
    private boolean allowEmployeeHeader = true;

    /** 是否启用旧版 compatibility-admin 匿名超管模式（已废弃，生产必须 false）。 */
    private boolean compatibilityAdmin = false;

    /** 会话有效期（小时）。 */
    private long sessionTimeoutHours = 12;

    /** @RequirePermission 引用的权限码未在 system_permissions 表注册时的启动行为：FAIL 启动失败 / WARN 仅告警。 */
    private PermissionCodeValidationMode permissionCodeValidation = PermissionCodeValidationMode.FAIL;

    public enum PermissionCodeValidationMode {
        FAIL, WARN
    }

    public PermissionCodeValidationMode getPermissionCodeValidation() {
        return permissionCodeValidation;
    }

    public void setPermissionCodeValidation(PermissionCodeValidationMode permissionCodeValidation) {
        this.permissionCodeValidation = permissionCodeValidation;
    }

    public boolean isAllowEmployeeHeader() {
        return allowEmployeeHeader;
    }

    public void setAllowEmployeeHeader(boolean allowEmployeeHeader) {
        this.allowEmployeeHeader = allowEmployeeHeader;
    }

    public boolean isCompatibilityAdmin() {
        return compatibilityAdmin;
    }

    public void setCompatibilityAdmin(boolean compatibilityAdmin) {
        this.compatibilityAdmin = compatibilityAdmin;
    }

    public long getSessionTimeoutHours() {
        return sessionTimeoutHours;
    }

    public void setSetSessionTimeoutHours(long sessionTimeoutHours) {
        this.sessionTimeoutHours = sessionTimeoutHours;
    }
}
