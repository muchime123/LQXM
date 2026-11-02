package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteIpItem;
import com.liqiang.archive.repository.WebsiteIpItemRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebsiteIpItemServiceTest {
    @Test
    void unpublishedStaticStyleClaimCannotBePublishedWithoutEvidence() {
        WebsiteIpItemRepository records = mock(WebsiteIpItemRepository.class);
        WebsiteMediaAssetRepository media = mock(WebsiteMediaAssetRepository.class);
        WebsiteIpItemService service = new WebsiteIpItemService(records, media, mock(AuditLogService.class));
        WebsiteIpItem item = new WebsiteIpItem();
        item.setId(1L);
        item.setName("气调保鲜包装机");
        item.setStatus("DRAFT");
        when(records.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.publish(1L));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(media);
        verify(records, never()).save(any());

        item.setPublicNumber("CN000000");
        item.setApplicant("申请人");
        item.setLegalStatus("审中待授权");
        item.setMediaId(9L);
        ResponseStatusException pending = assertThrows(ResponseStatusException.class, () -> service.publish(1L));
        assertEquals(HttpStatus.CONFLICT, pending.getStatusCode());
        verifyNoInteractions(media);
        verify(records, never()).save(any());
    }
}
