package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.repository.WebsiteSocialLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteSocialLinkServiceTest {
    @Test
    void createsDraftAndPublishesExplicitly() {
        var repository = mock(WebsiteSocialLinkRepository.class);
        var audit = mock(AuditLogService.class);
        var service = new WebsiteSocialLinkService(repository, audit);
        when(repository.save(any(WebsiteSocialLink.class))).thenAnswer(call -> {
            WebsiteSocialLink link = call.getArgument(0);
            link.setId(7L);
            return link;
        });
        var draft = service.create(new WebsiteSocialLinkService.Fields(" 抖音 ", " 官方号 ", " https://example.com/a ", 1));
        assertEquals("DRAFT", draft.getStatus());
        assertEquals("官方号", draft.getAccountName());
        when(repository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(draft));
        service.publish(7L);
        assertEquals("PUBLISHED", draft.getStatus());
        assertNotNull(draft.getPublishedAt());
        verify(audit).log("WebsiteSocialLink", 7L, "官方号", "WEBSITE_SOCIAL_LINK_PUBLISH", "发布官网官方链接");
    }

    @Test
    void blocksUnsafeUrlAndPublishedDraftEdit() {
        var repository = mock(WebsiteSocialLinkRepository.class);
        var service = new WebsiteSocialLinkService(repository, mock(AuditLogService.class));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(new WebsiteSocialLinkService.Fields("抖音", "号", "javascript:alert(1)", 0))).getStatusCode());
        verify(repository, never()).save(any());
        var published = new WebsiteSocialLink();
        published.setStatus("PUBLISHED");
        when(repository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(published));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(2L, new WebsiteSocialLinkService.Fields("抖音", "号", "https://example.com", 0))).getStatusCode());
    }
}
