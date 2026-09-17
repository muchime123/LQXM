package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
@RequirePermission("system.employee.manage")
public class DepartmentController {
    private final DepartmentService service;

    @GetMapping public List<Map<String,Object>> list(){ return service.list(); }
    @GetMapping("/quality") public Map<String,Object> quality(){ return service.quality(); }
    @GetMapping("/companies") public List<Map<String,Object>> companies(){ return service.listCompanies(); }

    @PostMapping public Map<String,Object> create(@RequestBody DepartmentPayload body){
        return service.save(null,body.departmentCode(),body.name(),body.parentId(),body.companyId(),body.ownershipStatus(),body.unitType(),body.status());
    }
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable Long id,@RequestBody DepartmentPayload body){
        return service.save(id,body.departmentCode(),body.name(),body.parentId(),body.companyId(),body.ownershipStatus(),body.unitType(),body.status());
    }
    @PostMapping("/companies") public Map<String,Object> createCompany(@RequestBody CompanyPayload body){
        return service.saveCompany(null,body.companyCode(),body.name(),body.parentId(),body.status());
    }
    @PutMapping("/companies/{id}") public Map<String,Object> updateCompany(@PathVariable Long id,@RequestBody CompanyPayload body){
        return service.saveCompany(id,body.companyCode(),body.name(),body.parentId(),body.status());
    }
    public record DepartmentPayload(String departmentCode,String name,Long parentId,Long companyId,String ownershipStatus,String unitType,String status){}
    public record CompanyPayload(String companyCode,String name,Long parentId,String status){}
}
