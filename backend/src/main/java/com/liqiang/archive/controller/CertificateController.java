package com.liqiang.archive.controller;

import com.liqiang.archive.dto.CertificateDtos.CertificateDetail;
import com.liqiang.archive.dto.CertificateDtos.CertificatePayload;
import com.liqiang.archive.dto.CertificateDtos.CertificateStats;
import com.liqiang.archive.dto.CertificateDtos.CertificateSummary;
import com.liqiang.archive.dto.CertificateDtos.FeeRecordPayload;
import com.liqiang.archive.dto.CertificateDtos.FeeRecordSummary;
import com.liqiang.archive.dto.CertificateDtos.FilterOptions;
import com.liqiang.archive.service.CertificateService;
import com.liqiang.archive.service.CertificateService.PaymentChecklist;
import com.liqiang.archive.security.RequirePermission;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;

    /** 分页搜索列表 */
    @GetMapping
    public Page<CertificateSummary> list(
            @RequestParam(required = false) String assetStatus,
            @RequestParam(required = false) String assetCategory,
            @RequestParam(required = false) String patentType,
            @RequestParam(required = false) String feeStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "applyDate") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return certificateService.search(assetStatus, assetCategory, patentType, feeStatus, keyword,
                PageRequest.of(page, size, Sort.by(dir, sort)));
    }

    /** 获取单条详情 */
    @GetMapping("/{id}")
    public CertificateDetail get(@PathVariable Long id) {
        return certificateService.get(id);
    }

    /** 新增证书 */
    @RequirePermission("certificate.manage")
    @PostMapping
    public CertificateSummary create(@RequestBody CertificatePayload payload) {
        return certificateService.create(payload);
    }

    /** 更新证书 */
    @RequirePermission("certificate.manage")
    @PutMapping("/{id}")
    public CertificateSummary update(@PathVariable Long id, @RequestBody CertificatePayload payload) {
        return certificateService.update(id, payload);
    }

    /** 删除证书 */
    @RequirePermission("certificate.manage")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        certificateService.delete(id);
    }

    /**
     * 通用批量修改字段：按白名单一次性更新选中证书的元数据字段。
     * body: { "ids": [...], "fields": { "assetStatus": "已转让", "feeStatus": "已支付", "agency": "..." } }
     * 未出现在 fields 中的字段不会被修改。
     */
    @RequirePermission("certificate.manage")
    @PutMapping("/batch")
    public Map<String, Integer> batchUpdateFields(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
                .map(Number::longValue).toList();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        int count = certificateService.batchUpdateFields(ids, fields);
        return Map.of("affected", count);
    }

    /** 统计数据 */
    @GetMapping("/stats")
    public CertificateStats stats() {
        return certificateService.stats();
    }

    /** 筛选下拉选项 */
    @GetMapping("/filters")
    public FilterOptions filters() {
        return certificateService.filterOptions();
    }

    /** 导出 Excel */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String assetStatus,
            @RequestParam(required = false) String assetCategory,
            @RequestParam(required = false) String patentType,
            @RequestParam(required = false) String feeStatus,
            @RequestParam(required = false) String keyword,
            HttpServletResponse response) throws IOException {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String filename = "知识产权_" + today + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encoded);
        certificateService.exportToExcel(response.getOutputStream(),
                assetStatus, assetCategory, patentType, feeStatus, keyword);
    }

    // ==================== 缴费记录 ====================

    /** 获取某证书的所有缴费记录 */
    @GetMapping("/{certificateId}/fee-records")
    public List<FeeRecordSummary> listFeeRecords(@PathVariable Long certificateId) {
        return certificateService.getFeeRecords(certificateId);
    }

    /** 为某证书新增缴费记录 */
    @RequirePermission("certificate.manage")
    @PostMapping("/{certificateId}/fee-records")
    @ResponseStatus(HttpStatus.CREATED)
    public FeeRecordSummary addFeeRecord(@PathVariable Long certificateId,
                                         @RequestBody FeeRecordPayload payload) {
        return certificateService.addFeeRecord(certificateId, payload);
    }

    /** 删除缴费记录 */
    @RequirePermission("certificate.manage")
    @DeleteMapping("/{certificateId}/fee-records/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFeeRecord(@PathVariable Long certificateId,
                                @PathVariable Long recordId) {
        certificateService.deleteFeeRecord(certificateId, recordId);
    }

    /** 更新缴费记录 */
    @RequirePermission("certificate.manage")
    @PutMapping("/{certificateId}/fee-records/{recordId}")
    public FeeRecordSummary updateFeeRecord(@PathVariable Long certificateId,
                                            @PathVariable Long recordId,
                                            @RequestBody FeeRecordPayload payload) {
        return certificateService.updateFeeRecord(certificateId, recordId, payload);
    }

}