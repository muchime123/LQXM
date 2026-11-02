package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.repository.WebsiteCompanyMilestoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteCompanyMilestoneServiceTest {
    @Test
    void createsIndependentDraftAndPublishesExplicitly() {
        var repository = mock(WebsiteCompanyMilestoneRepository.class);
        var audit = mock(AuditLogService.class);
        var service = new WebsiteCompanyMilestoneService(repository, audit);
        when(repository.save(any(WebsiteCompanyMilestone.class))).thenAnswer(call -> {
            WebsiteCompanyMilestone item = call.getArgument(0);
            item.setId(7L);
            return item;
        });
        var draft = service.create(new WebsiteCompanyMilestoneService.Fields(2020, " 新厂投产 ", " 介绍 ", 2));
        assertEquals("DRAFT", draft.getStatus());
        assertEquals("新厂投产", draft.getTitle());
        when(repository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(draft));
        service.publish(7L);
        assertEquals("PUBLISHED", draft.getStatus());
        assertNotNull(draft.getPublishedAt());
        assertEquals(List.of(), service.published());
        verify(repository).findByStatusAndDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc("PUBLISHED");
    }

    @Test
    void rejectsInvalidAndPublishedEdits() {
        var repository = mock(WebsiteCompanyMilestoneRepository.class);
        var service = new WebsiteCompanyMilestoneService(repository, mock(AuditLogService.class));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(new WebsiteCompanyMilestoneService.Fields(2020, " ", null, 0))).getStatusCode());
        verify(repository, never()).save(any());
        var published = new WebsiteCompanyMilestone();
        published.setStatus("PUBLISHED");
        when(repository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(published));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(2L, new WebsiteCompanyMilestoneService.Fields(2020, "标题", null, 0))).getStatusCode());
    }
}
