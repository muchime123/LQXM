package com.liqiang.archive.repository;

import com.liqiang.archive.entity.MediaAccountSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MediaAccountSnapshotRepository extends JpaRepository<MediaAccountSnapshot, Long> {

    List<MediaAccountSnapshot> findByAccountIdOrderBySnapshotDateDesc(Long accountId);

    boolean existsByAccountIdAndSnapshotDate(Long accountId, java.time.LocalDate snapshotDate);

    @Modifying
    @Query("DELETE FROM MediaAccountSnapshot s WHERE s.accountId = :accountId")
    void deleteAllByAccountId(@Param("accountId") Long accountId);
}
