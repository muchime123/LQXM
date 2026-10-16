package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.liqiang.archive.security.CurrentUserService;
import java.util.UUID;

/** 氚云全量重导服务：提供影响预检、保护快照和受令牌约束的来源数据重建。 */
@Service
@RequiredArgsConstructor
public class H3ResetService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    private final ObjectMapper objectMapper;

    public static final String PRESERVE_IDENTITIES = "PRESERVE_IDENTITIES";
    public static final String REASSESS_IDENTITIES = "REASSESS_IDENTITIES";
    private static final Map<String, String> H3_RULE_TABLES = Map.of(
            "source_identity_aliases", "source_system='H3YUN'",
            "customer_source_cluster_mappings", "source_system='H3YUN'",
            "employee_external_mappings", "source_system='H3YUN'",
            "sales_product_source_aliases", "source_system='H3YUN'",
            "salesperson_identity_candidates", "source_system='H3YUN'",
            "h3_warehouse_name_mappings", "1=1");

    /** 创建不可变保护快照，不删除来源数据。重复准备会生成独立批次，便于回滚和审计。 */
    @Transactional
    public Map<String, Object> prepare(String mode) {
        String selectedMode = normalizeMode(mode);
        var user = users.current();
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO h3_reset_runs(reset_token,mode,created_by_employee_id,created_by_name) VALUES(?,?,?,?)",
                token, selectedMode, user.employeeId(), user.name());
        Long runId = jdbc.queryForObject("SELECT id FROM h3_reset_runs WHERE reset_token=?", Long.class, token);
        jdbc.update("""
            INSERT INTO h3_reset_identity_snapshots(
              reset_run_id,entity_type,source_key,source_name,target_id,resolution_status,confidence,resolution_note)
            SELECT ?,entity_type,source_key,source_name,target_id,resolution_status,confidence,resolution_note
              FROM source_identity_aliases
             WHERE source_system='H3YUN' AND resolution_status IN ('AUTO','CONFIRMED','IGNORED')
            """, runId);
        jdbc.update("""
            INSERT INTO h3_reset_outbound_nature_snapshots(
              reset_run_id,outbound_no,confirmed_business_type,original_outbound_no)
            SELECT ?,o.outbound_no,o.confirmed_business_type,original.outbound_no
              FROM sales_outbounds o
              LEFT JOIN sales_outbounds original ON original.id=o.original_outbound_id
             WHERE o.external_source LIKE 'H3%' AND o.business_type_confirmed=1
            """, runId);
        long identities = count("SELECT COUNT(*) FROM h3_reset_identity_snapshots WHERE reset_run_id=" + runId);
        long natures = count("SELECT COUNT(*) FROM h3_reset_outbound_nature_snapshots WHERE reset_run_id=" + runId);
        long rules = selectedMode.equals(REASSESS_IDENTITIES) ? snapshotRules(runId) : 0;
        jdbc.update("UPDATE h3_reset_runs SET identity_count=?,outbound_nature_count=?,rule_snapshot_count=? WHERE id=?",
                identities, natures, rules, runId);
        return Map.of("runId", runId, "resetToken", token, "status", "PREPARED", "mode", selectedMode,
                "identityCount", identities, "outboundNatureCount", natures, "ruleSnapshotCount", rules,
                "message", "保护快照已创建；尚未删除任何氚云来源数据。");
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank() || PRESERVE_IDENTITIES.equals(mode)) return PRESERVE_IDENTITIES;
        if (REASSESS_IDENTITIES.equals(mode)) return REASSESS_IDENTITIES;
        throw new IllegalArgumentException("未知的重导身份策略：" + mode);
    }

    private long snapshotRules(long runId) {
        long total = 0;
        for (Map.Entry<String, String> source : H3_RULE_TABLES.entrySet()) {
            if (!tableExists(source.getKey())) continue;
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + source.getKey() + " WHERE " + source.getValue())) {
                Object id = row.get("id");
                if (!(id instanceof Number number)) throw new IllegalStateException("映射规则缺少稳定行 ID：" + source.getKey());
                try {
                    jdbc.update("INSERT INTO h3_reset_rule_snapshots(reset_run_id,source_table,source_id,row_json) VALUES(?,?,?,?)",
                            runId, source.getKey(), number.longValue(), objectMapper.writeValueAsString(row));
                    total++;
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("映射规则保护快照无法序列化：" + source.getKey(), e);
                }
            }
        }
        return total;
    }

    /**
     * 使用保护快照令牌执行来源重建清理。整个方法处于同一事务中；任一关联表未正确处理时数据库
     * 会拒绝删除并回滚。客户、联系人、员工和产品主档始终保留；来源身份及映射规则是否保留由
     * 本次保护快照选择的身份策略决定。
     */
    @Transactional
    public Map<String, Object> execute(String resetToken) {
        if (resetToken == null || resetToken.isBlank()) {
            throw new IllegalArgumentException("请输入保护快照令牌");
        }
        Map<String, Object> run = jdbc.queryForMap("""
            SELECT id,status,mode,identity_count,outbound_nature_count,rule_snapshot_count
              FROM h3_reset_runs WHERE reset_token=? FOR UPDATE
            """, resetToken.trim());
        long runId = ((Number) run.get("id")).longValue();
        if (!"PREPARED".equals(run.get("status"))) {
            throw new IllegalStateException("该保护快照已执行或不可用");
        }
        long expectedIdentities = ((Number) run.get("identity_count")).longValue();
        long actualIdentities = count("SELECT COUNT(*) FROM h3_reset_identity_snapshots WHERE reset_run_id=?", runId);
        long expectedNatures = ((Number) run.get("outbound_nature_count")).longValue();
        long actualNatures = count("SELECT COUNT(*) FROM h3_reset_outbound_nature_snapshots WHERE reset_run_id=?", runId);
        long expectedRules = ((Number) run.get("rule_snapshot_count")).longValue();
        long actualRules = count("SELECT COUNT(*) FROM h3_reset_rule_snapshots WHERE reset_run_id=?", runId);
        String mode = normalizeMode(String.valueOf(run.get("mode")));
        if (expectedIdentities != actualIdentities || expectedNatures != actualNatures || expectedRules != actualRules) {
            throw new IllegalStateException("保护快照校验失败，禁止执行重置");
        }
        long referencedVersions = countReferencedCommissionVersions();
        if (referencedVersions > 0) throw new IllegalStateException("有 " + referencedVersions + " 个提成核算版本引用氚云出库，禁止重置来源；请先按核算版本流程处理");
        if (REASSESS_IDENTITIES.equals(mode)) {
            long currentRules = countRules();
            if (currentRules != expectedRules) {
                throw new IllegalStateException("创建快照后映射规则数量已变化，请重新创建重导保护快照");
            }
            verifyRuleSnapshot(runId);
        }

        jdbc.execute("DROP TEMPORARY TABLE IF EXISTS tmp_h3_reset_outbounds");
        jdbc.execute("DROP TEMPORARY TABLE IF EXISTS tmp_h3_reset_productions");
        jdbc.execute("DROP TEMPORARY TABLE IF EXISTS tmp_h3_reset_receipts");
        jdbc.execute("CREATE TEMPORARY TABLE tmp_h3_reset_outbounds(id BIGINT PRIMARY KEY) ENGINE=MEMORY");
        jdbc.execute("CREATE TEMPORARY TABLE tmp_h3_reset_productions(id BIGINT PRIMARY KEY) ENGINE=MEMORY");
        jdbc.execute("CREATE TEMPORARY TABLE tmp_h3_reset_receipts(id BIGINT PRIMARY KEY) ENGINE=MEMORY");
        jdbc.update("INSERT INTO tmp_h3_reset_outbounds SELECT id FROM sales_outbounds WHERE external_source LIKE 'H3%'");
        jdbc.update("INSERT INTO tmp_h3_reset_productions SELECT id FROM production_orders WHERE external_source LIKE 'H3%'");
        jdbc.update("INSERT INTO tmp_h3_reset_receipts SELECT id FROM receipts WHERE external_source LIKE 'H3%'");
        long outbounds = count("SELECT COUNT(*) FROM tmp_h3_reset_outbounds");
        long productions = count("SELECT COUNT(*) FROM tmp_h3_reset_productions");
        long receipts = count("SELECT COUNT(*) FROM tmp_h3_reset_receipts");

        // 下游计算结果、治理运行明细和台账关联先清除，主档不受影响。
        deleteByIdsIfPresent("sales_outbound_finished_goods_allocations", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("customer_split_run_items", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("sales_outbound_profit_adjustments", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("sales_outbound_commissions", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("sales_outbound_profits", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("sales_profit_calculations", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("sales_outbound_payment_snapshots", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("settlement_events", "sales_outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("settlement_outbound_links", "outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("receipt_outbound_links", "outbound_id", "tmp_h3_reset_outbounds");
        deleteByIdsIfPresent("receipt_allocations", "sales_outbound_id", "tmp_h3_reset_outbounds");
        if (tableExists("customer_product_match_memory")) {
            jdbc.update("UPDATE customer_product_match_memory SET last_outbound_id=NULL WHERE last_outbound_id IN (SELECT id FROM tmp_h3_reset_outbounds)");
        }
        jdbc.update("UPDATE sales_outbounds SET original_outbound_id=NULL WHERE original_outbound_id IN (SELECT id FROM tmp_h3_reset_outbounds)");
        deleteByIdsIfPresent("sales_outbound_items", "sales_outbound_id", "tmp_h3_reset_outbounds");

        deleteByIdsIfPresent("production_order_components", "production_order_id", "tmp_h3_reset_productions");
        deleteByIdsIfPresent("receipt_reconciliations", "receipt_id", "tmp_h3_reset_receipts");
        deleteByIdsIfPresent("settlement_events", "receipt_id", "tmp_h3_reset_receipts");
        deleteByIdsIfPresent("settlement_outbound_links", "receipt_id", "tmp_h3_reset_receipts");
        deleteByIdsIfPresent("receipt_outbound_links", "receipt_id", "tmp_h3_reset_receipts");
        deleteByIdsIfPresent("receipt_allocations", "receipt_id", "tmp_h3_reset_receipts");

        jdbc.update("DELETE FROM sales_outbounds WHERE id IN (SELECT id FROM tmp_h3_reset_outbounds)");
        jdbc.update("DELETE FROM production_orders WHERE id IN (SELECT id FROM tmp_h3_reset_productions)");
        jdbc.update("DELETE FROM receipts WHERE id IN (SELECT id FROM tmp_h3_reset_receipts)");

        long products = deleteAllIfPresent("h3_product_catalog");
        long stocks = deleteAllIfPresent("h3_warehouse_stock");
        long dealers = deleteAllIfPresent("h3_dealer_catalog");

        // 台账合并（MERGE-IMPORT）生成的库存余额与流水是台账的派生物，不属于氚云原始来源。
        // 合并确认按「目标值幂等写入」更新余额且不反向回收，若不清此处，重导后会出现
        // 「台账已清空、产品库存仍残留」的假象。先删合并流水，再回收因此失去任何流水支撑的余额行；
        // 存在真实业务流水的余额（数量增减来自制造/出库）予以保留。
        long mergeTx = count("SELECT COUNT(*) FROM product_inventory_transactions WHERE source_type='OPENING' AND source_no='MERGE-IMPORT'");
        jdbc.update("DELETE FROM product_inventory_transactions WHERE source_type='OPENING' AND source_no='MERGE-IMPORT'");
        long mergeBalances = count("""
            SELECT COUNT(*) FROM product_inventory_balances b
             WHERE NOT EXISTS (SELECT 1 FROM product_inventory_transactions t
                                WHERE t.warehouse_id=b.warehouse_id AND t.sales_product_id=b.sales_product_id)
            """);
        jdbc.update("""
            DELETE b FROM product_inventory_balances b
             WHERE NOT EXISTS (SELECT 1 FROM product_inventory_transactions t
                                WHERE t.warehouse_id=b.warehouse_id AND t.sales_product_id=b.sales_product_id)
            """);

        deleteAllIfPresent("h3_manufacturing_master_rows");
        deleteAllIfPresent("commission_import_deltas");
        long rawRows = deleteAllIfPresent("h3_import_rows");
        long stagingRows = deleteAllIfPresent("h3_import_staging_rows");
        if (tableExists("source_identity_aliases")) {
            jdbc.update("UPDATE source_identity_aliases SET first_batch_id=NULL,last_batch_id=NULL WHERE source_system='H3YUN'");
        }
        long clearedRules = 0;
        if (REASSESS_IDENTITIES.equals(mode)) {
            for (Map.Entry<String, String> source : H3_RULE_TABLES.entrySet()) {
                if (tableExists(source.getKey())) {
                    clearedRules += jdbc.update("DELETE FROM " + source.getKey() + " WHERE " + source.getValue());
                }
            }
        }
        long batches = deleteAllIfPresent("h3_import_batches");

        jdbc.update("UPDATE h3_reset_runs SET status='EXECUTED',executed_at=NOW() WHERE id=?", runId);
        return Map.ofEntries(
                Map.entry("runId", runId), Map.entry("resetToken", resetToken.trim()),
                Map.entry("status", "EXECUTED"), Map.entry("outboundCount", outbounds),
                Map.entry("productionCount", productions), Map.entry("receiptCount", receipts),
                Map.entry("productCount", products), Map.entry("stockCount", stocks),
                Map.entry("dealerCount", dealers),
                Map.entry("inventoryTxCount", mergeTx), Map.entry("inventoryBalanceCount", mergeBalances),
                Map.entry("rawRowCount", rawRows), Map.entry("stagingRowCount", stagingRows),
                Map.entry("batchCount", batches),
                Map.entry("mode", mode), Map.entry("clearedRuleCount", clearedRules),
                Map.entry("message", REASSESS_IDENTITIES.equals(mode)
                        ? "氚云来源数据和旧身份规则已清理，规则原行留存在本次保护快照；重导后须重新核对客户、员工、产品和仓库关联。"
                        : "氚云来源数据已清理（含台账合并生成的库存余额与流水），可以按统一来源重新导入。客户、产品主档和身份映射均已保留。"));
    }

    /** 作废尚未执行的保护快照令牌；快照内容继续保留用于审计。 */
    @Transactional
    public Map<String, Object> cancel(String resetToken) {
        if (resetToken == null || resetToken.isBlank()) {
            throw new IllegalArgumentException("缺少保护快照令牌");
        }
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT id,status FROM h3_reset_runs WHERE reset_token=? FOR UPDATE", resetToken.trim());
        if (!"PREPARED".equals(run.get("status"))) {
            throw new IllegalStateException("该保护快照已执行或已作废");
        }
        long runId = ((Number) run.get("id")).longValue();
        jdbc.update("UPDATE h3_reset_runs SET status='CANCELLED' WHERE id=?", runId);
        return Map.of("runId", runId, "status", "CANCELLED", "message", "本次保护快照已作废，可重新选择身份策略。");
    }

    private long countRules() {
        long total = 0;
        for (Map.Entry<String, String> source : H3_RULE_TABLES.entrySet()) {
            if (tableExists(source.getKey())) total += count("SELECT COUNT(*) FROM " + source.getKey() + " WHERE " + source.getValue());
        }
        return total;
    }

    private long countReferencedCommissionVersions() {
        return count("""
                SELECT COUNT(DISTINCT version_id) FROM (
                  SELECT l.version_id FROM commission_calculation_source_lines l
                  JOIN sales_outbounds o ON o.id=l.outbound_id WHERE o.external_source LIKE 'H3%'
                  UNION ALL
                  SELECT a.version_id FROM commission_outbound_assessment_lines a
                  JOIN sales_outbounds o ON o.id=a.outbound_id WHERE o.external_source LIKE 'H3%'
                ) referenced
                """);
    }

    /** 数量相同但目标或状态变化时也禁止使用旧快照，避免重置期间丢掉新决策。 */
    private void verifyRuleSnapshot(long runId) {
        Map<String, String> protectedRows = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT source_table,source_id,row_json FROM h3_reset_rule_snapshots WHERE reset_run_id=?", runId)) {
            protectedRows.put(row.get("source_table") + ":" + row.get("source_id"), String.valueOf(row.get("row_json")));
        }
        for (Map.Entry<String, String> source : H3_RULE_TABLES.entrySet()) {
            if (!tableExists(source.getKey())) continue;
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + source.getKey() + " WHERE " + source.getValue())) {
                String key = source.getKey() + ":" + row.get("id");
                try {
                    if (!objectMapper.writeValueAsString(row).equals(protectedRows.remove(key))) {
                        throw new IllegalStateException("映射规则在快照后已改变，请重新创建保护快照：" + key);
                    }
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("映射规则校验失败：" + key, e);
                }
            }
        }
        if (!protectedRows.isEmpty()) throw new IllegalStateException("快照后有映射规则被移除，请重新创建保护快照");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview() {
        List<Map<String, Object>> source = new ArrayList<>();
        add(source, "IMPORT_BATCH", "导入批次", count("SELECT COUNT(*) FROM h3_import_batches"), true,
                "重导后重新生成");
        add(source, "IMPORT_STAGING", "待确认暂存行", count("SELECT COUNT(*) FROM h3_import_staging_rows"), true,
                "仅解析缓存，可安全重建");
        add(source, "IMPORT_SNAPSHOT", "已确认原始快照", count("SELECT COUNT(*) FROM h3_import_rows"), true,
                "删除前需随数据库备份留档");
        add(source, "OUTBOUND", "氚云销售出库", count("SELECT COUNT(*) FROM sales_outbounds WHERE external_source LIKE 'H3%'"), true,
                "客户、未收款和提成的权威来源");
        add(source, "OUTBOUND_ITEM", "销售出库产品明细", count("SELECT COUNT(*) FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id WHERE o.external_source LIKE 'H3%'"), true,
                "随销售出库重建");
        add(source, "PAYMENT_SNAPSHOT", "出库收款快照", count("SELECT COUNT(*) FROM sales_outbound_payment_snapshots p JOIN sales_outbounds o ON o.id=p.sales_outbound_id WHERE o.external_source LIKE 'H3%'"), true,
                "月度提成的来源明细");
        add(source, "PRODUCTION", "氚云生产任务工单", count("SELECT COUNT(*) FROM production_orders WHERE external_source LIKE 'H3%'"), true,
                "历史工单事实");
        add(source, "RECEIPT", "氚云收款与结清台账", count("SELECT COUNT(*) FROM receipts WHERE external_source LIKE 'H3%'"), true,
                "历史核对台账，不决定现行未收款");
        add(source, "PRODUCT", "氚云产品信息台账", count("SELECT COUNT(*) FROM h3_product_catalog"), true,
                "按产品编号重建");
        add(source, "STOCK", "氚云仓库明细台账", count("SELECT COUNT(*) FROM h3_warehouse_stock"), true,
                "某一导出时点的库存快照");
        add(source, "DEALER", "氚云经销商信息台账", count("SELECT COUNT(*) FROM h3_dealer_catalog"), true,
                "按经销商来源编号重新建立");

        List<Map<String, Object>> impacts = new ArrayList<>();
        add(impacts, "CUSTOMER_LINK", "已关联客户的出库单", count("SELECT COUNT(*) FROM sales_outbounds WHERE external_source LIKE 'H3%' AND customer_id IS NOT NULL"), false,
                "保留客户档案，重导后按来源身份恢复关联");
        add(impacts, "EMPLOYEE_LINK", "已关联员工的出库单", count("SELECT COUNT(*) FROM sales_outbounds WHERE external_source LIKE 'H3%' AND employee_id IS NOT NULL"), false,
                "保留员工，重导后按身份映射恢复");
        add(impacts, "IDENTITY", "氚云来源身份", count("SELECT COUNT(*) FROM source_identity_aliases WHERE source_system='H3YUN'"), false,
                "人工确认映射必须备份，不能直接清空");
        add(impacts, "PROFIT", "销售出库利润结果", count("SELECT COUNT(*) FROM sales_outbound_profits p JOIN sales_outbounds o ON o.id=p.sales_outbound_id WHERE o.external_source LIKE 'H3%'"), false,
                "清理计算结果；保留利润公式和重算能力");
        add(impacts, "COMMISSION_SOURCE", "已进入提成版本的来源明细", count("SELECT COUNT(*) FROM commission_calculation_source_lines l JOIN sales_outbounds o ON o.id=l.outbound_id WHERE o.external_source LIKE 'H3%'"), false,
                "尚未正式投入使用，可随来源清理后重新生成");
        add(impacts, "FINAL_COMMISSION", "引用氚云出库的正式提成版本", count("SELECT COUNT(DISTINCT l.version_id) FROM commission_calculation_source_lines l JOIN sales_outbounds o ON o.id=l.outbound_id JOIN commission_calculation_versions v ON v.id=l.version_id WHERE o.external_source LIKE 'H3%' AND v.status='FINAL'"), false,
                "当前没有正式结算，测试版本可清理");
        add(impacts, "INVENTORY_MERGE", "台账合并产生的库存余额与流水", count("SELECT COUNT(*) FROM product_inventory_transactions WHERE source_type='OPENING' AND source_no='MERGE-IMPORT'"), true,
                "随台账一并清理；重导后由产品信息与仓库明细台账重新合并生成");

        long referencedVersions = countReferencedCommissionVersions();

        List<Map<String, Object>> blockers = new ArrayList<>();
        blocker(blockers, "COMMISSION_VERSION", referencedVersions, "提成核算版本已引用氚云出库，必须先按版本流程处理；当前禁止来源重置");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now());
        result.put("recommendedMode", "SOURCE_REBUILD");
        result.put("executionAllowed", referencedVersions == 0);
        result.put("identityRuleCount", countRules());
        result.put("referencedCommissionVersions", referencedVersions);
        result.put("sourceData", source);
        result.put("downstreamImpacts", impacts);
        result.put("blockers", blockers);
        result.put("message", referencedVersions > 0
                ? "提成核算版本引用了氚云出库，禁止来源重置。"
                : "先选择身份策略并创建保护快照；执行前会再次校验来源与映射规则。请先完成数据库备份和导出文件准备。");
        return result;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private boolean tableExists(String table) {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", table) > 0;
    }

    private long deleteAllIfPresent(String table) {
        return tableExists(table) ? jdbc.update("DELETE FROM " + table) : 0L;
    }

    private void deleteByIdsIfPresent(String table, String column, String tempTable) {
        if (tableExists(table)) {
            jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (SELECT id FROM " + tempTable + ")");
        }
    }

    private static void add(List<Map<String, Object>> rows, String code, String label, long count,
                            boolean rebuildable, String handling) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("label", label);
        row.put("count", count);
        row.put("rebuildable", rebuildable);
        row.put("handling", handling);
        rows.add(row);
    }

    private static void blocker(List<Map<String, Object>> rows, String code, long count, String message) {
        if (count <= 0) return;
        rows.add(Map.of("code", code, "count", count, "message", message));
    }
}
