package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebsiteMediaAssetRepository extends JpaRepository<WebsiteMediaAsset, Long> {
    Page<WebsiteMediaAsset> findByDeletedAtIsNull(Pageable pageable);

    Optional<WebsiteMediaAsset> findByIdAndDeletedAtIsNull(Long id);

    Optional<WebsiteMediaAsset> findByStorageKeyAndDeletedAtIsNull(String storageKey);
}
