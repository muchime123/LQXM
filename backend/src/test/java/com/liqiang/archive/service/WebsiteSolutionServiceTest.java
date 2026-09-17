package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.SolutionDtos.SolutionView;
import com.liqiang.archive.entity.WebsiteSolution;
import com.liqiang.archive.entity.WebsiteSolution.SolutionSection;
import com.liqiang.archive.repository.WebsiteSolutionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebsiteSolutionServiceTest {

    private final WebsiteSolutionRepository repository = mock(WebsiteSolutionRepository.class);
    private final WebsiteSolutionService service = new WebsiteSolutionService(repository, new ObjectMapper());

    @Test
    void publishedArticlesDeserializeStructuredContentAndScenes() {
        WebsiteSolution article = article();
        when(repository.findBySectionAndWebsiteEnabledTrueOrderBySortOrderAsc(SolutionSection.ARTICLE))
                .thenReturn(List.of(article));

        List<SolutionView> result = service.listPublishedArticles();

        assertEquals(1, result.size());
        assertEquals("tray-sealing", result.getFirst().slug());
        assertEquals("排查封口问题", result.getFirst().contentSections().getFirst().title());
        assertEquals(List.of("fresh-meat"), result.getFirst().relatedSceneKeys());
        verify(repository).findBySectionAndWebsiteEnabledTrueOrderBySortOrderAsc(SolutionSection.ARTICLE);
    }

    @Test
    void publishedArticleRejectsNonArticleRows() {
        WebsiteSolution scene = article();
        scene.setSection(SolutionSection.INDUSTRY);
        when(repository.findBySlugAndWebsiteEnabledTrue("tray-sealing")).thenReturn(Optional.of(scene));

        assertThrows(IllegalArgumentException.class, () -> service.getPublishedArticle("tray-sealing"));
    }

    private WebsiteSolution article() {
        WebsiteSolution article = new WebsiteSolution();
        article.setId(1L);
        article.setSection(SolutionSection.ARTICLE);
        article.setItemKey("tray-sealing");
        article.setSlug("tray-sealing");
        article.setTitle("托盒封口排查");
        article.setCategory("问题诊断");
        article.setSummary("摘要");
        article.setContentSections("[{\"title\":\"排查封口问题\",\"body\":\"正文\",\"points\":[\"盒\",\"膜\"]}]");
        article.setRelatedSceneKeys("[\"fresh-meat\"]");
        article.setSortOrder(1);
        article.setWebsiteEnabled(true);
        article.setWebsiteFeatured(true);
        return article;
    }
}
