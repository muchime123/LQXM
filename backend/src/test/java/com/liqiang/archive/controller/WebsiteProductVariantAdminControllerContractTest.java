package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsiteProductVariant;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteProductVariantService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteProductVariantAdminControllerContractTest {
    @Test
    void variantCreationRequiresWebsiteManage() throws Exception {
        WebsiteProductVariantService service = mock(WebsiteProductVariantService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductVariantAdminController(service))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        String body = "{\"name\":\"公开规格\",\"publicSku\":\"MODEL-1\"}";
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));

        mvc.perform(post("/api/website/admin/products/10/variants")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(service, never()).createDraft(eq(10L), any());

        WebsiteProductVariant variant = new WebsiteProductVariant();
        variant.setId(20L);
        variant.setProductId(10L);
        variant.setName("公开规格");
        when(service.createDraft(eq(10L), any())).thenReturn(variant);
        mvc.perform(post("/api/website/admin/products/10/variants")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.productId").value(10));
    }

    private static CurrentUserService.CurrentUser user(Set<String> permissions) {
        return new CurrentUserService.CurrentUser(1L, "User", "Dept", Set.of(), permissions,
                "ALL", false, "LOCAL", 1L, null, Map.of());
    }
}
