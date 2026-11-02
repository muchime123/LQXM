package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionTrackingUnknownDateTest {
    @Test void doneWithoutDateNeedsExplicitConfirmationAndEvidence() {
        assertThrows(IllegalArgumentException.class,()->validate("DONE",null,null,"核实过"));
        assertThrows(IllegalArgumentException.class,()->validate("DONE",null,"NO_DATE",null));
        assertDoesNotThrow(()->validate("DONE",null,"NO_DATE","现场确认已完成，日期未留档"));
    }

    @Test void actualDateAndUnknownDateCannotBeMixed() {
        LocalDate date=LocalDate.of(2026,10,18);
        assertThrows(IllegalArgumentException.class,()->validate("DONE",date,"NO_DATE",""));
        assertDoesNotThrow(()->validate("DONE",date,"MANUAL","现场补登记"));
        assertDoesNotThrow(()->validate("DONE",date,null,null));
        assertThrows(IllegalArgumentException.class,()->validate("IN_PROGRESS",null,"NO_DATE",""));
    }

    private void validate(String status,LocalDate completed,String source,String note) {
        ProductionTrackingService.validateMilestoneCompletion(
                new ProductionTrackingService.Milestone("MOLD",status,completed,note,null,null,source),"模具");
    }
}
