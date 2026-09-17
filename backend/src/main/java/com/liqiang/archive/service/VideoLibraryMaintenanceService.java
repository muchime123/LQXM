package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.VideoAssetView;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.service.file.FileAssetBehaviorService;
import com.liqiang.archive.service.file.FileAssetLocationService;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 设备视频库维护任务：文件哈希回填（SHA-256）、重复检测、媒体信息提取与自动封面。
 * 重复只做提示分组，不自动删除或合并；封面仅在缺失时生成，由人工确认效果。
 */
@Service
public class VideoLibraryMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(VideoLibraryMaintenanceService.class);

    private static final List<String> VIDEO_CATEGORIES = List.of("device_video", "operation_video");

    /** 抽帧时间点策略：取视频前 15% 处（不超过 5 秒），至少从 0.5 秒开始避开黑屏首帧。 */
    private static final double COVER_AT_FRACTION = 0.15;
    private static final double COVER_MAX_SECONDS = 5.0;
    private static final double COVER_MIN_SECONDS = 0.5;
    private static final DateTimeFormatter COVER_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ProductAssetRepository assetRepository;
    private final FileAssetLocationService locationService;
    private final FileAssetBehaviorService behaviorService;
    private final FfmpegMediaService ffmpegMediaService;

    public VideoLibraryMaintenanceService(ProductAssetRepository assetRepository,
                                          FileAssetLocationService locationService,
                                          FileAssetBehaviorService behaviorService,
                                          FfmpegMediaService ffmpegMediaService) {
        this.assetRepository = assetRepository;
        this.locationService = locationService;
        this.behaviorService = behaviorService;
        this.ffmpegMediaService = ffmpegMediaService;
    }

    /** 为缺失摘要的视频流式计算 SHA-256；返回统计信息。 */
    public Map<String, Object> backfillHashes() {
        List<ProductAsset> videos = assetRepository.findAll(videoSpec());
        int computed = 0;
        int alreadyHashed = 0;
        int missingFiles = 0;
        List<ProductAsset> dirty = new ArrayList<>();
        for (ProductAsset asset : videos) {
            if (asset.getFileHash() != null && !asset.getFileHash().isBlank()) {
                alreadyHashed++;
                continue;
            }
            try {
                asset.setFileHash(hashOf(asset));
                dirty.add(asset);
                computed++;
            } catch (NoSuchFileException e) {
                missingFiles++;
            } catch (IOException e) {
                log.warn("计算视频哈希失败 id={} file={}: {}", asset.getId(), asset.getFileName(), e.getMessage());
                missingFiles++;
            }
        }
        if (!dirty.isEmpty()) {
            assetRepository.saveAll(dirty);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", videos.size());
        result.put("computed", computed);
        result.put("alreadyHashed", alreadyHashed);
        result.put("missingFiles", missingFiles);
        return result;
    }

    /** 精确重复（同 SHA-256）与疑似重复（清洗后标题一致）分组。 */
    @Transactional(readOnly = true)
    public Map<String, Object> findDuplicates() {
        List<ProductAsset> videos = assetRepository.findAll(videoSpec());
        return Map.of(
                "exactGroups", groupBy(videos, asset -> normalizedKey(asset.getFileHash())),
                "suspectedGroups", groupBy(videos,
                        asset -> normalizedKey(ProductCatalogService.cleanVideoTitle(asset.getFileName())))
        );
    }

    /** 为缺失时长/分辨率信息的视频执行 ffprobe 提取；返回统计信息。 */
    public Map<String, Object> backfillMediaInfo(int limit) {
        requireFfmpeg();
        List<ProductAsset> videos = assetRepository.findAll(videoSpec());
        int processed = 0;
        int extracted = 0;
        int failed = 0;
        List<ProductAsset> dirty = new ArrayList<>();
        for (ProductAsset asset : videos) {
            if (processed >= limit) break;
            if (asset.getVideoDurationSeconds() != null && asset.getVideoWidth() != null) continue;
            processed++;
            try {
                FfmpegMediaService.MediaInfo info = ffmpegMediaService.probe(
                        locationService.resolveExistingPath(FileHandle.from(asset)));
                if (info.durationSeconds() != null) asset.setVideoDurationSeconds(info.durationSeconds());
                if (info.width() != null) {
                    asset.setVideoWidth(info.width());
                    asset.setVideoHeight(info.height());
                }
                dirty.add(asset);
                extracted++;
            } catch (NoSuchFileException e) {
                failed++;
            } catch (IOException e) {
                log.warn("提取媒体信息失败 id={} file={}: {}", asset.getId(), asset.getFileName(), e.getMessage());
                failed++;
            }
        }
        if (!dirty.isEmpty()) {
            assetRepository.saveAll(dirty);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", videos.size());
        result.put("extracted", extracted);
        result.put("failed", failed);
        return result;
    }

    /** 为单个视频生成自动封面（已有时封面且不强制时跳过）。 */
    public Map<String, Object> autoCover(Long assetId, boolean force) {
        requireFfmpeg();
        ProductAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在: " + assetId));
        if (!VIDEO_CATEGORIES.contains(asset.getCategory())) {
            throw new IllegalArgumentException("仅视频素材支持自动封面: " + asset.getCategory());
        }
        boolean hasCover = asset.getCoverFileName() != null && !asset.getCoverFileName().isBlank();
        if (hasCover && !force) {
            return Map.of("generated", false, "reason", "已有自定义封面");
        }
        try {
            Path videoFile = locationService.resolveExistingPath(FileHandle.from(asset));
            // 顺带补齐媒体信息：抽帧时间点也依赖时长
            Integer durationSeconds = asset.getVideoDurationSeconds();
            Integer width = asset.getVideoWidth();
            Integer height = asset.getVideoHeight();
            if (durationSeconds == null || width == null) {
                FfmpegMediaService.MediaInfo info = ffmpegMediaService.probe(videoFile);
                durationSeconds = info.durationSeconds() != null ? info.durationSeconds() : durationSeconds;
                if (info.width() != null) {
                    width = info.width();
                    height = info.height();
                }
            }
            double at = coverAtSeconds(durationSeconds);
            FileHandle handle = FileHandle.from(asset);
            Path dir = locationService.resolveAssetDirectory(handle);
            String baseName = stripExtension(asset.getFileName());
            String coverName = baseName + "_cover_" + LocalDateTime.now().format(COVER_STAMP) + ".jpg";
            Path target = dir.resolve(coverName).normalize();
            if (!target.startsWith(dir)) {
                throw new IOException("封面路径不合法");
            }
            ffmpegMediaService.extractCoverFrame(videoFile, target, at);
            String oldCover = asset.getCoverFileName();
            behaviorService.setCover(OwnerType.PRODUCT_MODEL, assetId, coverName);
            deleteQuietly(oldCover == null ? null : dir.resolve(oldCover));

            asset.setCoverFileName(coverName);
            asset.setVideoDurationSeconds(durationSeconds);
            if (width != null) {
                asset.setVideoWidth(width);
                asset.setVideoHeight(height);
            }
            assetRepository.save(asset);
            return Map.of("generated", true, "coverFileName", coverName,
                    "durationSeconds", durationSeconds == null ? 0 : durationSeconds,
                    "width", width == null ? 0 : width,
                    "height", height == null ? 0 : height,
                    "atSeconds", at);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("视频文件不存在，无法抽帧", e);
        } catch (IOException e) {
            throw new IllegalStateException("自动封面失败: " + e.getMessage(), e);
        }
    }

    /** 批量为无封面的视频生成自动封面。 */
    public Map<String, Object> backfillAutoCovers(int limit) {
        requireFfmpeg();
        List<ProductAsset> videos = assetRepository.findAll(videoSpec());
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (ProductAsset asset : videos) {
            if (generated >= limit) break;
            boolean hasCover = asset.getCoverFileName() != null && !asset.getCoverFileName().isBlank();
            if (hasCover) {
                skipped++;
                continue;
            }
            try {
                autoCover(asset.getId(), false);
                generated++;
            } catch (RuntimeException e) {
                log.warn("批量自动封面失败 id={} file={}: {}", asset.getId(), asset.getFileName(), e.getMessage());
                failed++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", videos.size());
        result.put("generated", generated);
        result.put("skippedHasCover", skipped);
        result.put("failed", failed);
        return result;
    }

    private void requireFfmpeg() {
        if (!ffmpegMediaService.available()) {
            throw new IllegalStateException(
                    "FFmpeg 未就绪：请在 application.yml 配置 app.ffmpeg.path / app.ffmpeg.probe-path 指向可执行文件");
        }
    }

    private double coverAtSeconds(Integer durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0) return COVER_MIN_SECONDS;
        return Math.min(Math.max(durationSeconds * COVER_AT_FRACTION, COVER_MIN_SECONDS),
                Math.min(COVER_MAX_SECONDS, Math.max(durationSeconds - 0.5, COVER_MIN_SECONDS)));
    }

    private String stripExtension(String fileName) {
        return String.valueOf(fileName == null ? "video" : fileName).replaceFirst("\\.[^.]+$", "");
    }

    private void deleteQuietly(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除旧封面失败 {}: {}", path, e.getMessage());
        }
    }

    private Specification<ProductAsset> videoSpec() {
        return (root, query, cb) -> cb.and(root.get("category").in(VIDEO_CATEGORIES));
    }

    private String normalizedKey(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private List<List<VideoAssetView>> groupBy(List<ProductAsset> videos,
                                               Function<ProductAsset, String> keyOf) {
        Map<String, List<VideoAssetView>> groups = new LinkedHashMap<>();
        for (ProductAsset asset : videos) {
            String key = keyOf.apply(asset);
            if (key == null) continue;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(VideoAssetView.from(asset));
        }
        return groups.values().stream().filter(group -> group.size() > 1).toList();
    }

    private String hashOf(ProductAsset asset) throws IOException {
        Path path = locationService.resolveExistingPath(FileHandle.from(asset));
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
