package com.liqiang.archive.controller;

import com.liqiang.archive.dto.EmployeeDtos.*;
import com.liqiang.archive.dto.EmployeeExternalImportDtos.*;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.entity.EmployeeFile;
import com.liqiang.archive.repository.EmployeeFileRepository;
import com.liqiang.archive.service.EmployeeExternalImportService;
import com.liqiang.archive.service.EmployeeService;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
@RequirePermission("system.employee.manage")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeExternalImportService externalImportService;
    private final EmployeeFileRepository employeeFileRepository;
    private final FileHandleMapper fileHandleMapper;

    // ==================== 查询 ====================

    @GetMapping
    public Page<EmployeeSummary> list(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isSalesperson,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "department") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return employeeService.search(department, status, isSalesperson, accountStatus, keyword,
                PageRequest.of(page, size, Sort.by(dir, sort)));
    }

    @GetMapping("/filters")
    public FilterOptions filters() {
        return employeeService.filters();
    }

    @GetMapping("/{id}")
    public EmployeeDetail get(@PathVariable Long id) {
        return employeeService.get(id);
    }

    // ==================== 增删改 ====================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeSummary create(@Valid @RequestBody EmployeePayload payload) {
        return employeeService.create(payload);
    }

    @PutMapping("/{id}")
    public EmployeeSummary update(@PathVariable Long id, @Valid @RequestBody EmployeePayload payload) {
        return employeeService.update(id, payload);
    }

    @DeleteMapping("/{id}")
    public EmployeeDeleteResult delete(@PathVariable Long id) {
        return employeeService.delete(id);
    }

    // ==================== 外部身份映射 ====================

    @GetMapping("/{id}/mappings")
    public List<MappingSummary> listMappings(@PathVariable Long id) {
        return employeeService.listMappings(id);
    }

    @PostMapping("/{id}/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public MappingSummary addMapping(@PathVariable Long id, @Valid @RequestBody MappingPayload payload) {
        return employeeService.addMapping(id, payload);
    }

    @PutMapping("/{id}/mappings/{mappingId}")
    public MappingSummary updateMapping(@PathVariable Long id,
                                        @PathVariable Long mappingId,
                                        @Valid @RequestBody MappingPayload payload) {
        return employeeService.updateMapping(id, mappingId, payload);
    }

    @DeleteMapping("/{id}/mappings/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMapping(@PathVariable Long id, @PathVariable Long mappingId) {
        employeeService.removeMapping(id, mappingId);
    }

    // ==================== 外部通讯录导入映射（V186：氚云/钉钉导出文件） ====================

    /**
     * 解析氚云/钉钉通讯录导出文件并给出本地员工匹配建议（不落库）。
     */
    @PostMapping("/external-identities/parse")
    public ExternalImportPreview parseExternalIdentities(
            @RequestParam(value = "hyFile", required = false) MultipartFile hyFile,
            @RequestParam(value = "ddFile", required = false) MultipartFile ddFile,
            @RequestParam(value = "rosterFile", required = false) MultipartFile rosterFile) {
        return externalImportService.parse(hyFile, ddFile, rosterFile);
    }

    /**
     * 应用导入映射：写外部身份映射 + 团队桥接 + 回填事实表 + 回填/新建员工档案。
     */
    @PostMapping("/external-identities/apply")
    public ApplyExternalImportResult applyExternalIdentities(
            @RequestBody List<ApplyIdentityItem> items,
            @RequestHeader(value = "X-User-Name", required = false) String operator) {
        return externalImportService.apply(items, operator);
    }

    // ==================== 档案文件 ====================

    /**
     * 注册员工档案文件（物理上传由 POST /api/files/upload 完成，本端点写库登记）。
     * 类别白名单：id_card / labor_contract / education_cert / resignation_cert / health_cert / other
     */
    @PostMapping("/{id}/files")
    @ResponseStatus(HttpStatus.CREATED)
    public FileHandle registerFile(@PathVariable Long id, @RequestBody EmployeeFileRegistration payload) {
        employeeService.get(id); // 确保员工存在
        if (payload == null || payload.category() == null || payload.category().isBlank()
                || payload.fileName() == null || payload.fileName().isBlank()) {
            throw new IllegalArgumentException("category 和 fileName 不能为空");
        }
        EmployeeFile file = new EmployeeFile();
        file.setEmployeeId(id);
        file.setCategory(payload.category());
        file.setFileName(payload.fileName());
        file.setOriginalFileName(payload.originalFileName());
        file.setMimeType(payload.mimeType());
        file.setFileSize(payload.fileSize());
        return fileHandleMapper.from(employeeFileRepository.save(file));
    }

    public record EmployeeFileRegistration(
            String category,
            String fileName,
            String originalFileName,
            String mimeType,
            Long fileSize
    ) {
    }
}
