package com.liqiang.archive.controller;

import com.liqiang.archive.dto.MediaAccountDtos.AccountAnalyticsData;
import com.liqiang.archive.dto.MediaAccountDtos.AccountDetail;
import com.liqiang.archive.dto.MediaAccountDtos.AccountSummary;
import com.liqiang.archive.dto.MediaAccountDtos.AccountUpdateFrequency;
import com.liqiang.archive.dto.MediaAccountDtos.AccountUpdatePayload;
import com.liqiang.archive.dto.MediaAccountDtos.AnalyticsData;
import com.liqiang.archive.dto.MediaAccountDtos.MediaStats;
import com.liqiang.archive.dto.MediaAccountDtos.UpdatePayload;
import com.liqiang.archive.dto.MediaAccountDtos.BatchUpdateRequest;
import com.liqiang.archive.dto.MediaAccountDtos.PublishTaskPayload;
import com.liqiang.archive.dto.MediaAccountDtos.PublishTaskView;
import com.liqiang.archive.dto.MediaAccountDtos.PublishOwnerAssignRequest;
import com.liqiang.archive.dto.MediaAccountDtos.UpdateRecord;
import com.liqiang.archive.dto.MediaAccountDtos.UpdateImportResult;
import com.liqiang.archive.dto.MediaAccountDtos.UpdateImportPreviewResult;
import com.liqiang.archive.dto.MediaAccountDtos.UpdateImportConfirmRequest;
import com.liqiang.archive.dto.MediaAccountDtos.AccountCalendarPoint;
import com.liqiang.archive.entity.MediaAccount;
import com.liqiang.archive.service.MediaAccountService;
import com.liqiang.archive.security.RequirePermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/media-accounts")
@RequiredArgsConstructor
public class MediaAccountController {

    private final MediaAccountService mediaAccountService;

    // ==================== 账号 ====================

    @GetMapping
    public Page<AccountSummary> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String publishOwner,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "lastUpdatedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return mediaAccountService.search(platform, operator, publishOwner, keyword,
                PageRequest.of(page, size, Sort.by(dir, sort)));
    }

    // ==================== 固定路径端点（必须放在 /{id} 前面）====================

    @GetMapping("/stats")
    public MediaStats stats() {
        return mediaAccountService.stats();
    }

    /** 获取全量运营人员列表（用于筛选下拉） */
    @GetMapping("/operators")
    public List<String> operators() {
        return mediaAccountService.getAllOperators();
    }

    @GetMapping("/publish-owners")
    public List<String> publishOwners() {
        return mediaAccountService.getAllPublishOwners();
    }

    @RequirePermission("media.manage")
    @PutMapping("/batch")
    public Map<String, Integer> batchUpdateAccounts(@RequestBody BatchFieldsRequest request) {
        return Map.of("affected", mediaAccountService.batchUpdateFields(request.ids(), request.fields()));
    }

    @RequirePermission("media.manage")
    @DeleteMapping("/batch")
    public Map<String, Integer> batchDeleteAccounts(@RequestBody List<Long> ids) {
        return Map.of("affected", mediaAccountService.batchDeleteAccounts(ids));
    }

    public record BatchFieldsRequest(List<Long> ids, Map<String, Object> fields) {}

    // ==================== 路径变量端点（放在固定路径后面）====================

    @GetMapping("/{id}")
    public AccountDetail get(@PathVariable Long id) {
        return mediaAccountService.get(id);
    }

    @RequirePermission("media.manage")
    @PostMapping
    public AccountSummary create(@RequestBody MediaAccount account) {
        return mediaAccountService.create(account);
    }

    @RequirePermission("media.manage")
    @PutMapping("/{id}")
    public AccountSummary update(@PathVariable Long id, @RequestBody MediaAccount account) {
        return mediaAccountService.update(id, account);
    }

    @RequirePermission("media.manage")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        mediaAccountService.delete(id);
    }

    @RequirePermission("media.manage")
    @PostMapping("/import")
    public List<AccountSummary> importAccounts(@RequestParam("file") MultipartFile file) throws Exception {
        return mediaAccountService.importFromExcel(file);
    }

    @RequirePermission("media.manage")
    @PostMapping("/updates/import-preview")
    public UpdateImportPreviewResult previewUpdateImport(@RequestParam("file") MultipartFile file) throws Exception {
        return mediaAccountService.previewUpdatesFromPublishExport(file);
    }

    @RequirePermission("media.manage")
    @PostMapping("/updates/import-confirm")
    public UpdateImportResult confirmUpdateImport(@RequestBody UpdateImportConfirmRequest request) {
        return mediaAccountService.confirmUpdateImport(request);
    }

    /** 导出账号数据为 Excel（可筛选平台/运营人员/关键词） */
    @GetMapping("/export")
    public void exportAccounts(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String publishOwner,
            @RequestParam(required = false) String keyword,
            HttpServletResponse response) throws IOException {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String filename = "自媒体账号_" + today + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encoded);
        mediaAccountService.exportToExcel(response.getOutputStream(), platform, operator, publishOwner, keyword);
    }

    // ==================== 更新记录 ====================

    /** 全局最近更新时间线 */
    @GetMapping("/updates/recent")
    public Page<UpdateRecord> getRecentUpdates(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return mediaAccountService.getRecentUpdates(days,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updateDate")));
    }

    /** 为某账号添加更新记录 */
    @RequirePermission("media.manage")
    @PostMapping("/{id}/updates")
    public UpdateRecord addUpdate(@PathVariable Long id, @RequestBody UpdatePayload payload) {
        return mediaAccountService.addUpdate(id, payload);
    }

    /** 编辑更新记录 */
    @RequirePermission("media.manage")
    @PutMapping("/updates/{updateId}")
    public UpdateRecord editUpdate(@PathVariable Long updateId, @RequestBody UpdatePayload payload) {
        return mediaAccountService.editUpdate(updateId, payload);
    }

    /** 删除更新记录 */
    @RequirePermission("media.manage")
    @DeleteMapping("/updates/{updateId}")
    public void deleteUpdate(@PathVariable Long updateId) {
        mediaAccountService.deleteUpdate(updateId);
    }

    // ==================== 更新频率 ====================

    /** 获取所有账号的更新频率摘要 */
    @GetMapping("/update-frequency")
    public List<AccountUpdateFrequency> getUpdateFrequencies(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String operator) {
        return mediaAccountService.getUpdateFrequencies(platform, operator);
    }

    // ==================== Analytics ====================

    /** 数据分析汇总 */
    @GetMapping("/analytics")
    public AnalyticsData analytics(
            @RequestParam(defaultValue = "30") int days) {
        return mediaAccountService.analytics(days);
    }

    /** 单账号数据分析汇总 */
    @GetMapping("/{id}/analytics")
    public AccountAnalyticsData accountAnalytics(
            @PathVariable Long id,
            @RequestParam(defaultValue = "90") int days) {
        return mediaAccountService.accountAnalytics(id, days);
    }

    /** 批量记录今日更新（按平台或账号ID列表） */
    @RequirePermission("media.manage")
    @PostMapping("/batch-update")
    public Map<String, Object> batchTodayUpdate(@RequestBody BatchUpdateRequest request) {
        int count = mediaAccountService.batchTodayUpdate(request);
        return Map.of("updated", count);
    }

    @RequirePermission("media.manage")
    @PostMapping("/publish-tasks")
    public PublishTaskView createPublishTask(@RequestBody PublishTaskPayload request) {
        return mediaAccountService.createPublishTask(request);
    }

    @RequirePermission("media.manage")
    @PostMapping("/assign-publish-owner")
    public Map<String, Object> assignPublishOwner(@RequestBody PublishOwnerAssignRequest request) {
        int count = mediaAccountService.assignPublishOwner(request);
        return Map.of("updated", count);
    }
}
