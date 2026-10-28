package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteProductSelectionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteProductSelectionControllerTest {
    @Test
    void onlyWebsiteManagerCanUpdateSelectionThroughHttp() throws Exception {
        var service = mock(WebsiteProductSelectionService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductSelectionController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var request = put("/api/website/admin/products/7/selection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicationTags\":[],\"containerTags\":[],\"processTags\":[],\"capacityLevel\":null}");
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "User", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(request).andExpect(status().isForbidden());
        verify(service, never()).update(any(), any());

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        mvc.perform(put("/api/website/admin/products/7/selection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicationTags\":[],\"containerTags\":[],\"processTags\":[],\"capacityLevel\":null}"))
                .andExpect(status().isOk());
        verify(service).update(eq(7L), any(WebsiteProductSelectionService.SelectionFields.class));
    }
}
