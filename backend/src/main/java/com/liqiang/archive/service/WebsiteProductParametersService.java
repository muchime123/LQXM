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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Edits only website-owned draft technical parameters. */
@Service
@RequiredArgsConstructor
public class WebsiteProductParametersService {
    private final WebsiteProductRepository products;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditLogService audit;

    @Transactional
    public ParametersView update(Long id, ParametersFields fields) {
        WebsiteProduct product = products.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
        if (!"DRAFT".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能修改官网产品草稿的技术参数");
        }
        if (fields == null || fields.technicalParameters() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技术参数必须是对象");
        }
        if (fields.technicalParameters().size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技术参数最多 50 项");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        fields.technicalParameters().forEach((key, value) -> {
            String name = key == null ? "" : key.trim();
            if (name.isEmpty() || name.length() > 100 || normalized.containsKey(name)
                    || value == null || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                    || value.toString().length() > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技术参数名称或内容不符合要求");
            }
            normalized.put(name, value instanceof String ? ((String) value).trim() : value);
        });
        try {
            String json = mapper.writeValueAsString(normalized);
            if (json.getBytes(StandardCharsets.UTF_8).length > 20_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技术参数内容不能超过 20 KB");
            }
            int changed = jdbc.update("""
                    UPDATE website_products SET technical_parameters = ?
                    WHERE id = ? AND status = 'DRAFT' AND deleted_at IS NULL
                    """, json, id);
            if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "官网产品状态已变化，请刷新后重试");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("官网产品技术参数无法序列化", e);
        }
        audit.log("WebsiteProduct", id, product.getName(), "WEBSITE_PRODUCT_PARAMETERS_UPDATE", "更新官网产品技术参数");
        return new ParametersView(id, normalized);
    }

    public record ParametersFields(Map<String, Object> technicalParameters) {}
    public record ParametersView(Long productId, Map<String, Object> technicalParameters) {}
}
