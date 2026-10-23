package com.liqiang.archive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.dto.CompanyInfoUpdateRequest;
import com.liqiang.archive.entity.CompanyAccount;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.CompanyCertificationService;
import com.liqiang.archive.service.CompanyInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyInfoP0ContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void jsonDistinguishesMissingAndExplicitNullAndCannotSetFiles() throws Exception {
        CompanyInfoUpdateRequest request = mapper.readValue(
                "{\"companyName\":\"Updated\",\"phone\":null,\"logoFileName\":\"attacker.png\",\"licenseFileName\":null,\"sealFileName\":null}",
                CompanyInfoUpdateRequest.class);
        assertTrue(request.has("companyName"));
        assertTrue(request.has("phone"));
        assertNull(request.getPhone());
        assertFalse(request.has("officeAddress"));
        assertFalse(request.has("logoFileName"));
        assertFalse(request.has("licenseFileName"));
        assertFalse(request.has("sealFileName"));
    }

    @Test void accountListRequiresManagePermissionOverHttp() throws Exception {
        CompanyInfoService service = mock(CompanyInfoService.class);
        CompanyCertificationService certifications = mock(CompanyCertificationService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(users);
        var mvc = MockMvcBuilders.standaloneSetup(new CompanyInfoController(service, certifications))
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(users.current()).thenReturn(user(Set.of("company.read")), user(Set.of("company.manage")));
        mvc.perform(get("/api/company/accounts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        verify(service, never()).getAccounts();
        when(service.getAccounts()).thenReturn(List.<CompanyAccount>of());
        mvc.perform(get("/api/company/accounts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        verify(service).getAccounts();
    }

    private static CurrentUserService.CurrentUser user(Set<String> permissions) {
        return new CurrentUserService.CurrentUser(1L, "User", "Dept", Set.of(), permissions,
                "ALL", false, "LOCAL", 1L, null, Map.of());
    }
}
