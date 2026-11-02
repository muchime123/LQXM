package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteCredential;
import com.liqiang.archive.repository.WebsiteCredentialRepository;
import com.liqiang.archive.security.PublicApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** 独立官网资质公开预览；页面完成对拍后再接入。 */
@RestController
@RequestMapping("/api/website/native/credentials")
@PublicApi
public class WebsiteCredentialPublicController {
    private final WebsiteCredentialRepository records;
    private final boolean enabled;

    public WebsiteCredentialPublicController(WebsiteCredentialRepository records,
            @Value("${app.website.credentials-native-enabled:false}") boolean enabled) {
        this.records = records;
        this.enabled = enabled;
    }

    @GetMapping
    public List<View> list() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<WebsiteCredential> dated = records.findByStatusAndDeletedAtIsNullAndValidUntilGreaterThanEqualOrderBySortOrderAscIdAsc("PUBLISHED", LocalDate.now());
        List<WebsiteCredential> perpetual = records.findByStatusAndDeletedAtIsNullAndValidUntilIsNullOrderBySortOrderAscIdAsc("PUBLISHED");
        return java.util.stream.Stream.concat(dated.stream(), perpetual.stream())
                .sorted(Comparator.comparing(WebsiteCredential::getSortOrder).thenComparing(WebsiteCredential::getId))
                .map(View::from).toList();
    }

    public record View(Long id, String name, String category, String description, String issuer,
                       LocalDate validUntil, String imageUrl) {
        static View from(WebsiteCredential item) {
            return new View(item.getId(), item.getName(), item.getCategory(), item.getPublicDescription(),
                    item.getIssuer(), item.getValidUntil(), item.getMediaId() == null ? null
                            : "/api/website/media/" + item.getMediaId() + "/content");
        }
    }
}
