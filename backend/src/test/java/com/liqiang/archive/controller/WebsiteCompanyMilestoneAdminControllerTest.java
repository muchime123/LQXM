package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteCompanyMilestoneService;
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

class WebsiteCompanyMilestoneAdminControllerTest {
    @Test
    void createRequiresWebsiteManage() throws Exception {
        var service = mock(WebsiteCompanyMilestoneService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteCompanyMilestoneAdminController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(post("/api/website/admin/company-milestones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"year\":2020,\"title\":\"官网历程\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any());

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        mvc.perform(post("/api/website/admin/company-milestones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"year\":2020,\"title\":\"官网历程\"}"))
                .andExpect(status().isOk());
        verify(service).create(any(WebsiteCompanyMilestoneService.Fields.class));
    }
}
