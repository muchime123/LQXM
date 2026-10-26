package com.liqiang.archive.dto;

import com.liqiang.archive.entity.Certificate;
import com.liqiang.archive.entity.CertificateFeeRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

public class CertificateDtos {

    // ==================== 列表摘要 ====================

    /** 列表用轻量级 DTO */
    public record CertificateSummary(
            Long id,
            String assetStatus,
            String assetCategory,
            String patentType,
            String name,
            String patentNo,
            String applicationNo,
            String applicant,
            LocalDate applyDate,
            LocalDate grantDate,
            LocalDate expireDate,
            Long daysRemaining,
            String feeStatus,
            LocalDate feeDueDate,
            BigDecimal lastFeeAmount,
            LocalDate feeQueryDate,
            String specFile,
            String certFile,
            String invoiceStatus,
            String agencyContact,
            Integer niceClass,
            String applicantAddress
    ) {
    public static CertificateSummary from(Certificate c) {
        return from(c, v -> v);
    }

    /** 带状态翻译的 from（DB英文 → 前端中文）；已失效/已转让不计算剩余天数 */
    public static CertificateSummary from(Certificate c, Function<String, String> translate) {
        String translatedStatus = translate.apply(c.getAssetStatus());
        Long daysRemaining = null;
        boolean shouldComputeDays = !"已失效".equals(translatedStatus) && !"已转让".equals(translatedStatus);
        if (c.getExpireDate() != null && shouldComputeDays) {
            daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), c.getExpireDate());
        }
        return new CertificateSummary(
                c.getId(),
                translatedStatus,
                c.getAssetCategory(),
                c.getPatentType(),
                c.getName(),
                c.getPatentNo(),
                c.getApplicationNo(),
                c.getApplicant(),
                c.getApplyDate(),
                c.getGrantDate(),
                c.getExpireDate(),
                daysRemaining,
                translate.apply(c.getFeeStatus()),
                c.getFeeDueDate(),
                c.getLastFeeAmount(),
                c.getFeeQueryDate(),
                c.getSpecFile(),
                c.getCertFile(),
                c.getInvoiceStatus(),
                c.getAgencyContact(),
                c.getNiceClass(),
                c.getApplicantAddress()
        );
    }
    }

    // ==================== 详情 ====================

    public record CertificateDetail(
            Long id,
            String assetStatus,
            String assetCategory,
            String patentType,
            String name,
            String specFile,
            String certFile,
            String publicationNo,
            String applicationNo,
            String queryNo,
            String patentNo,
            String applicant,
            String inventors,
            String agency,
            LocalDate applyDate,
            LocalDate grantDate,
            Integer legalYears,
            LocalDate expireDate,
            Long daysRemaining,
            String legalStatus,
            String feeStatus,
            LocalDate feeQueryDate,
            LocalDate feeDueDate,
            BigDecimal lastFeeAmount,
            Boolean hasDrawing,
            String feeDetail,
            String remark,
            String invoiceStatus,
            String agencyContact,
            String reviewProgress,
            Integer niceClass,
            String niceClassDescription,
            String applicantAddress
    ) {
        public static CertificateDetail from(Certificate c) {
            return from(c, v -> v);
        }

        /** 带状态翻译的 from（DB英文 → 前端中文）；已失效/已转让不计算剩余天数 */
        public static CertificateDetail from(Certificate c, Function<String, String> translate) {
            String translatedStatus = translate.apply(c.getAssetStatus());
            Long daysRemaining = null;
            boolean shouldComputeDays = !"已失效".equals(translatedStatus) && !"已转让".equals(translatedStatus);
            if (c.getExpireDate() != null && shouldComputeDays) {
                daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), c.getExpireDate());
            }
            return new CertificateDetail(
                    c.getId(),
                    translatedStatus,
                    c.getAssetCategory(),
                    c.getPatentType(),
                    c.getName(),
                    c.getSpecFile(),
                    c.getCertFile(),
                    c.getPublicationNo(),
                    c.getApplicationNo(),
                    c.getQueryNo(),
                    c.getPatentNo(),
                    c.getApplicant(),
                    c.getInventors(),
                    c.getAgency(),
                    c.getApplyDate(),
                    c.getGrantDate(),
                    c.getLegalYears(),
                    c.getExpireDate(),
                    daysRemaining,
                    c.getLegalStatus(),
                    translate.apply(c.getFeeStatus()),
                    c.getFeeQueryDate(),
                    c.getFeeDueDate(),
                    c.getLastFeeAmount(),
                    c.getHasDrawing(),
                    c.getFeeDetail(),
                    c.getRemark(),
                    c.getInvoiceStatus(),
                    c.getAgencyContact(),
                    c.getReviewProgress(),
                    c.getNiceClass(),
                    c.getNiceClassDescription(),
                    c.getApplicantAddress()
            );
        }
    }

    // ==================== 创建/更新入参 ====================

    /** 前端提交的证书数据 */
    public record CertificatePayload(
            String assetStatus,
            String assetCategory,
            String patentType,
            String name,
            String specFile,
            String certFile,
            String publicationNo,
            String applicationNo,
            String queryNo,
            String patentNo,
            String applicant,
            String inventors,
            String agency,
            LocalDate applyDate,
            LocalDate grantDate,
            Integer legalYears,
            LocalDate expireDate,
            String legalStatus,
            String feeStatus,
            LocalDate feeQueryDate,
            LocalDate feeDueDate,
            BigDecimal lastFeeAmount,
            Boolean hasDrawing,
            String feeDetail,
            String remark,
            String invoiceStatus,
            String agencyContact,
            String reviewProgress,
            Integer niceClass,
            String niceClassDescription,
            String applicantAddress
    ) {
        /** 将 Payload 写入 Entity（用于更新） */
        public void applyTo(Certificate c) {
            if (assetStatus != null) c.setAssetStatus(assetStatus);
            if (assetCategory != null) c.setAssetCategory(assetCategory);
            if (patentType != null) c.setPatentType(patentType);
            if (name != null) c.setName(name);
            if (specFile != null) c.setSpecFile(specFile);
            if (certFile != null) c.setCertFile(certFile);
            if (publicationNo != null) c.setPublicationNo(publicationNo);
            if (applicationNo != null) c.setApplicationNo(applicationNo);
            if (queryNo != null) c.setQueryNo(queryNo);
            if (patentNo != null) c.setPatentNo(patentNo);
            if (applicant != null) c.setApplicant(applicant);
            if (inventors != null) c.setInventors(inventors);
            if (agency != null) c.setAgency(agency);
            if (applyDate != null) c.setApplyDate(applyDate);
            if (grantDate != null) c.setGrantDate(grantDate);
            if (legalYears != null) c.setLegalYears(legalYears);
            if (expireDate != null) c.setExpireDate(expireDate);
            if (legalStatus != null) c.setLegalStatus(legalStatus);
            if (feeStatus != null) c.setFeeStatus(feeStatus);
            if (feeQueryDate != null) c.setFeeQueryDate(feeQueryDate);
            if (feeDueDate != null) c.setFeeDueDate(feeDueDate);
            if (lastFeeAmount != null) c.setLastFeeAmount(lastFeeAmount);
            if (hasDrawing != null) c.setHasDrawing(hasDrawing);
            if (feeDetail != null) c.setFeeDetail(feeDetail);
            if (remark != null) c.setRemark(remark);
            if (invoiceStatus != null) c.setInvoiceStatus(invoiceStatus);
            if (agencyContact != null) c.setAgencyContact(agencyContact);
            if (reviewProgress != null) c.setReviewProgress(reviewProgress);
            if (niceClass != null) c.setNiceClass(niceClass);
            if (niceClassDescription != null) c.setNiceClassDescription(niceClassDescription);
            if (applicantAddress != null) c.setApplicantAddress(applicantAddress);
        }
    }

    // ==================== 统计 ====================

    public record CertificateStats(
            long totalCount,
            long activeCount,
            long expiredCount,
            long pendingCount,
            long transferredCount,
            long feeDueSoonCount,
            long inventionCount,
            long utilityCount,
            long designCount,
            long trademarkCount,
            long softwareCopyrightCount,
            List<DueSoonItem> dueSoonItems
    ) {
    }

    /** 到期提醒项（支持年费到期、保护期到期、资质到期） */
    public record DueSoonItem(
            Long id,
            String sourceType,
            String name,
            String number,
            LocalDate dueDate,
            long daysRemaining,
            /** 纯数字查询号（专利局/年费网站检索用），非专利来源为 null */
            String queryNo
    ) {
    }

    // ==================== 筛选可用值 ====================

    public record FilterOptions(
            List<String> assetStatuses,
            List<String> assetCategories,
            List<String> patentTypes,
            List<String> feeStatuses
    ) {
    }

    // ==================== 缴费记录 ====================

    /** 缴费记录摘要 */
    public record FeeRecordSummary(
            Long id,
            Long certificateId,
            Integer feeYear,
            LocalDate paidDate,
            LocalDate dueDate,
            BigDecimal amount,
            String feeType,
            String receiptNo,
            String orderNo,
            String transactionNo,
            String receiptTitle,
            String invoiceStatus,
            String payer,
            String note,
            LocalDateTime createdAt
    ) {
        public static FeeRecordSummary from(CertificateFeeRecord r) {
            return new FeeRecordSummary(
                    r.getId(),
                    r.getCertificateId(),
                    r.getFeeYear(),
                    r.getPaidDate(),
                    r.getDueDate(),
                    r.getAmount(),
                    r.getFeeType(),
                    r.getReceiptNo(),
                    r.getOrderNo(),
                    r.getTransactionNo(),
                    r.getReceiptTitle(),
                    r.getInvoiceStatus(),
                    r.getPayer(),
                    r.getNote(),
                    r.getCreatedAt()
            );
        }
    }

    /** 缴费记录入参 */
    public record FeeRecordPayload(
            Integer feeYear,
            LocalDate paidDate,
            LocalDate dueDate,
            BigDecimal amount,
            String feeType,
            String receiptNo,
            String orderNo,
            String transactionNo,
            String receiptTitle,
            String invoiceStatus,
            String payer,
            String note
    ) {
        public void applyTo(CertificateFeeRecord r) {
            r.setFeeYear(feeYear);
            r.setPaidDate(paidDate);
            r.setDueDate(dueDate);
            r.setAmount(amount);
            r.setFeeType(feeType != null ? feeType : "年费");
            r.setReceiptNo(receiptNo);
            r.setOrderNo(orderNo);
            r.setTransactionNo(transactionNo);
            r.setReceiptTitle(receiptTitle);
            r.setInvoiceStatus(invoiceStatus);
            r.setPayer(payer);
            r.setNote(note);
        }
    }
}
