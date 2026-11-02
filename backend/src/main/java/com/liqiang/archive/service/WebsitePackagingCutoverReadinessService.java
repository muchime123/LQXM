package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsitePackagingItemRepository;
import com.liqiang.archive.repository.WebsitePackagingVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 只读检查已发布官网包材系列的内容完整性。 */
@Service
@RequiredArgsConstructor
public class WebsitePackagingCutoverReadinessService {
    private final WebsitePackagingItemRepository items;
    private final WebsitePackagingVariantRepository variants;
    private final WebsiteMediaAssetRepository media;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional(readOnly = true)
    public Result check() {
        List<WebsitePackagingItem> all = items.findByDeletedAtIsNullOrderByIdAsc();
        List<WebsitePackagingItem> published = all.stream()
                .filter(item -> "PUBLISHED".equals(item.getStatus())).toList();
        List<String> blockers = new ArrayList<>();
        if (published.isEmpty()) blockers.add("没有已发布的官网包材系列");
        Map<Long, Long> variantCounts = published.isEmpty() ? Map.of()
                : variants.findByItemIdInAndDeletedAtIsNullOrderBySortOrderAscIdAsc(
                        published.stream().map(WebsitePackagingItem::getId).toList()).stream()
                .collect(Collectors.groupingBy(v -> v.getItemId(), Collectors.counting()));
        List<ItemIssue> issues = new ArrayList<>();
        for (WebsitePackagingItem item : published) {
            List<String> reasons = new ArrayList<>();
            if (item.getName() == null || item.getName().isBlank()) reasons.add("缺少公开名称");
            if (item.getCoverMediaId() == null) reasons.add("缺少封面图片");
            else {
                WebsiteMediaAsset cover = media.findByIdAndDeletedAtIsNull(item.getCoverMediaId()).orElse(null);
                if (cover == null || !"IMAGE".equals(cover.getMediaType())
                        || !"PUBLISHED".equals(cover.getStatus())) reasons.add("封面媒体未发布或不可用");
                else if (!readableWebsiteFile(cover.getStorageKey())) reasons.add("封面文件缺失或不在官网媒体目录");
            }
            if (variantCounts.getOrDefault(item.getId(), 0L) == 0) reasons.add("没有公开规格");
            if (!reasons.isEmpty()) issues.add(new ItemIssue(item.getId(), item.getName(), reasons));
        }
        if (!issues.isEmpty()) blockers.add("存在未完善的已发布包材系列");
        return new Result(blockers.isEmpty(), all.size(), published.size(),
                variantCounts.values().stream().mapToLong(Long::longValue).sum(), blockers, issues);
    }

    private boolean readableWebsiteFile(String storageKey) {
        if (fileRoot == null || storageKey == null || storageKey.isBlank()) return false;
        try {
            Path root = Path.of(fileRoot).toAbsolutePath().normalize().resolve("website");
            Path file = Path.of(fileRoot).toAbsolutePath().normalize().resolve(storageKey).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) && Files.isReadable(file)
                    && file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    public record ItemIssue(Long id, String name, List<String> reasons) {}
    public record Result(boolean technicallyReady, int totalItems, int publishedItems,
                         long publishedVariants, List<String> blockers, List<ItemIssue> itemIssues) {}
}
