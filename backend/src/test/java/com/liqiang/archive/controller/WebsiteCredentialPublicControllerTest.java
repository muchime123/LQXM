package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteCredential;
import com.liqiang.archive.repository.WebsiteCredentialRepository;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.config.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteCredentialPublicControllerTest {
    @Test void disabledByDefaultAndOnlyPublishesCurrentWebsiteRecords() {
        var records = mock(WebsiteCredentialRepository.class);
        var controller = new WebsiteCredentialPublicController(records, false);
        assertThrows(ResponseStatusException.class, controller::list);
        verifyNoInteractions(records);
        var enabledController = new WebsiteCredentialPublicController(records, true);
        WebsiteCredential item = new WebsiteCredential();
        item.setId(7L); item.setName("ISO 9001"); item.setSortOrder(1); item.setMediaId(4L);
        when(records.findByStatusAndDeletedAtIsNullAndValidUntilGreaterThanEqualOrderBySortOrderAscIdAsc(eq("PUBLISHED"), any(LocalDate.class)))
                .thenReturn(List.of(item));
        when(records.findByStatusAndDeletedAtIsNullAndValidUntilIsNullOrderBySortOrderAscIdAsc("PUBLISHED"))
                .thenReturn(List.of());
        var result = enabledController.list();
        assertEquals(1, result.size());
        assertEquals("/api/website/media/4/content", result.get(0).imageUrl());
    }

    @Test void anonymousHttpAccessHonorsNativeSwitch() throws Exception {
        var records = mock(WebsiteCredentialRepository.class);
        var users = mock(CurrentUserService.class);
        var disabled = MockMvcBuilders.standaloneSetup(new WebsiteCredentialPublicController(records, false))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        disabled.perform(get("/api/website/native/credentials")).andExpect(status().isNotFound());
        verifyNoInteractions(records, users);

        WebsiteCredential item = new WebsiteCredential();
        item.setId(7L); item.setName("ISO 9001"); item.setSortOrder(1);
        when(records.findByStatusAndDeletedAtIsNullAndValidUntilGreaterThanEqualOrderBySortOrderAscIdAsc(eq("PUBLISHED"), any(LocalDate.class)))
                .thenReturn(List.of(item));
        when(records.findByStatusAndDeletedAtIsNullAndValidUntilIsNullOrderBySortOrderAscIdAsc("PUBLISHED"))
                .thenReturn(List.of());
        var enabled = MockMvcBuilders.standaloneSetup(new WebsiteCredentialPublicController(records, true))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        enabled.perform(get("/api/website/native/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("ISO 9001"));
        verifyNoInteractions(users);
    }
}
