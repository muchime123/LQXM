package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyAssetCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyAssetCategoryRepository extends JpaRepository<CompanyAssetCategory, Long> {

    List<CompanyAssetCategory> findAllByOrderBySortOrderAsc();

    Optional<CompanyAssetCategory> findByCode(String code);
}
