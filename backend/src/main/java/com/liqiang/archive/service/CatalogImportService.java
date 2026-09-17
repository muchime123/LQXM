package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.ImportResult;
import com.liqiang.archive.entity.ImportRecord;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.repository.ImportRecordRepository;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CatalogImportService {

    private final ProductModelRepository modelRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductAssetRepository assetRepository;
    private final ImportRecordRepository importRecordRepository;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String H_NAME = "主字段";
    private static final String H_MAIN_IMAGE = "主图";
    private static final String H_SCENE_IMAGE = "应用场景图";
    private static final String H_SKU_IMAGE = "SKU主图";
    private static final String H_DETAIL_IMAGE = "细节图";
    private static final String H_PARAMETER_IMAGE = "参数表";
    private static final String H_DEVICE_VIDEO = "设备视频";
    private static final String H_OPERATION_VIDEO = "操作视频";
    private static final String H_MANUAL = "说明书";
    private static final String H_BROCHURE = "产品彩页";
    private static final String H_DRAWING = "结构图/图纸";
    private static final String H_CERTIFICATE = "合格证/证书";

    public static final List<AssetColumn> ASSET_COLUMNS = List.of(
            new AssetColumn(H_MAIN_IMAGE, "main_image"),
            new AssetColumn(H_SCENE_IMAGE, "scene_image"),
            new AssetColumn(H_SKU_IMAGE, "sku_image"),
            new AssetColumn(H_DETAIL_IMAGE, "detail_image"),
            new AssetColumn(H_PARAMETER_IMAGE, "parameter_image"),
            new AssetColumn(H_DEVICE_VIDEO, "device_video"),
            new AssetColumn(H_OPERATION_VIDEO, "operation_video"),
            new AssetColumn(H_MANUAL, "manual"),
            new AssetColumn(H_BROCHURE, "brochure"),
            new AssetColumn(H_DRAWING, "drawing"),
            new AssetColumn(H_CERTIFICATE, "certificate")
    );

    private static final Map<String, String> CATEGORY_FOLDERS = Map.ofEntries(
            Map.entry("main_image", "main_image"),
            Map.entry("scene_image", "scene_image"),
            Map.entry("sku_image", "sku_image"),
            Map.entry("detail_image", "detail_image"),
            Map.entry("parameter_image", "parameter_image"),
            Map.entry("device_video", "device_video"),
            Map.entry("operation_video", "operation_video"),
            Map.entry("manual", "manual"),
            Map.entry("brochure", "brochure"),
            Map.entry("drawing", "drawing"),
            Map.entry("certificate", "certificate")
    );

    private final DataFormatter formatter = new DataFormatter();

    /** 模板表头 — 与导入解析器完全匹配的列顺序 */
    public static final List<String> TEMPLATE_HEADERS = List.of(
            // === 标识列 ===
            "主字段",
            // === 产品级别 V2 ===
            "备注", "核心配置说明",
            // === 素材（填写文件名即可，放在对应子目录下） ===
            "主图", "应用场景图", "SKU主图", "细节图", "参数表",
            "设备视频", "操作视频", "说明书", "产品彩页", "结构图/图纸", "合格证/证书",
            // === 配置级别 V2 ===
            "规格备注"
    );

    @Value("${app.files.root}")
    private String fileRoot;

    /**
     * 执行 reset 模式时的全量清理。
     * <p>注意：必须用原生 DELETE 绕过 Hibernate @SQLRestriction，否则只能删"当前可见行"，
     * 导致已软删行残留在主表并不断累积，占用主键与磁盘空间。
     * 本方法不会操作独立的 *_archive 表（它们由 V41 迁移脚本管理）。
     */
    private void physicalResetProductTables() {
        // 按外键依赖顺序删除：assets -> variants -> models
        entityManager.createNativeQuery("DELETE FROM product_assets").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM product_variants").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM product_models").executeUpdate();
        auditLogService.log("CatalogImport", null, "physicalReset",
                "CATALOG_FULL_REBUILD", "全量重建：物理清空 product_assets / product_variants / product_models 三张表");
    }

    @Transactional
    public ImportResult importCatalog(MultipartFile file, boolean reset) throws IOException {
        ZipSecureFile.setMaxFileCount(10_000);
        if (reset) {
            physicalResetProductTables();
        }

        // 生成导入批次ID
        ImportRecord placeholder = new ImportRecord(
                file.getOriginalFilename(), 0, 0, 0, 0,
                reset ? "full_rebuild" : "incremental", "pending", "导入中..."
        );
        ImportRecord savedRecord = importRecordRepository.save(placeholder);
        Long batchId = savedRecord.getId();
        String sourceFile = file.getOriginalFilename();
        String sourceSheet = "Sheet1"; // 默认取第一个工作表

        Map<String, ProductModel> modelByName = new LinkedHashMap<>();
        int variants = 0;
        int assets = 0;
        int skippedRows = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            HeaderIndex headers = new HeaderIndex(sheet.getRow(0), formatter);

            // 获取工作表名
            if (workbook.getSheetName(0) != null) {
                sourceSheet = workbook.getSheetName(0);
            }

            List<ProductAsset> assetsToSave = new ArrayList<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String name = nameFromHeaders(row, headers);
                if (name.isBlank()) {
                    skippedRows++;
                    continue;
                }

                ProductModel model = modelByName.computeIfAbsent(name, key -> modelRepository.findByName(key)
                        .orElseGet(() -> createModel(row, headers, key)));
                mergeModel(model, row, headers);
                // V108 起 ProductModel 不再持有 sourceRowNumber，按 id 是否为空判断新记录
                modelRepository.save(model);

                ProductVariant variant = createVariant(model, row, headers, rowIndex + 1);
                variantRepository.save(variant);
                variants++;

                List<ProductAsset> rowAssets = createAssets(model, variant, row, headers, rowIndex + 1, batchId, sourceFile, sourceSheet);
                assetsToSave.addAll(rowAssets);
                assets += rowAssets.size();
            }

            if (!assetsToSave.isEmpty()) {
                assetRepository.saveAll(assetsToSave);
            }
        }

        String status = (skippedRows == 0) ? "success" : (modelByName.isEmpty()) ? "error" : "partial";
        String message = String.format("主产品 %d 个，配置 %d 条，素材 %d 个，跳过 %d 行",
                modelByName.size(), variants, assets, skippedRows);

        // 更新导入记录
        savedRecord.setModels(modelByName.size());
        savedRecord.setVariants(variants);
        savedRecord.setAssets(assets);
        savedRecord.setSkippedRows(skippedRows);
        savedRecord.setStatus(status);
        savedRecord.setMessage(message);
        savedRecord.setBatchId(batchId);
        importRecordRepository.save(savedRecord);

        auditLogService.log("CatalogImport", batchId, sourceFile,
                "CATALOG_IMPORT", "导入产品目录：" + message + "（模式 " + (reset ? "full_rebuild" : "incremental") + "）");

        return new ImportResult(modelByName.size(), variants, assets, skippedRows);
    }

    private ProductModel createModel(Row row, HeaderIndex headers, String name) {
        ProductModel model = new ProductModel();
        model.setName(name);
        mergeModel(model, row, headers);
        return modelRepository.save(model);
    }

    private void mergeModel(ProductModel model, Row row, HeaderIndex headers) {
        // V108 起 V1 model 字段全部删除，仅保留 V2 字段写入
        setIfBlank(model::getRemark, model::setRemark, cell(row, headers, "备注"));
        setIfBlank(model::getDescriptionCn, model::setDescriptionCn, cell(row, headers, "核心配置说明"));
        setExistingIfBlankOrMissing(model, model::getDefaultImage, model::setDefaultImage, firstExistingFile(model, cell(row, headers, H_MAIN_IMAGE), "main_image"), "main_image");
    }

    /**
     * UPSERT 逻辑：按 model_id + source_row_number 业务键匹配。
     * 已存在则更新字段，不存在则新建。
     * 避免每次增量导入都新增重复 variant 记录。
     */
    private ProductVariant createVariant(ProductModel model, Row row, HeaderIndex headers, int sourceRowNumber) {
        ProductVariant variant = variantRepository
                .findByModelIdAndSortOrder(model.getId(), sourceRowNumber)
                .orElseGet(() -> {
                    ProductVariant v = new ProductVariant();
                    v.setModel(model);
                    v.setSortOrder(sourceRowNumber);
                    return v;
                });
        variant.setVariantName(buildVariantName(row, headers, sourceRowNumber));
        variant.setRemark(cell(row, headers, "规格备注"));
        return variant;
    }

    private List<ProductAsset> createAssets(ProductModel model, ProductVariant variant, Row row, HeaderIndex headers, int sourceRowNumber,
                                              Long batchId, String sourceFile, String sourceSheet) {
        List<ProductAsset> result = new ArrayList<>();
        int sort = 0;
        for (AssetColumn column : ASSET_COLUMNS) {
            for (String fileName : splitFiles(cell(row, headers, column.header()))) {
                if (!fileExists(model, column.category(), fileName)) {
                    continue;
                }
                ProductAsset asset = new ProductAsset();
                asset.setModel(model);
                asset.setVariant(isVariantAsset(column.category()) ? variant : null);
                asset.setCategory(column.category());
                asset.setFileName(fileName);
                asset.setSourceColumn(column.header());
                asset.setSourceRowNumber(sourceRowNumber);
                asset.setSourceFile(sourceFile);
                asset.setSourceSheet(sourceSheet);
                asset.setImportBatchId(batchId);
                asset.setSortOrder(sort++);
                asset.setDefaultAsset(result.isEmpty());
                result.add(asset);
            }
        }
        return result;
    }

    private boolean isVariantAsset(String category) {
        return "sku_image".equals(category);
    }

    private String buildVariantName(Row row, HeaderIndex headers, int sourceRowNumber) {
        List<String> parts = new ArrayList<>();
        addPart(parts, firstNonBlank(cell(row, headers, "产品型号"), cell(row, headers, "型号"), cell(row, headers, "型号代码")));
        addPart(parts, cell(row, headers, "片膜/盒子尺寸"));
        addPart(parts, cell(row, headers, "盒/模具形状"));
        addPart(parts, cell(row, headers, "一模几出"));
        addPart(parts, cell(row, headers, "补充"));
        if (parts.isEmpty()) {
            return "配置 " + sourceRowNumber;
        }
        return String.join(" / ", parts);
    }

    private void addPart(List<String> parts, String value) {
        if (value != null && !value.isBlank() && !parts.contains(value)) {
            parts.add(value);
        }
    }

    private List<String> splitFiles(String value) {
        List<String> files = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return files;
        }
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank() && !trimmed.endsWith("...") && !files.contains(trimmed)) {
                files.add(trimmed);
            }
        }
        return files;
    }

    private String firstExistingFile(ProductModel model, String value, String category) {
        for (String fileName : splitFiles(value)) {
            if (fileExists(model, category, fileName)) {
                return fileName;
            }
        }
        return "";
    }

    private boolean fileExists(ProductModel model, String category, String fileName) {
        String folder = CATEGORY_FOLDERS.get(category);
        if (folder == null || fileName == null || fileName.isBlank() || fileName.contains("..")) {
            return false;
        }
        Path root = Path.of(fileRoot).toAbsolutePath().normalize();
        Path dir = root;
        if (model != null && model.getId() != null) {
            dir = fileStorageService.productDir(model.getId());
        }
        Path file = dir.resolve(folder).resolve(fileName).normalize();
        return file.startsWith(root) && Files.isRegularFile(file);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isModelCodeLike(String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.trim();
        return !Set.of("小型", "台式", "立式", "流水线").contains(v) && v.matches(".*[A-Za-z].*");
    }

    /**
     * 后值覆盖：只要传入值非空就覆盖写入。
     * 解决 Excel 中产品级字段散落在不同行的问题（第一行可能空白，后面行才有值）。
     */
    private void setIfBlank(java.util.function.Supplier<String> getter,
                            java.util.function.Consumer<String> setter,
                            String value) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private void setExistingIfBlankOrMissing(ProductModel model,
                                             java.util.function.Supplier<String> getter,
                                             java.util.function.Consumer<String> setter,
                                             String value,
                                             String category) {
        String current = getter.get();
        if (value == null || value.isBlank()) {
            return;
        }
        if (current == null || current.isBlank() || !fileExists(model, category, current)) {
            setter.accept(value);
        }
    }

    private String cell(Row row, HeaderIndex headers, String name) {
        Integer index = headers.index(name);
        if (index == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(index)).trim();
    }

    /** 从表头获取产品名称，兼容"数据标题"和"主字段"两种列名 */
    private String nameFromHeaders(Row row, HeaderIndex headers) {
        // 优先尝试 LQ.xlsx 格式的"数据标题"
        String name = cell(row, headers, "数据标题");
        if (!name.isBlank()) return name;
        // 回退到标准模板的"主字段"
        return cell(row, headers, H_NAME);
    }

    public record AssetColumn(String header, String category) {
    }

    private static class HeaderIndex {
        private final Map<String, Integer> firstByName = new HashMap<>();

        HeaderIndex(Row headerRow, DataFormatter formatter) {
            if (headerRow == null) {
                return;
            }
            for (Cell cell : headerRow) {
                String name = formatter.formatCellValue(cell).trim();
                if (!name.isBlank()) {
                    firstByName.putIfAbsent(name, cell.getColumnIndex());
                }
            }
        }

        Integer index(String name) {
            return firstByName.get(name);
        }
    }
}
