package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsiteCredential;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteCredentialAdminControllerTest {
    @Test
    void readerCannotListCreateOrPublishButWebsiteManagerCan() throws Exception {
        var service = mock(WebsiteCredentialService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteCredentialAdminController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(get("/api/website/admin/credentials")).andExpect(status().isForbidden());
        mvc.perform(post("/api/website/admin/credentials")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ISO 9001\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/website/admin/credentials/7/publish"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        WebsiteCredential record = new WebsiteCredential();
        record.setId(7L);
        record.setName("ISO 9001");
        record.setStatus("PUBLISHED");
        when(service.publish(7L)).thenReturn(record);
        mvc.perform(post("/api/website/admin/credentials/7/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        verify(service).publish(7L);
        verify(service, never()).create(any());
    }
}
