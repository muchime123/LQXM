package com.liqiang.archive.service;

import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportPreview;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportConfirmRequest;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportRow;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.entity.MoldImportBatch;
import com.liqiang.archive.entity.MoldImportBatchItem;
import com.liqiang.archive.repository.MoldAccessoryRepository;
import com.liqiang.archive.repository.MoldVariantLinkRepository;
import com.liqiang.archive.repository.MoldImportBatchRepository;
import com.liqiang.archive.repository.MoldImportBatchItemRepository;
import com.liqiang.archive.repository.PackagingModelRepository;
import com.liqiang.archive.repository.PackagingMoldGroupRepository;
import com.liqiang.archive.repository.PackagingMoldRepository;
import com.liqiang.archive.repository.PackagingVariantRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PackagingMoldImportServiceTest {

    @Mock private PackagingMoldRepository moldRepo;
    @Mock private PackagingMoldGroupRepository groupRepo;
    @Mock private PackagingModelRepository modelRepo;
    @Mock private PackagingVariantRepository variantRepo;
    @Mock private MoldVariantLinkRepository linkRepo;
    @Mock private MoldAccessoryRepository accessoryRepo;
    @Mock private PackagingMoldService moldService;
    @Mock private AuditLogService auditLogService;
    @Mock private MoldImportBatchRepository importBatchRepo;
    @Mock private MoldImportBatchItemRepository importBatchItemRepo;

    private PackagingMoldImportService service;
    private PackagingModel model;

    @BeforeEach
    void setUp() {
        service = new PackagingMoldImportService(
                moldRepo, groupRepo, modelRepo, variantRepo, linkRepo,
                accessoryRepo, moldService, auditLogService, importBatchRepo, importBatchItemRepo);
        model = new PackagingModel();
        model.setId(10L);
        model.setName("打包盒");
        model.setPackagingKind("container");
        model.setMaterial("PP");
        lenient().when(modelRepo.findById(10L)).thenReturn(Optional.of(model));
        lenient().when(moldRepo.findAllMoldCodes()).thenReturn(List.of());
    }

    @Test
    void previewPreselectsHighestScoreWhenMultipleVariantsMatch() throws Exception {
        when(variantRepo.findByModelIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of(
                variant(101L, "140×140×25mm A", "140", "140", "25"),
                variant(102L, "140×140×25mm B", "140", "140", "25")
        ));

        MoldImportPreview preview = service.preview(workbook("1414方盒", "140×140", "M20708-8F", "25"), 10L);

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).variantMatch()).isEqualTo("multi_match");
        assertThat(preview.rows().get(0).matchCandidates()).hasSize(2);
        assertThat(preview.rows().get(0).selectedVariantId())
                .isEqualTo(preview.rows().get(0).matchCandidates().get(0).variantId());
    }

    @Test
    void previewDoesNotMatchByPlanarDimensionsWhenHeightIsOutsideTolerance() throws Exception {
        when(variantRepo.findByModelIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of(
                variant(101L, "140×140×30mm", "140", "140", "30")
        ));

        MoldImportPreview preview = service.preview(workbook("1414方盒", "140×140", "M20708-8F", "25"), 10L);

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).variantMatch()).isEqualTo("no_match");
        assertThat(preview.rows().get(0).matchCandidates()).isEmpty();
        assertThat(preview.rows().get(0).selectedVariantId()).isNull();
    }

    @Test
    void previewKeepsNominalPackagingSizeAsLooseCandidateForActualMoldCaliber() throws Exception {
        when(variantRepo.findByModelIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of(
                variant(101L, "180×120×35mm", "180", "120", "35")
        ));

        MoldImportPreview preview = service.preview(workbook("1812方盒", "187×120.2", "M24503-6F", "35"), 10L);

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).variantMatch()).isEqualTo("matched");
        assertThat(preview.rows().get(0).selectedVariantId()).isEqualTo(101L);
        assertThat(preview.rows().get(0).matchCandidates().get(0).diffFields())
                .contains("length_mm~loose");
    }

    @Test
    void previewMatchesRectangularCaliberWhenLengthAndWidthAreRotated() throws Exception {
        when(variantRepo.findByModelIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of(
                variant(101L, "180×120×35mm", "180", "120", "35")
        ));

        MoldImportPreview preview = service.preview(workbook("1812方盒", "120×180", "M24503-6F", "35"), 10L);

        assertThat(preview.rows().get(0).variantMatch()).isEqualTo("matched");
        assertThat(preview.rows().get(0).selectedVariantId()).isEqualTo(101L);
    }

    @Test
    void previewExpandsAsteriskSeparatedHeightsInsteadOfTreatingThemAsOneValue() throws Exception {
        when(variantRepo.findByModelIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of(
                variant(101L, "180×120×35mm", "180", "120", "35"),
                variant(102L, "180×120×50mm", "180", "120", "50")
        ));

        MoldImportPreview preview = service.preview(workbook("1812方盒", "180×120", "M24503-6F", "35*50"), 10L);

        assertThat(preview.rows()).extracting(row -> row.heightMm()).containsExactly("35", "50");
        assertThat(preview.rows()).extracting(row -> row.selectedVariantId()).containsExactly(101L, 102L);
    }

    @Test
    void confirmSkipsWholeMoldWhenExpandedRowsUseDifferentModels() {
        MoldImportRow first = importRow(2, "M20708-8F", "25");
        MoldImportRow second = importRow(2, "M20708-8F", "35");
        MoldImportPreview preview = new MoldImportPreview(
                null, "全系列自动匹配", "molds.xlsx", "模具规格",
                1, 2, 2, 0, 0, 0, 0, 2,
                List.of(), List.of(first, second), List.of());
        MoldImportConfirmRequest request = new MoldImportConfirmRequest(
                preview, List.of(0, 1), null, Map.of(), Map.of(0, 10L, 1, 11L));

        var result = service.confirm(preview, request);

        assertThat(result.createdMolds()).isZero();
        assertThat(result.mergedMolds()).isZero();
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.issues()).anyMatch(issue -> issue.contains("存在系列、产品名称或口径冲突"));
    }

    private MoldImportRow importRow(int sourceRow, String moldCode, String height) {
        return new MoldImportRow(
                sourceRow, "1414方盒", moldCode, "M20708", "F", 8,
                "方盒", "单格", "140×140", "140", "140", null,
                height, height, null, null, null, List.of(), null, null,
                "", "new", null, "no_match", List.of(), null, 10L, "打包盒");
    }

    private PackagingVariant variant(Long id, String name, String length, String width, String height) {
        PackagingVariant variant = new PackagingVariant();
        variant.setId(id);
        variant.setModel(model);
        variant.setVariantName(name);
        variant.setLengthMm(length);
        variant.setWidthMm(width);
        variant.setHeightMm(height);
        return variant;
    }

    private MockMultipartFile workbook(String productName, String caliber, String moldCode, String height)
            throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("模具规格");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品名称");
            header.createCell(1).setCellValue("产品口径/mm");
            header.createCell(2).setCellValue("模号");
            header.createCell(3).setCellValue("高度/mm");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(productName);
            row.createCell(1).setCellValue(caliber);
            row.createCell(2).setCellValue(moldCode);
            row.createCell(3).setCellValue(height);
            workbook.write(output);
            return new MockMultipartFile(
                    "file", "molds.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray());
        }
    }
}
