package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.repository.WebsiteCompanyMilestoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/** Website-owned timeline. Internal milestones are never copied or synchronized. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyMilestoneService {
    private final WebsiteCompanyMilestoneRepository milestones;
    private final AuditLogService audit;

    @Transactional(readOnly = true)
    public List<WebsiteCompanyMilestone> list() {
        return milestones.findByDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<WebsiteCompanyMilestone> published() {
        return milestones.findByStatusAndDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc("PUBLISHED");
    }

    @Transactional
    public WebsiteCompanyMilestone create(Fields fields) {
        validate(fields);
        var milestone = new WebsiteCompanyMilestone();
        apply(milestone, fields);
        WebsiteCompanyMilestone saved = milestones.save(milestone);
        audit.log("WebsiteCompanyMilestone", saved.getId(), saved.getTitle(),
                "WEBSITE_COMPANY_MILESTONE_CREATE", "创建官网发展历程草稿");
        return saved;
    }

    @Transactional
    public WebsiteCompanyMilestone update(Long id, Fields fields) {
        validate(fields);
        WebsiteCompanyMilestone item = getDraft(id);
        apply(item, fields);
        WebsiteCompanyMilestone saved = milestones.save(item);
        audit.log("WebsiteCompanyMilestone", id, saved.getTitle(),
                "WEBSITE_COMPANY_MILESTONE_UPDATE", "更新官网发展历程草稿");
        return saved;
    }

    @Transactional
    public WebsiteCompanyMilestone publish(Long id) {
        WebsiteCompanyMilestone item = milestones.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网发展历程不存在"));
        if ("PUBLISHED".equals(item.getStatus())) return item;
        if (!"DRAFT".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿可以发布");
        item.setStatus("PUBLISHED");
        item.setPublishedAt(LocalDateTime.now());
        WebsiteCompanyMilestone saved = milestones.save(item);
        audit.log("WebsiteCompanyMilestone", id, saved.getTitle(),
                "WEBSITE_COMPANY_MILESTONE_PUBLISH", "发布官网发展历程");
        return saved;
    }

    @Transactional
    public void archive(Long id) {
        WebsiteCompanyMilestone item = milestones.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网发展历程不存在"));
        item.setDeletedAt(LocalDateTime.now());
        milestones.save(item);
        audit.log("WebsiteCompanyMilestone", id, item.getTitle(),
                "WEBSITE_COMPANY_MILESTONE_ARCHIVE", "归档官网发展历程");
    }

    private WebsiteCompanyMilestone getDraft(Long id) {
        WebsiteCompanyMilestone item = milestones.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网发展历程不存在"));
        if (!"DRAFT".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "已发布历程不能通过草稿接口修改");
        return item;
    }

    private static void validate(Fields fields) {
        if (fields == null || fields.year() == null || fields.year() < 1800 || fields.year() > 2200
                || fields.title() == null || fields.title().isBlank() || fields.title().length() > 200
                || (fields.description() != null && fields.description().length() > 20_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网发展历程年份、标题或描述无效");
        }
    }

    private static void apply(WebsiteCompanyMilestone item, Fields fields) {
        item.setYear(fields.year());
        item.setTitle(fields.title().trim());
        item.setDescription(fields.description() == null || fields.description().isBlank() ? null : fields.description().trim());
        item.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    public record Fields(Integer year, String title, String description, Integer sortOrder) {}
}
