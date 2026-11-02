package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

public interface WebsiteCredentialRepository extends JpaRepository<WebsiteCredential, Long> {
    List<WebsiteCredential> findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    Optional<WebsiteCredential> findByIdAndDeletedAtIsNull(Long id);
    List<WebsiteCredential> findByStatusAndDeletedAtIsNullAndValidUntilGreaterThanEqualOrderBySortOrderAscIdAsc(String status, LocalDate today);
    List<WebsiteCredential> findByStatusAndDeletedAtIsNullAndValidUntilIsNullOrderBySortOrderAscIdAsc(String status);
    boolean existsByMediaIdAndStatusAndDeletedAtIsNullAndValidUntilGreaterThanEqual(Long mediaId, String status, LocalDate today);
    boolean existsByMediaIdAndStatusAndDeletedAtIsNullAndValidUntilIsNull(Long mediaId, String status);
}
