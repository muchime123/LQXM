package com.liqiang.archive.service;

import com.liqiang.archive.entity.Employee;
import com.liqiang.archive.entity.LoginAccount;
import com.liqiang.archive.repository.EmployeeRepository;
import com.liqiang.archive.repository.LoginAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按员工手机号维护默认本地登录账号。
 * 新账号初始密码为 123456；已有账号只同步手机号用户名，不重置已有密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLoginAccountService {

    private static final String INITIAL_PASSWORD = "123456";

    private final EmployeeRepository employeeRepository;
    private final LoginAccountRepository loginAccountRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void provisionExistingEmployees() {
        if (loginAccountRepository.count() == 0) {
            log.info("系统尚无首位管理员账号，暂缓批量创建员工账号");
            return;
        }
        int created = 0;
        int skipped = 0;
        for (Employee employee : employeeRepository.findAll()) {
            try {
                if (synchronize(employee)) created++;
                else if (loginAccountRepository.findByEmployeeId(employee.getId()).isEmpty()) skipped++;
            } catch (IllegalArgumentException ex) {
                skipped++;
                log.warn(ex.getMessage());
            }
        }
        log.info("员工默认账号检查完成：新增 {}，因手机号为空或冲突跳过 {}", created, skipped);
    }

    /** @return 是否新建了账号。 */
    @Transactional
    public boolean synchronize(Employee employee) {
        if (employee == null || employee.getId() == null) return false;

        LoginAccount account = loginAccountRepository.findByEmployeeId(employee.getId()).orElse(null);
        String phone = normalizePhone(employee.getPhone());
        if (phone == null) {
            if (account != null && "ACTIVE".equals(employee.getStatus())) {
                log.warn("员工 {}（id={}）没有手机号，无法按手机号维护登录账号", employee.getName(), employee.getId());
            }
            if (account != null && !"ACTIVE".equals(employee.getStatus())) account.setEnabled(false);
            return false;
        }

        // 离职/停薪留职员工如尚无账号，不再补建一个无实际用途的停用账号。
        if (account == null && !"ACTIVE".equals(employee.getStatus())) return false;

        LoginAccount occupied = loginAccountRepository.findByUsername(phone).orElse(null);
        if (occupied != null && !occupied.getEmployeeId().equals(employee.getId())) {
            throw new IllegalArgumentException("手机号 " + phone + " 已被其他员工用作登录账号");
        }

        if (account == null) {
            account = new LoginAccount();
            account.setEmployeeId(employee.getId());
            account.setUsername(phone);
            account.setPasswordHash(passwordEncoder.encode(INITIAL_PASSWORD));
            account.setEnabled("ACTIVE".equals(employee.getStatus()));
            account.setCreatedBy("EMPLOYEE_DEFAULT_ACCOUNT");
            loginAccountRepository.save(account);
            return true;
        }

        account.setUsername(phone);
        if (!"ACTIVE".equals(employee.getStatus())) account.setEnabled(false);
        loginAccountRepository.save(account);
        return false;
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String normalized = phone.trim().replace(" ", "").replace("-", "");
        return normalized.isBlank() ? null : normalized;
    }
}
