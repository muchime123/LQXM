package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteCompanyMilestoneParityService;
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

class WebsiteCompanyMilestoneParityControllerTest {
    @Test
    void comparisonRequiresWebsiteManage() throws Exception {
        var service = mock(WebsiteCompanyMilestoneParityService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteCompanyMilestoneParityController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(get("/api/website/admin/company-milestones/parity")).andExpect(status().isForbidden());
        verify(service, never()).compare();

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        when(service.compare()).thenReturn(new WebsiteCompanyMilestoneParityService.ParityView(
                0, 0, 0, 0, List.of(), List.of()));
        mvc.perform(get("/api/website/admin/company-milestones/parity")).andExpect(status().isOk());
        verify(service).compare();
    }
}
