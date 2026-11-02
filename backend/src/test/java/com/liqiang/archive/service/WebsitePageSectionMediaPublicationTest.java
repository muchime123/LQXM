package com.liqiang.archive.service;

import com.liqiang.archive.dto.PageSectionDtos.PageSectionPayload;
import com.liqiang.archive.entity.WebsitePageSection;
import com.liqiang.archive.repository.WebsitePageSectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebsitePageSectionMediaPublicationTest {
    @Test
    void enabledSectionPublishesReferencedWebsiteMediaOnly() {
        var sections = mock(WebsitePageSectionRepository.class);
        var publication = mock(WebsiteProductPublicationService.class);
        var service = new WebsitePageSectionService(sections, mock(AuditLogService.class), publication);
        when(sections.save(any())).thenAnswer(call -> {
            WebsitePageSection item = call.getArgument(0);
            item.setId(7L);
            return item;
        });

        service.create(new PageSectionPayload("HOME", "intro", "Intro", null,
                "{\"image\":\"/api/website/media/9/content\"}",
                "/api/website/media/4/content", 1, true));
        verify(publication).publishMedia(4L);
        verify(publication).publishMedia(9L);

        clearInvocations(publication);
        service.create(new PageSectionPayload("HOME", "hidden", "Hidden", null,
                "{\"image\":\"/api/website/media/10/content\"}",
                "/api/website/media/11/content", 2, false));
        verifyNoInteractions(publication);
    }

    @Test
    void restoringEnabledSectionRechecksItsWebsiteMedia() {
        var sections = mock(WebsitePageSectionRepository.class);
        var publication = mock(WebsiteProductPublicationService.class);
        var service = new WebsitePageSectionService(sections, mock(AuditLogService.class), publication);
        WebsitePageSection item = new WebsitePageSection();
        item.setId(8L);
        item.setPageKey("ABOUT");
        item.setSectionKey("intro");
        item.setEnabled(true);
        item.setImageUrl("/api/website/media/12/content");
        item.setDeletedAt(LocalDateTime.now());
        when(sections.findById(8L)).thenReturn(Optional.of(item));
        when(sections.save(item)).thenReturn(item);

        service.restore(8L);
        verify(publication).publishMedia(12L);
        verify(sections).save(item);
    }

    @Test
    void newEnabledSectionRejectsInternalArchiveImage() {
        var sections = mock(WebsitePageSectionRepository.class);
        var service = new WebsitePageSectionService(sections, mock(AuditLogService.class),
                mock(WebsiteProductPublicationService.class));

        var error = assertThrows(ResponseStatusException.class, () -> service.create(
                new PageSectionPayload("ABOUT", "factory", "Factory", null, null,
                        "/api/file-assets/company/assets/7/preview", 0, true)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(sections, never()).save(any());
    }

    @Test
    void enablingExistingDraftRejectsInternalArchiveImage() {
        var sections = mock(WebsitePageSectionRepository.class);
        var service = new WebsitePageSectionService(sections, mock(AuditLogService.class),
                mock(WebsiteProductPublicationService.class));
        WebsitePageSection draft = new WebsitePageSection();
        draft.setEnabled(false);
        draft.setImageUrl("/api/files/company/old.jpg");
        when(sections.findAllById(List.of(8L))).thenReturn(List.of(draft));

        var error = assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateEnabled(List.of(8L), true));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertFalse(draft.getEnabled());
        verify(sections, never()).saveAll(any());
    }

    @Test
    void publishedSectionCannotReplaceImageWithArchiveFile() {
        var sections = mock(WebsitePageSectionRepository.class);
        var service = new WebsitePageSectionService(sections, mock(AuditLogService.class),
                mock(WebsiteProductPublicationService.class));
        WebsitePageSection published = new WebsitePageSection();
        published.setId(8L);
        published.setPageKey("ABOUT");
        published.setSectionKey("factory");
        published.setEnabled(true);
        when(sections.findById(8L)).thenReturn(Optional.of(published));

        var error = assertThrows(ResponseStatusException.class, () -> service.update(8L,
                new PageSectionPayload("ABOUT", "factory", "Factory", null, null,
                        "/api/file-assets/company/assets/7/preview", 0, true)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(sections, never()).save(any());
    }

    @Test
    void retiredMoldPageIsHiddenButHistoricalSectionRemainsInAdminList() {
        var sections = mock(WebsitePageSectionRepository.class);
        var service = new WebsitePageSectionService(sections, mock(AuditLogService.class),
                mock(WebsiteProductPublicationService.class));
        WebsitePageSection old = new WebsitePageSection();
        old.setId(9L);
        old.setPageKey("MOLDS");
        old.setSectionKey("intro");
        old.setEnabled(true);
        when(sections.findByDeletedAtIsNullOrderByPageKeyAscSortOrderAscIdAsc()).thenReturn(List.of(old));

        assertTrue(service.listPublic("MOLDS").isEmpty());
        verify(sections, never()).findByPageKeyAndEnabledTrueAndDeletedAtIsNullOrderBySortOrderAscIdAsc("MOLDS");
        assertEquals(9L, service.listAll().get(0).id());
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new PageSectionPayload("MOLDS", "new", "New", null, null, null, 0, true)));
    }
}
