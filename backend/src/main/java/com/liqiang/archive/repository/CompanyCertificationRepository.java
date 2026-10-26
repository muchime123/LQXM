package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyCertification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CompanyCertificationRepository extends JpaRepository<CompanyCertification, Long> {

    @Query("SELECT c.category, COUNT(c) FROM CompanyCertification c GROUP BY c.category")
    List<Object[]> countByCategory();

    /** 查询 N 天内到期（expiry_date between today and today+days），排除长期有效 */
    @Query("SELECT c FROM CompanyCertification c WHERE c.isPermanent = false AND c.expiryDate IS NOT NULL AND c.expiryDate BETWEEN :from AND :to ORDER BY c.expiryDate ASC")
    List<CompanyCertification> findExpiringSoon(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
