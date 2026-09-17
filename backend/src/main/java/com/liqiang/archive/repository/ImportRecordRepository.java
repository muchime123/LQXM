package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ImportRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRecordRepository extends JpaRepository<ImportRecord, Long> {
}
