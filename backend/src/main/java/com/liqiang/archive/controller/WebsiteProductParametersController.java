package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductParametersService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/products")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductParametersController {
    private final WebsiteProductParametersService service;

    @PutMapping("/{id}/technical-parameters")
    public WebsiteProductParametersService.ParametersView update(@PathVariable Long id,
            @RequestBody WebsiteProductParametersService.ParametersFields fields) {
        return service.update(id, fields);
    }
}
