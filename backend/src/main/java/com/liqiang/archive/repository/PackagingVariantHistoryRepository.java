package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingVariantHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackagingVariantHistoryRepository extends JpaRepository<PackagingVariantHistory, Long> {

    List<PackagingVariantHistory> findByVariantIdOrderByCreatedAtDescIdDesc(Long variantId);
}
