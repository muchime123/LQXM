package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsiteInquiry;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteInquiryService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteInquiryControllerPermissionTest {
    @Test
    void inquiryManagementReadsRequireWebsiteManage() throws Exception {
        var service = mock(WebsiteInquiryService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteInquiryController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(get("/api/website/inquiries/admin/list")).andExpect(status().isForbidden());
        mvc.perform(get("/api/website/inquiries/admin/7")).andExpect(status().isForbidden());
        mvc.perform(get("/api/website/inquiries/admin/stats")).andExpect(status().isForbidden());
        verifyNoInteractions(service);

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(null, null, null,
                Set.of(), Set.of(), "SELF", false, "ANONYMOUS", null, null, Map.of()));
        mvc.perform(get("/api/website/inquiries/admin/list")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);

        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        when(service.search(isNull(), isNull(), any(PageRequest.class))).thenReturn(Page.empty());
        WebsiteInquiry inquiry = new WebsiteInquiry();
        inquiry.setId(7L);
        inquiry.setName("Customer");
        when(service.getById(7L)).thenReturn(inquiry);
        when(service.stats()).thenReturn(new WebsiteInquiryService.InquiryStats(1, 0, 0, 1, 1));
        mvc.perform(get("/api/website/inquiries/admin/list"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/website/inquiries/admin/7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Customer"));
        mvc.perform(get("/api/website/inquiries/admin/stats"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(1));
        verify(service).search(isNull(), isNull(), any(PageRequest.class));
        verify(service).getById(7L);
        verify(service).stats();
    }
}
