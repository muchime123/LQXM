package com.liqiang.archive.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.liqiang.archive.dto.CustomerRegionDtos.RegionAliasCreateRequest;
import com.liqiang.archive.dto.CustomerRegionDtos.RegionAliasItem;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CustomerRegionService;

import lombok.RequiredArgsConstructor;

/** 客户档案 · 地区缺口治理：写法别名（字典持久化扩展）与待标注清单导出。 */
@RestController
@RequestMapping("/api/customers/region")
@RequiredArgsConstructor
public class CustomerRegionController {

    private final CustomerRegionService regionService;

    /** 写法别名列表。 */
    @GetMapping("/aliases")
    public List<RegionAliasItem> listAliases() {
        return regionService.list();
    }

    /** 登记写法别名（立即对分布聚合与写入口归一生效）。 */
    @PostMapping("/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("customer.manage")
    public RegionAliasItem createAlias(@RequestBody RegionAliasCreateRequest request) {
        return regionService.create(request);
    }

    /** 删除写法别名。 */
    @DeleteMapping("/aliases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("customer.manage")
    public void deleteAlias(@PathVariable Long id) {
        regionService.delete(id);
    }

    /**
     * 地区缺口 CSV 导出（与客户列表筛选口径一致）：
     * noRegion=true 导出「未标注地区」，regionPartial=true 导出「待补市县」。
     */
    @GetMapping(value = "/export.csv", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) Boolean noRegion,
            @RequestParam(required = false) Boolean regionPartial,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String district) {
        String csv = regionService.buildRegionCsv(noRegion, regionPartial, province, city, district);
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String scope = Boolean.TRUE.equals(noRegion) ? "no-region"
                : Boolean.TRUE.equals(regionPartial) ? "partial-region" : "gap";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("customer-region-" + scope + "-" + stamp + ".csv")
                                .build().toString())
                .body(body);
    }

    /** 预览人工填写后的地区 CSV，不修改客户档案。 */
    @PostMapping("/import/preview")
    @RequirePermission("customer.manage")
    public Map<String, Object> previewImport(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        return regionService.previewRegionCsv(file);
    }

    /** 确认写回 CSV 中通过行政区划校验的客户地区。 */
    @PostMapping("/import/apply")
    @RequirePermission("customer.manage")
    public Map<String, Object> applyImport(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        return regionService.applyRegionCsv(file);
    }

    /** 预览可由详细地址自动补全、且通过地址选择器三级校验的客户。 */
    @PostMapping("/auto-parse/preview")
    @RequirePermission("customer.manage")
    public Map<String, Object> previewAutoParse() {
        return regionService.previewAutoParse();
    }

    /** 批量写回预览中校验通过的省市区县；已有有效字段发生冲突时不覆盖。 */
    @PostMapping("/auto-parse/apply")
    @RequirePermission("customer.manage")
    public Map<String, Object> applyAutoParse() {
        return regionService.applyAutoParse();
    }

    /** 预览无地址客户的出库单「地区」标注可回填的省份（自提单等没有收货地址的客户兜底）。 */
    @PostMapping("/outbound-fallback/preview")
    @RequirePermission("customer.manage")
    public Map<String, Object> previewRegionFallback() {
        return regionService.previewRegionFallback();
    }

    /** 写回出库单地区标注解析出的省份；仅补空白省份，不覆盖已有值。 */
    @PostMapping("/outbound-fallback/apply")
    @RequirePermission("customer.manage")
    public Map<String, Object> applyRegionFallback() {
        return regionService.applyRegionFallback();
    }

    /**
     * 「出库单地区=国外」的客户判定清单：真实外贸客户（标为境外）与国内转发/港澳台
     * （按档案地址回填省市）在这里逐条人工确认。
     */
    @PostMapping("/overseas/preview")
    @RequirePermission("customer.manage")
    public Map<String, Object> previewOverseas() {
        return regionService.previewOverseasDecisions();
    }

    /** 写回境外判定结果（OVERSEAS / DOMESTIC / PENDING）。 */
    @PostMapping("/overseas/apply")
    @RequirePermission("customer.manage")
    public Map<String, Object> applyOverseas(
            @RequestBody List<CustomerRegionService.OverseasDecision> decisions) {
        return regionService.applyOverseasDecisions(decisions);
    }
}
