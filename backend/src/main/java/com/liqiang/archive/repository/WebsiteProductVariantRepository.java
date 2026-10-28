package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebsiteProductVariantRepository extends JpaRepository<WebsiteProductVariant, Long> {
    List<WebsiteProductVariant> findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long productId);

    Optional<WebsiteProductVariant> findByIdAndProductIdAndDeletedAtIsNull(Long id, Long productId);
}
