package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/** Explicit, audited publication. No archive data is read or synchronized. */
@Service
@RequiredArgsConstructor
public class WebsiteProductPublicationService {
    private final WebsiteMediaAssetRepository media;
    private final WebsiteProductCategoryRepository categories;
    private final WebsiteProductRepository products;
    private final WebsiteProductPublicationReadinessService readiness;
    private final AuditLogService audit;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional
    public WebsiteMediaAsset publishMedia(Long id) {
        WebsiteMediaAsset asset = media.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网媒体不存在"));
        if ("PUBLISHED".equals(asset.getStatus())) return asset;
        if (!"DRAFT".equals(asset.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿媒体可以发布");
        if (asset.getStorageKey() == null || asset.getStorageKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网媒体缺少文件路径");
        }
        Path root = Path.of(fileRoot).toAbsolutePath().normalize();
        Path websiteRoot = root.resolve("website");
        Path file = root.resolve(asset.getStorageKey()).normalize();
        try {
            if (!file.startsWith(websiteRoot) || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(websiteRoot.toRealPath())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "官网媒体文件不存在或超出专用目录");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网媒体文件不可读取", e);
        }
        asset.setStatus("PUBLISHED");
        WebsiteMediaAsset saved = media.save(asset);
        audit.log("WebsiteMediaAsset", id, saved.getDisplayName(), "WEBSITE_MEDIA_PUBLISH", "发布官网媒体");
        return saved;
    }

    @Transactional
    public WebsiteProductCategory publishCategory(Long id) {
        WebsiteProductCategory category = categories.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网分类不存在"));
        if ("PUBLISHED".equals(category.getStatus())) return category;
        if (!"DRAFT".equals(category.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿分类可以发布");
        category.setStatus("PUBLISHED");
        WebsiteProductCategory saved = categories.save(category);
        audit.log("WebsiteProductCategory", id, saved.getName(), "WEBSITE_PRODUCT_CATEGORY_PUBLISH", "发布官网分类");
        return saved;
    }

    @Transactional
    public WebsiteProduct publishProduct(Long id) {
        WebsiteProduct product = products.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
        if ("PUBLISHED".equals(product.getStatus())) return product;
        if (!"DRAFT".equals(product.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿产品可以发布");
        var check = readiness.check(id);
        if (!check.ready()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "官网产品未达到发布条件：" + String.join("；", check.blockers()));
        product.setStatus("PUBLISHED");
        product.setPublishedAt(LocalDateTime.now());
        WebsiteProduct saved = products.save(product);
        audit.log("WebsiteProduct", id, saved.getName(), "WEBSITE_PRODUCT_PUBLISH", "发布官网产品");
        return saved;
    }
}
