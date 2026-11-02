package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteSocialLinkService;
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

class WebsiteSocialLinkAdminControllerTest {
    @Test
    void createRequiresWebsiteManage() throws Exception {
        var service = mock(WebsiteSocialLinkService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteSocialLinkAdminController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(post("/api/website/admin/social-links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"抖音\",\"accountName\":\"官网号\",\"link\":\"https://example.com\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any());

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        mvc.perform(post("/api/website/admin/social-links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"抖音\",\"accountName\":\"官网号\",\"link\":\"https://example.com\"}"))
                .andExpect(status().isOk());
        verify(service).create(any(WebsiteSocialLinkService.Fields.class));
    }
}
