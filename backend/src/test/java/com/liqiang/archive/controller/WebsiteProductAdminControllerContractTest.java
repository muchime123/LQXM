package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteProductService;
import com.liqiang.archive.service.WebsiteProductReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteProductAdminControllerContractTest {
    @Test
    void draftCreationRequiresWebsiteManageAndIgnoresRequestedPublication() throws Exception {
        WebsiteProductService service = mock(WebsiteProductService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductAdminController(service,
                        mock(WebsiteProductReviewService.class)))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        String body = """
                {"name":"官网产品","slug":"website-product","status":"PUBLISHED","publishedAt":"2026-10-27T12:00:00"}
                """;

        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(post("/api/website/admin/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(service, never()).createDraft(any());

        WebsiteProduct draft = new WebsiteProduct();
        draft.setId(5L);
        draft.setName("官网产品");
        draft.setSlug("website-product");
        when(service.createDraft(any())).thenReturn(draft);
        mvc.perform(post("/api/website/admin/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        verify(service).createDraft(any());
    }

    @Test
    void reviewRejectsOrdinaryReadersAndReturnsWebsiteOwnedDraft() throws Exception {
        WebsiteProductService products = mock(WebsiteProductService.class);
        WebsiteProductReviewService reviews = mock(WebsiteProductReviewService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteProductAdminController(products, reviews))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("website.manage")));
        mvc.perform(get("/api/website/admin/products/5/review"))
                .andExpect(status().isForbidden());
        verify(reviews, never()).get(5L);

        when(reviews.get(5L)).thenReturn(new WebsiteProductReviewService.ReviewView(
                5L, 2L, "官网产品", null, null, "product-5", null, null, null, null,
                List.of(), List.of(), List.of(), null, null, null, null, null, 0,
                false, "DRAFT", "封口机", "sealing-machines", List.of()));
        mvc.perform(get("/api/website/admin/products/5/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.categoryName").value("封口机"));
    }

    private static CurrentUserService.CurrentUser user(Set<String> permissions) {
        return new CurrentUserService.CurrentUser(1L, "User", "Dept", Set.of(), permissions,
                "ALL", false, "LOCAL", 1L, null, Map.of());
    }
}
