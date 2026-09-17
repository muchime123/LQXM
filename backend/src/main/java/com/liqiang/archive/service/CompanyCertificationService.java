package com.liqiang.archive.service;

import com.liqiang.archive.dto.CompanyDtos.CertificationView;
import com.liqiang.archive.dto.CompanyDtos.CertificationPayload;
import com.liqiang.archive.entity.CompanyCertification;
import com.liqiang.archive.repository.CompanyCertificationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公司资质与合规文件管理服务。
 * 承载检测报告、营业执照、生产许可证、ISO 认证等所有时效性合规文件，
 * 通过 {@link CompanyCertification#getCategory()} 区分类型。
 * 到期数据由 {@link CertificateService#stats()} 聚合到知识产权页的到期提醒看板。
 */
@Service
@RequiredArgsConstructor
public class CompanyCertificationService {

    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "name", "nameEn", "category", "certNumber", "issueDate", "expiryDate",
            "isPermanent", "issuingAuthority", "description", "status",
            "websiteEnabled", "websiteFeatured", "websiteSortOrder", "publicDescription");

    private final CompanyCertificationRepository certRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<CertificationView> list(String category, String keyword) {
        return certRepository.findAll().stream()
                .filter(c -> category == null || category.isBlank() || category.equals(c.getCategory()))
                .filter(c -> keyword == null || keyword.isBlank()
                        || (c.getName() != null && c.getName().contains(keyword))
                        || (c.getCertNumber() != null && c.getCertNumber().contains(keyword))
                        || (c.getIssuingAuthority() != null && c.getIssuingAuthority().contains(keyword)))
                .map(CertificationView::from)
                .toList();
    }

    /** 官网资质分页查询：只返回 status='有效' 的资质。 */
    @Transactional(readOnly = true)
    public Page<CertificationView> searchWebsite(String keyword, String category, Pageable pageable) {
        return certRepository.searchWebsiteCertifications(keyword, category, pageable)
                .map(CertificationView::from);
    }

    /** 官网资质分类列表。 */
    @Transactional(readOnly = true)
    public List<String> getWebsiteCategories() {
        return certRepository.findWebsiteCategories();
    }

    @Transactional
    public CertificationView create(CertificationPayload payload) {
        CompanyCertification c = new CompanyCertification();
        applyPayload(c, payload);
        CompanyCertification saved = certRepository.save(c);
        auditLogService.log("CompanyCertification", saved.getId(), c.getName(),
                "CERT_CREATE", "创建公司资质/检测报告");
        return CertificationView.from(saved);
    }

    @Transactional
    public CertificationView update(Long id, CertificationPayload payload) {
        CompanyCertification c = certRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("资质记录不存在: " + id));
        applyPayload(c, payload);
        CompanyCertification saved = certRepository.save(c);
        auditLogService.log("CompanyCertification", id, c.getName(),
                "CERT_UPDATE", "更新公司资质/检测报告");
        return CertificationView.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        CompanyCertification c = certRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("资质记录不存在: " + id));
        certRepository.delete(c);
        auditLogService.log("CompanyCertification", id, c.getName(),
                "CERT_DELETE", "删除公司资质/检测报告 id=" + id);
    }

    @Transactional
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<CompanyCertification> certs = certRepository.findAllById(ids);
        certRepository.deleteAll(certs);
        int count = certs.size();
        if (count > 0) {
            auditLogService.log("CompanyCertification", null, "公司资质/合规文件",
                    "CERT_BATCH_DELETE", "批量删除公司资质：" + count + " 项");
        }
        return count;
    }

    @Transactional
    public int batchUpdateCategory(List<Long> ids, String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("分类不能为空");
        }
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<CompanyCertification> certs = certRepository.findAllById(ids);
        for (CompanyCertification c : certs) {
            c.setCategory(category);
        }
        if (!certs.isEmpty()) {
            certRepository.saveAll(certs);
        }
        int count = certs.size();
        if (count > 0) {
            auditLogService.log("CompanyCertification", null, "公司资质/合规文件",
                    "CERT_BATCH_MOVE_CATEGORY", "批量修改分类：" + count + " 项 → " + category);
        }
        return count;
    }

    @Transactional
    public int batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (ids == null || ids.isEmpty()) return 0;
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("未指定要修改的字段");
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) throw new IllegalArgumentException("不支持批量修改字段: " + key);
        }
        List<CompanyCertification> certs = certRepository.findAllById(ids);
        for (CompanyCertification cert : certs) applyBatchFields(cert, fields);
        if (!certs.isEmpty()) certRepository.saveAll(certs);
        if (!certs.isEmpty()) {
            auditLogService.log("CompanyCertification", null, "公司资质/合规文件",
                    "CERT_BATCH_UPDATE", "批量修改公司资质：" + certs.size() + " 项，字段：" + fields.keySet());
        }
        return certs.size();
    }

    private void applyBatchFields(CompanyCertification cert, Map<String, Object> fields) {
        fields.forEach((key, value) -> {
            switch (key) {
                case "name" -> cert.setName(stringValue(value));
                case "nameEn" -> cert.setNameEn(stringValue(value));
                case "category" -> cert.setCategory(stringValue(value));
                case "certNumber" -> cert.setCertNumber(stringValue(value));
                case "issueDate" -> cert.setIssueDate(dateValue(value));
                case "expiryDate" -> cert.setExpiryDate(dateValue(value));
                case "isPermanent" -> {
                    boolean permanent = booleanValue(value);
                    cert.setIsPermanent(permanent);
                    if (permanent) cert.setExpiryDate(null);
                }
                case "issuingAuthority" -> cert.setIssuingAuthority(stringValue(value));
                case "description" -> cert.setDescription(stringValue(value));
                case "status" -> cert.setStatus(stringValue(value));
                case "websiteEnabled" -> cert.setWebsiteEnabled(booleanValue(value));
                case "websiteFeatured" -> cert.setWebsiteFeatured(booleanValue(value));
                case "websiteSortOrder" -> cert.setWebsiteSortOrder(intValue(value));
                case "publicDescription" -> cert.setPublicDescription(stringValue(value));
                default -> throw new IllegalArgumentException("不支持批量修改字段: " + key);
            }
        });
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private LocalDate dateValue(Object value) {
        String text = stringValue(value);
        return text == null || text.isBlank() ? null : LocalDate.parse(text);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void applyPayload(CompanyCertification c, CertificationPayload p) {
        if (p.name() != null) c.setName(p.name());
        if (p.nameEn() != null) c.setNameEn(p.nameEn());
        if (p.category() != null) c.setCategory(p.category());
        if (p.certNumber() != null) c.setCertNumber(p.certNumber());
        if (p.issueDate() != null) c.setIssueDate(p.issueDate());
        if (p.expiryDate() != null) c.setExpiryDate(p.expiryDate());
        if (p.isPermanent() != null) c.setIsPermanent(p.isPermanent());
        if (p.issuingAuthority() != null) c.setIssuingAuthority(p.issuingAuthority());
        if (p.fileName() != null) c.setFileName(p.fileName());
        if (p.description() != null) c.setDescription(p.description());
        if (p.status() != null) c.setStatus(p.status());
        if (p.websiteEnabled() != null) c.setWebsiteEnabled(p.websiteEnabled());
        if (p.websiteFeatured() != null) c.setWebsiteFeatured(p.websiteFeatured());
        if (p.websiteSortOrder() != null) c.setWebsiteSortOrder(p.websiteSortOrder());
        if (p.publicDescription() != null) c.setPublicDescription(p.publicDescription());
        // 勾选"长期有效"时清空到期日
        if (Boolean.TRUE.equals(p.isPermanent())) c.setExpiryDate(null);
    }
}
