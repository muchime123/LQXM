package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsitePageSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebsitePageSectionRepository extends JpaRepository<WebsitePageSection, Long> {

    /** 官网公开区块：某页面下所有启用且未删除的区块，按 sortOrder 升序。 */
    List<WebsitePageSection> findByPageKeyAndEnabledTrueAndDeletedAtIsNullOrderBySortOrderAscIdAsc(String pageKey);

    Optional<WebsitePageSection> findByPageKeyAndSectionKeyAndDeletedAtIsNull(String pageKey, String sectionKey);

    boolean existsByPageKeyAndSectionKeyAndDeletedAtIsNull(String pageKey, String sectionKey);

    /** 页面内是否已有其它未删除记录占用该 sectionKey（排除自身）。 */
    boolean existsByPageKeyAndSectionKeyAndIdNotAndDeletedAtIsNull(String pageKey, String sectionKey, Long id);

    /** 管理端列表：列出未删除的全部区块（sort 升序）。 */
    List<WebsitePageSection> findByDeletedAtIsNullOrderByPageKeyAscSortOrderAscIdAsc();
}
