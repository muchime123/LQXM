package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Maintains website-owned product filters without touching archive product fields. */
@Service
@RequiredArgsConstructor
public class WebsiteProductSelectionService {
    private final WebsiteProductRepository products;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditLogService audit;

    @Transactional
    public SelectionView update(Long id, SelectionFields fields) {
        WebsiteProduct product = products.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
        if (!"DRAFT".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能修改官网产品草稿的筛选信息");
        }
        if (fields == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交筛选信息");
        List<String> applications = tags(fields.applicationTags());
        List<String> containers = tags(fields.containerTags());
        List<String> processes = tags(fields.processTags());
        String capacity = fields.capacityLevel() == null ? null : fields.capacityLevel().trim();
        if (capacity != null && capacity.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标产能不能超过 200 字");
        }
        if (capacity != null && capacity.isEmpty()) capacity = null;
        try {
            int changed = jdbc.update("""
                    UPDATE website_products
                    SET application_tags = ?, container_tags = ?, process_tags = ?, capacity_level = ?
                    WHERE id = ? AND status = 'DRAFT' AND deleted_at IS NULL
                    """, mapper.writeValueAsString(applications), mapper.writeValueAsString(containers),
                    mapper.writeValueAsString(processes), capacity, id);
            if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "官网产品状态已变化，请刷新后重试");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("官网产品筛选标签无法序列化", e);
        }
        audit.log("WebsiteProduct", id, product.getName(), "WEBSITE_PRODUCT_SELECTION_UPDATE", "更新官网产品筛选信息");
        return new SelectionView(id, applications, containers, processes, capacity);
    }

    private static List<String> tags(List<String> values) {
        if (values == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选标签必须是数组");
        if (values.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每组筛选标签最多 20 个");
        var normalized = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.trim().length() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选标签不能为空且不能超过 100 字");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    public record SelectionFields(List<String> applicationTags, List<String> containerTags,
                                  List<String> processTags, String capacityLevel) {}

    public record SelectionView(Long productId, List<String> applicationTags,
                                List<String> containerTags, List<String> processTags,
                                String capacityLevel) {}
}
