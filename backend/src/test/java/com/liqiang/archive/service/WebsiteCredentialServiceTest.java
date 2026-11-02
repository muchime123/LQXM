package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCredential;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteCredentialRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebsiteCredentialServiceTest {
    @TempDir Path fileRoot;
    private final WebsiteCredentialRepository records = mock(WebsiteCredentialRepository.class);
    private final WebsiteMediaAssetRepository media = mock(WebsiteMediaAssetRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebsiteCredentialService service = new WebsiteCredentialService(records, media, audit);

    @Test void createsDraftWithWebsiteMediaOnly() {
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE"); image.setStatus("DRAFT");
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(image));
        when(records.save(any())).thenAnswer(call -> { WebsiteCredential item = call.getArgument(0); item.setId(8L); return item; });
        WebsiteCredential created = service.create(new WebsiteCredentialService.Fields(" ISO 9001 ", "管理体系", null, null, null, 4L, 1));
        assertEquals("DRAFT", created.getStatus());
        assertEquals("ISO 9001", created.getName());
        assertEquals(4L, created.getMediaId());
    }

    @Test void publishedRecordCannotBeEdited() {
        WebsiteCredential item = new WebsiteCredential(); item.setStatus("PUBLISHED");
        when(records.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(item));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(8L, new WebsiteCredentialService.Fields("ISO 9001", null, null, null, null, null, 0))).getStatusCode());
        verify(records, never()).save(any());
    }

    @Test void expiredCredentialCannotPublish() {
        WebsiteCredential item = new WebsiteCredential();
        item.setName("旧证书"); item.setMediaId(4L);
        item.setValidUntil(java.time.LocalDate.now().minusDays(1));
        when(records.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(item));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(8L)).getStatusCode());
        verify(media, never()).save(any());
    }

    @Test void publishAndUnpublishPreserveWebsiteImage() {
        WebsiteCredential item = new WebsiteCredential();
        item.setId(8L); item.setName("ISO 9001"); item.setMediaId(4L);
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE"); image.setStatus("DRAFT");
        image.setStorageKey("website/images/certificate.png");
        ReflectionTestUtils.setField(service, "fileRoot", fileRoot.toString());
        try {
            Path imageFile = fileRoot.resolve(image.getStorageKey());
            Files.createDirectories(imageFile.getParent());
            Files.write(imageFile, new byte[] {1, 2, 3});
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        when(records.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(item));
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(image));
        when(records.save(item)).thenReturn(item);
        service.publish(8L);
        assertEquals("PUBLISHED", item.getStatus());
        assertEquals("PUBLISHED", image.getStatus());
        service.unpublish(8L);
        assertEquals("DRAFT", item.getStatus());
        assertEquals(4L, item.getMediaId());
        assertNull(item.getPublishedAt());
    }

    @Test void missingOrExternalImageCannotPublish() {
        WebsiteCredential item = new WebsiteCredential();
        item.setId(8L); item.setName("ISO 9001"); item.setMediaId(4L);
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE"); image.setStatus("DRAFT");
        when(records.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(item));
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(image));
        ReflectionTestUtils.setField(service, "fileRoot", fileRoot.toString());
        image.setStorageKey("website/images/missing.png");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(8L)).getStatusCode());
        image.setStorageKey("company/internal.png");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(8L)).getStatusCode());
        verify(media, never()).save(any());
        verify(records, never()).save(any());
    }
}
