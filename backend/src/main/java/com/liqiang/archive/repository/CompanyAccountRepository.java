package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompanyAccountRepository extends JpaRepository<CompanyAccount, Long> {

    List<CompanyAccount> findAllByOrderBySortOrderAsc();
}
