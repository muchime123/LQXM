package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteInquiry;
import com.liqiang.archive.repository.WebsiteInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 官网询盘后台管理服务。
 * 支持分页查询、状态流转、跟进记录、批量处理和统计。
 */
@Service
@RequiredArgsConstructor
public class WebsiteInquiryService {

    private final WebsiteInquiryRepository inquiryRepository;
    private final AuditLogService auditLogService;

    /** 允许的状态值。 */
    private static final Set<String> VALID_STATUSES = Set.of("pending", "processed", "closed");

    /**
     * 后台分页查询。
     *
     * @param status  状态筛选（null 查全部）
     * @param keyword 关键词（匹配联系人/公司/电话/邮箱/关注产品）
     */
    @Transactional(readOnly = true)
    public Page<WebsiteInquiry> search(String status, String keyword, Pageable pageable) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return inquiryRepository.searchForAdmin(normalizedStatus, normalizedKeyword, pageable);
    }

    /** 询盘详情。 */
    @Transactional(readOnly = true)
    public WebsiteInquiry getById(Long id) {
        return inquiryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("询盘不存在: " + id));
    }

    /**
     * 更新询盘：状态流转 + 跟进记录 + 负责人 + 下次联系时间 + 关闭原因。
     * 仅更新非 null 字段，支持部分更新。
     */
    @Transactional
    public WebsiteInquiry update(Long id, InquiryUpdateCommand cmd) {
        WebsiteInquiry inquiry = getById(id);
        StringBuilder changes = new StringBuilder();

        if (cmd.status() != null && !cmd.status().equals(inquiry.getStatus())) {
            validateStatus(cmd.status());
            String oldStatus = inquiry.getStatus();
            inquiry.setStatus(cmd.status());
            inquiry.setProcessedAt(LocalDateTime.now());
            changes.append(String.format("状态: %s → %s; ", oldStatus, cmd.status()));

            // 关闭时记录原因
            if ("closed".equals(cmd.status()) && cmd.closedReason() != null) {
                inquiry.setClosedReason(cmd.closedReason().trim());
                changes.append("关闭原因: ").append(cmd.closedReason()).append("; ");
            }
        }

        if (cmd.assignee() != null) {
            String oldAssignee = inquiry.getAssignee();
            inquiry.setAssignee(cmd.assignee().isBlank() ? null : cmd.assignee().trim());
            changes.append("负责人: ").append(oldAssignee).append(" → ").append(inquiry.getAssignee()).append("; ");
        }

        if (cmd.followUpNote() != null && !cmd.followUpNote().isBlank()) {
            // 跟进记录追加（而非覆盖），带时间戳
            String timestamp = LocalDateTime.now().toString().substring(0, 16);
            String newNote = "[" + timestamp + "] " + cmd.followUpNote().trim();
            String existing = inquiry.getFollowUpNote();
            inquiry.setFollowUpNote(existing == null || existing.isBlank() ? newNote : existing + "\n" + newNote);
            changes.append("追加跟进记录; ");
        }

        if (cmd.nextContactAt() != null) {
            inquiry.setNextContactAt(cmd.nextContactAt());
            changes.append("下次联系: ").append(cmd.nextContactAt()).append("; ");
        } else if (cmd.clearNextContactAt() != null && cmd.clearNextContactAt()) {
            inquiry.setNextContactAt(null);
            changes.append("清除下次联系时间; ");
        }

        WebsiteInquiry saved = inquiryRepository.save(inquiry);
        auditLogService.log("website_inquiry", saved.getId(), null, "update", changes.toString());
        return saved;
    }

    /**
     * 批量更新状态。
     */
    @Transactional
    public int batchUpdateStatus(List<Long> ids, String status) {
        validateStatus(status);
        List<WebsiteInquiry> inquiries = inquiryRepository.findAllById(ids);
        if (inquiries.isEmpty()) {
            throw new EntityNotFoundException("未找到指定询盘");
        }
        boolean shouldSetProcessedAt = "processed".equals(status) || "closed".equals(status);
        LocalDateTime now = LocalDateTime.now();
        for (WebsiteInquiry inquiry : inquiries) {
            inquiry.setStatus(status);
            if (shouldSetProcessedAt) {
                inquiry.setProcessedAt(now);
            }
        }
        inquiryRepository.saveAll(inquiries);
        auditLogService.log("website_inquiry", 0L, null, "batch-update-status",
                String.format("批量更新 %d 条询盘状态为 %s", inquiries.size(), status));
        return inquiries.size();
    }

    /**
     * 统计数据：各状态数量 + 本月新增 + 待跟进数。
     */
    @Transactional(readOnly = true)
    public InquiryStats stats() {
        long pending = inquiryRepository.countByStatus("pending");
        long processed = inquiryRepository.countByStatus("processed");
        long closed = inquiryRepository.countByStatus("closed");

        LocalDateTime monthStart = LocalDateTime.of(LocalDateTime.now().getYear(),
                LocalDateTime.now().getMonth(), 1, 0, 0);
        long thisMonth = inquiryRepository.countByCreatedAtAfter(monthStart);

        // 待跟进：有下次联系时间且已到期且未关闭
        long pendingFollowUp = inquiryRepository
                .findByNextContactAtBeforeAndStatusNot(LocalDateTime.now(), "closed")
                .size();

        return new InquiryStats(pending, processed, closed, thisMonth, pendingFollowUp);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        validateStatus(status);
        return status;
    }

    private void validateStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("无效的询盘状态: " + status + "，允许值: " + VALID_STATUSES);
        }
    }

    /** 更新命令，所有字段可选（部分更新）。 */
    public record InquiryUpdateCommand(
            String status,
            String assignee,
            String followUpNote,
            LocalDateTime nextContactAt,
            Boolean clearNextContactAt,
            String closedReason
    ) {}

    /** 统计数据。 */
    public record InquiryStats(
            long pending,
            long processed,
            long closed,
            long thisMonth,
            long pendingFollowUp
    ) {}
}
