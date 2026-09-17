package com.liqiang.archive.repository;

import com.liqiang.archive.entity.WebsiteInquiry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WebsiteInquiryRepository extends JpaRepository<WebsiteInquiry, Long> {

    /**
     * 后台分页查询：按状态筛选 + 关键词搜索（联系人/公司/电话/关注产品）。
     * status 为 null 时查全部状态。
     */
    @Query("""
            SELECT i FROM WebsiteInquiry i
            WHERE (:status IS NULL OR i.status = :status)
              AND (
                :keyword IS NULL OR :keyword = ''
                OR LOWER(i.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.company) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.phone) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.productFocus) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(i.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            ORDER BY i.createdAt DESC
            """)
    Page<WebsiteInquiry> searchForAdmin(@Param("status") String status,
                                         @Param("keyword") String keyword,
                                         Pageable pageable);

    long countByStatus(String status);

    long countByCreatedAtAfter(LocalDateTime after);

    /** 查询待跟进（有下次联系时间且已到期）的询盘。 */
    List<WebsiteInquiry> findByNextContactAtBeforeAndStatusNot(LocalDateTime before, String excludeStatus);
}
