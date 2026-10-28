package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteProductCutoverReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteProductCutoverReadinessControllerTest {
    @Test
    void onlyWebsiteManagerCanReadCutoverGate() throws Exception {
        var service = mock(WebsiteProductCutoverReadinessService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductCutoverReadinessController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(get("/api/website/admin/native-products/cutover-readiness"))
                .andExpect(status().isForbidden());
        verify(service, never()).check();

        when(service.check()).thenReturn(new WebsiteProductCutoverReadinessService.CutoverReadinessView(
                false, 0, 45, 0, 0, 9, 0, 136,
                List.of("没有已发布的官网产品"), List.of(), List.of()));
        mvc.perform(get("/api/website/admin/native-products/cutover-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicallyReady").value(false))
                .andExpect(jsonPath("$.draftProducts").value(45));
        verify(service).check();
    }

    private static CurrentUserService.CurrentUser user(Set<String> permissions) {
        return new CurrentUserService.CurrentUser(1L, "User", "Dept", Set.of(), permissions,
                "ALL", false, "LOCAL", 1L, null, Map.of());
    }
}
