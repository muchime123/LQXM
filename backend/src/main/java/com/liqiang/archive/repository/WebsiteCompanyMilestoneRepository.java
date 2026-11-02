package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebsiteCompanyMilestoneRepository extends JpaRepository<WebsiteCompanyMilestone, Long> {
    List<WebsiteCompanyMilestone> findByDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc();
    List<WebsiteCompanyMilestone> findByStatusAndDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc(String status);
    Optional<WebsiteCompanyMilestone> findByIdAndDeletedAtIsNull(Long id);
}
