package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.CatalogStats;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 目录统计与导出服务
 *
 * <p>从原 {@link ProductCatalogService} 拆分，负责只读统计与导出相关业务。
 */
@Service
@RequiredArgsConstructor
public class CatalogStatsExportService {

    private final ProductModelRepository modelRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductAssetRepository assetRepository;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public CatalogStats stats() {
        return new CatalogStats(modelRepository.count(), variantRepository.count(), assetRepository.count());
    }

    @Transactional(readOnly = true)
    public byte[] exportCatalog(String keyword, String category) throws Exception {
        List<ProductModel> models;
        if (keyword != null && !keyword.isBlank()) {
            models = modelRepository.findAll().stream()
                    .filter(m -> m.getName().contains(keyword) || (m.getCategory() != null && m.getCategory().contains(keyword)))
                    .collect(Collectors.toList());
        } else if (category != null && !category.isBlank()) {
            final String filter = category;
            models = modelRepository.findAll().stream()
                    .filter(m -> filter.equals(m.getCategory()))
                    .collect(Collectors.toList());
        } else {
            models = modelRepository.findAll();
        }
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("产品目录");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < CatalogImportService.TEMPLATE_HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(CatalogImportService.TEMPLATE_HEADERS.get(i));
            }
            int rowIndex = 1;
            for (ProductModel model : models) {
                List<ProductVariant> variants = variantRepository.findByModelIdOrderBySortOrderAsc(model.getId());
                if (variants.isEmpty()) {
                    Row row = sheet.createRow(rowIndex++);
                    fillModelRow(row, model, null);
                } else {
                    for (ProductVariant variant : variants) {
                        Row row = sheet.createRow(rowIndex++);
                        fillModelRow(row, model, variant);
                    }
                }
            }
            for (int i = 0; i < CatalogImportService.TEMPLATE_HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            sheet.createFreezePane(0, 1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private void fillModelRow(Row row, ProductModel model, ProductVariant variant) {
        int col = 0;
        setCell(row, col++, model.getName());
        setCell(row, col++, model.getRemark());
        setCell(row, col++, model.getDescriptionCn());
        List<ProductAsset> assets = assetRepository.findByModelIdOrderBySortOrderAscIdAsc(model.getId());
        Map<String, List<String>> assetFileNames = new HashMap<>();
        for (ProductAsset asset : assets) {
            assetFileNames.computeIfAbsent(asset.getCategory(), k -> new ArrayList<>()).add(asset.getFileName());
        }
        for (var colEntry : CatalogImportService.ASSET_COLUMNS) {
            List<String> files = assetFileNames.getOrDefault(colEntry.category(), List.of());
            setCell(row, col++, files.isEmpty() ? "" : String.join(",", files));
        }
        if (variant != null) {
            setCell(row, col++, variant.getRemark());
        }
    }

    private void setCell(Row row, int col, String value) {
        if (value == null) value = "";
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
    }

    // ===== 素材批量导出 ZIP =====

    private static final Map<String, String> CATEGORY_FOLDER_MAP = Map.ofEntries(
            Map.entry("main_image", "主图"),
            Map.entry("sku_image", "SKU图"),
            Map.entry("scene_image", "场景图"),
            Map.entry("detail_image", "细节图"),
            Map.entry("parameter_image", "参数表"),
            Map.entry("manual", "说明书"),
            Map.entry("brochure", "产品彩页"),
            Map.entry("drawing", "图纸"),
            Map.entry("certificate", "证书"),
            Map.entry("device_video", "设备视频"),
            Map.entry("operation_video", "操作视频")
    );

    @Transactional(readOnly = true)
    public byte[] exportProductAssetsZip(Long modelId) throws Exception {
        ProductModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException("产品不存在: " + modelId));
        List<ProductAsset> assets = assetRepository.findByModelIdOrderBySortOrderAscIdAsc(modelId);
        if (assets.isEmpty()) {
            throw new IllegalArgumentException("该产品没有素材文件");
        }
        Path productDir = fileStorageService.productDir(modelId);
        if (!Files.exists(productDir)) {
            throw new IllegalArgumentException("产品文件目录不存在");
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            Map<String, Integer> nameCount = new HashMap<>();
            for (ProductAsset asset : assets) {
                Path filePath = productDir.resolve(asset.getCategory()).resolve(asset.getFileName());
                if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                    continue;
                }
                String folderName = CATEGORY_FOLDER_MAP.getOrDefault(asset.getCategory(), asset.getCategory());
                String rawName = asset.getFileName();
                String entryName = folderName + "/" + rawName;
                int cnt = nameCount.merge(entryName, 1, Integer::sum);
                if (cnt > 1) {
                    int dot = rawName.lastIndexOf('.');
                    if (dot >= 0) {
                        entryName = folderName + "/" + rawName.substring(0, dot) + "_(" + cnt + ")" + rawName.substring(dot);
                    } else {
                        entryName = folderName + "/" + rawName + "_(" + cnt + ")";
                    }
                }
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                Files.copy(filePath, zos);
                zos.closeEntry();
            }
        }

        auditLogService.log("ProductModel", modelId, model.getName(), "export_zip",
                "打包导出 " + assets.size() + " 个素材");
        return bos.toByteArray();
    }
}
