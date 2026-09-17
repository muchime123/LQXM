package com.liqiang.archive.security;

import com.liqiang.archive.entity.AuthSession;
import com.liqiang.archive.entity.LoginAccount;
import com.liqiang.archive.config.SecurityProperties;
import com.liqiang.archive.repository.AuthSessionRepository;
import com.liqiang.archive.repository.EmployeeRepository;
import com.liqiang.archive.repository.LoginAccountRepository;
import com.liqiang.archive.service.DefaultLoginAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P7 正式身份认证：
 * <ul>
 *   <li>登录方式：员工手机号对应的本地账号（login_accounts）</li>
 *   <li>会话：不透明 Token（数据库仅存 SHA-256 哈希），可过期、可撤销</li>
 * </ul>
 * 业务代码只应通过 {@link CurrentUserService#current()} 获取"我是谁"，禁止再自行解析请求头。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final LoginAccountRepository loginAccounts;
    private final AuthSessionRepository sessions;
    private final EmployeeRepository employeeRepository;
    private final CurrentUserFactory currentUserFactory;
    private final SecurityProperties securityProps;
    private final JdbcTemplate jdbc;
    private final DefaultLoginAccountService defaultLoginAccountService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.security.session-timeout-hours:12}")
    private long sessionTimeoutHours;

    /** 登录成功返回的 Token 与当前用户信息。 */
    public record LoginResult(String token, CurrentUserService.CurrentUser user) {
    }

    // ===================== 登录 =====================

    /** 员工手机号账号登录。 */
    @Transactional
    public LoginResult loginLocal(String username, String password, String ip, String userAgent) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new SecurityException("请输入用户名和密码");
        }
        LoginAccount account = loginAccounts.findByUsername(username.trim()).orElse(null);
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())
                || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new SecurityException("用户名或密码错误");
        }
        ensureEmployeeActive(account.getEmployeeId());
        var user = currentUserFactory.build(account.getEmployeeId(), "LOCAL", account.getId(), null);
        account.setLastLoginAt(LocalDateTime.now());
        loginAccounts.save(account);
        return new LoginResult(issueToken(account.getEmployeeId(), "LOCAL", null, ip, userAgent), user);
    }

    // ===================== 会话 =====================

    /** 校验 Token：无效/过期/已撤销返回 null。 */
    public AuthSession validateSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        AuthSession session = sessions.findByTokenHashAndRevokedAtIsNull(sha256(rawToken)).orElse(null);
        if (session == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresAt().isBefore(now) || session.getRevokedAt() != null) {
            return null;
        }
        // 活跃时间每 5 分钟刷新一次，避免高频写库
        if (session.getLastActiveAt().isBefore(now.minusMinutes(5))) {
            sessions.touchLastActive(session.getId(), now);
            session.setLastActiveAt(now);
        }
        return session;
    }

    /** 退出登录：撤销当前 Token。 */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        sessions.revokeByTokenHash(sha256(rawToken), LocalDateTime.now());
    }

    // ===================== 账号管理 =====================

    /** 首次引导：仅当系统没有任何登录账号时允许创建第一个账号（幂等保护）。 */
    @Transactional
    public LoginResult bootstrap(Long employeeId, String ip, String userAgent) {
        if (loginAccounts.count() > 0) {
            throw new SecurityException("系统已存在登录账号，引导初始化不可用");
        }
        String phone = employeeRepository.findById(employeeId)
                .map(employee -> employee.getPhone() == null ? null : employee.getPhone().trim())
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new SecurityException("所选管理员未填写手机号，请先完善员工档案"));
        createOrUpdateAccount(employeeId, phone, "123456", true);
        // 顶层管理员：引导创建的第一个账号自动授予 ADMIN 角色（全量权限），避免"无人能管权限"的死锁
        jdbc.update("INSERT IGNORE INTO employee_roles(employee_id, role_id) SELECT ?, id FROM system_roles WHERE role_code='ADMIN'", employeeId);
        defaultLoginAccountService.provisionExistingEmployees();
        return loginLocal(phone, "123456", ip, userAgent);
    }

    /** 创建或更新某员工的本地登录账号（一个员工最多一个）。 */
    @Transactional
    public LoginAccount createOrUpdateAccount(Long employeeId, String username, String password, Boolean enabled) {
        if (employeeId == null) {
            throw new SecurityException("员工不能为空");
        }
        ensureEmployeeActive(employeeId);
        String name = employeeRepository.findById(employeeId)
                .map(employee -> employee.getPhone() == null ? null : employee.getPhone().trim().replace(" ", "").replace("-", ""))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new SecurityException("员工未填写手机号，无法创建登录账号"));
        LoginAccount existing = loginAccounts.findByEmployeeId(employeeId).orElse(null);
        if (existing == null) {
            if (password == null || password.length() < 6) {
                throw new SecurityException("密码至少 6 位");
            }
            if (loginAccounts.existsByUsername(name)) {
                throw new SecurityException("用户名已被占用");
            }
            LoginAccount account = new LoginAccount();
            account.setEmployeeId(employeeId);
            account.setUsername(name);
            account.setPasswordHash(passwordEncoder.encode(password));
            account.setEnabled(enabled == null || enabled);
            return loginAccounts.save(account);
        }
        // 更新既有账号
        LoginAccount byUser = loginAccounts.findByUsername(name).orElse(null);
        if (byUser != null && !byUser.getId().equals(existing.getId())) {
            throw new SecurityException("用户名已被占用");
        }
        existing.setUsername(name);
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) {
                throw new SecurityException("密码至少 6 位");
            }
            existing.setPasswordHash(passwordEncoder.encode(password));
        }
        if (enabled != null) {
            existing.setEnabled(enabled);
        }
        return loginAccounts.save(existing);
    }

    /** 重置密码并撤销该员工的所有会话（密码变更后旧会话一律失效）。 */
    @Transactional
    public void resetPassword(Long accountId) {
        LoginAccount account = loginAccounts.findById(accountId)
                .orElseThrow(() -> new SecurityException("登录账号不存在"));
        account.setPasswordHash(passwordEncoder.encode("123456"));
        loginAccounts.save(account);
        sessions.revokeByEmployeeId(account.getEmployeeId(), LocalDateTime.now());
    }

    /** 仅调整账号可登录状态；停用时立即撤销该员工全部会话。 */
    @Transactional
    public void setAccountEnabled(Long accountId, boolean enabled) {
        LoginAccount account = loginAccounts.findById(accountId)
                .orElseThrow(() -> new SecurityException("登录账号不存在"));
        if (enabled) ensureEmployeeActive(account.getEmployeeId());
        account.setEnabled(enabled);
        loginAccounts.save(account);
        if (!enabled) {
            sessions.revokeByEmployeeId(account.getEmployeeId(), LocalDateTime.now());
        }
    }

    /** 删除本地登录账号并撤销该员工全部会话。 */
    @Transactional
    public void deleteAccount(Long accountId) {
        LoginAccount account = loginAccounts.findById(accountId)
                .orElseThrow(() -> new SecurityException("登录账号不存在"));
        sessions.revokeByEmployeeId(account.getEmployeeId(), LocalDateTime.now());
        loginAccounts.delete(account);
    }

    /** 系统是否尚未创建任何登录账号（决定登录页是否显示"首次引导"）。 */
    public boolean bootstrapRequired() {
        return loginAccounts.count() == 0;
    }

    /**
     * 登录页可公开查询的在职员工列表（仅首次引导或开发兼容模式允许）。
     * 用于创建第一个管理员账号，或开发环境快捷切换身份。
     */
    public List<Map<String, Object>> publicEmployees() {
        boolean allowed = bootstrapRequired() || securityProps.isAllowEmployeeHeader();
        if (!allowed) return List.of();
        return employeeRepository.findByStatusOrderByNameAsc("ACTIVE").stream()
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", e.getId());
                    entry.put("name", e.getName());
                    if (e.getDepartment() != null) {
                        entry.put("department", e.getDepartment());
                    }
                    return entry;
                })
                .toList();
    }

    /** 登录账号列表（含员工姓名/部门）。 */
    public List<Map<String, Object>> listAccounts() {
        return jdbc.queryForList(
                "SELECT a.id, a.employee_id AS employeeId, e.name AS employeeName, e.department,"
                        + " a.username, a.enabled, a.last_login_at AS lastLoginAt, a.created_at AS createdAt"
                        + " FROM login_accounts a JOIN employees e ON e.id = a.employee_id ORDER BY a.id");
    }

    public Map<String, Object> accountOfEmployee(Long employeeId) {
        return jdbc.queryForList(
                "SELECT a.id, a.employee_id AS employeeId, e.name AS employeeName, e.department,"
                        + " a.username, a.enabled, a.last_login_at AS lastLoginAt, a.created_at AS createdAt"
                        + " FROM login_accounts a JOIN employees e ON e.id=a.employee_id WHERE a.employee_id=?",
                employeeId).stream().findFirst().orElse(null);
    }

    // ===================== 内部 =====================

    private void ensureEmployeeActive(Long employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM employees WHERE id=?", employeeId);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.getFirst().get("status"))) {
            throw new SecurityException("员工不存在或已停用，无法登录");
        }
    }

    private String issueToken(Long employeeId, String loginType, String externalUserId,
                              String ip, String userAgent) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthSession session = new AuthSession();
        session.setTokenHash(sha256(rawToken));
        session.setEmployeeId(employeeId);
        session.setLoginType(loginType);
        session.setExternalUserId(externalUserId);
        session.setIp(truncate(ip, 64));
        session.setUserAgent(truncate(userAgent, 500));
        session.setLastActiveAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, sessionTimeoutHours)));
        sessions.save(session);
        return rawToken;
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
