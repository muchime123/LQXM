package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsitePackagingItemService;
import com.liqiang.archive.service.WebsitePackagingCutoverReadinessService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsitePackagingAdminControllerTest {
    @Test
    void cutoverReadinessRequiresWebsiteManage() throws Exception {
        var service = mock(WebsitePackagingItemService.class);
        var readiness = mock(WebsitePackagingCutoverReadinessService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsitePackagingAdminController(service, readiness))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(get("/api/website/admin/packaging/cutover-readiness"))
                .andExpect(status().isForbidden());
        verify(readiness, never()).check();

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        when(readiness.check()).thenReturn(new WebsitePackagingCutoverReadinessService.Result(
                false, 1, 0, 0, java.util.List.of("没有已发布的官网包材系列"), java.util.List.of()));
        mvc.perform(get("/api/website/admin/packaging/cutover-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicallyReady").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("没有已发布的官网包材系列"));
        verify(readiness).check();
    }
    @Test
    void createRequiresWebsiteManage() throws Exception {
        var service = mock(WebsitePackagingItemService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsitePackagingAdminController(service, mock(WebsitePackagingCutoverReadinessService.class)))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String body = "{\"slug\":\"food-tray\",\"name\":\"托盒\"}";

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(post("/api/website/admin/packaging").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(service, never()).createDraft(any());

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        WebsitePackagingItem created = new WebsitePackagingItem();
        created.setId(7L);
        created.setSlug("food-tray");
        created.setName("托盒");
        when(service.createDraft(any())).thenReturn(created);
        mvc.perform(post("/api/website/admin/packaging").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(service).createDraft(any(WebsitePackagingItemService.Fields.class));
    }

    @Test
    void publishRequiresWebsiteManage() throws Exception {
        var service = mock(WebsitePackagingItemService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsitePackagingAdminController(service, mock(WebsitePackagingCutoverReadinessService.class)))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(post("/api/website/admin/packaging/7/publish"))
                .andExpect(status().isForbidden());
        verify(service, never()).publish(7L);

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        WebsitePackagingItem published = new WebsitePackagingItem();
        published.setId(7L);
        published.setName("托盒");
        published.setStatus("PUBLISHED");
        when(service.publish(7L)).thenReturn(published);
        mvc.perform(post("/api/website/admin/packaging/7/publish"))
                .andExpect(status().isOk());
        verify(service).publish(7L);
    }

    @Test
    void unpublishRequiresWebsiteManage() throws Exception {
        var service = mock(WebsitePackagingItemService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsitePackagingAdminController(service, mock(WebsitePackagingCutoverReadinessService.class)))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(post("/api/website/admin/packaging/7/unpublish"))
                .andExpect(status().isForbidden());
        verify(service, never()).unpublish(7L);

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        WebsitePackagingItem draft = new WebsitePackagingItem();
        draft.setId(7L);
        draft.setName("托盒");
        draft.setStatus("DRAFT");
        when(service.unpublish(7L)).thenReturn(draft);
        mvc.perform(post("/api/website/admin/packaging/7/unpublish"))
                .andExpect(status().isOk());
        verify(service).unpublish(7L);
    }
}
