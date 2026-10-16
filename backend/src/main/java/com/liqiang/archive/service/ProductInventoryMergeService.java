package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 氚云台账只读服务（产品信息 / 仓库明细 / 经销商）。
 *
 * <p><b>2026-10-07 收敛</b>：产品库存口径确定为「只认氚云产品信息 + 仓库明细两张台账」，
 * 原「台账合并入库 / 期初开账」账务链路（{@code preview / confirm / openingPreflight /
 * mergePreviewOf} 及库存余额、库存流水的全部读写）已随该设计下线整体移除。
 * 本类现只承担台账列表与单行详情的读取，供氚云导入中心的台账面板使用。
 *
 * <p>保留的读写边界：本类<b>不写任何表</b>，写入台账由
 * {@link ProductInventoryLedgerImportService} 负责，读取产品库存清单由
 * {@link ProductInventoryService} 负责。
 */
@Service
@RequiredArgsConstructor
public class ProductInventoryMergeService {

    private final JdbcTemplate jdbc;
    // ===== 台账排序白名单（2026-10-08）=====
    // 与 H3BusinessQueryService 同口径：非白名单键静默回落默认序，方向只作用于主键、兜底键固定 DESC。
    static final LedgerSort.Spec PRODUCT_SORT = LedgerSort.Spec.of(
            "created_at DESC,id DESC", "id",
            LedgerSort.TIME, "created_at",
            LedgerSort.CODE, "product_code",
            LedgerSort.NAME, "product_name");
    static final LedgerSort.Spec STOCK_SORT = LedgerSort.Spec.of(
            "created_at DESC,id DESC", "id",
            LedgerSort.TIME, "created_at",
            LedgerSort.CODE, "product_code",
            LedgerSort.NAME, "product_name");
    static final LedgerSort.Spec DEALER_SORT = LedgerSort.Spec.of(
            "created_at DESC,id DESC", "id",
            LedgerSort.TIME, "created_at",
            LedgerSort.CODE, "dealer_code",
            LedgerSort.NAME, "dealer_name");

    // 费用名单与单位归一统一到 util（口径单一来源，勿在本类另写一份）：
    //   费用 → ProductFeeFilter（含「类目命中」与「编号兜底」两条判定）
    //   单位 → UnitNormalizer（去空白/尾随噪声 + 别名，同物不同写的单位必须归一）

    // ===== 台账查询 =====

    /**
     * 台账行通用「导入与系统信息」列（产品信息 / 仓库明细同构，2026-09-16 补）。
     *
     * <p>氚云全字段导出在业务列之后携带系统列（数据ID / 数据标题 / 创建人 / 拥有者 /
     * 所属部门 / 创建时间 / 修改时间 / 数据状态），详情页据此回答「这行数据从氚云哪条记录来、
     * 什么时候建的、最后一次改是什么时候」。source_* 为氚云侧观测值，只读回显。
     */
    private static final String LEDGER_SYSTEM_COLUMNS =
            " source_object_id AS sourceObjectId, source_title AS sourceTitle, owner_name AS ownerName," +
            " department_name AS departmentName, created_by_name AS createdByName, data_status AS dataStatus," +
            " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
            " source_hash AS sourceHash, last_synced_at AS lastSyncedAt," +
            " created_at AS createdAt, updated_at AS updatedAt";

    public Map<String, Object> productCatalogLedger(int page, int size, String keyword, String sort, String direction) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (product_code LIKE ? OR product_name LIKE ? OR product_category LIKE ?)");
            String kw = "%" + keyword + "%";
            params.addAll(List.of(kw, kw, kw));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_product_catalog" + where, Long.class, params.toArray());
        int offset = (page - 1) * size;
        params.addAll(List.of(size, offset));
        var items = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, product_category AS category, product_code AS productCode," +
                        " product_name AS productName, specification, unit, purchase_price AS purchasePrice," +
                        LEDGER_SYSTEM_COLUMNS +
                        " FROM h3_product_catalog" + where + LedgerSort.orderBy(PRODUCT_SORT, sort, direction) + " LIMIT ? OFFSET ?",
                params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> warehouseStockLedger(int page, int size, String keyword, String sort, String direction) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (product_code LIKE ? OR product_name LIKE ? OR warehouse LIKE ? OR product_category LIKE ?)");
            String kw = "%" + keyword + "%";
            params.addAll(List.of(kw, kw, kw, kw));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_warehouse_stock" + where, Long.class, params.toArray());
        int offset = (page - 1) * size;
        params.addAll(List.of(size, offset));
        var items = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, warehouse, product_category AS category," +
                        " product_code AS productCode, product_name AS productName, specification, unit," +
                        " quantity, purchase_price AS purchasePrice," + LEDGER_SYSTEM_COLUMNS +
                        " FROM h3_warehouse_stock" + where + LedgerSort.orderBy(STOCK_SORT, sort, direction) + " LIMIT ? OFFSET ?",
                params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    // ===== 台账单行详情（与列表同构字段 + 全字段，供 H3LedgerPanel 详情抽屉） =====

    public Map<String, Object> productCatalogDetail(Long id) {
        var rows = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, product_category AS category, product_code AS productCode," +
                        " product_name AS productName, specification, unit, purchase_price AS purchasePrice," +
                        LEDGER_SYSTEM_COLUMNS + " FROM h3_product_catalog WHERE id=?",
                id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("产品台账记录不存在（id=" + id + "）");
        return Map.of("kind", "product", "row", rows.getFirst());
    }

    public Map<String, Object> warehouseStockDetail(Long id) {
        var rows = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, warehouse, product_category AS category," +
                        " product_code AS productCode, product_name AS productName, specification, unit," +
                        " quantity, purchase_price AS purchasePrice," + LEDGER_SYSTEM_COLUMNS +
                        " FROM h3_warehouse_stock WHERE id=?",
                id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("仓库明细台账记录不存在（id=" + id + "）");
        return Map.of("kind", "stock", "row", rows.getFirst());
    }

    // ===== 经销商信息台账（只读；导入由 ProductInventoryLedgerImportService 负责） =====

    public Map<String, Object> dealerLedger(int page, int size, String keyword, String sort, String direction) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (dealer_code LIKE ? OR dealer_name LIKE ? OR contact LIKE ?"
                    + " OR owner_salesperson LIKE ? OR address LIKE ?)");
            String kw = "%" + keyword + "%";
            params.addAll(List.of(kw, kw, kw, kw, kw));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_dealer_catalog" + where, Long.class, params.toArray());
        int offset = (page - 1) * size;
        params.addAll(List.of(size, offset));
        var items = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, dealer_code AS dealerCode, dealer_name AS dealerName," +
                        " contact, address, remark, owner_salesperson AS ownerSalesperson," +
                        " data_status AS dataStatus, source_object_id AS sourceObjectId," +
                        " source_title AS sourceTitle, owner_name AS ownerName," +
                        " department_name AS departmentName, created_by_name AS createdByName," +
                        " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
                        " source_hash AS sourceHash, last_synced_at AS lastSyncedAt," +
                        " created_at AS createdAt, updated_at AS updatedAt" +
                        " FROM h3_dealer_catalog" + where + LedgerSort.orderBy(DEALER_SORT, sort, direction) + " LIMIT ? OFFSET ?",
                params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> dealerDetail(Long id) {
        var rows = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, dealer_code AS dealerCode, dealer_name AS dealerName," +
                        " contact, address, remark, owner_salesperson AS ownerSalesperson," +
                        " data_status AS dataStatus, source_object_id AS sourceObjectId," +
                        " source_title AS sourceTitle, owner_name AS ownerName," +
                        " department_name AS departmentName, created_by_name AS createdByName," +
                        " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
                        " source_hash AS sourceHash, last_synced_at AS lastSyncedAt," +
                        " created_at AS createdAt, updated_at AS updatedAt" +
                        " FROM h3_dealer_catalog WHERE id=?",
                id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("经销商台账记录不存在（id=" + id + "）");
        return Map.of("kind", "dealer", "row", rows.getFirst());
    }
}
