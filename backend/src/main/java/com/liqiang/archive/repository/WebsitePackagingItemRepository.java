package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsitePackagingItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface WebsitePackagingItemRepository extends JpaRepository<WebsitePackagingItem, Long> {
    Page<WebsitePackagingItem> findByDeletedAtIsNull(Pageable pageable);

    java.util.List<WebsitePackagingItem> findByDeletedAtIsNullOrderByIdAsc();

    Optional<WebsitePackagingItem> findByIdAndDeletedAtIsNull(Long id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    List<WebsitePackagingItem> findByDeletedAtIsNullAndStatusOrderBySortOrderAscIdAsc(String status);

    boolean existsByCoverMediaIdAndStatusAndDeletedAtIsNull(Long mediaId, String status);
}
