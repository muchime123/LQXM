package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class H3EmployeeIdentityServiceTest {
    @Test void unresolvedNameRemainsUnassigned() {
        assertNull(H3EmployeeIdentityService.uniqueEmployee(List.of()));
    }
    @Test void sameNameAcrossEmployeesIsNotFirstMatch() {
        assertNull(H3EmployeeIdentityService.uniqueEmployee(List.of(1L, 2L)));
    }
    @Test void multipleExternalIdentitiesOfSameEmployeeAreSafe() {
        assertEquals(1L, H3EmployeeIdentityService.uniqueEmployee(List.of(1L, 1L)));
    }
}
