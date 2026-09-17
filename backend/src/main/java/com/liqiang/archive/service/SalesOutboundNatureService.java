package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 销售出库退换货类型推断与人工确认。人工结论不会被氚云重复导入覆盖。 */
@Service
public class SalesOutboundNatureService {
    private static final Set<String> CONFIRMABLE = Set.of("SALE", "RETURN", "EXCHANGE");

    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    private final AuditLogService audit;

    public SalesOutboundNatureService(JdbcTemplate jdbc, CurrentUserService users, AuditLogService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    /** 只刷新系统推断列；人工确认列和原单关联始终保留。 */
    @Transactional
    public void refreshInference(long outboundId) {
        Map<String, Object> outbound = jdbc.queryForMap(
                "SELECT sales_amount,shipping_method,order_remark FROM sales_outbounds WHERE id=?", outboundId);
        List<BigDecimal> quantities = jdbc.query(
                "SELECT quantity FROM sales_outbound_items WHERE sales_outbound_id=? AND quantity IS NOT NULL",
                (rs, n) -> rs.getBigDecimal(1), outboundId);
        BigDecimal sales = outbound.get("sales_amount") instanceof BigDecimal b ? b : BigDecimal.ZERO;
        String shipping = text(outbound.get("shipping_method"));
        String remark = text(outbound.get("order_remark"));

        Inference inference = infer(sales, quantities, shipping, remark);
        jdbc.update("UPDATE sales_outbounds SET inferred_business_type=?,business_type_confidence=?,business_type_reason=? WHERE id=?",
                inference.type(), inference.confidence(), inference.reason(), outboundId);
    }

    static Inference infer(BigDecimal sales, List<BigDecimal> quantities, String shipping, String remark) {
        sales = sales == null ? BigDecimal.ZERO : sales;
        quantities = quantities == null ? List.of() : quantities;
        shipping = shipping == null ? "" : shipping;
        remark = remark == null ? "" : remark;
        boolean negative = quantities.stream().anyMatch(v -> v.signum() < 0);
        boolean positive = quantities.stream().anyMatch(v -> v.signum() > 0);
        String type;
        int confidence;
        String reason;
        if (negative && positive) {
            type = "EXCHANGE"; confidence = 90; reason = "同时存在正数与负数产品明细，疑似换货";
        } else if (sales.signum() < 0) {
            type = "RETURN"; confidence = 95; reason = "销售金额为负数，疑似退货";
        } else if (negative) {
            type = "RETURN"; confidence = 95; reason = "存在负数出库产品明细，疑似退货";
        } else if (shipping.contains("退换货")) {
            type = "RETURN_OR_EXCHANGE"; confidence = 60; reason = "运输方式包含退换货，需人工确认";
        } else if (containsKeyword(remark)) {
            type = "RETURN_OR_EXCHANGE"; confidence = 60; reason = "订单备注包含退换货相关关键词，需人工确认";
        } else {
            type = "SALE"; confidence = 100; reason = "未发现退换货特征";
        }
        return new Inference(type, confidence, reason);
    }

    record Inference(String type, int confidence, String reason) {}

    public List<Map<String, Object>> originalCandidates(long outboundId) {
        return jdbc.queryForList("""
                SELECT candidate.id,candidate.outbound_no outboundNo,candidate.ship_date shipDate,
                       candidate.customer_name customerName,candidate.sales_amount salesAmount
                FROM sales_outbounds current
                JOIN sales_outbounds candidate ON candidate.customer_id=current.customer_id
                  AND candidate.id<>current.id AND candidate.outbound_status='CONFIRMED'
                  AND COALESCE(candidate.ship_date,candidate.business_date,'1000-01-01')
                      <= COALESCE(current.ship_date,current.business_date,'9999-12-31')
                WHERE current.id=?
                ORDER BY COALESCE(candidate.ship_date,candidate.business_date) DESC,candidate.id DESC
                LIMIT 50
                """, outboundId);
    }

    @Transactional
    public Map<String, Object> confirm(long outboundId, String businessType, Long originalOutboundId) {
        String type = businessType == null ? "" : businessType.trim().toUpperCase();
        if (!CONFIRMABLE.contains(type)) throw new IllegalArgumentException("业务类型必须是 SALE、RETURN 或 EXCHANGE");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sales_outbounds WHERE id=?", Integer.class, outboundId);
        if (exists == null || exists == 0) throw new IllegalArgumentException("销售出库不存在");
        if (originalOutboundId != null) {
            if (originalOutboundId.equals(outboundId)) throw new IllegalArgumentException("原销售单不能是当前出库单");
            Integer originalExists = jdbc.queryForObject("SELECT COUNT(*) FROM sales_outbounds WHERE id=?", Integer.class, originalOutboundId);
            if (originalExists == null || originalExists == 0) throw new IllegalArgumentException("关联的原销售单不存在");
        }
        if ("SALE".equals(type)) originalOutboundId = null;
        String operator = users.current().name();
        jdbc.update("""
                UPDATE sales_outbounds SET business_type_confirmed=1,confirmed_business_type=?,original_outbound_id=?,
                    business_type_confirmed_by=?,business_type_confirmed_at=NOW() WHERE id=?
                """, type, originalOutboundId, operator, outboundId);
        audit.log("SalesOutbound", outboundId, "退换货类型", "BUSINESS_TYPE_CONFIRM",
                "人工确认类型=" + type + (originalOutboundId == null ? "" : "，原销售出库ID=" + originalOutboundId));
        return Map.of("id", outboundId, "businessType", type, "originalOutboundId", originalOutboundId == null ? "" : originalOutboundId);
    }

    private static boolean containsKeyword(String value) {
        return value.contains("退货") || value.contains("换货") || value.contains("补发") || value.contains("重发");
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
