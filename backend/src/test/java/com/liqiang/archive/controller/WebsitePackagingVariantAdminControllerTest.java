package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsitePackagingVariantService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsitePackagingVariantAdminControllerTest {
    @Test
    void createRequiresWebsiteManage() throws Exception {
        var service = mock(WebsitePackagingVariantService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsitePackagingVariantAdminController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String body = "{\"name\":\"500ml 托盒\"}";
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(post("/api/website/admin/packaging/7/variants")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verify(service, never()).create(any(), any());

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        WebsitePackagingVariant result = new WebsitePackagingVariant();
        result.setId(19L);
        result.setItemId(7L);
        result.setName("500ml 托盒");
        when(service.create(any(), any())).thenReturn(result);
        mvc.perform(post("/api/website/admin/packaging/7/variants")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(service).create(any(), any());
    }
}
