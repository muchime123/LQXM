package com.liqiang.archive.controller;

import com.liqiang.archive.service.WebsiteSolutionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteSolutionDeleteRetirementTest {
    @Test
    void oldHardDeleteEndpointDoesNotRemoveWebsiteContent() throws Exception {
        var service = mock(WebsiteSolutionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteSolutionController(service)).build();

        mvc.perform(delete("/api/website/solutions/admin/7"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
