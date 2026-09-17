package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingMoldHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackagingMoldHistoryRepository extends JpaRepository<PackagingMoldHistory, Long> {

    List<PackagingMoldHistory> findByMoldIdOrderByCreatedAtDescIdDesc(Long moldId);
}
