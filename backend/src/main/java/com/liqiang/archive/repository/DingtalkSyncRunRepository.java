package com.liqiang.archive.repository;

import com.liqiang.archive.entity.DingtalkSyncRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DingtalkSyncRunRepository extends JpaRepository<DingtalkSyncRun, Long> {

    Page<DingtalkSyncRun> findBySyncTypeOrderByStartedAtDesc(String syncType, Pageable pageable);

    @Query("SELECT r FROM DingtalkSyncRun r WHERE r.syncType = :syncType ORDER BY r.startedAt DESC LIMIT 1")
    Optional<DingtalkSyncRun> findLatestBySyncType(String syncType);

    default Optional<DingtalkSyncRun> findLatestReimbursement() {
        return findLatestBySyncType("DAILY_REIMBURSEMENT");
    }
}
