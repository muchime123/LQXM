package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsitePackagingVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface WebsitePackagingVariantRepository extends JpaRepository<WebsitePackagingVariant, Long> {
    List<WebsitePackagingVariant> findByItemIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long itemId);

    Optional<WebsitePackagingVariant> findByIdAndItemIdAndDeletedAtIsNull(Long id, Long itemId);

    List<WebsitePackagingVariant> findByItemIdInAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Collection<Long> itemIds);
}
