package com.liqiang.archive.service.file;

import com.liqiang.archive.entity.CompanyAsset;
import com.liqiang.archive.repository.CompanyAssetRepository;
import com.liqiang.archive.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 一次性数据迁移：把 company/ 平铺目录下的品牌资产文件移动到 company/<category>/ 子目录。
 *
 * 通过标记文件 .company-subdir-migrated 防止重复执行。
 */
@Component
public class CompanyAssetSubdirMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanyAssetSubdirMigrationRunner.class);
    private static final String MARKER = ".company-subdir-migrated";

    private final FileStorageService fileStorageService;
    private final CompanyAssetRepository assetRepo;

    public CompanyAssetSubdirMigrationRunner(FileStorageService fileStorageService,
                                              CompanyAssetRepository assetRepo) {
        this.fileStorageService = fileStorageService;
        this.assetRepo = assetRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path markerFile = fileStorageService.fileRoot().resolve("company").resolve(MARKER);
        if (Files.exists(markerFile)) {
            return; // 已迁移
        }

        Path companyDir = fileStorageService.fileRoot().resolve("company");
        if (!Files.isDirectory(companyDir)) {
            touchMarker(markerFile);
            return;
        }

        List<CompanyAsset> assets = assetRepo.findAll();
        int moved = 0;
        int skipped = 0;
        for (CompanyAsset asset : assets) {
            String category = asset.getCategory();
            String fileName = asset.getFileName();
            if (category == null || fileName == null || fileName.isBlank()) continue;
            // 跳过合成文件类型（它们不在 company/ 平铺目录）
            if ("company".equals(category) || "company_finance".equals(category) || "company_team".equals(category)) continue;

            Path flatFile = companyDir.resolve(fileName).normalize();
            Path subDir = companyDir.resolve(category).normalize();
            Path subFile = subDir.resolve(fileName).normalize();

            try {
                if (Files.isRegularFile(flatFile) && !Files.exists(subFile)) {
                    Files.createDirectories(subDir);
                    Files.move(flatFile, subFile, StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } else {
                    skipped++;
                }
            } catch (IOException e) {
                log.warn("迁移品牌资产文件失败: {}/{}", category, fileName, e);
            }
        }

        touchMarker(markerFile);
        log.info("品牌资产子目录迁移完成: 移动 {} 个文件, 跳过 {} 个", moved, skipped);
    }

    private void touchMarker(Path markerFile) {
        try {
            Files.createDirectories(markerFile.getParent());
            Files.createFile(markerFile);
        } catch (IOException ignored) {
        }
    }
}
