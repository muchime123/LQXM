package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteCompanyContentCutoverReadinessService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteCompanyContentCutoverReadinessControllerTest {
    @Test
    void reportRequiresWebsiteManage() throws Exception {
        var service = mock(WebsiteCompanyContentCutoverReadinessService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteCompanyContentCutoverReadinessController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(get("/api/website/admin/company-content/cutover-readiness")).andExpect(status().isForbidden());
        verify(service, never()).check();

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        when(service.check()).thenReturn(new WebsiteCompanyContentCutoverReadinessService.ReadinessView(
                false, List.of("未发布"), List.of(), 0, 0, 0, 0, 0, 0, 0));
        mvc.perform(get("/api/website/admin/company-content/cutover-readiness")).andExpect(status().isOk());
        verify(service).check();
    }
}
