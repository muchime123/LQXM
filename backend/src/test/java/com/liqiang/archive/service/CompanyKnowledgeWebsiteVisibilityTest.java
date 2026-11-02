package com.liqiang.archive.service;

import com.liqiang.archive.entity.KnowledgeArticle;
import com.liqiang.archive.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanyKnowledgeWebsiteVisibilityTest {
    private final KnowledgeArticleRepository articles = mock(KnowledgeArticleRepository.class);
    private final CompanyInfoService service = new CompanyInfoService(
            mock(CompanyInfoRepository.class), mock(CompanyCertificationRepository.class),
            mock(CompanyAccountRepository.class), mock(CompanyAssetRepository.class),
            mock(KnowledgeCategoryRepository.class), articles, mock(CertificateRepository.class),
            mock(MediaAccountRepository.class), mock(CompanyMilestoneRepository.class),
            mock(FileStorageService.class), mock(AuditLogService.class));

    @Test
    void newInternalArticleCannotEnableLegacyWebsiteExposure() {
        KnowledgeArticle incoming = new KnowledgeArticle();
        incoming.setTitle("内部文章");
        incoming.setWebsiteVisible(true);
        when(articles.save(any())).thenAnswer(call -> call.getArgument(0));

        KnowledgeArticle saved = service.createKbArticle(incoming);

        assertFalse(saved.getWebsiteVisible());
    }

    @Test
    void editingInternalArticlePreservesExistingLegacyExposureFlag() {
        KnowledgeArticle existing = new KnowledgeArticle();
        existing.setId(7L);
        existing.setTitle("原文章");
        existing.setWebsiteVisible(true);
        KnowledgeArticle incoming = new KnowledgeArticle();
        incoming.setTitle("更新文章");
        incoming.setWebsiteVisible(false);
        when(articles.findById(7L)).thenReturn(Optional.of(existing));
        when(articles.save(existing)).thenReturn(existing);

        KnowledgeArticle saved = service.updateKbArticle(7L, incoming);

        assertEquals("更新文章", saved.getTitle());
        assertTrue(saved.getWebsiteVisible());
    }

    @Test
    void legacyVisibleArticleCannotBeHardDeleted() {
        KnowledgeArticle existing = new KnowledgeArticle();
        existing.setId(7L);
        existing.setWebsiteVisible(true);
        when(articles.findById(7L)).thenReturn(Optional.of(existing));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.deleteKbArticle(7L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(articles, never()).deleteById(7L);
    }

    @Test
    void internalOnlyArticleCanStillBeDeleted() {
        KnowledgeArticle existing = new KnowledgeArticle();
        existing.setId(8L);
        existing.setTitle("内部草稿");
        existing.setWebsiteVisible(false);
        when(articles.findById(8L)).thenReturn(Optional.of(existing));

        service.deleteKbArticle(8L);

        verify(articles).deleteById(8L);
    }
}
