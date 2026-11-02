package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteIpItem;
import com.liqiang.archive.service.WebsiteIpItemService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebsiteIpItemPublicControllerTest {
    @Test
    void disabledSourceDoesNotExposeNativeItems() {
        WebsiteIpItemService service = mock(WebsiteIpItemService.class);
        WebsiteIpItemPublicController controller = new WebsiteIpItemPublicController(service, false);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, controller::list);
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void enabledSourceReturnsOnlyServicePublishedItemsAsPublicFields() {
        WebsiteIpItemService service = mock(WebsiteIpItemService.class);
        WebsiteIpItem item = new WebsiteIpItem();
        item.setId(7L);
        item.setName("已核实专利");
        item.setCategory("专利");
        item.setPublicNumber("CN000000");
        item.setMediaId(21L);
        when(service.published()).thenReturn(List.of(item));
        var rows = new WebsiteIpItemPublicController(service, true).list();
        assertEquals(1, rows.size());
        assertEquals("CN000000", rows.get(0).patentNo());
        assertEquals("/api/website/media/21/content", rows.get(0).image());
        verify(service).published();
    }
}
