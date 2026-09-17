package com.liqiang.archive.repository;

import com.liqiang.archive.entity.KnowledgeArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, Long> {

    List<KnowledgeArticle> findByCategoryIdAndStatusOrderBySortOrderAsc(Long categoryId, String status);

    List<KnowledgeArticle> findByStatusOrderBySortOrderAsc(String status);

    @Query("SELECT a FROM KnowledgeArticle a WHERE a.status = 'published' AND (a.title LIKE %:keyword% OR a.content LIKE %:keyword% OR a.tags LIKE %:keyword%) ORDER BY a.sortOrder")
    List<KnowledgeArticle> searchByKeyword(String keyword);

    List<KnowledgeArticle> findByCategoryIdOrderBySortOrderAsc(Long categoryId);

    List<KnowledgeArticle> findAllByOrderBySortOrderAsc();

    @Query("SELECT a FROM KnowledgeArticle a WHERE (a.title LIKE %:keyword% OR a.content LIKE %:keyword% OR a.tags LIKE %:keyword%) ORDER BY a.sortOrder")
    List<KnowledgeArticle> searchByKeywordAllStatus(String keyword);

    /** 官网展示的文章：已发布 + 官网可见，按 sortOrder 升序。 */
    List<KnowledgeArticle> findByStatusAndWebsiteVisibleTrueOrderBySortOrderAsc(String status);

    /** 官网展示且属于指定分类的文章。 */
    List<KnowledgeArticle> findByCategoryIdAndStatusAndWebsiteVisibleTrueOrderBySortOrderAsc(Long categoryId, String status);

    /** 官网展示的文章按关键词检索。 */
    @Query("SELECT a FROM KnowledgeArticle a WHERE a.status = 'published' AND a.websiteVisible = true "
            + "AND (a.title LIKE %:keyword% OR a.content LIKE %:keyword% OR a.tags LIKE %:keyword%) ORDER BY a.sortOrder")
    List<KnowledgeArticle> searchByKeywordWebsiteVisible(String keyword);
}
