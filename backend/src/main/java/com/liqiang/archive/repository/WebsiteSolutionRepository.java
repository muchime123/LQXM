package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteSolution;
import com.liqiang.archive.entity.WebsiteSolution.SolutionSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebsiteSolutionRepository extends JpaRepository<WebsiteSolution, Long> {

    List<WebsiteSolution> findBySectionAndWebsiteEnabledTrueOrderBySortOrderAsc(SolutionSection section);

    List<WebsiteSolution> findBySectionOrderBySortOrderAsc(SolutionSection section);

    List<WebsiteSolution> findAllByOrderBySectionAscSortOrderAsc();

    Optional<WebsiteSolution> findBySlugAndWebsiteEnabledTrue(String slug);

    boolean existsByWebsiteEnabledTrueAndCoverImageUrl(String coverImageUrl);

    boolean existsByWebsiteEnabledTrueAndContentHtmlContaining(String mediaUrl);

    boolean existsByWebsiteEnabledTrueAndContentSectionsContaining(String mediaUrl);

}
