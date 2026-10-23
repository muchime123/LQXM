package com.liqiang.archive.service;

import com.liqiang.archive.dto.CompanyInfoUpdateRequest;
import com.liqiang.archive.entity.CompanyAccount;
import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.repository.CompanyAccountRepository;
import com.liqiang.archive.repository.CompanyInfoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyInfoServiceTest {
    @Mock CompanyInfoRepository companyInfoRepository;
    @Mock CompanyAccountRepository accountRepo;
    @Mock AuditLogService auditLogService;
    @InjectMocks CompanyInfoService service;

    @Test void profileUpdatePreservesFilesAndClearsEditableField() {
        CompanyInfo existing = new CompanyInfo();
        existing.setId(1L);
        existing.setCompanyName("Old");
        existing.setPhone("123");
        existing.setLogoFileName("logo.png");
        existing.setLicenseFileName("license.pdf");
        existing.setSealFileName("seal.png");
        when(companyInfoRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(companyInfoRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        CompanyInfoUpdateRequest request = new CompanyInfoUpdateRequest();
        request.setCompanyName("New");
        request.setPhone(null);
        CompanyInfo result = service.updateCompanyInfo(request);
        assertEquals("New", result.getCompanyName());
        assertNull(result.getPhone());
        assertEquals("logo.png", result.getLogoFileName());
        assertEquals("license.pdf", result.getLicenseFileName());
        assertEquals("seal.png", result.getSealFileName());
    }

    @Test void accountUpdatePersistsInternationalFields() {
        CompanyAccount existing = new CompanyAccount();
        existing.setId(2L);
        when(accountRepo.findById(2L)).thenReturn(Optional.of(existing));
        when(accountRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        CompanyAccount request = new CompanyAccount();
        request.setAccountName("International");
        request.setIban("IBAN");
        request.setRoutingNumber("ROUTE");
        request.setBankAddress("Address");
        request.setBankLocation("Location");
        request.setDomesticBankCode("CODE");
        CompanyAccount result = service.updateAccount(2L, request);
        assertEquals("IBAN", result.getIban());
        assertEquals("ROUTE", result.getRoutingNumber());
        assertEquals("Address", result.getBankAddress());
        assertEquals("Location", result.getBankLocation());
        assertEquals("CODE", result.getDomesticBankCode());
    }
}
