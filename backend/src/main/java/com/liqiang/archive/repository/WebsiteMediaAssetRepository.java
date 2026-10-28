package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebsiteMediaAssetRepository extends JpaRepository<WebsiteMediaAsset, Long> {
    Page<WebsiteMediaAsset> findByDeletedAtIsNull(Pageable pageable);

    Page<WebsiteMediaAsset> findByDeletedAtIsNullAndMediaTypeAndStatusNot(
            String mediaType, String status, Pageable pageable);

    Optional<WebsiteMediaAsset> findByIdAndDeletedAtIsNull(Long id);

    long countByDeletedAtIsNullAndStatus(String status);

    Optional<WebsiteMediaAsset> findByStorageKeyAndDeletedAtIsNull(String storageKey);
}
