package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteProductCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteProductCategoryAdminControllerTest {
    @Test
    void categoryUpdateRequiresWebsiteManage() throws Exception {
        var service = mock(WebsiteProductCategoryService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductCategoryAdminController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "User", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(put("/api/website/admin/product-categories/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"设备\",\"slug\":\"equipment\",\"sortOrder\":1}"))
                .andExpect(status().isForbidden());
        verify(service, never()).updateDraft(any(), any());

        var category = new WebsiteProductCategory();
        category.setId(7L);
        category.setName("设备");
        category.setSlug("equipment");
        when(service.updateDraft(eq(7L), any(WebsiteProductCategoryService.CategoryFields.class)))
                .thenReturn(category);
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        mvc.perform(put("/api/website/admin/product-categories/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"设备\",\"slug\":\"equipment\",\"sortOrder\":1}"))
                .andExpect(status().isOk());
        verify(service).updateDraft(eq(7L), any(WebsiteProductCategoryService.CategoryFields.class));
    }
}
