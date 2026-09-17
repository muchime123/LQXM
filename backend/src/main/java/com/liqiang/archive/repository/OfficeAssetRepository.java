package com.liqiang.archive.repository;

import com.liqiang.archive.entity.OfficeAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OfficeAssetRepository extends JpaRepository<OfficeAsset, Long> {
    Optional<OfficeAsset> findByAssetCodeIgnoreCase(String assetCode);
    boolean existsByAssetCodeIgnoreCase(String assetCode);

    @Query("""
        SELECT a FROM OfficeAsset a
        WHERE (:keyword IS NULL OR LOWER(a.assetCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(COALESCE(a.serialNumber, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(COALESCE(a.custodian, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:category IS NULL OR a.category = :category)
          AND (:status IS NULL OR a.status = :status)
          AND (:department IS NULL OR a.department = :department)
        """)
    Page<OfficeAsset> search(@Param("keyword") String keyword, @Param("category") String category,
                             @Param("status") String status, @Param("department") String department,
                             Pageable pageable);

    long countByStatus(String status);
}
