package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteIpItem;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.service.WebsiteIpItemService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/website/native/ip-items")
@PublicApi
public class WebsiteIpItemPublicController {
    private final WebsiteIpItemService service;
    private final boolean enabled;

    public WebsiteIpItemPublicController(WebsiteIpItemService service,
            @Value("${app.website.ip-native-enabled:false}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @GetMapping
    public List<View> list() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service.published().stream().map(View::from).toList();
    }

    public record View(Long id, String name, String assetCategory, String patentType,
                       String patentNo, String applicant, String legalStatus, String image) {
        static View from(WebsiteIpItem item) {
            return new View(item.getId(), item.getName(), item.getCategory(), item.getPatentType(),
                    item.getPublicNumber(), item.getApplicant(), item.getLegalStatus(),
                    item.getMediaId() == null ? null : "/api/website/media/" + item.getMediaId() + "/content");
        }
    }
}
