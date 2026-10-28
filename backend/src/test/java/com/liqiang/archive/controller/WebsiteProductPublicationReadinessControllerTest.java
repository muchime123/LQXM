package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteProductPublicationReadinessService;
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

class WebsiteProductPublicationReadinessControllerTest {
    @Test
    void requiresWebsiteManageForReadiness() throws Exception {
        var service = mock(WebsiteProductPublicationReadinessService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductPublicationReadinessController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(get("/api/website/admin/products/7/publication-readiness"))
                .andExpect(status().isForbidden());
        verify(service, never()).check(7L);

        when(service.check(7L)).thenReturn(new WebsiteProductPublicationReadinessService.ReadinessView(
                7L, false, List.of("主图未设置"), List.of("SEO 标题待审阅")));
        mvc.perform(get("/api/website/admin/products/7/publication-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("主图未设置"));
    }

    private static CurrentUserService.CurrentUser user(Set<String> permissions) {
        return new CurrentUserService.CurrentUser(1L, "User", "Dept", Set.of(), permissions,
                "ALL", false, "LOCAL", 1L, null, Map.of());
    }
}
