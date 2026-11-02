package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.SolutionDtos.SolutionView;
import com.liqiang.archive.dto.SolutionDtos.SolutionPayload;
import com.liqiang.archive.entity.WebsiteSolution;
import com.liqiang.archive.entity.WebsiteSolution.SolutionSection;
import com.liqiang.archive.repository.WebsiteSolutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebsiteSolutionServiceTest {

    private final WebsiteSolutionRepository repository = mock(WebsiteSolutionRepository.class);
    private final WebsiteProductPublicationService publication = mock(WebsiteProductPublicationService.class);
    private final WebsiteSolutionService service = new WebsiteSolutionService(repository, new ObjectMapper(), publication);

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

    @Test
    void publishedSolutionPromotesOnlyReferencedWebsiteMedia() {
        when(repository.save(any(WebsiteSolution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Example",
                null, null, "/api/website/media/12/content", null, null, null, null,
                null, null, "<p><img src=\"/api/website/media/34/content\"></p>",
                null, null, null, 0, true, false);

        service.create(payload);

        verify(publication).publishMedia(12L);
        verify(publication).publishMedia(34L);
    }

    @Test
    void newlyPublishedSolutionCannotReferenceArchiveFiles() {
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "archive-image", "Example",
                null, null, "/api/file-assets/company/assets/5/preview", null, null, null, null,
                null, null, null, null, null, null, 0, true, false);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.create(payload)).getStatusCode());
        verify(repository, never()).save(any());
        verifyNoInteractions(publication);
    }

    @Test
    void enablingDraftCannotPublishArchiveImageInStructuredContent() {
        WebsiteSolution draft = article();
        draft.setWebsiteEnabled(false);
        draft.setContentSections("[{\"imageUrl\":\"/api/files/company/old.png\"}]");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(draft));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateWebsiteEnabled(List.of(1L), true)).getStatusCode());
        assertFalse(draft.getWebsiteEnabled());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void editingDraftIntoPublishedSolutionRejectsArchiveFileInHtml() {
        WebsiteSolution draft = article();
        draft.setWebsiteEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(draft));
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Example",
                null, null, null, null, null, null, null, null, null,
                "<img src=\"/api/file-assets/product_model/assets/7/thumbnail\">",
                null, null, null, 0, true, false);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(1L, payload)).getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void publishedSolutionCannotAddNewArchiveFileButCanKeepExistingLegacyImage() {
        WebsiteSolution published = article();
        published.setCoverImageUrl("/api/files/company/existing.png");
        published.setContentSections(null);
        when(repository.findById(1L)).thenReturn(Optional.of(published));
        when(repository.save(any(WebsiteSolution.class))).thenAnswer(call -> call.getArgument(0));
        SolutionPayload textOnly = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Updated",
                null, null, "/api/files/company/existing.png", null, null, null, null,
                null, null, null, null, null, null, 0, true, false);
        service.update(1L, textOnly);

        SolutionPayload newArchiveImage = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Updated",
                null, null, "/api/files/company/new.png", null, null, null, null,
                null, null, null, null, null, null, 0, true, false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(1L, newArchiveImage)).getStatusCode());
        verify(repository, times(1)).save(any());
    }

    @Test
    void publishedLegacyStructuredSectionPromotesItsWebsiteImage() {
        when(repository.save(any(WebsiteSolution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "structured", "Structured",
                null, null, null, null, null, null, null,
                null, List.of(new com.liqiang.archive.dto.SolutionDtos.ContentSection(
                        "Step", null, null, "/api/website/media/56/content")), null,
                null, null, null, 0, true, false);

        service.create(payload);

        verify(publication).publishMedia(56L);
    }

    @Test
    void draftSolutionDoesNotPublishReferencedMedia() {
        when(repository.save(any(WebsiteSolution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Example",
                null, null, "/api/website/media/12/content", null, null, null, null,
                null, null, null, null, null, null, 0, false, false);

        service.create(payload);

        verifyNoInteractions(publication);
    }

    @Test
    void enablingExistingDraftPublishesItsReferencedMedia() {
        WebsiteSolution draft = article();
        draft.setWebsiteEnabled(false);
        draft.setCoverImageUrl("/api/website/media/45/content");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(draft));
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.batchUpdateWebsiteEnabled(List.of(1L), true);

        assertEquals(true, draft.getWebsiteEnabled());
        verify(publication).publishMedia(45L);
    }

    @Test
    void disablingPublishedSolutionDoesNotChangeMediaPublication() {
        WebsiteSolution article = article();
        article.setCoverImageUrl("/api/website/media/45/content");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(article));
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.batchUpdateWebsiteEnabled(List.of(1L), false);

        assertEquals(false, article.getWebsiteEnabled());
        verifyNoInteractions(publication);
    }

    @Test
    void newSolutionWithoutPublicationFlagRemainsDraft() {
        when(repository.save(any(WebsiteSolution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Example",
                null, null, "/api/website/media/12/content", null, null, null, null,
                null, null, null, null, null, null, 0, null, false);

        SolutionView created = service.create(payload);

        assertEquals(false, created.websiteEnabled());
        verifyNoInteractions(publication);
    }

    @Test
    void updateWithoutPublicationFlagPreservesCurrentState() {
        WebsiteSolution existing = article();
        existing.setWebsiteEnabled(false);
        existing.setCoverImageUrl("/api/website/media/12/content");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(WebsiteSolution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SolutionPayload payload = new SolutionPayload(SolutionSection.ARTICLE, null, "example", "Updated",
                null, null, "/api/website/media/12/content", null, null, null, null,
                null, null, null, null, null, null, 0, null, false);

        SolutionView updated = service.update(1L, payload);

        assertEquals(false, updated.websiteEnabled());
        verifyNoInteractions(publication);
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
