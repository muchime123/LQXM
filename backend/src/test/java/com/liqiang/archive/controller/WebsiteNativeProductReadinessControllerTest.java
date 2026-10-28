package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteNativeProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteNativeProductReadinessControllerTest {
    @Test
    void readThroughRequiresWebsiteManageAndReturnsOnlyServiceResult() throws Exception {
        WebsiteNativeProductQueryService service = mock(WebsiteNativeProductQueryService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteNativeProductReadinessController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(get("/api/website/admin/native-products")).andExpect(status().isForbidden());
        verify(service, never()).list(nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt());

        when(service.list(null, null, null, null, null, null, 0, 12))
                .thenReturn(new WebsiteNativeProductQueryService.PageView(List.of(), 0, 0, 0));
        mvc.perform(get("/api/website/admin/native-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        verify(service).list(null, null, null, null, null, null, 0, 12);
    }

    @Test
    void draftPreviewRequiresWebsiteManageAndNeverUsesPublicRead() throws Exception {
        WebsiteNativeProductQueryService service = mock(WebsiteNativeProductQueryService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteNativeProductReadinessController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(get("/api/website/admin/native-products/drafts/7"))
                .andExpect(status().isForbidden());
        verify(service, never()).previewDetail(7L);

        var product = new WebsiteNativeProductQueryService.ProductView(7L, "设备", null,
                "LQ-7", "封口机", "machine", "简介", List.of(), List.of(), List.of(),
                null, false, 0, null, null, 5L, 5L);
        when(service.previewDetail(7L)).thenReturn(Optional.of(
                new WebsiteNativeProductQueryService.DetailView(product, "<p>介绍</p>", null,
                        Map.of(), List.of(), List.of())));
        mvc.perform(get("/api/website/admin/native-products/drafts/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.heroMediaId").value(5));
        verify(service).previewDetail(7L);
        verify(service, never()).detail("7");
    }

    @Test
    void filterOptionsAreProtected() throws Exception {
        WebsiteNativeProductQueryService service = mock(WebsiteNativeProductQueryService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteNativeProductReadinessController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(get("/api/website/admin/native-products/filter-options"))
                .andExpect(status().isForbidden());
        verify(service, never()).filterOptions();
        when(service.filterOptions()).thenReturn(new WebsiteNativeProductQueryService.CategoryFiltersView(
                List.of("封口机"), List.of("封口机"), List.of(), List.of(), List.of(), List.of()));
        mvc.perform(get("/api/website/admin/native-products/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentCategories[0]").value("封口机"));
    }

    private static CurrentUserService.CurrentUser user(Set<String> permissions) {
        return new CurrentUserService.CurrentUser(1L, "User", "Dept", Set.of(), permissions,
                "ALL", false, "LOCAL", 1L, null, Map.of());
    }
}
