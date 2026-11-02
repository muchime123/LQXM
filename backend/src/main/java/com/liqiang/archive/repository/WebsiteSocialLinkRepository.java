package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteSocialLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebsiteSocialLinkRepository extends JpaRepository<WebsiteSocialLink, Long> {
    List<WebsiteSocialLink> findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    List<WebsiteSocialLink> findByStatusAndDeletedAtIsNullOrderBySortOrderAscIdAsc(String status);
    Optional<WebsiteSocialLink> findByIdAndDeletedAtIsNull(Long id);
}
