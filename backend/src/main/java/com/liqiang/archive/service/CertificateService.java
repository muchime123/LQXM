package com.liqiang.archive.service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.liqiang.archive.dto.CertificateDtos.CertificateDetail;
import com.liqiang.archive.dto.CertificateDtos.CertificatePayload;
import com.liqiang.archive.dto.CertificateDtos.CertificateStats;
import com.liqiang.archive.dto.CertificateDtos.CertificateSummary;
import com.liqiang.archive.dto.CertificateDtos.DueSoonItem;
import com.liqiang.archive.dto.CertificateDtos.FeeRecordPayload;
import com.liqiang.archive.dto.CertificateDtos.FeeRecordSummary;
import com.liqiang.archive.dto.CertificateDtos.FilterOptions;
import com.liqiang.archive.entity.AssetStatus;
import com.liqiang.archive.entity.Certificate;
import com.liqiang.archive.entity.CertificateFeeRecord;
import com.liqiang.archive.entity.CompanyCertification;
import com.liqiang.archive.entity.FeeStatus;
import com.liqiang.archive.repository.CertificateFeeRecordRepository;
import com.liqiang.archive.repository.CertificateRepository;
import com.liqiang.archive.repository.CompanyCertificationRepository;
import com.liqiang.archive.util.StringUtils;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final CertificateFeeRecordRepository feeRecordRepository;
    private final CompanyCertificationRepository companyCertificationRepository;
    private final AuditLogService auditLogService;

    /** 通用批量更新允许修改的字段白名单（不含名称/编号/日期/金额等标识与运营字段） */
    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "assetStatus", "assetCategory", "patentType", "applicant", "inventors",
            "examiner", "agency", "agencyContact", "legalStatus", "feeStatus",
            "invoiceStatus", "reviewProgress", "remark",
            "websiteEnabled", "websiteFeatured", "websiteSortOrder"
    );
    private static final Set<String> ASSET_CATEGORIES = Set.of("专利", "软著", "商标");
    private static final Set<String> PATENT_TYPES = Set.of("发明专利", "实用新型", "外观设计", "软著");

    /** 前端中文 → DB英文（用于搜索筛选） */
    private static String toDbStatus(String value) {
        if (value == null || value.isBlank())
            return null;
        String dbValue = AssetStatus.toDbValue(value.trim());
        return dbValue != null ? dbValue : value.trim();
    }

    /** DB英文 → 前端中文（用于列表/详情展示） */
    private static String toStatusLabel(String dbValue) {
        if (dbValue == null || dbValue.isBlank())
            return "";
        String label = AssetStatus.toDisplayName(dbValue);
        if (label != null && !label.equals(dbValue)) {
            return label;
        }
        label = FeeStatus.toDisplayName(dbValue);
        return label != null ? label : dbValue;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Page<CertificateSummary> search(
            String assetStatus,
            String assetCategory,
            String patentType,
            String feeStatus,
            String keyword,
            Pageable pageable) {
        // 前端传中文 → 转为 DB 英文枚举值
        Page<Certificate> page = certificateRepository.search(
                toDbStatus(assetStatus),
                StringUtils.blankToNull(assetCategory),
                StringUtils.blankToNull(patentType),
                StringUtils.blankToNull(feeStatus),
                StringUtils.blankToNull(keyword),
                pageable);
        // 英文 → 中文翻译后返回
        return page.map(c -> CertificateSummary.from(c, CertificateService::toStatusLabel));
    }

    @Transactional(readOnly = true)
    public CertificateDetail get(Long id) {
        return certificateRepository.findById(id)
                .map(c -> CertificateDetail.from(c, CertificateService::toStatusLabel))
                .orElseThrow(() -> new EntityNotFoundException("证书不存在: " + id));
    }

    // ==================== 增删改 ====================

    @Transactional
    public CertificateSummary create(CertificatePayload payload) {
        Certificate c = new Certificate();
        applyPayloadToEntity(c, payload);
        c.setQueryNo(resolveQueryNo(c, payload));
        // 自动计算到期日
        if (c.getApplyDate() != null && c.getLegalYears() != null && c.getExpireDate() == null) {
            c.setExpireDate(c.getApplyDate().plusYears(c.getLegalYears()));
        }
        Certificate saved = certificateRepository.save(c);
        auditLogService.log("Certificate", saved.getId(), c.getName(), "CERT_CREATE", "创建证书");
        return CertificateSummary.from(saved);
    }

    @Transactional
    public CertificateSummary update(Long id, CertificatePayload payload) {
        Certificate c = certificateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("证书不存在: " + id));
        applyPayloadToEntity(c, payload);
        // 号码变更后同步纯数字查询号（前端未显式填写时按申请号→专利号重新提取）
        c.setQueryNo(resolveQueryNo(c, payload));
        // 如果修改了申请日或法定年限，重新计算到期日
        if (c.getApplyDate() != null && c.getLegalYears() != null) {
            c.setExpireDate(c.getApplyDate().plusYears(c.getLegalYears()));
        }
        Certificate saved = certificateRepository.save(c);
        auditLogService.log("Certificate", id, c.getName(), "CERT_UPDATE", "更新证书");
        return CertificateSummary.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Certificate c = certificateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("证书不存在: " + id));
        // 软删除：设置 deletedAt（@SQLRestriction 会自动在普通查询隐藏）
        c.setDeletedAt(LocalDateTime.now());
        certificateRepository.save(c);
        auditLogService.log("Certificate", id, c.getName(), "CERT_DELETE", "软删除证书 id=" + id);
    }

    /**
     * 通用批量更新：按字段白名单 + 类型转换，将选中证书的指定元数据字段统一修改。
     * 仅接受 BATCH_EDITABLE_FIELDS 内的字段；未在 fields 中的字段不会被修改。
     *
     * @param ids    目标证书 ID 列表
     * @param fields 字段名 -> 新值（中文枚举值 / 文本）
     * @return 实际更新条数
     */
    @Transactional
    public int batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("未指定要修改的字段");
        }
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("不支持的批量修改字段: " + key);
            }
        }
        // 枚举字段取值校验
        if (fields.containsKey("assetStatus")) {
            String v = fields.get("assetStatus") == null ? null : fields.get("assetStatus").toString();
            if (StringUtils.nn(v).isBlank()) {
                throw new IllegalArgumentException("资产状态不能为空");
            }
            if (AssetStatus.fromDisplayName(v) == null) {
                throw new IllegalArgumentException("资产状态不合法: " + v);
            }
        }
        if (fields.containsKey("assetCategory")) {
            String v = fields.get("assetCategory") == null ? null : fields.get("assetCategory").toString();
            if (!ASSET_CATEGORIES.contains(v)) {
                throw new IllegalArgumentException("资产类别不合法: " + v);
            }
        }
        if (fields.containsKey("patentType")) {
            String v = fields.get("patentType") == null ? null : fields.get("patentType").toString();
            if (!PATENT_TYPES.contains(v)) {
                throw new IllegalArgumentException("专利类型不合法: " + v);
            }
        }
        if (fields.containsKey("feeStatus")) {
            String v = fields.get("feeStatus") == null ? null : fields.get("feeStatus").toString();
            if (FeeStatus.fromDisplayName(v) == null) {
                throw new IllegalArgumentException("缴费状态不合法: " + v);
            }
        }
        List<Certificate> certificates = certificateRepository.findAllById(ids);
        for (Certificate c : certificates) {
            applyFieldUpdate(c, fields);
            normalizeWebsitePublishing(c);
        }
        if (!certificates.isEmpty()) {
            certificateRepository.saveAll(certificates);
        }
        int count = certificates.size();
        auditLogService.log("Certificate", null, "批量",
                "CERT_BATCH_UPDATE", "批量修改 " + count + " 个证书字段：" + String.join("、", fields.keySet()));
        return count;
    }

    /** 将 fields 中的白名单字段逐个应用到实体（只覆盖传入的字段）。 */
    private void applyFieldUpdate(Certificate c, Map<String, Object> fields) {
        fields.forEach((key, value) -> {
            String v = value == null ? null : value.toString();
            switch (key) {
                case "assetStatus" -> c.setAssetStatus(toDbStatus(v));
                case "assetCategory" -> c.setAssetCategory(StringUtils.blankToNull(v));
                case "patentType" -> c.setPatentType(StringUtils.blankToNull(v));
                case "applicant" -> c.setApplicant(StringUtils.blankToNull(v));
                case "inventors" -> c.setInventors(StringUtils.blankToNull(v));
                case "examiner" -> c.setExaminer(StringUtils.blankToNull(v));
                case "agency" -> c.setAgency(StringUtils.blankToNull(v));
                case "agencyContact" -> c.setAgencyContact(StringUtils.blankToNull(v));
                case "legalStatus" -> c.setLegalStatus(StringUtils.blankToNull(v));
                case "feeStatus" -> c.setFeeStatus(StringUtils.blankToNull(v));
                case "invoiceStatus" -> c.setInvoiceStatus(StringUtils.blankToNull(v));
                case "reviewProgress" -> c.setReviewProgress(StringUtils.blankToNull(v));
                case "remark" -> c.setRemark(v == null ? null : v);
                case "websiteEnabled" -> c.setWebsiteEnabled(Boolean.parseBoolean(v));
                case "websiteFeatured" -> c.setWebsiteFeatured(Boolean.parseBoolean(v));
                case "websiteSortOrder" -> c.setWebsiteSortOrder(v == null || v.isBlank() ? 0 : Integer.parseInt(v));
                default -> { }
            }
        });
    }

    @Transactional
    public int autoCleanTrash(int daysOld) {
        int count = certificateRepository.autoCleanTrash(LocalDateTime.now().minusDays(daysOld));
        if (count > 0) {
            auditLogService.log("Certificate", null, "回收站", "CERT_AUTO_CLEAN",
                    "自动清理证书回收站，永久删除 " + count + " 条超过 " + daysOld + " 天的记录");
        }
        return count;
    }

    // ==================== 统计 ====================

    @Transactional(readOnly = true)
    public CertificateStats stats() {
        long total = certificateRepository.count();
        long active = certificateRepository.countByAssetStatus("ACTIVE");
        long expired = certificateRepository.countByAssetStatus("EXPIRED");
        long pending = certificateRepository.countByAssetStatus("PENDING");
        long transferred = certificateRepository.countByAssetStatus("TRANSFERRED");

        // 查询范围：已逾期1年 ~ 未来180天
        LocalDate today = LocalDate.now();
        LocalDate lookback = today.minusDays(365);
        LocalDate in180Days = today.plusDays(180);

        List<DueSoonItem> dueSoonItems = new ArrayList<>();

        // 1. 专利年费到期
        for (Certificate c : certificateRepository.findDueSoon(lookback, in180Days)) {
            if (c.getFeeDueDate() == null) continue;
            if ("EXPIRED".equals(c.getAssetStatus()) || "TRANSFERRED".equals(c.getAssetStatus())) continue;
            long days = ChronoUnit.DAYS.between(today, c.getFeeDueDate());
            dueSoonItems.add(new DueSoonItem(
                    c.getId(), "FEE_DUE", c.getName(),
                    c.getPatentNo(), c.getFeeDueDate(), days,
                    resolveQueryNo(c, null)));
        }

        // 2. 专利/软著保护期到期
        for (Certificate c : certificateRepository.findExpiringSoon(lookback, in180Days)) {
            if (c.getExpireDate() == null) continue;
            if ("EXPIRED".equals(c.getAssetStatus()) || "TRANSFERRED".equals(c.getAssetStatus())) continue;
            long days = ChronoUnit.DAYS.between(today, c.getExpireDate());
            dueSoonItems.add(new DueSoonItem(
                    c.getId(), "EXPIRY", c.getName(),
                    c.getPatentNo(), c.getExpireDate(), days,
                    resolveQueryNo(c, null)));
        }

        // 3. 企业资质到期
        for (CompanyCertification c : companyCertificationRepository.findExpiringSoon(lookback, in180Days)) {
            if (c.getExpiryDate() == null) continue;
            long days = ChronoUnit.DAYS.between(today, c.getExpiryDate());
            dueSoonItems.add(new DueSoonItem(
                    c.getId(), "CERT_EXPIRY", c.getName(),
                    c.getCertNumber(), c.getExpiryDate(), days, null));
        }

        // 按剩余天数升序（已逾期排最前）
        dueSoonItems.sort(Comparator.comparingLong(DueSoonItem::daysRemaining));

        // 按类型统计
        Map<String, Long> typeCounts = certificateRepository.countByPatentType().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]));

        // 按资产类别统计（专利/软著/商标）
        Map<String, Long> categoryCounts = certificateRepository.countByAssetCategory().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]));

        return new CertificateStats(
                total,
                active, expired, pending, transferred,
                dueSoonItems.size(),
                typeCounts.getOrDefault("发明专利", 0L),
                typeCounts.getOrDefault("实用新型", 0L),
                typeCounts.getOrDefault("外观设计", 0L),
                categoryCounts.getOrDefault("商标", 0L),
                categoryCounts.getOrDefault("软著", 0L),
                dueSoonItems);
    }

    // ==================== 筛选选项 ====================

    @Transactional(readOnly = true)
    public FilterOptions filterOptions() {
        return new FilterOptions(
                Arrays.asList("有效", "已失效", "未生效", "已转让"),
                Arrays.asList("专利", "软著", "商标"),
                Arrays.asList("发明专利", "实用新型", "外观设计", "软著"),
                Arrays.asList("待查询/待核实", "已支付", "未支付", "已失效待缴费", "已登记/已公告"));
    }

    // ==================== 缴费检查清单（参考手册第十一章） ====================

    /** 缴费前检查清单 */
    public record PaymentChecklist(
            List<ChecklistItem> beforePayment,
            List<ChecklistItem> afterPayment,
            List<ChecklistItem> archiveItems) {
        public record ChecklistItem(String label, String description) {
        }
    }

    @Transactional(readOnly = true)
    public PaymentChecklist getPaymentChecklist() {
        List<PaymentChecklist.ChecklistItem> beforePayment = Arrays.asList(
                new PaymentChecklist.ChecklistItem("专利清单已更新", "确认本次需要缴费的专利"),
                new PaymentChecklist.ChecklistItem("申请号/专利号已核对", "复制后肉眼核对，不带空格、中文符号"),
                new PaymentChecklist.ChecklistItem("公司名称已核对", "票据抬头：浙江利强包装科技有限公司"),
                new PaymentChecklist.ChecklistItem("统一社会信用代码已核对", "以公司证照信息为准"),
                new PaymentChecklist.ChecklistItem("费用名称已核对", "确认年费、滞纳金或其他费用项目"),
                new PaymentChecklist.ChecklistItem("缴费期限届满日已核对", "重点看是否已临近或逾期"),
                new PaymentChecklist.ChecklistItem("金额已核对", "以系统应缴费用查询结果为准，不手动改金额"),
                new PaymentChecklist.ChecklistItem("总金额与明细一致", "多件合并缴费时逐条展开检查"));
        List<PaymentChecklist.ChecklistItem> afterPayment = Arrays.asList(
                new PaymentChecklist.ChecklistItem("保存缴费成功截图", "记录订单金额、订单编号、订单交易号"),
                new PaymentChecklist.ChecklistItem("保存电子缴费清单", "文件名建议：专利缴费清单_YYYYMMDD.xlsx"),
                new PaymentChecklist.ChecklistItem("更新专利台账", "记录本次缴费日期、金额、项目、下次缴费期限"),
                new PaymentChecklist.ChecklistItem("反馈代理机构", "如代理机构参与提醒，同步反馈已完成缴费"));
        List<PaymentChecklist.ChecklistItem> archiveItems = Arrays.asList(
                new PaymentChecklist.ChecklistItem("发票已归档", "发票文件与缴费清单放在同一文件夹"),
                new PaymentChecklist.ChecklistItem("凭证已归档", "付款凭证、缴费截图、电子缴费清单统一归档"),
                new PaymentChecklist.ChecklistItem("下次缴费提醒已设置", "更新 fee_due_date 和 fee_query_date"),
                new PaymentChecklist.ChecklistItem("按年度归档", "建议结构：年份/年费缴纳记录/日期_金额/"));
        return new PaymentChecklist(beforePayment, afterPayment, archiveItems);
    }

    // ==================== Excel 导出 ====================

    @Transactional(readOnly = true)
    public void exportToExcel(OutputStream outputStream,
            String assetStatus, String assetCategory,
            String patentType, String feeStatus, String keyword) throws IOException {
        List<Certificate> list = certificateRepository.search(
                StringUtils.blankToNull(assetStatus),
                StringUtils.blankToNull(assetCategory),
                StringUtils.blankToNull(patentType),
                StringUtils.blankToNull(feeStatus),
                StringUtils.blankToNull(keyword),
                Pageable.unpaged()).getContent();

        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("知识产权清单");

        // 表头
        String[] headers = { "序号", "资产状态", "资产类别", "专利类型", "名称", "专利号",
                "申请号", "申请人", "发明人", "申请日", "授权日", "到期日", "剩余天数",
                "法律状态", "缴费状态", "年费截止日", "缴费金额", "备注",
                "尼斯分类", "核定使用商品", "注册人地址" };
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (Certificate c : list) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(rowIdx - 1);
            row.createCell(1).setCellValue(StringUtils.nn(c.getAssetStatus()));
            row.createCell(2).setCellValue(StringUtils.nn(c.getAssetCategory()));
            row.createCell(3).setCellValue(StringUtils.nn(c.getPatentType()));
            row.createCell(4).setCellValue(StringUtils.nn(c.getName()));
            row.createCell(5).setCellValue(StringUtils.nn(c.getPatentNo()));
            row.createCell(6).setCellValue(StringUtils.nn(c.getApplicationNo()));
            row.createCell(7).setCellValue(StringUtils.nn(c.getApplicant()));
            row.createCell(8).setCellValue(StringUtils.nn(c.getInventors()));
            row.createCell(9).setCellValue(c.getApplyDate() != null ? c.getApplyDate().toString() : "");
            row.createCell(10).setCellValue(c.getGrantDate() != null ? c.getGrantDate().toString() : "");
            row.createCell(11).setCellValue(c.getExpireDate() != null ? c.getExpireDate().toString() : "");
            if (c.getExpireDate() != null) {
                row.createCell(12).setCellValue(LocalDate.now().until(c.getExpireDate()).getDays());
            } else {
                row.createCell(12).setCellValue("");
            }
            row.createCell(13).setCellValue(StringUtils.nn(c.getLegalStatus()));
            row.createCell(14).setCellValue(StringUtils.nn(c.getFeeStatus()));
            row.createCell(15).setCellValue(c.getFeeDueDate() != null ? c.getFeeDueDate().toString() : "");
            if (c.getLastFeeAmount() != null) {
                row.createCell(16).setCellValue(c.getLastFeeAmount().doubleValue());
            } else {
                row.createCell(16).setCellValue("");
            }
            row.createCell(17).setCellValue(StringUtils.nn(c.getRemark()));
            // 商标专用字段（专利/软著行留空）
            row.createCell(18).setCellValue(c.getNiceClass() != null ? String.valueOf(c.getNiceClass()) : "");
            row.createCell(19).setCellValue(StringUtils.nn(c.getNiceClassDescription()));
            row.createCell(20).setCellValue(StringUtils.nn(c.getApplicantAddress()));
        }

        // 自动列宽
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        wb.write(outputStream);
        wb.close();
    }

    // ==================== 缴费记录 ====================

    @Transactional(readOnly = true)
    public List<FeeRecordSummary> getFeeRecords(Long certificateId) {
        // 先确认证书存在
        certificateRepository.findById(certificateId)
                .orElseThrow(() -> new EntityNotFoundException("证书不存在: " + certificateId));
        return feeRecordRepository.findByCertificateIdOrderByPaidDateDesc(certificateId)
                .stream()
                .map(FeeRecordSummary::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public FeeRecordSummary addFeeRecord(Long certificateId, FeeRecordPayload payload) {
        Certificate c = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new EntityNotFoundException("证书不存在: " + certificateId));
        CertificateFeeRecord r = new CertificateFeeRecord();
        r.setCertificateId(certificateId);
        payload.applyTo(r);
        // 同步更新证书表的最新缴费信息
        if (payload.paidDate() != null) {
            c.setFeeQueryDate(payload.paidDate().plusYears(1));
        }
        if (payload.dueDate() != null) {
            c.setFeeDueDate(payload.dueDate().plusYears(1));
        }
        if (payload.amount() != null) {
            c.setLastFeeAmount(payload.amount());
        }
        c.setFeeStatus("已支付");
        certificateRepository.save(c);
        CertificateFeeRecord saved = feeRecordRepository.save(r);
        auditLogService.log("Certificate", certificateId, c.getName(), "CERT_FEE_ADD", "新增缴费记录 recordId=" + saved.getId());
        return FeeRecordSummary.from(saved);
    }

    @Transactional
    public void deleteFeeRecord(Long certificateId, Long recordId) {
        CertificateFeeRecord r = feeRecordRepository.findById(recordId)
                .orElseThrow(() -> new EntityNotFoundException("缴费记录不存在: " + recordId));
        if (!r.getCertificateId().equals(certificateId)) {
            throw new IllegalArgumentException("缴费记录不属于该证书");
        }
        feeRecordRepository.delete(r);
        auditLogService.log("Certificate", certificateId, "", "CERT_FEE_DELETE", "删除缴费记录 recordId=" + recordId);
    }

    @Transactional
    public FeeRecordSummary updateFeeRecord(Long certificateId, Long recordId, FeeRecordPayload payload) {
        CertificateFeeRecord r = feeRecordRepository.findById(recordId)
                .orElseThrow(() -> new EntityNotFoundException("缴费记录不存在: " + recordId));
        if (!r.getCertificateId().equals(certificateId)) {
            throw new IllegalArgumentException("缴费记录不属于该证书");
        }
        payload.applyTo(r);
        CertificateFeeRecord saved = feeRecordRepository.save(r);
        auditLogService.log("Certificate", certificateId, "", "CERT_FEE_UPDATE", "更新缴费记录 recordId=" + recordId);
        return FeeRecordSummary.from(saved);
    }

    /** 将 Payload 写入 Entity，同时将中文状态值转为英文 DB 枚举 */
    private void applyPayloadToEntity(Certificate c, CertificatePayload payload) {
        if (payload.assetStatus() != null)
            c.setAssetStatus(toDbStatus(payload.assetStatus()));
        if (payload.assetCategory() != null)
            c.setAssetCategory(payload.assetCategory());
        if (payload.patentType() != null)
            c.setPatentType(payload.patentType());
        if (payload.name() != null)
            c.setName(payload.name());
        if (payload.specFile() != null)
            c.setSpecFile(payload.specFile());
        if (payload.certFile() != null)
            c.setCertFile(payload.certFile());
        if (payload.publicationNo() != null)
            c.setPublicationNo(payload.publicationNo());
        if (payload.applicationNo() != null)
            c.setApplicationNo(payload.applicationNo());
        if (payload.queryNo() != null)
            c.setQueryNo(payload.queryNo());
        if (payload.patentNo() != null)
            c.setPatentNo(payload.patentNo());
        if (payload.applicant() != null)
            c.setApplicant(payload.applicant());
        if (payload.inventors() != null)
            c.setInventors(payload.inventors());
        if (payload.examiner() != null)
            c.setExaminer(payload.examiner());
        if (payload.agency() != null)
            c.setAgency(payload.agency());
        if (payload.applyDate() != null)
            c.setApplyDate(payload.applyDate());
        if (payload.grantDate() != null)
            c.setGrantDate(payload.grantDate());
        if (payload.legalYears() != null)
            c.setLegalYears(payload.legalYears());
        if (payload.expireDate() != null)
            c.setExpireDate(payload.expireDate());
        if (payload.legalStatus() != null)
            c.setLegalStatus(payload.legalStatus());
        if (payload.feeStatus() != null)
            c.setFeeStatus(payload.feeStatus());
        if (payload.feeQueryDate() != null)
            c.setFeeQueryDate(payload.feeQueryDate());
        if (payload.feeDueDate() != null)
            c.setFeeDueDate(payload.feeDueDate());
        if (payload.lastFeeAmount() != null)
            c.setLastFeeAmount(payload.lastFeeAmount());
        if (payload.hasDrawing() != null)
            c.setHasDrawing(payload.hasDrawing());
        if (payload.feeDetail() != null)
            c.setFeeDetail(payload.feeDetail());
        if (payload.remark() != null)
            c.setRemark(payload.remark());
        if (payload.invoiceStatus() != null)
            c.setInvoiceStatus(payload.invoiceStatus());
        if (payload.agencyContact() != null)
            c.setAgencyContact(payload.agencyContact());
        if (payload.reviewProgress() != null)
            c.setReviewProgress(payload.reviewProgress());
        if (payload.niceClass() != null)
            c.setNiceClass(payload.niceClass());
        if (payload.niceClassDescription() != null)
            c.setNiceClassDescription(payload.niceClassDescription());
        if (payload.applicantAddress() != null)
            c.setApplicantAddress(payload.applicantAddress());
        if (payload.websiteEnabled() != null)
            c.setWebsiteEnabled(payload.websiteEnabled());
        if (payload.websiteFeatured() != null)
            c.setWebsiteFeatured(payload.websiteFeatured());
        if (payload.websiteSortOrder() != null)
            c.setWebsiteSortOrder(payload.websiteSortOrder());
        normalizeWebsitePublishing(c);
    }

    /**
     * 解析「查询号」：官方检索站（专利局/年费缴费）用的是纯数字申请号，
     * 例如 ZL202411238193.4、CN202411238193.4 都对应 2024112381934。
     * 前端显式填写时以其数字部分为准；否则按「申请号 → 专利号」自动提取，保证搜索栏可直接搜纯数字。
     */
    private static String resolveQueryNo(Certificate c, CertificatePayload payload) {
        String provided = payload == null ? null : StringUtils.blankToNull(payload.queryNo());
        if (provided != null) {
            return StringUtils.blankToNull(digitsOnly(provided));
        }
        String derived = digitsOnly(c.getApplicationNo());
        if (derived.isEmpty()) {
            derived = digitsOnly(c.getPatentNo());
        }
        return derived.isEmpty() ? null : derived;
    }

    /** 提取字符串中的数字部分（去除 ZL/CN/点号/空格等非数字字符） */
    private static String digitsOnly(String value) {
        if (value == null) return "";
        return value.replaceAll("\\D", "");
    }

    /** 保持官网发布状态一致：精选必然公开，下线同时取消精选。 */
    private void normalizeWebsitePublishing(Certificate c) {
        if (Boolean.TRUE.equals(c.getWebsiteFeatured())) {
            c.setWebsiteEnabled(true);
        }
        if (!Boolean.TRUE.equals(c.getWebsiteEnabled())) {
            c.setWebsiteFeatured(false);
        }
        if (c.getWebsiteSortOrder() == null) {
            c.setWebsiteSortOrder(0);
        }
    }

    // ==================== 官网公开查询 ====================

    /**
     * 官网专利/知识产权分页查询：只返回 website_enabled=true 且 asset_status='有效'。
     * 支持按 assetCategory（专利/软著/商标）和 patentType（发明专利/实用新型/外观设计/软著）筛选 + 关键词。
     * 返回脱敏后的 Certificate 实体（不含 feeStatus/feeDueDate/agencyContact/processStage 等内部敏感字段，
     * 由 WebsiteController.PublicPatentView 进一步脱敏）。
     */
    @Transactional(readOnly = true)
    public Page<Certificate> searchWebsite(String assetCategory, String patentType, String keyword, Pageable pageable) {
        return certificateRepository.searchWebsite(assetCategory, patentType, keyword, pageable);
    }

    /** 官网可见专利类型列表（用于筛选 Tab）。 */
    @Transactional(readOnly = true)
    public List<String> getWebsitePatentTypes() {
        return certificateRepository.findWebsitePatentTypes();
    }

    /** 官网可见资产类别列表（用于筛选 Tab，含专利/软著/商标）。 */
    @Transactional(readOnly = true)
    public List<String> getWebsiteAssetCategories() {
        return certificateRepository.findWebsiteAssetCategories();
    }

    /**
     * 官网首页精选专利：取 website_enabled=true 且 asset_status='有效' 的前 N 条，
     * 按 website_featured DESC, website_sort_order ASC, grant_date DESC 排序。
     */
    @Transactional(readOnly = true)
    public List<Certificate> getWebsiteFeaturedPatents(int limit) {
        return certificateRepository.findAllWebsiteEnabled(PageRequest.of(0, limit))
                .stream().toList();
    }

    /** 官网公开专利总数（首页统计用）。 */
    @Transactional(readOnly = true)
    public long getWebsitePatentCount() {
        return certificateRepository.countWebsiteEnabled();
    }
}
