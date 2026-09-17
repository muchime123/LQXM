package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteBanner;
import com.liqiang.archive.entity.WebsiteBanner.BannerPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WebsiteBannerRepository extends JpaRepository<WebsiteBanner, Long> {

    List<WebsiteBanner> findByPositionAndDeletedAtIsNullOrderBySortOrderAscIdAsc(
            BannerPosition position);

    List<WebsiteBanner> findByDeletedAtIsNullOrderByPositionAscSortOrderAscIdAsc();

    @Query("SELECT b FROM WebsiteBanner b " +
           "WHERE b.position = :position " +
           "AND b.websiteEnabled = true " +
           "AND b.deletedAt IS NULL " +
           "AND (b.startDate IS NULL OR b.startDate <= CURRENT_DATE) " +
           "AND (b.endDate IS NULL OR b.endDate >= CURRENT_DATE) " +
           "ORDER BY b.sortOrder ASC, b.id ASC")
    List<WebsiteBanner> findActiveByPosition(@Param("position") BannerPosition position);

    @Query("SELECT b FROM WebsiteBanner b " +
           "WHERE b.websiteEnabled = true " +
           "AND b.deletedAt IS NULL " +
           "AND (b.startDate IS NULL OR b.startDate <= CURRENT_DATE) " +
           "AND (b.endDate IS NULL OR b.endDate >= CURRENT_DATE) " +
           "ORDER BY b.position ASC, b.sortOrder ASC, b.id ASC")
    List<WebsiteBanner> findAllActive();

    long countByPositionAndDeletedAtIsNull(BannerPosition position);
}
