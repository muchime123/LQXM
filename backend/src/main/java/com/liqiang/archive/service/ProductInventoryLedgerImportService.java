package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.H3FactImportDtos.BatchProgress;
import com.liqiang.archive.dto.H3FactImportDtos.LedgerImportPreview;
import com.liqiang.archive.dto.H3FactImportDtos.Result;
import com.liqiang.archive.dto.H3FactImportDtos.StageAccepted;
import com.liqiang.archive.util.LedgerHeaderGuard;
import com.liqiang.archive.util.UnitNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.IOUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 产品信息 / 仓库明细台账「批次化增量导入」服务（与销售出库 V160 管线同思路）。
 *
 * <p>与旧「覆盖式上传」（DELETE 全表后重写）的本质差异：
 * <ol>
 *   <li>上传即登记 {@code h3_import_batches} 批次（PRODUCT_INFO / WAREHOUSE_STOCK），可在氚云导入中心「最近导入」核对；</li>
 *   <li>后台解析落 {@code h3_import_staging_rows} 暂存行，预览阶段与当前台账逐行比对，给出 共/新增/更新/忽略；</li>
 *   <li>确认时才写入台账：新行→新增、已有行按内容 diff 决定更新或忽略（不删除既有历史行、不整表覆盖）；</li>
 *   <li>重复确认幂等（批号 FOR UPDATE 串行化；已完成的批次直接返回统计）。</li>
 * </ol>
 *
 * <p>扩展其他氚云表格时：在 {@link Kind} 增加枚举、提供 parseExcel 与
 * loadExisting / applyRow（含字段 diff 与中文变更标签），即可复用同一批次与预览/确认骨架。
 */
@Service
@RequiredArgsConstructor
public class ProductInventoryLedgerImportService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BatchImportPipelineService pipeline;

    /** 自身代理引用：在本类内触发 @Async（避免 self-invocation）时经 Spring 代理调用。 */
    @Autowired @Lazy
    private ProductInventoryLedgerImportService self;

    /** 台账导入种类：元数据委托统一描述符 {@link ImportKindDescriptor}（单一事实源，避免 dbType/文案漂移）。 */
    private enum Kind {
        CATALOG(ImportKindDescriptor.CATALOG),
        STOCK(ImportKindDescriptor.STOCK),
        /** 经销商信息台账：业务键为氚云经销商编号（JXS…）。 */
        DEALER(ImportKindDescriptor.DEALER);
        final String path;
        final String dbType;
        final String label;
        Kind(ImportKindDescriptor kind) { this.path = kind.route(); this.dbType = kind.dbType(); this.label = kind.displayName(); }
        static Kind from(String value) {
            return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
                case "catalog" -> CATALOG;
                case "stock" -> STOCK;
                case "dealer" -> DEALER;
                default -> throw new IllegalArgumentException("不支持的台账导入类型：" + value);
            };
        }

        /** 按批次表 data_type（dbType）反查种类，用于 confirm 阶段读取历史批次。 */
        static Kind fromDbType(String dbType) {
            return switch (dbType == null ? "" : dbType) {
                case "PRODUCT_INFO" -> CATALOG;
                case "WAREHOUSE_STOCK" -> STOCK;
                case "DEALER" -> DEALER;
                default -> throw new IllegalArgumentException("未知台账数据类型：" + dbType);
            };
        }
    }

    static {
        IOUtils.setByteArrayMaxOverride(1_000_000_000);
    }

    // ====================================================================
    // Step 1：上传 → 登记批次（UPLOADED）→ 后台异步解析 → 落暂存 → 比对预览
    // 与销售出库等四类业务事实管线一致：stage 仅登记并立即返回，进度经
    // /ledger-batches/{id}/progress 轮询（PARSING → STAGING → PREVIEW_READY）。
    // ====================================================================

    public StageAccepted stage(String type, MultipartFile file) {
        Kind kind = Kind.from(type);
        byte[] bytes = readBytes(file);
        String fileHash = sha256(bytes);
        String fileName = file.getOriginalFilename() == null ? "未命名.xlsx" : file.getOriginalFilename();

        // 幂等命中：同类型同文件哈希且仍处于 PREVIEW_READY（未确认）→ 复用旧批次预览
        Long cached = pipeline.findPreviewReadyBatchId(kind.dbType, fileHash);
        if (cached != null) {
            return new StageAccepted(cached, kind.dbType, fileName, true, "PREVIEW_READY",
                    "文件内容与未确认批次一致，已复用解析结果");
        }

        // 先登记批次（即使异步解析失败也留痕），随后台虚拟线程异步解析
        long batchId = pipeline.createBatch(fileName, bytes.length, fileHash, kind.dbType, null);
        // 经 Spring 代理调用使 @Async 生效
        self.asyncParseAndStage(batchId, kind, bytes, fileName);

        return new StageAccepted(batchId, kind.dbType, fileName, false, "UPLOADED",
                "文件已登记，后台解析中，完成后自动进入预览");
    }

    /**
     * 异步解析：解析 Excel → 按业务键去重 → 落暂存（分批报进度）→ 与台账 diff 生成比对预览。
     * 进度写入 h3_import_batches，由前端每 500ms 轮询；完成一次性置 PREVIEW_READY。
     */
    @Async("h3ImportExecutor")
    public void asyncParseAndStage(Long batchId, Kind kind, byte[] bytes, String fileName) {
        try {
            updateProgress(batchId, "PARSING", 5, "PARSING");
            List<Map<String, Object>> rows;
            try {
                rows = parseRows(kind, bytes);
            } catch (Exception e) {
                markFailed(batchId, "PARSING", "Excel 解析失败：" + safeMsg(e), e);
                return;
            }
            // 同一文件内按业务键去重（产品编号 / 仓库+编号+单位），只保留首行，避免重复计数与重复写入
            rows = dedupe(rows, kind);

            // 落暂存表（与 h3_import_staging_rows 结构对齐；main_json 保存规范化行，rows_json 恒为 []）
            // 分批 200 行写入，边写边推进度条
            final int slice = 200;
            for (int i = 0; i < rows.size(); i += slice) {
                int end = Math.min(rows.size(), i + slice);
                insertStagingSlice(batchId, i, rows.subList(i, end));
                int pct = 50 + (int) ((end * 40L) / Math.max(1, rows.size()));
                updateProgress(batchId, "PARSING", pct, "STAGING");
            }

            // 与当前台账逐行比对（新增/更新/无变化），生成预览缓存并一次置终态
            DiffOutcome outcome = buildDiff(kind, rows);
            String previewJson = toJson(previewMap(batchId, kind.dbType, fileName, outcome));
            pipeline.markPreviewReady(batchId, previewJson, outcome.rows().size(), null);
        } catch (Exception e) {
            markFailed(batchId, "STAGING", "暂存/预览生成失败：" + safeMsg(e), e);
        }
    }

    /** 批次解析进度（与四类业务事实同形，供前端每 500ms 轮询）。 */
    public BatchProgress batchProgress(Long batchId) {
        return pipeline.batchProgress(batchId);
    }

    private void updateProgress(long batchId, String status, int pct, String stage) {
        pipeline.updateProgress(batchId, status, pct, stage);
    }

    // ====================================================================
    // Step 2：读取比对预览（命中 preview_json 缓存）
    // ====================================================================

    public LedgerImportPreview previewOf(Long batchId) {
        List<Map<String, Object>> metas = jdbc.query(
                "SELECT data_type, status, preview_json FROM h3_import_batches WHERE id=?", this::toMap, batchId);
        if (metas.isEmpty()) throw new IllegalArgumentException("批次不存在或尚无预览：id=" + batchId);
        Map<String, Object> meta = metas.getFirst();
        String status = str(meta.get("status"));
        if ("FAILED".equals(status)) throw new IllegalStateException("批次解析失败，无法预览");
        String cached = str(meta.get("preview_json"));
        if (cached == null || cached.isBlank()) throw new IllegalStateException("批次尚无预览缓存：status=" + status);
        LedgerImportPreview parsed = readJson(cached, LedgerImportPreview.class);
        // 历史预览缓存若缺 batchId（老批次），以路径 id 兜底
        if (parsed.batchId() == null || !Objects.equals(parsed.batchId(), batchId)) {
            return new LedgerImportPreview(batchId, parsed.dataType(), parsed.fileName(), parsed.totalCount(),
                    parsed.createCount(), parsed.updateCount(), parsed.unchangedCount(), parsed.rows());
        }
        return parsed;
    }

    // ====================================================================
    // Step 3：确认 → 与当前台账 diff 后增量写入（幂等）
    // ====================================================================

    @Transactional
    public Result confirm(Long batchId) {
        // 行锁串行化：防止并发双击把同一批重复写入
        List<Map<String, Object>> metas = jdbc.query(
                "SELECT id, data_type, status, total_count, created_count, updated_count, unchanged_count FROM h3_import_batches WHERE id=? FOR UPDATE",
                this::toMap, batchId);
        if (metas.isEmpty()) throw new IllegalArgumentException("批次不存在：id=" + batchId);
        Map<String, Object> meta = metas.getFirst();
        String status = str(meta.get("status"));
        String dataType = str(meta.get("data_type"));
        if ("COMPLETED".equals(status) || "PARTIAL_FAILED".equals(status)) {
            // 幂等：已经确认过，直接返回统计
            return new Result(batchId, dataType,
                    numInt(meta.get("total_count")), numInt(meta.get("created_count")),
                    numInt(meta.get("updated_count")), numInt(meta.get("unchanged_count")), 0);
        }
        if (!"PREVIEW_READY".equals(status)) {
            throw new IllegalStateException("批次不在可确认状态：status=" + status);
        }

        Kind kind;
        try { kind = Kind.fromDbType(dataType); }
        catch (Exception e) { throw new IllegalStateException("未知数据类型批次无法确认：" + dataType, e); }

        List<Map<String, Object>> rows = loadStagedRows(batchId);
        int[] c = switch (kind) {
            case CATALOG -> applyCatalog(batchId, rows);
            case STOCK -> applyStock(batchId, rows);
            case DEALER -> applyDealer(batchId, rows);
        };
        // 覆盖标记（V304）：本次文件覆盖到的行刷新 last_seen_at——含 UNCHANGED 行。
        // last_synced_at 只在内容变化时才刷新，不能当「还在氚云里」的信号；
        // 「30 天未被任何导入覆盖」的生命周期报告依赖本标记，因此每行都要触到。
        // 部分导入只标记其覆盖到的行，天然安全（不会把未导入行误判成已删除）。
        if (kind == Kind.CATALOG) markCatalogSeen(batchId, rows);
        else if (kind == Kind.STOCK) markStockSeen(batchId, rows);
        int created = c[0], updated = c[1], unchanged = c[2];
        jdbc.update("UPDATE h3_import_batches SET status='COMPLETED', progress_stage='DONE', progress_pct=100," +
                        " total_count=?, created_count=?, updated_count=?, unchanged_count=?, completed_at=NOW() WHERE id=?",
                rows.size(), created, updated, unchanged, batchId);
        return new Result(batchId, kind.dbType, rows.size(), created, updated, unchanged, 0);
    }

    // ====================================================================
    // 产品信息（catalog）：Excel 解析 + 比对 + 写入
    // ====================================================================

    private List<Map<String, Object>> parseCatalog(byte[] bytes) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            validateHeader("产品信息", sheet, fmt, LedgerHeaderGuard.PRODUCT_INFO);
            Map<String, Integer> header = headerIndex(sheet.getRow(1), fmt);
            String lastCategory = "";
            // 主表系统列向下填充槽位：0 创建人 1 拥有者 2 所属部门 3 创建时间 4 修改时间 5 数据状态
            String[] main = new String[6];
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String code = cellText(fmt, row, 1);
                if (code == null || code.isBlank()) continue;
                String cat = cellText(fmt, row, 0);
                if (cat != null && !cat.isBlank()) lastCategory = cat;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productCode", code);
                m.put("category", lastCategory);
                m.put("productName", cellText(fmt, row, 2));
                m.put("specification", cellText(fmt, row, 3));
                m.put("unit", UnitNormalizer.normalize(cellText(fmt, row, 4)));
                m.put("purchasePrice", cellDecimal(row, 7));
                // 导入与系统信息：行级「产品明细.数据ID(不可修改)」每行独立，主表系统列向下填充
                m.put("sourceObjectId", cellText(fmt, row, header, "产品明细.数据ID(不可修改)", "数据ID(不可修改)"));
                m.put("sourceTitle", cellText(fmt, row, header, "产品明细.数据标题", "数据标题"));
                m.put("createdByName", fill(main, 0, cellText(fmt, row, header, "创建人(必填)", "创建人")));
                m.put("ownerName", fill(main, 1, cellText(fmt, row, header, "拥有者(必填)", "拥有者")));
                m.put("departmentName", fill(main, 2, cellText(fmt, row, header, "所属部门(必填)", "所属部门")));
                m.put("sourceCreatedAt", fill(main, 3, cellText(fmt, row, header, "创建时间(必填)", "创建时间")));
                m.put("sourceModifiedAt", fill(main, 4, cellText(fmt, row, header, "修改时间(必填)", "修改时间")));
                m.put("dataStatus", fill(main, 5, cellText(fmt, row, header, "数据状态", "Status")));
                list.add(m);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("产品信息 Excel 解析失败：" + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 表头指纹校验：氚云导出为「两行表头 + 数据从第 3 行起」的固定列位，上游一改列序就会静默错位，
     * 因此导入前先校验第 2 行中文表头的关键列，不符即拒绝整批（见 {@link LedgerHeaderGuard}）。
     */
    private void validateHeader(String reportName, Sheet sheet, DataFormatter fmt, List<LedgerHeaderGuard.Column> spec) {
        if (sheet.getLastRowNum() < 2) {
            throw new IllegalArgumentException("「" + reportName + "」台账缺少数据行（应为 2 行表头 + 数据行），请确认导出的报表是否正确。");
        }
        Row header = sheet.getRow(1);
        LedgerHeaderGuard.validate(reportName, spec, col -> header == null ? null : cellText(fmt, header, col));
    }

    private Map<String, Object> existingCatalogRow(Map<String, Object> row) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, product_category AS category, product_name AS productName, specification, unit," +
                        " purchase_price AS purchasePrice, source_object_id AS sourceObjectId," +
                        " source_title AS sourceTitle, owner_name AS ownerName, department_name AS departmentName," +
                        " created_by_name AS createdByName, data_status AS dataStatus," +
                        " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
                        " last_synced_at AS lastSyncedAt" +
                        " FROM h3_product_catalog WHERE product_code=?",
                this::toMap, row.get("productCode"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertCatalogRow(long batchId, Map<String, Object> row) {
        String tag = "B" + batchId;
        jdbc.update("INSERT INTO h3_product_catalog(import_batch_id, product_category, product_code, product_name," +
                        " specification, unit, purchase_price, source_object_id, source_title, owner_name," +
                        " department_name, created_by_name, data_status, source_created_at, source_modified_at," +
                        " last_synced_at, source_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?)",
                tag, row.get("category"), row.get("productCode"), row.get("productName"),
                row.get("specification"), row.get("unit"), row.get("purchasePrice"),
                row.get("sourceObjectId"), row.get("sourceTitle"), row.get("ownerName"),
                row.get("departmentName"), row.get("createdByName"), row.get("dataStatus"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")), sourceHash(row));
    }

    private void updateCatalogRow(Map<String, Object> existing, long batchId, Map<String, Object> row) {
        jdbc.update("UPDATE h3_product_catalog SET product_category=?, product_name=?, specification=?, unit=?," +
                        " purchase_price=?, import_batch_id=?, source_object_id=?, source_title=?, owner_name=?," +
                        " department_name=?, created_by_name=?, data_status=?, source_created_at=?," +
                        " source_modified_at=?, last_synced_at=NOW(), source_hash=? WHERE id=?",
                row.get("category"), row.get("productName"), row.get("specification"), row.get("unit"),
                row.get("purchasePrice"), "B" + batchId,
                row.get("sourceObjectId"), row.get("sourceTitle"), row.get("ownerName"),
                row.get("departmentName"), row.get("createdByName"), row.get("dataStatus"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")),
                sourceHash(row), existing.get("id"));
    }

    /**
     * 观测列回填：业务字段无变化（UNCHANGED）但氚云系统列与库内不一致时刷新，只写
     * source_* / last_synced_at，不计入 updated_count，因此不会破坏导入幂等统计。
     *
     * <p>存在意义：系统列是 2026-09-16 才补的，存量 2.9 万行台账的这些列为 NULL；
     * 若 UNCHANGED 一律不写库，用户重新导入同一份文件也永远回填不上，详情里会一直是空。
     */
    private void refreshCatalogObservations(Map<String, Object> existing, Map<String, Object> row) {
        jdbc.update("UPDATE h3_product_catalog SET source_object_id=?, source_title=?, owner_name=?," +
                        " department_name=?, created_by_name=?, data_status=?, source_created_at=?," +
                        " source_modified_at=?, last_synced_at=NOW() WHERE id=?",
                row.get("sourceObjectId"), row.get("sourceTitle"), row.get("ownerName"),
                row.get("departmentName"), row.get("createdByName"), row.get("dataStatus"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")),
                existing.get("id"));
    }

    /** 观测列（氚云系统列）是否需要回填；只比较系统列本身，不看业务字段。 */
    private boolean observDiffer(Map<String, Object> e, Map<String, Object> r) {
        return !sEq(e.get("sourceObjectId"), r.get("sourceObjectId"))
                || !sEq(e.get("sourceTitle"), r.get("sourceTitle"))
                || !sEq(e.get("ownerName"), r.get("ownerName"))
                || !sEq(e.get("departmentName"), r.get("departmentName"))
                || !sEq(e.get("createdByName"), r.get("createdByName"))
                || !sEq(e.get("dataStatus"), r.get("dataStatus"))
                || !tEq(e.get("sourceCreatedAt"), r.get("sourceCreatedAt"))
                || !tEq(e.get("sourceModifiedAt"), r.get("sourceModifiedAt"));
    }

    private boolean catalogEqual(Map<String, Object> a, Map<String, Object> b) {
        return sEq(a.get("category"), b.get("category"))
                && sEq(a.get("productName"), b.get("productName"))
                && sEq(a.get("specification"), b.get("specification"))
                && sEq(a.get("unit"), b.get("unit"))
                && nEq(a.get("purchasePrice"), b.get("purchasePrice"));
    }

    /** 批量刷新「被本次导入覆盖」标记（分块 IN，避免超长语句）。 */
    private void markCatalogSeen(long batchId, List<Map<String, Object>> rows) {
        List<Object> codes = rows.stream()
                .map(r -> r.get("productCode"))
                .filter(c -> c != null && !String.valueOf(c).isBlank())
                .distinct()
                .toList();
        for (int i = 0; i < codes.size(); i += 1000) {
            List<Object> chunk = codes.subList(i, Math.min(i + 1000, codes.size()));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.update("UPDATE h3_product_catalog SET last_seen_at=NOW(), last_seen_batch_id=? WHERE product_code IN (" + in + ")",
                    prepend(batchId, chunk));
        }
    }

    /** 仓库明细按业务键 (仓库,编号,单位) 精确标记：<=> 保证 unit/warehouse 为 NULL 的行也能命中，且可走 uk_h3ws_wh_code_unit。 */
    private void markStockSeen(long batchId, List<Map<String, Object>> rows) {
        List<Object[]> args = rows.stream()
                .map(r -> new Object[]{batchId, r.get("warehouse"), r.get("productCode"), r.get("unit")})
                .toList();
        jdbc.batchUpdate("UPDATE h3_warehouse_stock SET last_seen_at=NOW(), last_seen_batch_id=?" +
                " WHERE warehouse<=>? AND product_code=? AND unit<=>?", args);
    }

    private Object[] prepend(Object first, List<Object> rest) {
        Object[] all = new Object[rest.size() + 1];
        all[0] = first;
        for (int i = 0; i < rest.size(); i++) all[i + 1] = rest.get(i);
        return all;
    }

    private int[] applyCatalog(long batchId, List<Map<String, Object>> rows) {
        int created = 0, updated = 0, unchanged = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> existing = existingCatalogRow(row);
            if (existing == null) { insertCatalogRow(batchId, row); created++; }
            else if (catalogEqual(existing, row)) {
                // 业务字段无变化：只回填缺失/变化的氚云系统列，不计入 updated_count（幂等口径不变）
                if (observDiffer(existing, row)) refreshCatalogObservations(existing, row);
                unchanged++;
            }
            else { updateCatalogRow(existing, batchId, row); updated++; }
        }
        return new int[]{created, updated, unchanged};
    }

    // ====================================================================
    // 仓库明细（stock）：Excel 解析 + 比对 + 写入
    // ====================================================================

    private List<Map<String, Object>> parseStock(byte[] bytes) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            validateHeader("仓库明细", sheet, fmt, LedgerHeaderGuard.WAREHOUSE_STOCK);
            Map<String, Integer> header = headerIndex(sheet.getRow(1), fmt);
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String code = cellText(fmt, row, 2);
                if (code == null || code.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("warehouse", cellText(fmt, row, 0));
                m.put("category", cellText(fmt, row, 1));
                m.put("productCode", code);
                m.put("productName", cellText(fmt, row, 3));
                m.put("specification", cellText(fmt, row, 4));
                m.put("unit", UnitNormalizer.normalize(cellText(fmt, row, 5)));
                m.put("quantity", cellDecimal(row, 6));
                // 采购价口径（2026-09-16 固化）：产品信息台账是采购价唯一有效来源。
                // 氚云仓库明细导出的第 8 列「采购价格」表头存在但值恒空（实测 16305 行 100% null），
                // 此处保留读取仅为通过 LedgerHeaderGuard 表头指纹校验；下游
                // ProductInventoryMergeService.enrich() 以产品信息台账优先、此值兜底，实际永远取不到。
                m.put("purchasePrice", cellDecimal(row, 7));
                // 导入与系统信息：仓库明细是扁平表，系统列每行独立（不向下填充，避免把别行
                // 创建时间带到本行）
                m.put("sourceObjectId", cellText(fmt, row, header, "数据ID(不可修改)"));
                m.put("sourceTitle", cellText(fmt, row, header, "数据标题"));
                m.put("createdByName", cellText(fmt, row, header, "创建人(必填)", "创建人"));
                m.put("ownerName", cellText(fmt, row, header, "拥有者(必填)", "拥有者"));
                m.put("departmentName", cellText(fmt, row, header, "所属部门(必填)", "所属部门"));
                m.put("sourceCreatedAt", cellText(fmt, row, header, "创建时间(必填)", "创建时间"));
                m.put("sourceModifiedAt", cellText(fmt, row, header, "修改时间(必填)", "修改时间"));
                m.put("dataStatus", cellText(fmt, row, header, "数据状态", "Status"));
                list.add(m);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("仓库明细 Excel 解析失败：" + e.getMessage(), e);
        }
        return list;
    }

    private Map<String, Object> existingStockRow(Map<String, Object> row) {
        // 业务键含单位：同仓库同编号允许 箱/个 等多单位行并存
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, warehouse, product_category AS category, product_name AS productName, specification, unit," +
                        " quantity, purchase_price AS purchasePrice, source_object_id AS sourceObjectId," +
                        " source_title AS sourceTitle, owner_name AS ownerName, department_name AS departmentName," +
                        " created_by_name AS createdByName, data_status AS dataStatus," +
                        " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt" +
                        " FROM h3_warehouse_stock" +
                        " WHERE warehouse<=>? AND product_code=? AND unit<=>? ORDER BY id LIMIT 1",
                this::toMap, row.get("warehouse"), row.get("productCode"), row.get("unit"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertStockRow(long batchId, Map<String, Object> row) {
        jdbc.update("INSERT INTO h3_warehouse_stock(import_batch_id, warehouse, product_category, product_code," +
                        " product_name, specification, unit, quantity, purchase_price, source_object_id," +
                        " source_title, owner_name, department_name, created_by_name, data_status," +
                        " source_created_at, source_modified_at, last_synced_at, source_hash)" +
                        " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?)",
                "B" + batchId, row.get("warehouse"), row.get("category"), row.get("productCode"),
                row.get("productName"), row.get("specification"), row.get("unit"), row.get("quantity"),
                row.get("purchasePrice"), row.get("sourceObjectId"), row.get("sourceTitle"),
                row.get("ownerName"), row.get("departmentName"), row.get("createdByName"),
                row.get("dataStatus"), dateTime(row.get("sourceCreatedAt")),
                dateTime(row.get("sourceModifiedAt")), sourceHash(row));
    }

    private void updateStockRow(Map<String, Object> existing, long batchId, Map<String, Object> row) {
        jdbc.update("UPDATE h3_warehouse_stock SET warehouse=?, product_category=?, product_name=?, specification=?," +
                        " unit=?, quantity=?, purchase_price=?, import_batch_id=?, source_object_id=?," +
                        " source_title=?, owner_name=?, department_name=?, created_by_name=?, data_status=?," +
                        " source_created_at=?, source_modified_at=?, last_synced_at=NOW(), source_hash=? WHERE id=?",
                row.get("warehouse"), row.get("category"), row.get("productName"), row.get("specification"),
                row.get("unit"), row.get("quantity"), row.get("purchasePrice"), "B" + batchId,
                row.get("sourceObjectId"), row.get("sourceTitle"), row.get("ownerName"),
                row.get("departmentName"), row.get("createdByName"), row.get("dataStatus"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")),
                sourceHash(row), existing.get("id"));
    }

    /** 观测列回填（同 {@link #refreshCatalogObservations}，口径与幂等约束一致）。 */
    private void refreshStockObservations(Map<String, Object> existing, Map<String, Object> row) {
        jdbc.update("UPDATE h3_warehouse_stock SET source_object_id=?, source_title=?, owner_name=?," +
                        " department_name=?, created_by_name=?, data_status=?, source_created_at=?," +
                        " source_modified_at=?, last_synced_at=NOW() WHERE id=?",
                row.get("sourceObjectId"), row.get("sourceTitle"), row.get("ownerName"),
                row.get("departmentName"), row.get("createdByName"), row.get("dataStatus"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")),
                existing.get("id"));
    }

    private boolean stockEqual(Map<String, Object> a, Map<String, Object> b) {
        return sEq(a.get("warehouse"), b.get("warehouse"))
                && sEq(a.get("category"), b.get("category"))
                && sEq(a.get("productName"), b.get("productName"))
                && sEq(a.get("specification"), b.get("specification"))
                && sEq(a.get("unit"), b.get("unit"))
                && nEq(a.get("quantity"), b.get("quantity"))
                && nEq(a.get("purchasePrice"), b.get("purchasePrice"));
    }

    private int[] applyStock(long batchId, List<Map<String, Object>> rows) {
        int created = 0, updated = 0, unchanged = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> existing = existingStockRow(row);
            if (existing == null) { insertStockRow(batchId, row); created++; }
            else if (stockEqual(existing, row)) {
                // 业务字段无变化：只回填缺失/变化的氚云系统列，不计入 updated_count（幂等口径不变）
                if (observDiffer(existing, row)) refreshStockObservations(existing, row);
                unchanged++;
            }
            else { updateStockRow(existing, batchId, row); updated++; }
        }
        return new int[]{created, updated, unchanged};
    }

    // ====================================================================
    // 经销商信息（dealer）：Excel 解析 + 比对 + 写入
    //
    // 氚云「经销商信息」导出为两行表头（第 1 行英文字段码、第 2 行中文列名），
    // 数据从第 3 行起，列位固定（与产品信息/仓库明细同约定）：
    //   0 修改时间  1 经销商编号(键)  2 联系方式  3 经销商名称  4 经销商地址
    //   5 备注      6 归属业务员      7 数据状态  8 附件(忽略) 9 数据ID(不可修改)
    //  10 数据标题 11 拥有者         12 所属部门 13 创建人    14 创建时间
    // ====================================================================

    private List<Map<String, Object>> parseDealer(byte[] bytes) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            validateHeader("经销商信息", sheet, fmt, LedgerHeaderGuard.DEALER);
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String code = cellText(fmt, row, 1);
                if (code == null || code.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dealerCode", code);
                m.put("dealerName", cellText(fmt, row, 3));
                m.put("contact", cellText(fmt, row, 2));
                m.put("address", cellText(fmt, row, 4));
                m.put("remark", cellText(fmt, row, 5));
                m.put("ownerSalesperson", cellText(fmt, row, 6));
                m.put("dataStatus", cellText(fmt, row, 7));
                m.put("sourceObjectId", cellText(fmt, row, 9));
                m.put("sourceTitle", cellText(fmt, row, 10));
                m.put("ownerName", cellText(fmt, row, 11));
                m.put("departmentName", cellText(fmt, row, 12));
                m.put("createdByName", cellText(fmt, row, 13));
                m.put("sourceCreatedAt", cellText(fmt, row, 14));
                m.put("sourceModifiedAt", cellText(fmt, row, 0));
                list.add(m);
            }
        } catch (IllegalArgumentException e) {
            throw e; // 表头指纹不符等可读错误原样抛出，不要包成「解析失败」
        } catch (Exception e) {
            throw new IllegalArgumentException("经销商信息 Excel 解析失败：" + e.getMessage(), e);
        }
        return list;
    }

    private Map<String, Object> existingDealerRow(Map<String, Object> row) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, dealer_name AS dealerName, contact, address, remark," +
                        " owner_salesperson AS ownerSalesperson, data_status AS dataStatus," +
                        " source_object_id AS sourceObjectId, source_title AS sourceTitle," +
                        " owner_name AS ownerName, department_name AS departmentName," +
                        " created_by_name AS createdByName, source_created_at AS sourceCreatedAt," +
                        " source_modified_at AS sourceModifiedAt FROM h3_dealer_catalog WHERE dealer_code=?",
                this::toMap, row.get("dealerCode"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertDealerRow(long batchId, Map<String, Object> row) {
        jdbc.update("INSERT INTO h3_dealer_catalog(import_batch_id, dealer_code, dealer_name, contact, address," +
                        " remark, owner_salesperson, data_status, source_object_id, source_title, owner_name," +
                        " department_name, created_by_name, source_created_at, source_modified_at, last_synced_at," +
                        " source_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?)",
                "B" + batchId, row.get("dealerCode"), row.get("dealerName"), row.get("contact"), row.get("address"),
                row.get("remark"), row.get("ownerSalesperson"), row.get("dataStatus"), row.get("sourceObjectId"),
                row.get("sourceTitle"), row.get("ownerName"), row.get("departmentName"), row.get("createdByName"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")), sourceHash(row));
    }

    private void updateDealerRow(Map<String, Object> existing, long batchId, Map<String, Object> row) {
        jdbc.update("UPDATE h3_dealer_catalog SET dealer_name=?, contact=?, address=?, remark=?," +
                        " owner_salesperson=?, data_status=?, source_object_id=?, source_title=?, owner_name=?," +
                        " department_name=?, created_by_name=?, source_created_at=?, source_modified_at=?," +
                        " import_batch_id=?, last_synced_at=NOW(), source_hash=? WHERE id=?",
                row.get("dealerName"), row.get("contact"), row.get("address"), row.get("remark"),
                row.get("ownerSalesperson"), row.get("dataStatus"), row.get("sourceObjectId"),
                row.get("sourceTitle"), row.get("ownerName"), row.get("departmentName"), row.get("createdByName"),
                dateTime(row.get("sourceCreatedAt")), dateTime(row.get("sourceModifiedAt")),
                "B" + batchId, sourceHash(row), existing.get("id"));
    }

    private boolean dealerEqual(Map<String, Object> a, Map<String, Object> b) {
        return sEq(a.get("dealerName"), b.get("dealerName"))
                && sEq(a.get("contact"), b.get("contact"))
                && sEq(a.get("address"), b.get("address"))
                && sEq(a.get("remark"), b.get("remark"))
                && sEq(a.get("ownerSalesperson"), b.get("ownerSalesperson"))
                && sEq(a.get("dataStatus"), b.get("dataStatus"))
                && sEq(a.get("sourceObjectId"), b.get("sourceObjectId"))
                && sEq(a.get("sourceTitle"), b.get("sourceTitle"))
                && sEq(a.get("ownerName"), b.get("ownerName"))
                && sEq(a.get("departmentName"), b.get("departmentName"))
                && sEq(a.get("createdByName"), b.get("createdByName"))
                // 源时间以文本入参、以 DATETIME 出库，统一按「解析后比较」避免格式差异造成假更新
                && tEq(a.get("sourceCreatedAt"), b.get("sourceCreatedAt"))
                && tEq(a.get("sourceModifiedAt"), b.get("sourceModifiedAt"));
    }

    private int[] applyDealer(long batchId, List<Map<String, Object>> rows) {
        int created = 0, updated = 0, unchanged = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> existing = existingDealerRow(row);
            if (existing == null) { insertDealerRow(batchId, row); created++; }
            else if (dealerEqual(existing, row)) unchanged++;
            else { updateDealerRow(existing, batchId, row); updated++; }
        }
        return new int[]{created, updated, unchanged};
    }

    // ====================================================================
    // 比对预览（stage 时对当前台账计算 新增/更新/忽略 + 预览行）
    // ====================================================================

    private DiffOutcome buildDiff(Kind kind, List<Map<String, Object>> rows) {
        List<Map<String, Object>> previewRows = new ArrayList<>();
        int create = 0, update = 0, unchanged = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> existing = existingOf(kind, row);
            String action;
            List<String> changes = new ArrayList<>();
            if (existing == null) { action = "CREATE"; create++; }
            else {
                boolean equal = equalOf(kind, existing, row);
                if (equal) { action = "UNCHANGED"; unchanged++; }
                else {
                    action = "UPDATE"; update++;
                    changes = diffLabels(kind, existing, row);
                }
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("action", action);
            p.putAll(row);
            if (!changes.isEmpty()) p.put("changes", String.join("；", changes));
            previewRows.add(p);
        }
        return new DiffOutcome(previewRows, create, update, unchanged);
    }

    /** 更新时生成中文变更标签，便于用户在确认前看清新旧差异。 */
    private List<String> diffLabels(Kind kind, Map<String, Object> oldRow, Map<String, Object> newRow) {
        List<String> labels = new ArrayList<>();
        if (kind == Kind.DEALER) {
            addStrLabel(labels, "经销商名称", oldRow.get("dealerName"), newRow.get("dealerName"));
            addStrLabel(labels, "联系方式", oldRow.get("contact"), newRow.get("contact"));
            addStrLabel(labels, "地址", oldRow.get("address"), newRow.get("address"));
            addStrLabel(labels, "归属业务员", oldRow.get("ownerSalesperson"), newRow.get("ownerSalesperson"));
            addStrLabel(labels, "数据状态", oldRow.get("dataStatus"), newRow.get("dataStatus"));
            addStrLabel(labels, "备注", oldRow.get("remark"), newRow.get("remark"));
            return labels;
        }
        if (kind == Kind.STOCK) {
            addStrLabel(labels, "仓库", oldRow.get("warehouse"), newRow.get("warehouse"));
        }
        addStrLabel(labels, "类目", oldRow.get("category"), newRow.get("category"));
        addStrLabel(labels, "产品名称", oldRow.get("productName"), newRow.get("productName"));
        addStrLabel(labels, "规格", oldRow.get("specification"), newRow.get("specification"));
        addStrLabel(labels, "单位", oldRow.get("unit"), newRow.get("unit"));
        if (kind == Kind.STOCK) addNumLabel(labels, "库存数量", oldRow.get("quantity"), newRow.get("quantity"));
        addNumLabel(labels, "采购价", oldRow.get("purchasePrice"), newRow.get("purchasePrice"));
        return labels;
    }

    private void addStrLabel(List<String> labels, String name, Object a, Object b) {
        if (!sEq(a, b)) labels.add(name + "：" + (a == null || a.toString().isBlank() ? "空" : a) + "→" + (b == null || b.toString().isBlank() ? "空" : b));
    }

    private void addNumLabel(List<String> labels, String name, Object a, Object b) {
        if (!nEq(a, b)) labels.add(name + "：" + (a == null ? "空" : a) + "→" + (b == null ? "空" : b));
    }

    private Map<String, Object> previewMap(Long batchId, String dataType, String fileName, DiffOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataType", dataType);
        m.put("fileName", fileName);
        m.put("batchId", batchId);
        m.put("totalCount", o.rows().size());
        m.put("createCount", o.create());
        m.put("updateCount", o.update());
        m.put("unchangedCount", o.unchanged());
        m.put("rows", o.rows());
        return m;
    }

    // ====================================================================
    // 暂存 / 批次表基础操作
    // ====================================================================

    private void insertStagingSlice(long batchId, int offset, List<Map<String, Object>> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String key = businessKey(row);
            // 外部 ID 优先用氚云「数据ID(不可修改)」（行级唯一），退化产品编号；暂存行据此可溯源
            String externalId = str(row.get("sourceObjectId"));
            if (externalId == null) externalId = str(row.get("productCode"));
            if (externalId == null) externalId = key;
            pipeline.insertStagingRow(batchId, offset + i, key, externalId, sourceHash(row),
                    toJson(row), "[]");
        }
    }

    private List<Map<String, Object>> loadStagedRows(Long batchId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query("SELECT main_json FROM h3_import_staging_rows WHERE batch_id=? ORDER BY row_seq",
                (rs, n) -> rs.getString("main_json"), batchId)
                .forEach(json -> rows.add(readJson(json, Map.class)));
        return rows;
    }

    private void markFailed(long batchId, String stage, String msg, Exception ex) {
        pipeline.markFailed(batchId, stage, msg, ex);
    }

    // ====================================================================
    // 解析 & 工具
    // ====================================================================

    private List<Map<String, Object>> parseRows(Kind kind, byte[] bytes) {
        return switch (kind) {
            case CATALOG -> parseCatalog(bytes);
            case STOCK -> parseStock(bytes);
            case DEALER -> parseDealer(bytes);
        };
    }

    /** 按种类取当前台账中的既有行（业务键命中）。 */
    private Map<String, Object> existingOf(Kind kind, Map<String, Object> row) {
        return switch (kind) {
            case CATALOG -> existingCatalogRow(row);
            case STOCK -> existingStockRow(row);
            case DEALER -> existingDealerRow(row);
        };
    }

    /** 按种类比较「台账既有行」与「本次导入行」是否有实质变化。 */
    private boolean equalOf(Kind kind, Map<String, Object> existing, Map<String, Object> row) {
        return switch (kind) {
            case CATALOG -> catalogEqual(existing, row);
            case STOCK -> stockEqual(existing, row);
            case DEALER -> dealerEqual(existing, row);
        };
    }

    /** 同批次内按业务键去重（产品编号 / 仓库+编号+单位），保留首行。 */
    private List<Map<String, Object>> dedupe(List<Map<String, Object>> rows, Kind kind) {
        LinkedHashMap<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            seen.putIfAbsent(businessKey(row), row);
        }
        return new ArrayList<>(seen.values());
    }

    private String businessKey(Map<String, Object> row) {
        // 经销商台账以氚云经销商编号为业务键
        if (row.containsKey("dealerCode")) {
            Object dc = row.get("dealerCode");
            return dc == null ? "" : String.valueOf(dc);
        }
        Object code = row.get("productCode");
        if (row.containsKey("warehouse")) {
            // 库存台账业务键含单位：同仓库同编号的多单位行（箱/个）各自独立，不可互相去重；
            // 单位过共用归一（Kg/公斤 → kg），否则同一单位的不同写法会被当成两行
            return (row.get("warehouse") == null ? "" : row.get("warehouse")) + "|" + (code == null ? "" : code)
                    + "|" + UnitNormalizer.key(row.get("unit") == null ? null : String.valueOf(row.get("unit")));
        }
        return code == null ? "" : String.valueOf(code);
    }

    private static String cellText(DataFormatter fmt, Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        String v = fmt.formatCellValue(cell).trim();
        return v.isEmpty() ? null : v;
    }

    // ====================================================================
    // 氚云系统列（导入与系统信息）：按中文表头名定位，不写死列位
    //
    // 业务列用固定列位读取（LedgerHeaderGuard 已对关键列做指纹校验，错位即拒绝整批）；
    // 系统列（数据ID/数据标题/创建人/拥有者/所属部门/创建时间/修改时间/数据状态）在报表
    // 尾部，字段增删会让列位漂移，且不同报表的位置不同（实测产品信息 25 列、仓库明细 19 列、
    // 经销商 15 列），因此只能按第 2 行中文表头名称匹配。未导出的字段返回 null，
    // 详情页显示「—」，不影响导入。
    // ====================================================================

    /** 第 2 行中文表头 → 列号（首个命中；空列名与空白折叠后再比对）。 */
    private static Map<String, Integer> headerIndex(Row header, DataFormatter fmt) {
        Map<String, Integer> index = new LinkedHashMap<>();
        if (header == null) return index;
        int last = Math.max(header.getLastCellNum(), 0);
        for (int c = 0; c < last; c++) {
            String v = cellText(fmt, header, c);
            if (v != null) index.putIfAbsent(v.replace(" ", "").replace("\u3000", ""), c);
        }
        return index;
    }

    /** 按候选中文名定位列号：先精确匹配（顺序即优先级），再退化为包含匹配；未命中返回 -1。 */
    private static int colOf(Map<String, Integer> index, String... candidates) {
        for (String c : candidates) {
            Integer col = index.get(c);
            if (col != null) return col;
        }
        for (String c : candidates) {
            for (Map.Entry<String, Integer> e : index.entrySet()) {
                if (e.getKey().contains(c)) return e.getValue();
            }
        }
        return -1;
    }

    /** 按表头名取单元格文本；该报表未导出此列时返回 null。 */
    private static String cellText(DataFormatter fmt, Row row, Map<String, Integer> header, String... names) {
        int col = colOf(header, names);
        return col < 0 ? null : cellText(fmt, row, col);
    }

    /**
     * 主-子结构导出的向下填充：产品信息是「主表 + 产品明细子表」，主表系统列
     * （创建人/创建时间/修改时间/拥有者/所属部门/数据状态）只在每个主记录首行有值，
     * 与产品类目同法填充，使每条产品明细行都能溯源到所属主记录。
     */
    private static String fill(String[] slots, int slot, String value) {
        if (value != null) slots[slot] = value;
        return slots[slot];
    }

    /**
     * 读数值单元格并归一：Excel 里的数量常带 1e-16 量级浮点残差（如 8.88e-16 实际是 0），
     * 直接落库会让「0 → 0」被反复判成变化。超过 6 位小数的部分四舍五入，归零后统一成
     * {@link BigDecimal#ZERO}，使两侧比较在数值上真正相等。
     */
    private static BigDecimal cellDecimal(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        BigDecimal raw = null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) raw = BigDecimal.valueOf(cell.getNumericCellValue());
            else if (cell.getCellType() == CellType.STRING) {
                String s = cell.getStringCellValue().trim();
                if (!s.isEmpty()) raw = new BigDecimal(s);
            }
            else if (cell.getCellType() == CellType.FORMULA) {
                try { raw = BigDecimal.valueOf(cell.getNumericCellValue()); } catch (Exception e) { raw = null; }
            }
        } catch (Exception e) { return null; }
        if (raw == null) return null;
        BigDecimal v = raw.scale() > 6 ? raw.setScale(6, RoundingMode.HALF_UP) : raw;
        return v.signum() == 0 ? BigDecimal.ZERO : v;
    }

    private static byte[] readBytes(MultipartFile file) {
        try { return file.getBytes(); }
        catch (Exception e) { throw new IllegalArgumentException("读取上传文件失败：" + safeMsg(e), e); }
    }

    /**
     * 不参与内容指纹的键：元信息 + 氚云系统列。
     *
     * <p>系统列必须排除——产品信息台账是主-子结构，主记录改一次「修改时间」会让其下几十条
     * 产品明细的指纹全部改变，把「业务内容变了没」这个判断污染成噪声。
     */
    private static final Set<String> HASH_EXCLUDED_KEYS = Set.of(
            "id", "action", "changes",
            "sourceObjectId", "sourceTitle", "ownerName", "departmentName", "createdByName",
            "dataStatus", "sourceCreatedAt", "sourceModifiedAt");

    private static String sourceHash(Map<String, Object> row) {
        // 仅对参与业务比对的有意义字段做摘要；不参与对比的 id/批次/系统列等一律排除
        List<String> parts = new ArrayList<>();
        row.forEach((k, v) -> {
            if (HASH_EXCLUDED_KEYS.contains(k)) return;
            parts.add(k + "=" + (v == null ? "" : v));
        });
        return sha256(String.join("|", parts));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(bytes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "null";
        String raw = e.getMessage();
        return raw == null || raw.isBlank() ? e.getClass().getSimpleName() : raw;
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }

    private <T> T readJson(String json, Class<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("JSON 反序列化失败", e); }
    }

    private Map<String, Object> toMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String key = md.getColumnLabel(i);
            Object value = rs.getObject(i);
            map.put(key, value);
        }
        return map;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static int numInt(Object v) { return v == null ? 0 : ((Number) v).intValue(); }

    private static String norm(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean sEq(Object a, Object b) { return Objects.equals(norm(a), norm(b)); }

    /** 数值比较容差：Excel 求和/乘积会留下 1e-16 量级的浮点残差（实测 61 行「0 vs 8.88e-16」），
     *  按精确相等判定会让这些行每次重导都判成 UPDATE，导入永不收敛。台账数值有效精度到 4 位小数，
     *  1e-9 的容差足够宽松又不可能掩盖真实差异。 */
    private static final BigDecimal NUMERIC_EPSILON = new BigDecimal("0.000000001");

    private static boolean nEq(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        try {
            BigDecimal x = new BigDecimal(norm(a)), y = new BigDecimal(norm(b));
            return x.subtract(y).abs().compareTo(NUMERIC_EPSILON) < 0;
        } catch (Exception e) { return Objects.equals(norm(a), norm(b)); }
    }

    /**
     * 时间相等比较：入参一侧是 Excel 文本（如 {@code 2026-08-17 10:24:41}），
     * 另一侧是库内 DATETIME，直接字符串比较必然不相等 → 统一解析后比较，
     * 避免每次重导都误判成「更新」。
     */
    private static boolean tEq(Object a, Object b) {
        Timestamp ta = asTimestamp(a);
        Timestamp tb = asTimestamp(b);
        if (ta == null || tb == null) return ta == null && tb == null;
        return ta.equals(tb);
    }

    private static Timestamp asTimestamp(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts;
        if (v instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
        if (v instanceof java.util.Date d) return new Timestamp(d.getTime());
        return dateTime(v);
    }

    /** 氚云导出的时间为文本，兼容常见分隔符与「缺省秒」写法；无法识别时返回 null（不阻断导入）。 */
    private static Timestamp dateTime(Object v) {
        String s = norm(v);
        if (s == null) return null;
        String t = s.replace('T', ' ').trim();
        for (String p : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/M/d H:mm:ss",
                "yyyy/M/d H:mm", "yyyy/M/dd H:mm:ss", "yyyy/MM/d H:mm:ss", "yyyy.MM.dd HH:mm:ss",
                "yyyy.M.d H:mm:ss", "yyyy年M月d日 HH:mm:ss",
                "yyyy-MM-dd", "yyyy/M/d", "yyyy/MM/d", "yyyy年M月d日")) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p);
                return p.indexOf('H') >= 0
                        ? Timestamp.valueOf(LocalDateTime.parse(t, f))
                        : Timestamp.valueOf(LocalDate.parse(t, f).atStartOfDay());
            } catch (Exception ignored) { /* 换下一个格式 */ }
        }
        return null;
    }

    private record DiffOutcome(List<Map<String, Object>> rows, int create, int update, int unchanged) {}
}
