package com.liqiang.archive.repository;

import com.liqiang.archive.entity.OfficeAssetRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OfficeAssetRecordRepository extends JpaRepository<OfficeAssetRecord, Long> {
    List<OfficeAssetRecord> findByAssetIdOrderByCreatedAtDesc(Long assetId);
}
