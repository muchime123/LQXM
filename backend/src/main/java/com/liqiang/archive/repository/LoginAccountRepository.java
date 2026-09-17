package com.liqiang.archive.repository;

import com.liqiang.archive.entity.LoginAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface LoginAccountRepository extends JpaRepository<LoginAccount, Long> {

    Optional<LoginAccount> findByUsername(String username);

    Optional<LoginAccount> findByEmployeeId(Long employeeId);

    boolean existsByUsername(String username);

    boolean existsByEmployeeId(Long employeeId);

    List<LoginAccount> findByEmployeeIdIn(List<Long> employeeIds);
}
