package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebsiteProductCategoryRepository extends JpaRepository<WebsiteProductCategory, Long> {
    List<WebsiteProductCategory> findByDeletedAtIsNullOrderBySortOrderAscIdAsc();

    Optional<WebsiteProductCategory> findByIdAndDeletedAtIsNull(Long id);

    long countByDeletedAtIsNullAndStatus(String status);

    boolean existsBySlug(String slug);
}
