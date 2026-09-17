package com.liqiang.archive.controller;

import com.liqiang.archive.dto.PackagingMoldGroupDtos.MoldGroupPayload;
import com.liqiang.archive.dto.PackagingMoldGroupDtos.MoldGroupSummary;
import com.liqiang.archive.service.PackagingMoldGroupService;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/packaging-mold-groups")
@RequiredArgsConstructor
public class PackagingMoldGroupController {

    private final PackagingMoldGroupService service;

    @GetMapping
    public List<MoldGroupSummary> list(@RequestParam Long modelId) {
        return service.listByModel(modelId);
    }

    @RequirePermission("mold.manage")
    @PostMapping
    public MoldGroupSummary create(@RequestBody MoldGroupPayload payload) {
        return service.create(payload);
    }
}
