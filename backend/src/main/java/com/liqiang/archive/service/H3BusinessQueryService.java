package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.FeatureCode;
import com.liqiang.archive.security.DataScopeSupport;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class H3BusinessQueryService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUsers;
    private final DataScopeSupport scopeSupport;

    /**
     * [历史台账] 销售出库台账：仅展示氚云导入的销售出库原始数据镜像
     * （external_source='H3YUN'，主单 + 明细，即氚云销售出库数据源的留底台账），
     * 不做业务口径整合；整合后的业务结果见统一订单 / 正式出库。
     * 行级不可变原始快照由 h3_import_rows 永久留存，可随批次追溯。
     */
    public Map<String,Object> outboundLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE o.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (o.outbound_no LIKE ? OR o.customer_name LIKE ? OR o.dealer_name LIKE ? OR o.salesperson_name LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_outbounds o" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT o.id,o.outbound_no,o.status,o.outbound_status,o.ship_date,o.business_date,
                       o.external_title,o.customer_name,o.dealer_name,o.salesperson_name,o.region,
                       o.customer_id,c.customer_name linked_customer_name,
                       o.employee_id,e.name linked_employee_name,
                       o.sales_amount,o.source_gross_profit,o.received_amount,o.source_received_amount,
                       o.outstanding_amount,o.invoiced_amount,o.uninvoiced_amount,
                       o.inferred_business_type,o.business_type_confidence,o.business_type_confirmed,o.confirmed_business_type,
                       o.department_name,o.source_created_by,o.source_created_at,o.source_updated_at,
                       (SELECT COUNT(*) FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id) item_count,
                       (SELECT COUNT(*) FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.line_type='PRODUCT') product_item_count,
                       (SELECT COUNT(*) FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.line_type='PRODUCT' AND i.sales_product_id IS NOT NULL) linked_product_item_count,
                       o.warehouse,o.warehouse_id linked_warehouse_id,
                       (SELECT w.warehouse_name FROM warehouses w WHERE w.id=o.warehouse_id LIMIT 1) linked_warehouse_name
                  FROM sales_outbounds o
                  LEFT JOIN customers c ON c.id=o.customer_id AND c.deleted_at IS NULL
                  LEFT JOIN employees e ON e.id=o.employee_id
                """ + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /**
     * 以销售出库为一行的项目内关联工作台。这里查询的是关系完整度，不修改氚云来源快照。
     * relation: all / incomplete / customer / employee / product / warehouse / complete。
     */
    public Map<String,Object> outboundRelations(String keyword, String relation, int page, int size) {
        String customerMissing = "(o.customer_id IS NULL OR NOT EXISTS(SELECT 1 FROM customers rc WHERE rc.id=o.customer_id AND rc.deleted_at IS NULL))";
        String employeeMissing = "(o.employee_id IS NULL OR NOT EXISTS(SELECT 1 FROM employees re WHERE re.id=o.employee_id AND re.status='ACTIVE'))";
        String productMissing = "EXISTS(SELECT 1 FROM sales_outbound_items ri WHERE ri.sales_outbound_id=o.id AND ri.line_type='PRODUCT' AND (ri.sales_product_id IS NULL OR NOT EXISTS(SELECT 1 FROM sales_products rp WHERE rp.id=ri.sales_product_id AND rp.deleted_at IS NULL)))";
        String warehouseMissing = "(o.warehouse IS NOT NULL AND TRIM(o.warehouse)<>'' AND (o.warehouse_id IS NULL OR NOT EXISTS(SELECT 1 FROM warehouses rw WHERE rw.id=o.warehouse_id AND rw.active=1)))";
        String incomplete = "(" + customerMissing + " OR " + employeeMissing + " OR " + productMissing + " OR " + warehouseMissing + ")";
        boolean draftOnly = "draft".equalsIgnoreCase(relation);
        StringBuilder where = new StringBuilder(" WHERE o.external_source='H3YUN'");
        if (draftOnly) where.append(" AND (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))");
        else where.append(" AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))");
        ArrayList<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (o.outbound_no LIKE ? OR o.customer_name LIKE ? OR o.contact_phone LIKE ? OR o.customer_address LIKE ? OR o.salesperson_name LIKE ? OR EXISTS(SELECT 1 FROM sales_outbound_items ki WHERE ki.sales_outbound_id=o.id AND (ki.product_code LIKE ? OR ki.product_name LIKE ? OR ki.specification LIKE ?)))");
            for (int i=0;i<8;i++) params.add(k);
        }
        switch (relation == null ? "all" : relation.toLowerCase()) {
            case "incomplete" -> where.append(" AND ").append(incomplete);
            case "customer" -> where.append(" AND ").append(customerMissing);
            case "employee" -> where.append(" AND ").append(employeeMissing);
            case "product" -> where.append(" AND ").append(productMissing);
            case "warehouse" -> where.append(" AND ").append(warehouseMissing);
            case "complete" -> where.append(" AND NOT ").append(incomplete);
            case "draft" -> { }
            default -> { }
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_outbounds o" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT o.id,o.outbound_no,o.ship_date,o.status,o.customer_name,o.contact_phone,o.customer_address,
                       o.customer_id,c.customer_name linked_customer_name,
                       o.salesperson_name,o.employee_id,e.name linked_employee_name,
                       o.warehouse,o.warehouse_id linked_warehouse_id,w.warehouse_name linked_warehouse_name,
                       o.sales_amount,o.received_amount,o.outstanding_amount,
                       (SELECT COUNT(*) FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.line_type='PRODUCT') product_item_count,
                       (SELECT COUNT(*) FROM sales_outbound_items i JOIN sales_products p ON p.id=i.sales_product_id AND p.deleted_at IS NULL WHERE i.sales_outbound_id=o.id AND i.line_type='PRODUCT') linked_product_item_count
                  FROM sales_outbounds o
                  LEFT JOIN customers c ON c.id=o.customer_id AND c.deleted_at IS NULL
                  LEFT JOIN employees e ON e.id=o.employee_id
                  LEFT JOIN warehouses w ON w.id=o.warehouse_id
                """ + where + " ORDER BY COALESCE(o.ship_date,o.business_date) DESC,o.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        Map<String,Object> result = new LinkedHashMap<>(page(items,total,page,size));
        String countsSql = """
                SELECT SUM(CASE WHEN NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT')) THEN 1 ELSE 0 END) total,
                       SUM(CASE WHEN NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT')) AND %s THEN 1 ELSE 0 END) missingCustomer,
                       SUM(CASE WHEN NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT')) AND %s THEN 1 ELSE 0 END) missingEmployee,
                       SUM(CASE WHEN NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT')) AND %s THEN 1 ELSE 0 END) missingProduct,
                       SUM(CASE WHEN NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT')) AND %s THEN 1 ELSE 0 END) missingWarehouse,
                       SUM(CASE WHEN NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT')) AND NOT %s
                         THEN 1 ELSE 0 END) complete,
                       SUM(CASE WHEN o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT') THEN 1 ELSE 0 END) draftExcluded
                  FROM sales_outbounds o WHERE o.external_source='H3YUN'
                """.formatted(customerMissing, employeeMissing, productMissing, warehouseMissing, incomplete);
        result.put("counts", jdbc.queryForMap(countsSql));
        return result;
    }

    /**
     * [历史台账] 生产任务工单台账：仅展示氚云导入的生产工单原始数据，不参与业务编排。
     * 只作为来源台账展示，不再接入已经下线的正式生产设计。
     */
    public Map<String,Object> productionLedger(String keyword,String filter,int page,int size) {
        String base=" WHERE p.external_source='H3YUN'";
        StringBuilder where=new StringBuilder(base);
        java.util.ArrayList<Object> params=new java.util.ArrayList<>();
        if(keyword!=null&&!keyword.isBlank()){
            String k="%"+keyword.trim()+"%";
            where.append(" AND (p.work_order_no LIKE ? OR p.customer_name LIKE ? OR p.product_name LIKE ?)");
            params.add(k);params.add(k);params.add(k);
        }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM production_orders p"+where,Long.class,params.toArray());
        params.add(size);params.add((page-1)*size);
        List<Map<String,Object>> items=jdbc.query("""
                SELECT p.id,p.work_order_no,p.status,p.plan_type,p.application_date,
                       p.required_completion_date,p.actual_completion_date,
                       p.customer_name,p.salesperson_name,p.product_name,
                       p.required_quantity,p.completed_quantity,p.warehoused_quantity,
                       p.production_progress,p.warehousing_progress,
                       p.department_name,p.source_created_by,p.source_created_at,
                       (SELECT COUNT(*) FROM production_order_components c WHERE c.production_order_id=p.id) component_count
                  FROM production_orders p
                """+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",this::row,params.toArray());
        return page(items,total,page,size);
    }

    /**
     * [历史台账] 收款/结清台账：receipt_type=SINGLE_OUTBOUND 为收款记录，MULTI_OUTBOUND 为多订单结清。
     * 仅展示氚云导入的原始数据，不与销售出库分配关联。
     */
    public Map<String,Object> receiptLedger(boolean settlement, String keyword, int page, int size) {
        String receiptType = settlement ? "MULTI_OUTBOUND" : "SINGLE_OUTBOUND";
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN' AND r.receipt_type=?");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(receiptType);
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.receipt_no LIKE ? OR r.customer_name LIKE ? OR r.salesperson_name LIKE ?)");
            params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM receipts r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.receipt_no,r.receipt_type,r.status,r.receipt_date,
                       r.customer_name,r.dealer_name,r.salesperson_name,r.payment_method,
                       r.receipt_amount,r.receivable_amount,r.department_name,
                       r.source_created_by,r.source_created_at,
                       (SELECT l.outbound_no FROM receipt_outbound_links l WHERE l.receipt_id=r.id ORDER BY l.sequence_no LIMIT 1) AS outbound_no,
                       (SELECT l.sales_order_date FROM receipt_outbound_links l WHERE l.receipt_id=r.id ORDER BY l.sequence_no LIMIT 1) AS sales_order_date
                  FROM receipts r
                """ + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        // 多订单结清台账展开「覆盖哪些出库单」：明细来自 settlement_outbound_links（V222 保存的导入原始关联）
        if (settlement && !items.isEmpty()) {
            List<Long> ids = items.stream().map(i -> ((Number) i.get("id")).longValue()).toList();
            String ph = String.join(",", ids.stream().map(x -> "?").toArray(String[]::new));
            List<Map<String,Object>> links = jdbc.query(
                "SELECT l.receipt_id, l.sequence_no, l.outbound_no, l.outbound_id, l.settled_amount,\n"
                + "       COALESCE(o.customer_name,'') customer_name, COALESCE(o.sales_amount,0) sales_amount\n"
                + "FROM settlement_outbound_links l\n"
                + "LEFT JOIN sales_outbounds o ON o.id=l.outbound_id\n"
                + "WHERE l.receipt_id IN (" + ph + ") ORDER BY l.receipt_id, l.sequence_no",
                this::row, ids.toArray());
            Map<Long, List<Map<String,Object>>> byReceipt = new LinkedHashMap<>();
            for (Map<String,Object> lk : links) {
                byReceipt.computeIfAbsent(((Number) lk.get("receiptId")).longValue(), k -> new ArrayList<>()).add(lk);
            }
            for (Map<String,Object> it : items) {
                it.put("outboundLinks", byReceipt.getOrDefault(((Number) it.get("id")).longValue(), List.of()));
            }
        }
        return page(items, total, page, size);
    }

    // ===== [历史台账] 单行详情（主记录全字段 + 子表明细，供 H3LedgerPanel 详情抽屉） =====

    /**
     * 销售出库台账详情：主单全字段 + 产品明细（sales_outbound_items）。
     * 响应结构 { kind, row, rows }，rows 为出库产品明细行。
     */
    public Map<String,Object> outboundDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT o.id,o.external_id,o.outbound_no,o.status,o.ship_date,o.business_date,
                       o.customer_id,o.customer_name,o.dealer_name,o.salesperson_id,o.employee_id,o.salesperson_name,
                       c.customer_name linked_customer_name,e.name linked_employee_name,
                       o.business_owner,o.region,o.contact_phone,o.customer_address,
                       o.shipping_method,o.free_shipping,o.freight,o.warehouse,o.warehouse_keeper,
                       o.warehouse_id linked_warehouse_id,w.warehouse_name linked_warehouse_name,
                       o.payment_status,o.order_remark,o.collection_remark,
                       o.purchase_amount,o.sales_amount,o.source_gross_profit,o.received_amount,
                       o.outstanding_amount,o.invoiced_amount,o.uninvoiced_amount,o.invoicer,
                       o.external_title,o.department_name,o.source_created_by,o.source_created_at,
                       o.source_updated_at,o.source_hash,o.last_synced_at,o.created_at,o.updated_at
                       ,o.inferred_business_type,o.business_type_confidence,o.business_type_reason,
                       o.business_type_confirmed,o.confirmed_business_type,o.original_outbound_id,
                       original.outbound_no original_outbound_no,o.business_type_confirmed_by,o.business_type_confirmed_at
                  FROM sales_outbounds o
                  LEFT JOIN sales_outbounds original ON original.id=o.original_outbound_id
                  LEFT JOIN customers c ON c.id=o.customer_id AND c.deleted_at IS NULL
                  LEFT JOIN employees e ON e.id=o.employee_id
                  LEFT JOIN warehouses w ON w.id=o.warehouse_id
                 WHERE o.id=? AND o.external_source='H3YUN'
                """, "出库记录", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT i.sequence_no,i.line_type,i.category,i.product_code,i.product_name,i.specification,i.unit,
                       i.sales_product_id,sp.product_code linked_product_code,sp.product_name linked_product_name,
                       sp.specification linked_product_specification,sp.status linked_product_status,
                       i.quantity,i.sales_unit_price,i.sales_total,i.purchase_unit_price,i.purchase_total,
                       i.source_profit,i.stock_quantity,i.remark
                  FROM sales_outbound_items i
                  LEFT JOIN sales_products sp ON sp.id=i.sales_product_id AND sp.deleted_at IS NULL
                 WHERE i.sales_outbound_id=? ORDER BY i.sequence_no,i.id
                """, this::row, id);
        return Map.of("kind", "outbound", "row", row, "rows", rows);
    }

    /**
     * 生产任务工单台账详情：工单主表全字段 + 制程明细（production_order_components）。
     * 响应结构 { kind, row, rows }，rows 为零部件制程明细行。
     */
    public Map<String,Object> productionDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT p.id,p.external_id,p.work_order_no,p.status,p.plan_type,
                       p.application_date,p.required_completion_date,p.expected_completion_date,
                       p.actual_completion_date,p.customer_id,p.customer_name,p.salesperson_id,
                       p.salesperson_name,p.product_name,
                       p.required_quantity,p.completed_quantity,p.warehoused_quantity,
                       p.production_progress,p.warehousing_progress,p.production_completed,p.warehousing_completed,
                       p.box_size,p.box_shape,p.box_material,p.mold_layout,p.voltage,p.plug_type,p.brand,
                       p.optional_configuration,p.production_requirement,p.process_name,p.production_line,
                       p.order_receiver,p.warehouse_operator,p.linked_sales_order_no,p.linked_sales_order_ref,
                       p.product_order_external_id,p.department_name,p.source_created_by,p.source_created_at,
                       p.source_updated_at,p.source_hash,p.last_synced_at,p.created_at,p.updated_at
                  FROM production_orders p
                 WHERE p.id=? AND p.external_source='H3YUN'
                """, "生产工单", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT c.sequence_no,c.component_name,c.component_ref,c.component_serial_no,
                       c.required_quantity,c.requirement_factor,c.stock_quantity,c.stock_used_quantity,
                       c.completed_quantity,c.completion_ratio,c.completed_at,c.final_process,c.completed
                  FROM production_order_components c
                 WHERE c.production_order_id=? ORDER BY c.sequence_no,c.id
                """, this::row, id);
        return Map.of("kind", "production", "row", row, "rows", rows);
    }

    /**
     * 收款/结清台账详情：receipts 主单全字段；settlement=true（MULTI_OUTBOUND）额外附带
     * 覆盖的出库单链接（settlement_outbound_links，rows），与列表展开口径一致。
     */
    public Map<String,Object> receiptDetail(boolean settlement, Long id) {
        String receiptType = settlement ? "MULTI_OUTBOUND" : "SINGLE_OUTBOUND";
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_id,r.receipt_no,r.receipt_type,r.status,r.receipt_date,
                       r.customer_id,r.customer_name,r.dealer_name,r.salesperson_id,r.salesperson_name,
                       r.payment_method,r.receipt_amount,r.receivable_amount,r.allocated_amount,
                       r.unallocated_amount,r.remark,r.department_name,r.source_created_by,
                       r.source_created_at,r.source_updated_at,r.source_hash,r.last_synced_at,
                       r.created_at,r.updated_at
                  FROM receipts r
                 WHERE r.id=? AND r.external_source='H3YUN' AND r.receipt_type=?
                """, "收款记录", id, receiptType);
        if (!settlement) {
            enrichReceiptOutbound(row, id);
            return Map.of("kind", "receipt", "row", row);
        }
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT l.sequence_no,l.outbound_no,l.outbound_id,l.settled_amount,
                       COALESCE(o.customer_name,'') customer_name,COALESCE(o.sales_amount,0) sales_amount
                  FROM settlement_outbound_links l
                  LEFT JOIN sales_outbounds o ON o.id=l.outbound_id
                 WHERE l.receipt_id=? ORDER BY l.sequence_no
                """, this::row, id);
        return Map.of("kind", "settlement", "row", row, "rows", rows);
    }

    // ===== [历史台账] 供应商主数据 / 采购入库（V305，2026-09-17） =====

    /**
     * 供应商主数据台账列表：含别名引用数（历史单据引用文本数）与内部/脏值标记，
     * 便于一眼识别 4 条内部车间与 2 条串名脏值。
     */
    public Map<String,Object> supplierLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE s.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (s.name LIKE ? OR s.contact_name LIKE ? OR s.address LIKE ? OR s.remark LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_supplier_catalog s" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT s.id,s.name,s.normalized_name,s.supplier_status,s.is_internal,s.is_dirty_value,
                       s.contact_name,s.contact_phone,s.address,s.data_status,s.department_name,
                       s.source_created_at,s.source_updated_at,
                       (SELECT COUNT(*) FROM h3_supplier_aliases a WHERE a.supplier_id=s.id) alias_count
                  FROM h3_supplier_catalog s
                """ + where + " ORDER BY s.id LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /**
     * 采购入库台账列表：主表摘要 + 明细行数 + 供应商解析状态。
     * supplier_id 为空 = 解析失败（不静默新建，待人工核对）；data_status='草稿' 不进正式口径。
     */
    public Map<String,Object> purchaseLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE p.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (p.source_no LIKE ? OR p.supplier_name_raw LIKE ? OR p.owner_company LIKE ? OR p.warehouse_name LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_receipts p" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT p.id,p.source_no,p.data_status,p.purchase_date,p.supplier_id,
                       s.name supplier_name,p.supplier_name_raw,p.owner_company,p.warehouse_name,
                       p.keeper,p.total_quantity,p.total_amount,p.unpaid_amount,p.payment_status,
                       p.department_name,p.source_created_at,
                       (SELECT COUNT(*) FROM purchase_receipt_items i WHERE i.purchase_receipt_id=p.id) item_count
                  FROM purchase_receipts p
                  LEFT JOIN h3_supplier_catalog s ON s.id=p.supplier_id
                """ + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 供应商主数据详情：主档全字段 + 引用别名列表（哪些历史单据文本指向本供应商）。 */
    public Map<String,Object> supplierDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT s.id,s.external_id,s.name,s.normalized_name,s.supplier_status,
                       s.is_internal,s.is_dirty_value,
                       s.private_bank_name,s.private_bank_account,s.private_bank_branch,
                       s.public_bank_name,s.public_bank_account,s.public_bank_branch,
                       s.contact_name,s.contact_phone,s.address,s.remark,
                       s.data_status,s.source_object_id,s.source_title,s.owner_name,
                       s.department_name,s.created_by_name,s.source_created_at,s.source_updated_at,
                       s.source_hash,s.last_synced_at,s.created_at,s.updated_at
                  FROM h3_supplier_catalog s
                 WHERE s.id=? AND s.external_source='H3YUN'
                """, "供应商", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT a.id,a.raw_text,a.source,a.created_at
                  FROM h3_supplier_aliases a
                 WHERE a.supplier_id=? ORDER BY a.id
                """, this::row, id);
        return Map.of("kind", "supplier", "row", row, "rows", rows);
    }

    /** 采购入库详情：主单全字段 + 产品明细行（purchase_receipt_items），含解析后的供应商主档链接。 */
    public Map<String,Object> purchaseDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT p.id,p.external_id,p.source_no,p.data_status,p.purchase_date,
                       p.warehouse_id,p.warehouse_name,p.owner_company,
                       p.supplier_id,p.supplier_name_raw,s.name linked_supplier_name,
                       p.contact_name,p.contact_phone,p.supplier_address,
                       p.total_amount,p.total_quantity,p.expected_arrival_date,p.keeper,
                       p.unpaid_amount,p.uninvoiced_amount,p.paid_amount,p.payment_status,
                       p.order_remark,p.payment_remark,
                       p.source_title,p.owner_name,p.department_name,p.created_by_name,
                       p.source_created_at,p.source_updated_at,p.source_hash,p.last_synced_at,
                       p.created_at,p.updated_at
                  FROM purchase_receipts p
                  LEFT JOIN h3_supplier_catalog s ON s.id=p.supplier_id
                 WHERE p.id=? AND p.external_source='H3YUN'
                """, "采购入库单", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT i.line_no,i.source_item_id,i.product_code,i.product_name,i.category,i.spec,i.unit,
                       i.quantity,i.purchase_price,i.amount,i.stock_quantity,i.is_mold,i.has_process,
                       i.task_salesperson,i.production_order_no,i.production_order_raw,i.remark
                  FROM purchase_receipt_items i
                 WHERE i.purchase_receipt_id=? ORDER BY i.line_no,i.id
                """, this::row, id);
        return Map.of("kind", "purchase", "row", row, "rows", rows);
    }

    // ===== [历史台账] 其他入库/其他出库（V306）与库存调拨（V307），2026-09-17 建设 =====

    /**
     * 其他入库 / 其他出库台账列表（V306 单表 + direction 位）。
     * 两份导出件同构且明细前缀都叫「其他出库明细.」→ 落库合并为一张表，靠方向区分；
     * data_status='草稿' 仅标记保留、不进正式口径（与采购入库/调拨同口径）。
     */
    public Map<String,Object> otherMovementLedger(boolean inbound, String keyword, int page, int size) {
        // dir 为字面量常量（非用户输入），无注入面
        String dir = inbound ? "IN" : "OUT";
        StringBuilder where = new StringBuilder(" WHERE m.external_source='H3YUN' AND m.direction='" + dir + "'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (m.source_no LIKE ? OR m.warehouse_name LIKE ? OR m.keeper LIKE ? OR m.remark LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_other_stock_movements m" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT m.id,m.direction,m.source_no,m.movement_date,
                       m.warehouse_id,m.warehouse_name,m.keeper,m.salesman,
                       m.total_quantity,m.total_amount,m.remark,m.data_status,
                       m.source_title,m.department_name,m.source_created_at,m.source_updated_at,
                       (SELECT COUNT(*) FROM h3_other_stock_movement_items i WHERE i.movement_id=m.id) item_count
                  FROM h3_other_stock_movements m
                """ + where + " ORDER BY m.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /**
     * 其他入库 / 其他出库详情：主单全字段 + 产品明细行。
     * kind 由行内 direction 决定（IN→otherIn / OUT→otherOut），供前端台账类型回显。
     */
    public Map<String,Object> otherMovementDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT m.id,m.direction,m.external_source,m.external_id,m.source_no,m.movement_date,
                       m.warehouse_id,m.warehouse_name,m.keeper,m.salesman,
                       m.total_quantity,m.total_amount,m.remark,m.data_status,
                       m.source_title,m.owner_name,m.department_name,m.created_by_name,
                       m.source_created_at,m.source_updated_at,m.source_hash,m.last_synced_at,
                       m.created_at,m.updated_at
                  FROM h3_other_stock_movements m
                 WHERE m.id=? AND m.external_source='H3YUN'
                """, "其他出入库单", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT i.line_no,i.source_item_id,i.product_code,i.product_name,i.category,i.spec,i.unit,
                       i.quantity,i.stock_quantity,i.purchase_price,i.amount,i.remark
                  FROM h3_other_stock_movement_items i
                 WHERE i.movement_id=? ORDER BY i.line_no,i.id
                """, this::row, id);
        String kind = "IN".equals(String.valueOf(row.get("direction"))) ? "otherIn" : "otherOut";
        return Map.of("kind", kind, "row", row, "rows", rows);
    }

    /**
     * 库存调拨台账列表（V307）：双仓库（调出/调入）与双仓管员，故不合并方向位。
     * data_status 非「已生效」（草稿/已取消）仅标记保留、不进正式口径。
     */
    public Map<String,Object> transferLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE t.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (t.source_no LIKE ? OR t.out_warehouse_name LIKE ? OR t.in_warehouse_name LIKE ?" +
                    " OR t.out_keeper LIKE ? OR t.remark LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_inventory_transfers t" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT t.id,t.source_no,t.transfer_date,
                       t.out_warehouse_id,t.out_warehouse_name,t.out_keeper,
                       t.in_warehouse_id,t.in_warehouse_name,t.in_keeper,
                       t.remark,t.data_status,t.department_name,
                       t.source_created_at,t.source_updated_at,
                       (SELECT COUNT(*) FROM h3_inventory_transfer_items i WHERE i.transfer_id=t.id) item_count,
                       (SELECT COALESCE(SUM(i.quantity),0) FROM h3_inventory_transfer_items i WHERE i.transfer_id=t.id) total_quantity
                  FROM h3_inventory_transfers t
                """ + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /**
     * 库存调拨详情：主单全字段（含调出/调入双仓库与双仓管员）+ 调拨明细行。
     * judge_raw = 氚云自带「调拨数量超调出库存」校验标记原文（实测 2311 行中 3 行为 1，不解读）。
     */
    public Map<String,Object> transferDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT t.id,t.external_source,t.external_id,t.source_no,t.transfer_date,
                       t.out_warehouse_id,t.out_warehouse_name,t.out_keeper,
                       t.in_warehouse_id,t.in_warehouse_name,t.in_keeper,
                       t.remark,t.data_status,t.source_title,t.owner_name,t.department_name,t.created_by_name,
                       t.source_created_at,t.source_updated_at,t.source_hash,t.last_synced_at,
                       t.created_at,t.updated_at
                  FROM h3_inventory_transfers t
                 WHERE t.id=? AND t.external_source='H3YUN'
                """, "库存调拨单", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT i.line_no,i.source_item_id,i.product_code,i.product_name,i.category,i.spec,i.unit,
                       i.quantity,i.out_stock_quantity,i.judge_raw,i.remark
                  FROM h3_inventory_transfer_items i
                 WHERE i.transfer_id=? ORDER BY i.line_no,i.id
                """, this::row, id);
        return Map.of("kind", "transfer", "row", row, "rows", rows);
    }

    /**
     * 收款记录（SINGLE_OUTBOUND）详情把其关联的销售出库合并到主行展示：
     * 关联来自 receipt_outbound_links（V269 导入时从「销售出库编号」列保存），
     * 历史较早导入的记录无关联 → 相关字段保持缺省，UI 显示「—」。
     */
    private void enrichReceiptOutbound(Map<String,Object> row, Long receiptId) {
        List<Map<String,Object>> links = jdbc.query("""
                SELECT l.sequence_no,l.outbound_no,l.outbound_id,l.sales_order_date,
                       COALESCE(o.customer_name,'') AS outbound_customer_name,
                       COALESCE(o.sales_amount,0) AS outbound_sales_amount
                  FROM receipt_outbound_links l
                  LEFT JOIN sales_outbounds o ON o.id=l.outbound_id
                 WHERE l.receipt_id=? ORDER BY l.sequence_no
                """, this::row, receiptId);
        if (links.isEmpty()) return;
        Map<String,Object> lk = links.getFirst();
        row.put("outboundNo", lk.get("outboundNo"));
        row.put("outboundId", lk.get("outboundId"));
        row.put("salesOrderDate", lk.get("salesOrderDate"));
        row.put("outboundCustomerName", lk.get("outboundCustomerName"));
        row.put("outboundSalesAmount", lk.get("outboundSalesAmount"));
        row.put("outboundLinks", links);
    }

    /** 详情内部工具：单行查询，未命中抛 404 语义异常（业务名用于报错提示）。 */
    /**
     * 开票资料台账列表（V309）：44 组同名户名是合法事实，搜索覆盖户名/税号/开户行。
     * customer_id 为空 = 未关联 customers（外协/一次性户名属正常，仅标记）。
     */
    public Map<String,Object> invoiceProfileLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE p.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (p.customer_name LIKE ? OR p.tax_no LIKE ? OR p.bank_name LIKE ? OR p.bank_account LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_customer_invoice_profiles p" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT p.id,p.customer_name,p.tax_no,p.address,p.phone,p.bank_name,p.bank_account,
                       p.customer_id,p.data_status,p.owner_name,p.department_name,
                       p.source_created_at,p.source_updated_at,
                       c.customer_name AS linked_customer_name
                  FROM h3_customer_invoice_profiles p
                  LEFT JOIN customers c ON c.id=p.customer_id
                """ + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 开票资料详情：全字段 + 关联客户名（未关联显示「—」）。 */
    public Map<String,Object> invoiceProfileDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT p.id,p.external_source,p.external_id,p.customer_name,p.normalized_name,
                       p.tax_no,p.address,p.phone,p.bank_name,p.bank_account,
                       p.customer_id,p.data_status,p.source_object_id,p.source_title,
                       p.owner_name,p.department_name,p.created_by_name,
                       p.source_created_at,p.source_updated_at,p.source_hash,p.last_synced_at,
                       p.created_at,p.updated_at,c.customer_name AS linked_customer_name
                  FROM h3_customer_invoice_profiles p
                  LEFT JOIN customers c ON c.id=p.customer_id
                 WHERE p.id=? AND p.external_source='H3YUN'
                """, "开票资料", id);
        return Map.of("kind", "invoiceProfile", "row", row, "rows", List.of());
    }

    /**
     * 开票申请台账列表（V309）：本系统唯一开票口径来源；18 单流水号为空（source_no 存数据ID兜底）。
     * 收款情况只建引用不写 receipts（收款权威=销售出库）。
     */
    public Map<String,Object> invoiceRequestLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.source_no LIKE ? OR r.customer_name LIKE ? OR r.tax_no LIKE ?" +
                    " OR r.salesperson LIKE ? OR r.remark LIKE ?)");
            params.add(k); params.add(k); params.add(k); params.add(k); params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_invoice_requests r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.source_no,r.invoice_type,r.request_date,r.salesperson,
                       r.customer_name,r.tax_no,r.request_amount,r.received_amount,r.unreceived_amount,
                       r.data_status,r.remark,r.department_name,r.source_created_at,r.source_updated_at,
                       (SELECT COUNT(*) FROM h3_invoice_request_items i WHERE i.request_id=r.id) item_count,
                       (SELECT COUNT(*) FROM h3_invoice_request_receipt_refs x WHERE x.request_id=r.id) receipt_count,
                       (SELECT c.customer_name FROM h3_customer_invoice_profiles p
                         LEFT JOIN customers c ON c.id=p.customer_id
                         WHERE p.external_id=r.profile_external_id LIMIT 1) linked_customer_name
                  FROM h3_invoice_requests r
                """ + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /**
     * 开票申请详情：主表全字段 + 开票内容（rows）+ 收款情况引用（rows2，抽屉第二子表）。
     * outbound_ref_raw 为自由文本（无单号），只展示原文。
     */
    public Map<String,Object> invoiceRequestDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.invoice_type,r.redflush_ref,
                       r.request_date,r.salesperson,r.profile_external_id,r.profile_id,
                       r.customer_name,r.tax_no,r.snapshot_address,r.snapshot_phone,
                       r.snapshot_bank_name,r.snapshot_bank_account,
                       r.request_amount,r.received_amount,r.unreceived_amount,
                       r.outbound_ref_raw,r.remark,r.data_status,r.source_object_id,r.source_title,
                       r.owner_name,r.department_name,r.created_by_name,
                       r.source_created_at,r.source_updated_at,r.source_hash,r.last_synced_at,
                       r.created_at,r.updated_at
                  FROM h3_invoice_requests r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "开票申请", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT i.line_no,i.source_item_id,i.product_name,i.spec_model,i.unit,
                       i.quantity,i.unit_price,i.amount
                  FROM h3_invoice_request_items i
                 WHERE i.request_id=? ORDER BY i.line_no,i.id
                """, this::row, id);
        List<Map<String,Object>> rows2 = jdbc.query("""
                SELECT x.source_item_id,x.raw_title,x.receipt_date,x.receipt_method,x.receipt_amount
                  FROM h3_invoice_request_receipt_refs x
                 WHERE x.request_id=? ORDER BY x.id
                """, this::row, id);
        return Map.of("kind", "invoiceRequest", "row", row, "rows", rows, "rows2", rows2);
    }

    /**
     * 生产报工台账列表（V311，第 15 类）：工序完工报工（工单×零部件制程粒度）。
     * 搜索覆盖工单号/工序/报工人/部门/产线；无往来单位关联。
     */
    public Map<String,Object> productionReportLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.work_order_no LIKE ? OR r.component_name LIKE ? OR r.reporter LIKE ?" +
                    " OR r.reporter_dept LIKE ? OR r.production_line LIKE ? OR r.component_serial LIKE ?)");
            for (int i = 0; i < 6; i++) params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_production_reports r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.report_time,r.work_order_no,r.work_order_text,r.component_name,r.component_serial,
                       r.report_qty,r.reporter,r.reporter_assist,r.reporter_dept,r.production_line,
                       r.stock_in_clerk,r.order_clerk,r.data_status,r.source_updated_at
                  FROM h3_production_reports r
                """ + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 生产报工详情：单表全字段（无子表）。行级历史键 = 数据ID UUID（无单号类）。 */
    public Map<String,Object> productionReportDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.report_time,
                       r.work_order_no,r.work_order_text,r.component_serial,r.component_name,
                       r.report_qty,r.over_flag,r.over_qty,r.order_demand_qty,r.remaining_qty,
                       r.reporter,r.reporter_assist,r.reporter_dept,r.production_line,r.part_serials_text,
                       r.has_component_flag,r.stock_in_clerk,r.order_clerk,r.is_custom_qty,
                       r.data_status,r.data_title,r.owner_name,r.department_name,r.created_by_name,
                       r.source_created_at,r.source_updated_at,r.source_hash,r.last_synced_at,
                       r.created_at,r.updated_at
                  FROM h3_production_reports r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "生产报工", id);
        return Map.of("kind", "productionReport", "row", row, "rows", List.of());
    }

    /**
     * 进项发票台账（V312，第 16 类）：税务平台全量导出（幂等键=数电发票号码）。
     * 搜索覆盖销方名称/税号/票种/状态/开票人。
     */
    public Map<String,Object> invoiceInputLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.source_no LIKE ? OR r.seller_name LIKE ? OR r.seller_tax_no LIKE ?" +
                    " OR r.invoice_kind LIKE ? OR r.invoice_status LIKE ? OR r.drawer LIKE ?)");
            for (int i = 0; i < 6; i++) params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_input_invoices r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.source_no,r.seller_tax_no,r.seller_name,r.buyer_name,r.invoice_date,
                       r.amount,r.tax_amount,r.total_amount,r.invoice_kind,r.invoice_status,
                       r.is_positive,r.drawer,r.invoice_date
                  FROM h3_input_invoices r
                """ + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 进项发票详情：单表全字段（无子表）。行级历史键 = 数电发票号码。 */
    public Map<String,Object> invoiceInputDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.seller_tax_no,r.seller_name,
                       r.buyer_tax_no,r.buyer_name,r.invoice_date,r.amount,r.tax_amount,r.total_amount,
                       r.invoice_source,r.invoice_kind,r.invoice_status,r.is_positive,r.risk_level,
                       r.drawer,r.remark,r.source_hash,r.last_synced_at,r.created_at,r.updated_at
                  FROM h3_input_invoices r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "进项发票", id);
        return Map.of("kind", "invoiceInput", "row", row, "rows", List.of());
    }

    /**
     * 销项发票台账（V312，第 16 类）：4 174 张 + 收款子行（校验源，收款权威=销售出库 §9.3）。
     * 搜索覆盖购买方/票号/付款方式/业务员/状态。
     */
    public Map<String,Object> invoiceSalesLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.source_no LIKE ? OR r.buyer_name LIKE ? OR r.buyer_tax_no LIKE ?" +
                    " OR r.payment_method LIKE ? OR r.salesman LIKE ? OR r.invoice_status LIKE ?)");
            for (int i = 0; i < 6; i++) params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_sales_invoices r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.source_no,r.buyer_tax_no,r.buyer_name,r.invoice_date,
                       r.amount,r.tax_amount,r.total_amount,
                       r.receipt_date,r.payment_method,r.receipt_amount,r.unreceived_amount,r.salesman,
                       r.invoice_kind,r.invoice_status,r.is_positive,
                       (SELECT COUNT(*) FROM h3_sales_invoice_receipts x WHERE x.invoice_id=r.id) receipt_count
                  FROM h3_sales_invoices r
                """ + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 销项发票详情：主表全字段 + 收款登记子行（rows）。行级历史键 = 数电发票号码。 */
    public Map<String,Object> invoiceSalesDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.seller_tax_no,r.seller_name,
                       r.buyer_tax_no,r.buyer_name,r.invoice_date,r.amount,r.tax_amount,r.total_amount,
                       r.receipt_date,r.payment_method,r.receipt_amount,r.unreceived_amount,
                       r.salesman,r.remark,r.remark_platform,
                       r.invoice_source,r.invoice_kind,r.invoice_status,r.is_positive,r.risk_level,r.drawer,
                       r.source_hash,r.last_synced_at,r.created_at,r.updated_at
                  FROM h3_sales_invoices r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "销项发票", id);
        List<Map<String,Object>> rows = jdbc.query("""
                SELECT x.source_item_id,x.receipt_date,x.payment_method,x.receipt_amount,x.salesman,x.remark
                  FROM h3_sales_invoice_receipts x
                 WHERE x.invoice_id=? ORDER BY x.id
                """, this::row, id);
        return Map.of("kind", "invoiceSales", "row", row, "rows", rows);
    }

    /**
     * 供应商应付台账（V313，第 17 类）：multiSheet 聚合的人工月度台账（幂等键=供应商#月份）。
     * 搜索覆盖供应商/月份/付款方式/折让。resolved_* 由 RK 反查脚本回填（可空）。
     * {@code supplier} 为 sheet 维度（= supplier_name）精确筛选，配合 {@link #supplierPayableSuppliers()} 汇总条使用。
     */
    public Map<String,Object> supplierPayableLedger(String keyword, String supplier, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (supplier != null && !supplier.isBlank()) {
            where.append(" AND r.supplier_name = ?");
            params.add(supplier.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.supplier_name LIKE ? OR r.month_raw LIKE ? OR r.pay_method LIKE ?" +
                    " OR CAST(r.allowance AS CHAR) LIKE ?)");
            for (int i = 0; i < 4; i++) params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_supplier_payables r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.supplier_name,r.month_no,r.month_raw,r.payable_amount,r.settled_amount,
                       r.allowance,r.pay_date,r.pay_method,r.pay_amount,r.remaining_amount,
                       r.resolved_supplier_id,r.resolved_supplier_name
                  FROM h3_supplier_payables r
                """ + where + " ORDER BY r.supplier_name, r.month_no, r.id LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 供应商应付详情：单表全字段（无子表；RK/费用明细行不入库）。行级历史键 = 供应商#月份。 */
    public Map<String,Object> supplierPayableDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.supplier_name,r.month_no,r.month_raw,
                       r.payable_amount,r.settled_amount,r.allowance,r.pay_date,r.pay_method,r.pay_amount,
                       r.remaining_amount,r.resolved_supplier_id,r.resolved_supplier_name,
                       r.source_hash,r.last_synced_at,r.created_at,r.updated_at
                  FROM h3_supplier_payables r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "供应商应付", id);
        return Map.of("kind", "supplierPayable", "row", row, "rows", List.of());
    }

    /**
     * 供应商应付「sheet 维度」汇总：源文件 59 张供应商 sheet 各聚合一行（supplier_name=sheet 名）。
     * 金额列合计与月数对应明细行；resolved_supplier_name 取该供应商下任一非空反查名（同供应商反查结论一致）。
     * 供台账汇总条的下拉与小计使用；行数 = distinct supplier_name（58）。
     */
    public Map<String,Object> supplierPayableSuppliers() {
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.supplier_name,
                       MAX(r.resolved_supplier_name) AS resolved_supplier_name,
                       COUNT(*)                      AS month_count,
                       COALESCE(SUM(r.payable_amount),0)   AS payable_amount,
                       COALESCE(SUM(r.settled_amount),0)   AS settled_amount,
                       COALESCE(SUM(r.allowance),0)        AS allowance,
                       COALESCE(SUM(r.pay_amount),0)       AS pay_amount,
                       COALESCE(SUM(r.remaining_amount),0) AS remaining_amount
                  FROM h3_supplier_payables r
                 WHERE r.external_source='H3YUN'
                 GROUP BY r.supplier_name
                 ORDER BY r.supplier_name
                """, this::row);
        return Map.of("items", items);
    }

    /**
     * 流水日记账台账（V315，第 18 类）：35 张账户 sheet 聚合的 7 588 行账户分录（校验源，§9.3）。
     * 账户维度（期初余额/币种/35 个账户）在 h3_cash_accounts，不在本台账；搜索覆盖账户/摘要/供应商/客户/报销人。
     * {@code account} 为 sheet 维度（= account_name）精确筛选，配合 {@link #cashflowJournalAccounts()} 汇总条使用。
     */
    public Map<String,Object> cashflowJournalLedger(String keyword, String account, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (account != null && !account.isBlank()) {
            where.append(" AND r.account_name = ?");
            params.add(account.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.account_name LIKE ? OR r.summary LIKE ? OR r.supplier_raw LIKE ?" +
                    " OR r.counterparty_raw LIKE ? OR r.reimbursed_by LIKE ? OR r.invoiced LIKE ?)");
            for (int i = 0; i < 6; i++) params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_cashflow_entries r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.account_name,r.entry_date,r.month_label,r.summary,r.supplier_raw,
                       r.counterparty_raw,r.reimbursed_by,r.invoiced,r.income,r.expense,r.balance,
                       r.fx_rate,r.fx_cny,r.settle_rate,r.settle_cny,r.fx_gain_loss
                  FROM h3_cashflow_entries r
                """ + where + " ORDER BY r.account_name, r.entry_date, r.id LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 流水日记账详情：单表全字段（无子表）。行级历史键 = external_id（账户#日期#收入#支出#结存#序号）。 */
    public Map<String,Object> cashflowJournalDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.account_name,r.entry_date,r.month_label,
                       r.summary,r.supplier_raw,r.counterparty_raw,r.reimbursed_by,r.invoiced,
                       r.income,r.expense,r.balance,r.fx_rate,r.fx_cny,r.settle_rate,r.settle_cny,r.fx_gain_loss,
                       r.source_hash,r.last_synced_at,r.created_at,r.updated_at
                  FROM h3_cashflow_entries r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "流水日记账", id);
        return Map.of("kind", "cashflowJournal", "row", row, "rows", List.of());
    }

    /**
     * 流水日记账「sheet 维度」账户汇总：源文件 35 张账户 sheet 各聚合一行，对应 h3_cash_accounts 全表
     * （账户档案由导入期初行回写，见 H3FactImportService.saveCashflowEntry / upsertCashAccount）。
     * 收入/支出为该账户全部分录合计（外币账户为外币原值，与期初同币种）；期末 = 期初 + 收入 − 支出。
     * 无分录的账户照常返回（LEFT JOIN，income/expense=0）。
     */
    public Map<String,Object> cashflowJournalAccounts() {
        List<Map<String,Object>> items = jdbc.query("""
                SELECT a.id, a.account_name, a.currency, a.opening_balance,
                       COALESCE(SUM(e.income),0) AS income,
                       COALESCE(SUM(e.expense),0) AS expense,
                       a.opening_balance + COALESCE(SUM(e.income),0) - COALESCE(SUM(e.expense),0) AS closing_balance,
                       COUNT(e.id) AS entry_count,
                       MAX(e.entry_date) AS last_entry_date
                  FROM h3_cash_accounts a
                  LEFT JOIN h3_cashflow_entries e
                         ON e.external_source='H3YUN' AND e.account_name = a.account_name
                 GROUP BY a.id, a.account_name, a.currency, a.opening_balance
                 ORDER BY a.id
                """, this::row);
        return Map.of("items", items);
    }

    /**
     * 收银通收单流水台账（V316，第 19 类）：6 088 行微信收单记录（校验源）。
     * 搜索覆盖摘要/业务员/付款人识别号/交易状态。
     */
    public Map<String,Object> cashierLogLedger(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE r.external_source='H3YUN'");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (r.summary LIKE ? OR r.salesperson LIKE ? OR r.payer_openid LIKE ?" +
                    " OR r.status LIKE ?)");
            for (int i = 0; i < 4; i++) params.add(k);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_cashier_log r" + where, Long.class, params.toArray());
        params.add(size); params.add((page - 1) * size);
        List<Map<String,Object>> items = jdbc.query("""
                SELECT r.id,r.tx_time,r.status,r.amount,r.fee,r.payer_openid,r.summary,r.salesperson
                  FROM h3_cashier_log r
                """ + where + " ORDER BY r.tx_time, r.id LIMIT ? OFFSET ?", this::row, params.toArray());
        return page(items, total, page, size);
    }

    /** 收银通详情：单表全字段（无子表）。行级历史键 = 时间#金额#付款人识别号。 */
    public Map<String,Object> cashierLogDetail(Long id) {
        Map<String,Object> row = oneRow("""
                SELECT r.id,r.external_source,r.external_id,r.source_no,r.tx_time,r.status,r.amount,r.fee,
                       r.payer_openid,r.summary,r.salesperson,r.source_hash,r.last_synced_at,r.created_at,r.updated_at
                  FROM h3_cashier_log r
                 WHERE r.id=? AND r.external_source='H3YUN'
                """, "收银通流水", id);
        return Map.of("kind", "cashierLog", "row", row, "rows", List.of());
    }

    private Map<String,Object> oneRow(String sql, String label, Object... args) {
        List<Map<String,Object>> rows = jdbc.query(sql, this::row, args);
        if (rows.isEmpty()) throw new NoSuchElementException(label + "不存在或不属于氚云台账（id=" + args[0] + "）");
        return rows.getFirst();
    }

    public Map<String,Object> batches(String type, int page, int size) {
        String normType = "NORMAL".equalsIgnoreCase(type) ? "OUTBOUND" : type;
        boolean needWhere = normType != null && !normType.isBlank();
        String where = needWhere ? " WHERE data_type=? OR data_type='NORMAL'" : "";
        Object[] args = needWhere ? new Object[]{normType} : new Object[]{};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_import_batches" + where, Long.class, args);
        Object[] listArgs = needWhere ? new Object[]{normType,size,(page-1)*size} : new Object[]{size,(page-1)*size};
        List<Map<String,Object>> items = jdbc.query("SELECT id,file_name,CASE WHEN data_type='NORMAL' THEN 'OUTBOUND' ELSE data_type END AS data_type,status,progress_pct,progress_stage,error_message,error_detail,total_count,created_count,updated_count,unchanged_count,created_at,completed_at FROM h3_import_batches" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                this::row, listArgs);
        return page(items,total,page,size);
    }

    /** 统一批次端点路由辅助：按 id 仅查 data_type（批次不存在返回 null；不做 NORMAL 归一，由调用方路由）。 */
    public String importBatchDataType(Long batchId) {
        List<String> dt = jdbc.query("SELECT data_type FROM h3_import_batches WHERE id=?",
                (rs, n) -> rs.getString(1), batchId);
        return dt.isEmpty() ? null : dt.getFirst();
    }

    private Map<String,Object> page(List<Map<String,Object>> items,Long total,int page,int size){return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size);}
    private Map<String,Object> row(ResultSet rs,int n)throws SQLException{
        ResultSetMetaData md=rs.getMetaData(); Map<String,Object> map=new LinkedHashMap<>();
        for(int i=1;i<=md.getColumnCount();i++) map.put(toCamel(md.getColumnLabel(i)),rs.getObject(i));
        return map;
    }
    private String toCamel(String s){StringBuilder b=new StringBuilder();boolean upper=false;for(char c:s.toLowerCase().toCharArray()){if(c=='_'){upper=true;}else{b.append(upper?Character.toUpperCase(c):c);upper=false;}}return b.toString();}
}
