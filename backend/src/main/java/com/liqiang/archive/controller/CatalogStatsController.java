package com.liqiang.archive.controller;

import com.liqiang.archive.dto.CatalogDtos.CatalogStats;
import com.liqiang.archive.security.LoginOnly;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CatalogStatsExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目录统计与导出接口
 *
 * <p>从 {@link ProductCatalogController} 拆分，保持原 URL 不变。
 * 直接调用 {@link CatalogStatsExportService}，避免 Middle Man 代理。
 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
@RequirePermission("product.manage")
public class CatalogStatsController {

    private final CatalogStatsExportService statsExportService;

    @GetMapping("/stats")
    @LoginOnly
    public CatalogStats stats() {
        return statsExportService.stats();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String category) throws Exception {
        byte[] excel = statsExportService.exportCatalog(keyword, category);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment().filename("产品档案导出.xlsx").build());
        headers.setContentLength(excel.length);
        return new ResponseEntity<>(excel, headers, HttpStatus.OK);
    }

    @GetMapping("/models/{id}/export-zip")
    public ResponseEntity<byte[]> exportAssetsZip(@PathVariable Long id) throws Exception {
        byte[] zip = statsExportService.exportProductAssetsZip(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment().filename("产品素材_" + id + ".zip").build());
        headers.setContentLength(zip.length);
        return new ResponseEntity<>(zip, headers, HttpStatus.OK);
    }
}
