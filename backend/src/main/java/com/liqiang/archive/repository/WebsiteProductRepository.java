package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebsiteProductRepository extends JpaRepository<WebsiteProduct, Long> {
    Page<WebsiteProduct> findByDeletedAtIsNull(Pageable pageable);

    Optional<WebsiteProduct> findByIdAndDeletedAtIsNull(Long id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);
}
