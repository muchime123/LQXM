package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 台账行级「更新历史」：从历史导入批次的 preview_json 中提取同一业务键的 CREATE/UPDATE 记录。
 *
 * 设计说明（2026-09-16，配套 docs/氚云导入中心与库存快照重设计-20260916.md）：
 * <ul>
 *   <li>preview_json 在批次确认后仍完整保留（COMPLETED/PARTIAL_FAILED），其中 UPDATE 行携带
 *       与「当时台账状态」比对的差异——A 域为 changes[{path,oldValue,newValue}] 字段级数组，
 *       B 域为「；」连接的文案串——是系统内唯一存有「旧值 → 新值」的载体，
 *       因此无需新建更新日志表即可回放任意行的变更史；</li>
 *   <li>每次查询按 data_type 扫描批次并解析 JSON：当前批次数为个位数、单份 preview 至多 MB 级，
 *       开销可忽略；若未来批次量增大（数百 × MB 级），再考虑确认时落一张行级更新日志表；</li>
 *   <li>匹配口径：A 域 preview 行自带 businessKey（= 清洗后的业务编号，与台账表列同源）；
 *       B 域按行字段拼键——product=TRIM(productCode)、stock=TRIM(productCode)|TRIM(unit)|TRIM(warehouse)、
 *       dealer=TRIM(dealerCode)，与台账行做 TRIM 后等值比对。</li>
 * </ul>
 */
@Service
public class H3LedgerRowHistoryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public H3LedgerRowHistoryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * kind → 批次 data_type 集合（outbound 兼容历史 NORMAL，与前端 normalizeH3DbType 同口径）。
     * ⚠️ 条目已超 Map.of 的 10 对上限（12 类），必须用 {@code Map.ofEntries}。
     */
    private static final Map<String, List<String>> DATA_TYPES = Map.ofEntries(
            Map.entry("product", List.of("PRODUCT_INFO")),
            Map.entry("stock", List.of("WAREHOUSE_STOCK")),
            Map.entry("dealer", List.of("DEALER")),
            Map.entry("supplier", List.of("SUPPLIER")),
            Map.entry("purchase", List.of("PURCHASE_IN")),
            Map.entry("otherIn", List.of("OTHER_IN")),
            Map.entry("otherOut", List.of("OTHER_OUT")),
            Map.entry("transfer", List.of("TRANSFER")),
            Map.entry("invoiceProfile", List.of("INVOICE_PROFILE")),
            Map.entry("invoiceRequest", List.of("INVOICE_REQUEST")),
            Map.entry("productionReport", List.of("PRODUCTION_REPORT")),
            Map.entry("outbound", List.of("OUTBOUND", "NORMAL")),
            Map.entry("production", List.of("PRODUCTION")),
            Map.entry("receipt", List.of("RECEIPT")),
            Map.entry("settlement", List.of("SETTLEMENT")));

    /** B 域（台账类）kind 集合：preview 行为扁平 Map、changes 为文案串。 */
    private static final List<String> LEDGER_KINDS = List.of("product", "stock", "dealer");

    /**
     * 查询某台账行的导入/更新历史。
     *
     * @return { kind, businessKey, title, entries:[{batchId, fileName, batchTime, action, changes, changeItems}] }
     *         entries 按批次 id 升序（即时间序）；action ∈ CREATE / UPDATE，UNCHANGED 不入史。
     */
    public Map<String, Object> history(String kind, Long id) {
        List<String> types = DATA_TYPES.get(kind);
        if (types == null) throw new IllegalArgumentException("未知台账类型：" + kind);
        RowKey key = resolveRowKey(kind, id);

        // types 为上方常量白名单，无注入面；status 限定已确认批次（PREVIEW_READY 未落台账，不算历史）
        String in = String.join(",", types.stream().map(t -> "'" + t + "'").toList());
        List<Map<String, Object>> batches = jdbc.queryForList(
                "SELECT id, file_name, created_at, completed_at, preview_json FROM h3_import_batches" +
                        " WHERE data_type IN (" + in + ") AND status IN ('COMPLETED','PARTIAL_FAILED')" +
                        " AND preview_json IS NOT NULL ORDER BY id");

        boolean isLedgerKind = LEDGER_KINDS.contains(kind);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> batch : batches) {
            JsonNode rows;
            try {
                JsonNode root = objectMapper.readTree(String.valueOf(batch.get("preview_json")));
                rows = root.path("rows");
            } catch (Exception e) {
                continue; // 单个批次缓存损坏不阻断整体历史
            }
            if (!rows.isArray()) continue;
            for (JsonNode row : rows) {
                String action = row.path("action").asText("");
                if ("UNCHANGED".equals(action) || action.isEmpty()) continue;
                if (!matches(isLedgerKind, kind, row, key)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("batchId", ((Number) batch.get("id")).longValue());
                entry.put("fileName", batch.get("file_name"));
                Object batchTime = batch.get("completed_at") != null ? batch.get("completed_at") : batch.get("created_at");
                entry.put("batchTime", batchTime);
                entry.put("action", action);
                JsonNode changes = row.path("changes");
                if (changes.isArray()) {
                    // A 域：字段级变化数组
                    List<Map<String, Object>> items = new ArrayList<>();
                    changes.forEach(c -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("path", c.path("path").asText(null));
                        item.put("oldValue", c.path("oldValue").asText(null));
                        item.put("newValue", c.path("newValue").asText(null));
                        items.add(item);
                    });
                    entry.put("changeItems", items);
                } else if (changes.isTextual() && !changes.asText().isBlank()) {
                    // B 域：文案串
                    entry.put("changes", changes.asText());
                }
                entries.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("businessKey", key.businessKey);
        result.put("title", key.title);
        result.put("entries", entries);
        return result;
    }

    /** preview 行是否与台账行业务键匹配。 */
    private boolean matches(boolean isLedgerKind, String kind, JsonNode row, RowKey key) {
        if (!isLedgerKind) {
            // A 域：businessKey = 清洗后的业务编号（与台账表列同源），TRIM 后等值
            return key.businessKey.equalsIgnoreCase(row.path("businessKey").asText("").trim());
        }
        return switch (kind) {
            case "product" -> key.businessKey.equalsIgnoreCase(row.path("productCode").asText("").trim());
            case "dealer" -> key.businessKey.equalsIgnoreCase(row.path("dealerCode").asText("").trim());
            case "stock" -> key.k1.equalsIgnoreCase(row.path("productCode").asText("").trim())
                    && key.k2.equalsIgnoreCase(row.path("unit").asText("").trim())
                    && key.k3.equalsIgnoreCase(row.path("warehouse").asText("").trim());
            default -> false;
        };
    }

    private RowKey resolveRowKey(String kind, Long id) {
        List<Map<String, Object>> rows = switch (kind) {
            case "product" -> jdbc.queryForList(
                    "SELECT TRIM(product_code) k, product_name title FROM h3_product_catalog WHERE id=?", id);
            case "stock" -> jdbc.queryForList(
                    "SELECT TRIM(product_code) k1, TRIM(unit) k2, TRIM(warehouse) k3, product_name title" +
                            " FROM h3_warehouse_stock WHERE id=?", id);
            case "dealer" -> jdbc.queryForList(
                    "SELECT TRIM(dealer_code) k, dealer_name title FROM h3_dealer_catalog WHERE id=?", id);
            case "outbound" -> jdbc.queryForList(
                    "SELECT TRIM(outbound_no) k, outbound_no title FROM sales_outbounds WHERE id=?", id);
            case "production" -> jdbc.queryForList(
                    "SELECT TRIM(work_order_no) k, work_order_no title FROM production_orders WHERE id=?", id);
            case "receipt", "settlement" -> jdbc.queryForList(
                    "SELECT TRIM(receipt_no) k, receipt_no title FROM receipts WHERE id=?", id);
            // 供应商业务键 = 名称原文（与 preview businessKey=clean(供应商名称) 同源）；
            // 采购业务键 = 采购入库编号（主表数据ID列被子表列首命中吞掉，external_id 统一取单号）
            case "supplier" -> jdbc.queryForList(
                    "SELECT TRIM(name) k, name title FROM h3_supplier_catalog WHERE id=?", id);
            case "purchase" -> jdbc.queryForList(
                    "SELECT TRIM(source_no) k, source_no title FROM purchase_receipts WHERE id=?", id);
            // 其他入/出库与调拨：业务键同为单号（preview businessKey=clean(单号)，external_id 亦取单号）
            case "otherIn", "otherOut" -> jdbc.queryForList(
                    "SELECT TRIM(source_no) k, source_no title FROM h3_other_stock_movements WHERE id=?", id);
            case "transfer" -> jdbc.queryForList(
                    "SELECT TRIM(source_no) k, source_no title FROM h3_inventory_transfers WHERE id=?", id);
            // 开票域分组键=数据ID UUID（18 单缺流水号，不能按 source_no 回查业务键）
            case "invoiceProfile" -> jdbc.queryForList(
                    "SELECT TRIM(external_id) k, customer_name title FROM h3_customer_invoice_profiles WHERE id=?", id);
            case "invoiceRequest" -> jdbc.queryForList(
                    "SELECT TRIM(external_id) k, source_no title FROM h3_invoice_requests WHERE id=?", id);
            // 生产报工无业务单号：业务键 = 数据ID UUID（分组键同为数据ID）
            case "productionReport" -> jdbc.queryForList(
                    "SELECT TRIM(external_id) k, work_order_no title FROM h3_production_reports WHERE id=?", id);
            default -> throw new IllegalArgumentException("未知台账类型：" + kind);
        };
        if (rows.isEmpty()) throw new NoSuchElementException("台账记录不存在：" + kind + "/" + id);
        Map<String, Object> r = rows.get(0);
        if (kind.equals("stock")) {
            String k1 = String.valueOf(r.get("k1"));
            String k2 = String.valueOf(r.get("k2"));
            String k3 = String.valueOf(r.get("k3"));
            return new RowKey(k1 + "|" + k2 + "|" + k3, String.valueOf(r.get("title")), k1, k2, k3);
        }
        return new RowKey(String.valueOf(r.get("k")), String.valueOf(r.get("title")), null, null, null);
    }

    /** 台账行业务键。stock 需三段键（编号|单位|仓库），其余只用主键段。 */
    private record RowKey(String businessKey, String title, String k1, String k2, String k3) {}
}
