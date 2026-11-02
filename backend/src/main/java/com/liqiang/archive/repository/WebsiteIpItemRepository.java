package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteIpItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WebsiteIpItemRepository extends JpaRepository<WebsiteIpItem, Long> {
    List<WebsiteIpItem> findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    List<WebsiteIpItem> findByStatusAndDeletedAtIsNullOrderBySortOrderAscIdAsc(String status);
    Optional<WebsiteIpItem> findByIdAndDeletedAtIsNull(Long id);
    boolean existsByMediaIdAndStatusAndDeletedAtIsNull(Long mediaId, String status);
}
