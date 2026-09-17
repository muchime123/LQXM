package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteInquiry;
import com.liqiang.archive.service.WebsiteInquiryService;
import com.liqiang.archive.service.WebsiteInquiryService.InquiryStats;
import com.liqiang.archive.service.WebsiteInquiryService.InquiryUpdateCommand;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 官网询盘后台管理接口。
 * 路径 /api/website/inquiries/admin/**，供后台员工处理询盘。
 * 公开提交接口仍在 WebsiteController 中（POST /api/website/inquiries）。
 */
@RestController
@RequestMapping("/api/website/inquiries/admin")
@RequiredArgsConstructor
public class WebsiteInquiryController {

    private final WebsiteInquiryService inquiryService;

    /** 分页查询：支持状态筛选 + 关键词搜索。 */
    @GetMapping("/list")
    public InquiryPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(1, size), 100));
        Page<WebsiteInquiry> result = inquiryService.search(status, keyword, pageable);
        List<InquirySummary> items = result.getContent().stream().map(InquirySummary::from).toList();
        return new InquiryPage(items, result.getTotalElements(), result.getTotalPages(), page);
    }

    /** 询盘详情。 */
    @GetMapping("/{id}")
    public InquiryDetail getById(@PathVariable Long id) {
        WebsiteInquiry inquiry = inquiryService.getById(id);
        return InquiryDetail.from(inquiry);
    }

    /** 更新询盘：状态流转 / 跟进记录 / 负责人 / 下次联系时间 / 关闭原因。 */
    @RequirePermission("website.manage")
    @PutMapping("/{id}")
    public InquiryDetail update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        InquiryUpdateCommand cmd = new InquiryUpdateCommand(
                req.status(),
                req.assignee(),
                req.followUpNote(),
                req.nextContactAt(),
                req.clearNextContactAt(),
                req.closedReason()
        );
        WebsiteInquiry updated = inquiryService.update(id, cmd);
        return InquiryDetail.from(updated);
    }

    /** 批量更新状态。 */
    @RequirePermission("website.manage")
    @PutMapping("/batch")
    public Map<String, Object> batchUpdateStatus(@RequestBody BatchUpdateRequest req) {
        int count = inquiryService.batchUpdateStatus(req.ids(), req.status());
        return Map.of("updated", count);
    }

    /** 统计：各状态数量 + 本月新增 + 待跟进数。 */
    @GetMapping("/stats")
    public InquiryStats stats() {
        return inquiryService.stats();
    }

    // ===== DTO =====

    /** 列表摘要视图（不含跟进记录等长文本）。 */
    public record InquirySummary(
            Long id,
            String name,
            String company,
            String phone,
            String email,
            String productFocus,
            String status,
            String sourcePage,
            String assignee,
            LocalDateTime createdAt,
            LocalDateTime nextContactAt
    ) {
        static InquirySummary from(WebsiteInquiry i) {
            return new InquirySummary(
                    i.getId(), i.getName(), i.getCompany(), i.getPhone(), i.getEmail(),
                    i.getProductFocus(), i.getStatus(), i.getSourcePage(), i.getAssignee(),
                    i.getCreatedAt(), i.getNextContactAt()
            );
        }
    }

    /** 详情视图（含完整跟进记录和需求描述）。 */
    public record InquiryDetail(
            Long id,
            String name,
            String company,
            String phone,
            String email,
            String productFocus,
            String description,
            String status,
            String sourcePage,
            String assignee,
            String followUpNote,
            String closedReason,
            LocalDateTime createdAt,
            LocalDateTime processedAt,
            LocalDateTime nextContactAt,
            LocalDateTime updatedAt
    ) {
        static InquiryDetail from(WebsiteInquiry i) {
            return new InquiryDetail(
                    i.getId(), i.getName(), i.getCompany(), i.getPhone(), i.getEmail(),
                    i.getProductFocus(), i.getDescription(), i.getStatus(), i.getSourcePage(),
                    i.getAssignee(), i.getFollowUpNote(), i.getClosedReason(),
                    i.getCreatedAt(), i.getProcessedAt(), i.getNextContactAt(), i.getUpdatedAt()
            );
        }
    }

    public record InquiryPage(List<InquirySummary> items, long total, int totalPages, int page) {}

    public record UpdateRequest(
            String status,
            String assignee,
            String followUpNote,
            LocalDateTime nextContactAt,
            Boolean clearNextContactAt,
            String closedReason
    ) {}

    public record BatchUpdateRequest(List<Long> ids, String status) {}
}
