package com.liqiang.archive.controller;

import com.liqiang.archive.dto.CatalogDtos.ImportResult;
import com.liqiang.archive.service.CatalogImportService;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 目录导入接口
 *
 * <p>从 {@link ProductCatalogController} 拆分，保持原 URL 不变。
 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogImportController {

    private final CatalogImportService catalogImportService;

    @RequirePermission("product.manage")
    @PostMapping("/import")
    public ImportResult importCatalog(@RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "true") boolean reset) throws Exception {
        return catalogImportService.importCatalog(file, reset);
    }
}
