package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActorResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;

/** Keeps product-code candidates separate from human-confirmed source quantities. */
@Service
@RequiredArgsConstructor
public class ProductionOutboundTraceService {
    private static final String VALID_ALLOCATION = "a.status='ACTIVE' AND pi.id IS NOT NULL AND si.id IS NOT NULL "
            +"AND pr.data_status='已生效' AND o.outbound_status='CONFIRMED' "
            +"AND COALESCE(o.confirmed_business_type,o.inferred_business_type)='SALE' "
            +"AND pi.production_order_no=p.work_order_no "
            +"AND TRIM(pi.product_code)=a.product_code AND TRIM(si.product_code)=a.product_code "
            +"AND TRIM(COALESCE(pi.unit,''))=a.unit AND TRIM(COALESCE(si.unit,''))=a.unit "
            +"AND pi.quantity=a.receipt_quantity_snapshot AND si.quantity=a.sale_quantity_snapshot "
            +"AND pr.purchase_date=a.receipt_date_snapshot "
            +"AND COALESCE(o.ship_date,o.business_date)=a.sale_date_snapshot "
            +"AND pr.source_no=a.purchase_receipt_no AND o.outbound_no=a.sales_outbound_no";

    private final JdbcTemplate jdbc;
    private final ProductionTrackingService tracking;
    private final ErpActorResolver actors;

    public record Confirmation(long receiptItemId,long saleItemId,BigDecimal quantity,String reason) {}
    public record Revocation(String reason) {}

    public void attachSummaries(List<Map<String,Object>> items) {
        if(items.isEmpty())return;
        List<Object> ids=new ArrayList<>(items.stream().map(item->item.get("id")).toList());
        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        var received=jdbc.queryForList("""
            SELECT p.id orderId,COALESCE(SUM(CASE WHEN pr.data_status='已生效' AND pi.quantity>0
              THEN pi.quantity ELSE 0 END),0) receivedQuantity,
              MAX(CASE WHEN pr.data_status='已生效' AND pi.quantity>0 THEN pr.purchase_date END) latestReceiptDate,
              COUNT(DISTINCT CASE WHEN pr.data_status='已生效' AND pi.quantity>0
                THEN COALESCE(NULLIF(TRIM(pi.unit),''),'未填单位') END) unitCount
            FROM production_orders p LEFT JOIN purchase_receipt_items pi ON pi.production_order_no=p.work_order_no
            LEFT JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id AND pr.external_source='H3YUN'
            WHERE p.id IN (%s) GROUP BY p.id
            """.formatted(marks),ids.toArray());
        var allocated=jdbc.queryForList("""
            SELECT a.production_order_id orderId,
              COALESCE(SUM(CASE WHEN %s THEN a.allocated_quantity ELSE 0 END),0) confirmedQuantity,
              COALESCE(SUM(CASE WHEN a.status='ACTIVE' AND NOT (%s) THEN 1 ELSE 0 END),0) staleCount
            FROM production_outbound_allocations a
            JOIN production_orders p ON p.id=a.production_order_id
            LEFT JOIN purchase_receipt_items pi ON pi.id=a.purchase_receipt_item_id
            LEFT JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
            LEFT JOIN sales_outbound_items si ON si.id=a.sales_outbound_item_id
            LEFT JOIN sales_outbounds o ON o.id=si.sales_outbound_id
            WHERE a.production_order_id IN (%s) GROUP BY a.production_order_id
            """.formatted(VALID_ALLOCATION,VALID_ALLOCATION,marks),ids.toArray());
        String outboundCoverageThrough=outboundCoverageThrough();
        // 先按 orderId 建索引再回填：原实现是「每个 item 都把全量结果 stream().filter() 两遍」——那是
        // O(商品数×结果行数)，30 行列表看不出来，但导出（EXPORT_MAX_ROWS=5000）会直接暴露。
        Map<Long,Map<String,Object>> receivedByOrder=new HashMap<>(Math.max(16,received.size()*2));
        for(var row:received){
            Object key=row.get("orderId");
            if(key instanceof Number number) receivedByOrder.put(number.longValue(),row);
        }
        Map<Long,Map<String,Object>> allocatedByOrder=new HashMap<>(Math.max(16,allocated.size()*2));
        for(var row:allocated){
            Object key=row.get("orderId");
            if(key instanceof Number number) allocatedByOrder.put(number.longValue(),row);
        }
        for(var item:items){
            long id=((Number)item.get("id")).longValue();
            var receivedRow=receivedByOrder.get(id);
            BigDecimal qty=receivedRow==null?BigDecimal.ZERO:(BigDecimal)receivedRow.get("receivedQuantity");
            var allocation=allocatedByOrder.get(id);
            BigDecimal confirmed=allocation==null?BigDecimal.ZERO:(BigDecimal)allocation.get("confirmedQuantity");
            boolean stale=allocation!=null&&((Number)allocation.get("staleCount")).longValue()>0;
            boolean mixedUnits=receivedRow!=null&&((Number)receivedRow.get("unitCount")).longValue()>1;
            boolean beyondCoverage=receivedRow!=null&&receivedRow.get("latestReceiptDate")!=null
                    &&(outboundCoverageThrough==null
                        ||receivedRow.get("latestReceiptDate").toString().compareTo(outboundCoverageThrough)>0);
            item.put("salesTraceReceivedQuantity",qty);
            item.put("salesTraceConfirmedQuantity",confirmed);
            item.put("salesTraceStale",stale);
            item.put("salesTraceMixedUnits",mixedUnits);
            item.put("salesTraceCoverageThrough",outboundCoverageThrough);
            item.put("salesTraceBeyondCoverage",beyondCoverage);
        }
    }

    public Map<String,Object> trace(long orderId) {
        Map<String,Object> order=tracking.order(orderId); // applies the existing row-level visibility rule
        String orderNo=(String)order.get("orderNo");
        List<Map<String,Object>> receipts=jdbc.queryForList("""
            SELECT pi.id receiptItemId,pr.id receiptId,pr.source_no receiptNo,pr.purchase_date receiptDate,
                   pr.data_status receiptStatus,pr.warehouse_name receiptWarehouse,
                   pi.product_code productCode,pi.product_name productName,pi.spec,pi.unit,pi.quantity,
                   pi.source_item_id sourceItemId
            FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
            WHERE pi.production_order_no=? AND pr.external_source='H3YUN'
            ORDER BY pr.purchase_date,pr.id,pi.line_no,pi.id
            """,orderNo);
        List<Map<String,Object>> allocations=jdbc.queryForList("""
            SELECT a.id,a.purchase_receipt_item_id receiptItemId,a.sales_outbound_item_id saleItemId,
                   a.purchase_receipt_no receiptNo,a.sales_outbound_no outboundNo,a.product_code productCode,
                   a.unit,a.allocated_quantity quantity,a.reason,a.status,a.confirmed_by_name confirmedByName,
                   a.confirmed_at confirmedAt,a.revoked_by_name revokedByName,a.revoked_at revokedAt,
                   a.revoke_reason revokeReason,
                   CASE WHEN %s THEN 1 ELSE 0 END currentSource
            FROM production_outbound_allocations a
            JOIN production_orders p ON p.id=a.production_order_id
            LEFT JOIN purchase_receipt_items pi ON pi.id=a.purchase_receipt_item_id
            LEFT JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
            LEFT JOIN sales_outbound_items si ON si.id=a.sales_outbound_item_id
            LEFT JOIN sales_outbounds o ON o.id=si.sales_outbound_id
            WHERE a.production_order_id=? ORDER BY a.id DESC
            """.formatted(VALID_ALLOCATION),orderId);
        BigDecimal confirmed=BigDecimal.ZERO;
        Map<Long,BigDecimal> byReceipt=new LinkedHashMap<>();
        Map<String,BigDecimal> confirmedByUnit=new LinkedHashMap<>();
        for(var allocation:allocations){
            if(((Number)allocation.get("currentSource")).intValue()!=1)continue;
            BigDecimal quantity=(BigDecimal)allocation.get("quantity");
            confirmed=confirmed.add(quantity);
            long receiptItemId=((Number)allocation.get("receiptItemId")).longValue();
            byReceipt.merge(receiptItemId,quantity,BigDecimal::add);
            confirmedByUnit.merge(Objects.toString(allocation.get("unit"),"未填单位"),quantity,BigDecimal::add);
        }
        BigDecimal received=BigDecimal.ZERO;
        Map<String,BigDecimal> receivedByUnit=new LinkedHashMap<>();
        for(var receipt:receipts){
            long itemId=((Number)receipt.get("receiptItemId")).longValue();
            receipt.put("confirmedQuantity",byReceipt.getOrDefault(itemId,BigDecimal.ZERO));
            if("已生效".equals(receipt.get("receiptStatus")) && receipt.get("quantity") instanceof BigDecimal quantity
                    && quantity.signum()>0){
                received=received.add(quantity);
                String unit=trim(receipt.get("unit"));
                receivedByUnit.merge(unit.isEmpty()?"未填单位":unit,quantity,BigDecimal::add);
            }
        }
        List<Map<String,Object>> unitSummaries=new ArrayList<>();
        for(var entry:receivedByUnit.entrySet()){
            var summary=new LinkedHashMap<String,Object>();
            summary.put("unit",entry.getKey());
            summary.put("receivedQuantity",entry.getValue());
            summary.put("confirmedQuantity",confirmedByUnit.getOrDefault(entry.getKey(),BigDecimal.ZERO));
            unitSummaries.add(summary);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("orderId",orderId);
        result.put("requiredQuantity",order.get("plannedQuantity"));
        result.put("requiredUnit",order.get("unit"));
        result.put("receivedQuantity",unitSummaries.size()==1?received:null);
        result.put("confirmedQuantity",unitSummaries.size()==1?confirmed:null);
        result.put("unitSummaries",unitSummaries);
        result.put("receipts",receipts);
        result.put("allocations",allocations);
        String outboundCoverageThrough=outboundCoverageThrough();
        result.put("outboundCoverageThrough",outboundCoverageThrough);
        result.put("coverageNote",outboundCoverageThrough==null
                ? "当前尚未导入销售出库数据；确认数量为 0 不代表未销售"
                : "当前销售出库台账的最新出库日期为 "+outboundCoverageThrough+"；此日期之后暂无出库记录，已核实归属销售为 0 不代表未销售");
        return result;
    }

    /**
     * 销售出库台账的「覆盖截止日」＝ {@code MAX(COALESCE(ship_date,business_date))}。
     *
     * <p>⚠️ 写法必须保持「两段 {@code ORDER BY ... DESC LIMIT 1}」，<b>不得</b>写成
     * {@code SELECT MAX(COALESCE(ship_date,business_date)) ... WHERE external_source='H3YUN'}
     * ——后者实测 277ms，是跟单列表 440ms 里最大的一笔（2026-10-21 实测）：
     * <ul>
     *   <li>带 {@code external_source='H3YUN'} 的 WHERE 会让优化器放弃 {@code idx_so_ship_date}
     *       改走 {@code uk_so_external} 全量索引扫描。该条件在全表 <b>109379/109379 行上恒真</b>，
     *       选择性为零，纯粹是白付的代价。去掉 WHERE 后 {@code MAX(ship_date)} 只要 0ms（走覆盖索引反向扫）。</li>
     *   <li>{@code business_date} 没有索引，只能排序 10 万行 → 40~55ms（不是 {@code COALESCE} 的锅：
     *       单独 {@code MAX(ship_date)} 带 WHERE 同样 277ms）。</li>
     *   <li>两段合起来 47ms，且结果与原写法逐字相等（真库对拍 2026-10-13）。</li>
     * </ul>
     *
     * <p>结果按表数据整体缓存：这是「整张台账的最新日期」，与任何筛选/分页无关，而每个列表请求
     * 都会读一次（{@link #attachSummaries}）。缓存只由本类的写入路径失效——表只经氚云导入写入，
     * 导入完成后下一轮自然刷新；TTL 是兜底，防止别的入口（重置/重导）改了表而这里不知道。
     */
    private String outboundCoverageThrough() {
        long now=System.nanoTime();
        String cached=coverageCache;
        if(cached!=null && now-coverageCachedAtNanos<COVERAGE_TTL_NANOS) return cached.isEmpty()?null:cached;
        java.sql.Date date=jdbc.queryForObject("""
            SELECT GREATEST(
              COALESCE((SELECT ship_date      FROM sales_outbounds ORDER BY ship_date      DESC LIMIT 1),'1000-01-01'),
              COALESCE((SELECT business_date  FROM sales_outbounds ORDER BY business_date  DESC LIMIT 1),'1000-01-01'))
            """,java.sql.Date.class);
        // 用空串表示「查过但没有数据」，与「还没查过」(null) 区分开，避免空表时每次都重查。
        String value=date==null?"":date.toString();
        coverageCache=value;
        coverageCachedAtNanos=now;
        return value.isEmpty()?null:value;
    }

    /** 见 {@link #outboundCoverageThrough()}。{@code null}=尚未查询，空串=查过且无数据。 */
    private volatile String coverageCache;
    private volatile long coverageCachedAtNanos;
    private static final long COVERAGE_TTL_NANOS=java.util.concurrent.TimeUnit.MINUTES.toNanos(5);

    public List<Map<String,Object>> candidates(long orderId,long receiptItemId,String keyword) {
        var receipt=receipt(orderId,receiptItemId);
        String code=((String)receipt.get("productCode"));
        if(code==null||code.isBlank())return List.of();
        String search=trim(keyword);
        if(search.length()>100)throw new IllegalArgumentException("搜索词过长");
        String pattern="%"+search+"%";
        return jdbc.queryForList("""
            SELECT si.id saleItemId,o.id outboundId,o.outbound_no outboundNo,
                   COALESCE(o.ship_date,o.business_date) outboundDate,
                   o.warehouse outboundWarehouse,o.outbound_status outboundStatus,
                   COALESCE(o.confirmed_business_type,o.inferred_business_type) businessType,
                   si.product_code productCode,si.product_name productName,si.specification spec,
                   si.unit,si.quantity,
                   CASE WHEN NULLIF(TRIM(si.unit),'') IS NOT NULL AND NULLIF(TRIM(?),'') IS NOT NULL
                      AND TRIM(si.unit)=TRIM(?) THEN 1 ELSE 0 END sameUnit,
                   CASE WHEN NULLIF(TRIM(o.warehouse),'') IS NOT NULL
                      AND NULLIF(TRIM(?),'') IS NOT NULL AND TRIM(o.warehouse)=TRIM(?) THEN 1 ELSE 0 END sameWarehouse,
                   CASE WHEN COALESCE(o.ship_date,o.business_date)>=? THEN 1 ELSE 0 END dateAfterReceipt
            FROM sales_outbound_items si JOIN sales_outbounds o ON o.id=si.sales_outbound_id
            WHERE o.external_source='H3YUN' AND si.line_type='PRODUCT' AND si.quantity>0
              AND TRIM(si.product_code)=TRIM(?)
              AND o.outbound_status='CONFIRMED'
              AND COALESCE(o.confirmed_business_type,o.inferred_business_type)='SALE'
              AND (?='' OR o.outbound_no LIKE ?)
            ORDER BY sameUnit DESC,sameWarehouse DESC,dateAfterReceipt DESC,
                     ABS(DATEDIFF(COALESCE(o.ship_date,o.business_date),?)),o.id DESC,si.id DESC LIMIT 100
            """,receipt.get("unit"),receipt.get("unit"),receipt.get("receiptWarehouse"),receipt.get("receiptWarehouse"),receipt.get("receiptDate"),
                code,search,pattern,receipt.get("receiptDate"));
    }

    public void requireCandidateOutbound(long orderId,long outboundId) {
        var order=tracking.order(orderId);
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM purchase_receipt_items pi
            JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
            JOIN sales_outbound_items si ON TRIM(si.product_code)=TRIM(pi.product_code)
            JOIN sales_outbounds o ON o.id=si.sales_outbound_id
            WHERE pi.production_order_no=? AND o.id=? AND pr.external_source='H3YUN'
              AND o.external_source='H3YUN' AND si.line_type='PRODUCT' AND si.quantity>0
            """,Integer.class,order.get("orderNo"),outboundId);
        if(count==null||count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该工单没有此销售出库候选");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long confirm(long orderId,Confirmation request,String key) {
        if(request==null||request.quantity()==null||request.quantity().signum()<=0)
            throw new IllegalArgumentException("确认数量必须大于零");
        String reason=requiredReason(request.reason());
        if(key==null||key.isBlank()||key.length()>100)throw new IllegalArgumentException("缺少有效请求键");
        tracking.order(orderId);
        var prior=jdbc.queryForList("SELECT id,production_order_id productionOrderId,"
                +"purchase_receipt_item_id purchaseReceiptItemId,sales_outbound_item_id salesOutboundItemId,"
                +"allocated_quantity allocatedQuantity FROM production_outbound_allocations WHERE request_key=?",key);
        if(!prior.isEmpty()){
            var saved=prior.getFirst();
            if(((Number)saved.get("productionOrderId")).longValue()!=orderId
                    ||((Number)saved.get("purchaseReceiptItemId")).longValue()!=request.receiptItemId()
                    ||((Number)saved.get("salesOutboundItemId")).longValue()!=request.saleItemId()
                    ||((BigDecimal)saved.get("allocatedQuantity")).compareTo(request.quantity())!=0)
                throw new IllegalArgumentException("请求键已用于其他销售归属记录");
            return ((Number)saved.get("id")).longValue();
        }
        // Lock both source rows. READ_COMMITTED ensures a competing allocation is visible after these locks.
        jdbc.queryForList("SELECT id FROM purchase_receipt_items WHERE id=? FOR UPDATE",request.receiptItemId());
        jdbc.queryForList("SELECT id FROM sales_outbound_items WHERE id=? FOR UPDATE",request.saleItemId());
        var receipt=receipt(orderId,request.receiptItemId());
        var sale=sale(request.saleItemId());
        if(!"已生效".equals(receipt.get("receiptStatus")))throw new IllegalArgumentException("采购入库单未生效");
        if(!"CONFIRMED".equals(sale.get("outboundStatus"))||!"SALE".equals(sale.get("businessType")))
            throw new IllegalArgumentException("仅可确认已生效的销售出库");
        String receiptCode=trim(receipt.get("productCode"));
        String saleCode=trim(sale.get("productCode"));
        String receiptUnit=trim(receipt.get("unit"));
        if(receiptCode.isEmpty()||receiptUnit.isEmpty()||!receiptCode.equals(saleCode)
                ||!receiptUnit.equals(trim(sale.get("unit"))))
            throw new IllegalArgumentException("产品号或单位不一致，不能确认数量归属");
        if(receipt.get("receiptDate")==null||sale.get("outboundDate")==null)
            throw new IllegalArgumentException("入库或出库日期缺失，需先核实源单");
        if(((java.sql.Date)sale.get("outboundDate")).before((java.sql.Date)receipt.get("receiptDate")))
            throw new IllegalArgumentException("销售出库日期早于采购入库，需先核实来源或补录日期");
        BigDecimal receiptQty=(BigDecimal)receipt.get("quantity");
        BigDecimal saleQty=(BigDecimal)sale.get("quantity");
        if(receiptQty==null||saleQty==null||receiptQty.signum()<=0||saleQty.signum()<=0)
            throw new IllegalArgumentException("来源数量无效");
        BigDecimal receiptUsed=used("a.purchase_receipt_item_id",request.receiptItemId());
        BigDecimal saleUsed=used("a.sales_outbound_item_id",request.saleItemId());
        requireAvailable(request.quantity(),receiptQty,receiptUsed,saleQty,saleUsed);
        var actor=actors.current();
        jdbc.update("""
            INSERT INTO production_outbound_allocations(production_order_id,purchase_receipt_item_id,
              sales_outbound_item_id,purchase_receipt_no,sales_outbound_no,product_code,unit,
              allocated_quantity,receipt_quantity_snapshot,sale_quantity_snapshot,receipt_date_snapshot,
              sale_date_snapshot,reason,request_key,confirmed_by_employee_id,confirmed_by_name)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,orderId,request.receiptItemId(),request.saleItemId(),receipt.get("receiptNo"),
                sale.get("outboundNo"),receiptCode,receiptUnit,request.quantity(),receiptQty,saleQty,
                receipt.get("receiptDate"),sale.get("outboundDate"),reason,key,actor.employeeId(),actor.name());
        return Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void revoke(long orderId,long allocationId,Revocation request) {
        tracking.order(orderId);
        String reason=requiredReason(request==null?null:request.reason());
        var rows=jdbc.queryForList("SELECT id,status FROM production_outbound_allocations "
                +"WHERE id=? AND production_order_id=? FOR UPDATE",allocationId,orderId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"销售归属记录不存在");
        if(!"ACTIVE".equals(rows.getFirst().get("status")))throw new IllegalArgumentException("该归属已撤销");
        var actor=actors.current();
        jdbc.update("UPDATE production_outbound_allocations SET status='REVOKED',revoked_by_employee_id=?,"
                +"revoked_by_name=?,revoked_at=NOW(),revoke_reason=? WHERE id=?",
                actor.employeeId(),actor.name(),reason,allocationId);
    }

    private BigDecimal used(String column,long id) {
        // column is chosen by this class only, never from a request.
        String sql="SELECT COALESCE(SUM(a.allocated_quantity),0) FROM production_outbound_allocations a "
                +"JOIN production_orders p ON p.id=a.production_order_id "
                +"LEFT JOIN purchase_receipt_items pi ON pi.id=a.purchase_receipt_item_id "
                +"LEFT JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id "
                +"LEFT JOIN sales_outbound_items si ON si.id=a.sales_outbound_item_id "
                +"LEFT JOIN sales_outbounds o ON o.id=si.sales_outbound_id "
                +"WHERE "+column+"=? AND "+VALID_ALLOCATION;
        return Objects.requireNonNull(jdbc.queryForObject(sql,BigDecimal.class,id));
    }

    private Map<String,Object> receipt(long orderId,long itemId) {
        var order=tracking.order(orderId);
        var rows=jdbc.queryForList("""
            SELECT pi.id receiptItemId,pi.product_code productCode,pi.unit,pi.quantity,
                   pr.id receiptId,pr.source_no receiptNo,pr.purchase_date receiptDate,
                   pr.warehouse_name receiptWarehouse,pr.data_status receiptStatus
            FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
            WHERE pi.id=? AND pi.production_order_no=? AND pr.external_source='H3YUN'
            """,itemId,order.get("orderNo"));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该工单未关联此入库明细");
        return rows.getFirst();
    }

    private Map<String,Object> sale(long itemId) {
        var rows=jdbc.queryForList("""
            SELECT si.id saleItemId,si.product_code productCode,si.unit,si.quantity,
                   o.id outboundId,o.outbound_no outboundNo,
                   COALESCE(o.ship_date,o.business_date) outboundDate,
                   o.outbound_status outboundStatus,
                   COALESCE(o.confirmed_business_type,o.inferred_business_type) businessType
            FROM sales_outbound_items si JOIN sales_outbounds o ON o.id=si.sales_outbound_id
            WHERE si.id=? AND si.line_type='PRODUCT' AND o.external_source='H3YUN'
            """,itemId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"销售出库明细不存在");
        return rows.getFirst();
    }

    private static String trim(Object value) {return value==null?"":value.toString().trim();}
    static void requireAvailable(BigDecimal quantity,BigDecimal receiptQuantity,BigDecimal receiptUsed,
            BigDecimal saleQuantity,BigDecimal saleUsed) {
        if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("确认数量必须大于零");
        if(receiptUsed.add(quantity).compareTo(receiptQuantity)>0)
            throw new IllegalArgumentException("确认数量超过该入库明细可分配量");
        if(saleUsed.add(quantity).compareTo(saleQuantity)>0)
            throw new IllegalArgumentException("确认数量超过该销售出库明细可分配量");
    }
    private static String requiredReason(String value) {
        String reason=trim(value);
        if(reason.isEmpty()||reason.length()>500)throw new IllegalArgumentException("请填写500字以内的核实说明");
        return reason;
    }
}
