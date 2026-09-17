package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PlatformConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlatformConfigRepository extends JpaRepository<PlatformConfig, Long> {

    List<PlatformConfig> findAllByOrderBySortOrderAsc();

    @Query("SELECT p FROM PlatformConfig p WHERE p.isActive = true ORDER BY p.sortOrder ASC")
    List<PlatformConfig> findAllActive();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
