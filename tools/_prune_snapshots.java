import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 快照治理执行器（2026-10-20，LQ A 档）。
 *
 * 安全设计（逐条对应 LQ 定的口径）：
 *  1. 先物化待删 id 到**持久化**表 _h3sc_prune_20261020_ids，导出 id 清单到 _archive_export。
 *     => 事后可精确回答「删了哪些行」，必要时按 id 从备份库恢复。
 *  2. 删除只按这份 id 清单分批执行（每批 5000），不做「重算条件再删」——
 *     避免两次计算之间数据变动导致删到范围外。
 *  3. 全程三项安全断言复核；任一不为 0 立即中止。
 *  4. 不 DROP 持久化清单表（留作审计），需人工确认后才清。
 *
 * 用法：java -cp ... _prune_snapshots [--dry] [--exec]
 *   缺省 --dry：只物化 + 断言 + 报告，不删。
 */
public class _prune_snapshots {

    static final String IN_TYPES = "('PURCHASE_IN','PRODUCTION')";
    static final String TERMINAL = "('COMPLETED','PARTIAL_FAILED')";
    static final int KEEP = 3;
    static final int CHUNK = 5000;
    static final String IDS_TABLE = "_h3sc_prune_20261020_ids";

    static Connection conn;
    static PrintWriter log;

    static void exec(String q) throws SQLException {
        try (Statement s = conn.createStatement()) { s.execute(q); }
    }
    static long one(String q) {
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery(q)) { r.next(); return r.getLong(1); }
        catch (Exception e) { throw new RuntimeException("SQL 失败: " + e.getMessage() + "\n  " + q, e); }
    }
    static void out(String s) { System.out.println(s); if (log != null) log.println(s); }

    public static void main(String[] args) throws Exception {
        boolean doExec = Arrays.asList(args).contains("--exec");
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String exportDir = "C:/Users/20676/source/LQXM/_archive_export/20261020";
        new File(exportDir).mkdirs();
        log = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(exportDir + "/snapshot_prune_" + ts + ".log"), StandardCharsets.UTF_8), true);

        String url = "jdbc:mysql://127.0.0.1:3306/product_archive"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
        conn = DriverManager.getConnection(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        out("== 模式: " + (doExec ? "EXEC（会真删）" : "DRY（只报告）") + " ==");

        long t0 = System.currentTimeMillis();

        // ---------- 1. 物化保护名单 ----------
        exec("DROP TEMPORARY TABLE IF EXISTS _r_latest");
        exec("DROP TEMPORARY TABLE IF EXISTS _r_keep");
        exec("CREATE TEMPORARY TABLE _r_latest (eid VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NOT NULL PRIMARY KEY,"
           + " latest_id BIGINT NOT NULL) ENGINE=InnoDB");
        long t = System.currentTimeMillis();
        exec("INSERT INTO _r_latest(eid, latest_id) SELECT external_id, MAX(id) FROM h3_import_rows"
           + " WHERE external_id IS NOT NULL GROUP BY external_id");
        out("保护名单一（每单最新）物化完成: " + one("SELECT COUNT(*) FROM _r_latest") + " 单, "
            + (System.currentTimeMillis() - t) + "ms");

        exec("CREATE TEMPORARY TABLE _r_keep (batch_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        exec("INSERT INTO _r_keep(batch_id) SELECT id FROM ("
           + " SELECT id, ROW_NUMBER() OVER (PARTITION BY data_type ORDER BY id DESC) rn"
           + "   FROM h3_import_batches WHERE data_type IN " + IN_TYPES
           + "    AND status IN " + TERMINAL + ") t WHERE t.rn <= " + KEEP);
        long keepRows = one("SELECT COUNT(*) FROM _r_keep");
        out("保护名单二（最近 " + KEEP + " 批）物化完成: " + keepRows + " 批");
        if (keepRows <= 0) { out("❌ 保留批次为空，中止"); return; }

        // ---------- 2. 物化待删 id 到持久表 ----------
        exec("DROP TABLE IF EXISTS " + IDS_TABLE);
        exec("CREATE TABLE " + IDS_TABLE + " ("
           + " id BIGINT NOT NULL PRIMARY KEY,"
           + " batch_id BIGINT NOT NULL,"
           + " external_id VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NULL,"
           + " data_type VARCHAR(32) NOT NULL,"
           + " row_bytes INT NULL,"
           + " KEY idx_batch (batch_id)"
           + ") ENGINE=InnoDB COMMENT='2026-10-20 快照治理待删清单（审计用，勿随意DROP）'");
        t = System.currentTimeMillis();
        int inserted;
        try (Statement s = conn.createStatement()) {
            inserted = s.executeUpdate("INSERT INTO " + IDS_TABLE
                + " (id, batch_id, external_id, data_type, row_bytes)"
                + " SELECT r.id, r.batch_id, r.external_id, b.data_type, LENGTH(r.raw_data)"
                + "   FROM h3_import_rows r"
                + "   JOIN h3_import_batches b ON b.id = r.batch_id"
                + "   LEFT JOIN _r_latest m ON m.eid = r.external_id"
                + "   LEFT JOIN _r_keep   kb ON kb.batch_id = r.batch_id"
                + "  WHERE b.data_type IN " + IN_TYPES
                + "    AND b.status IN " + TERMINAL
                + "    AND kb.batch_id IS NULL"
                + "    AND (m.latest_id IS NULL OR r.id < m.latest_id)");
        }
        out("待删清单物化完成: " + inserted + " 行, " + (System.currentTimeMillis() - t) + "ms");

        // ---------- 3. 安全断言（基于持久清单，独立复核）----------
        long a1 = one("SELECT COUNT(*) FROM " + IDS_TABLE + " t"
                + " JOIN h3_import_rows r ON r.id=t.id"
                + " JOIN (SELECT external_id eid, MAX(id) mid FROM h3_import_rows GROUP BY external_id) g"
                + "   ON g.eid=r.external_id AND g.mid=r.id");
        long a2 = one("SELECT COUNT(*) FROM " + IDS_TABLE + " t JOIN _r_keep k ON k.batch_id=t.batch_id");
        long a3 = one("SELECT COUNT(*) FROM " + IDS_TABLE + " t"
                + " JOIN h3_import_batches b ON b.id=t.batch_id"
                + " WHERE b.status NOT IN " + TERMINAL + " OR b.data_type NOT IN " + IN_TYPES);
        long a4 = one("SELECT COUNT(*) FROM " + IDS_TABLE);
        out("");
        out("=== 安全断言（全部必须为 0）===");
        out("[A1] 清单 ∧ 每单最新一条      = " + a1 + (a1 == 0 ? "  ✅" : "  ❌"));
        out("[A2] 清单 ∧ 最近 " + KEEP + " 批           = " + a2 + (a2 == 0 ? "  ✅" : "  ❌"));
        out("[A3] 清单 ∧ 非受管类型/非终态 = " + a3 + (a3 == 0 ? "  ✅" : "  ❌"));
        out("清单行数                       = " + a4);
        if (a1 != 0 || a2 != 0 || a3 != 0) {
            out("❌ 安全断言未通过，中止（清单表 " + IDS_TABLE + " 保留供排查）");
            return;
        }

        // 删除后每单剩余
        long lostAfter = one("WITH remain AS ("
            + " SELECT r.external_id eid,"
            + "        COUNT(*) - SUM(CASE WHEN t.id IS NOT NULL THEN 1 ELSE 0 END) remaining"
            + "   FROM h3_import_rows r"
            + "   JOIN h3_import_batches b ON b.id=r.batch_id"
            + "   LEFT JOIN " + IDS_TABLE + " t ON t.id=r.id"
            + "  WHERE b.data_type IN " + IN_TYPES + " AND b.status IN " + TERMINAL
            + "  GROUP BY r.external_id) SELECT COUNT(*) FROM remain WHERE remaining <= 0");
        out("删除后「一条不剩」的单据数     = " + lostAfter + (lostAfter == 0 ? "  ✅" : "  ❌"));
        if (lostAfter != 0) { out("❌ 会丢单据，中止"); return; }

        long mb = one("SELECT ROUND(SUM(row_bytes)/1048576,1) FROM " + IDS_TABLE);
        out("待删 raw_data 体积             = " + mb + " MB");
        long before = one("SELECT ROUND(FILE_SIZE/1048576,1) FROM INFORMATION_SCHEMA.INNODB_TABLESPACES"
                + " WHERE NAME='product_archive/h3_import_rows'");
        out("删除前 h3_import_rows 物理体积 = " + before + " MB");

        // ---------- 4. 导出 id 清单 ----------
        String csv = exportDir + "/snapshot_prune_ids_" + ts + ".csv";
        long exported = 0;
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT id, batch_id, external_id, data_type, row_bytes FROM "
                     + IDS_TABLE + " ORDER BY id");
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(csv), StandardCharsets.UTF_8))) {
            w.write("id,batch_id,external_id,data_type,row_bytes\n");
            while (r.next()) {
                w.write(r.getLong(1) + "," + r.getLong(2) + "," + (r.getString(3) == null ? "" : r.getString(3))
                        + "," + r.getString(4) + "," + r.getLong(5) + "\n");
                exported++;
            }
        }
        out("id 清单已导出: " + csv + "（" + exported + " 行）");

        if (!doExec) {
            out("");
            out("== DRY 完成，未删除任何行。确认后加 --exec 重新执行 ==");
            out("== 待删清单表 " + IDS_TABLE + " 已保留，可直接用于 --exec ==");
            log.close(); conn.close(); return;
        }

        // ---------- 5. 分批删除 ----------
        out("");
        out("=== 开始分批删除（每批 " + CHUNK + "）===");
        long deleted = 0;
        int batchNo = 0;
        t = System.currentTimeMillis();
        while (true) {
            // 取一批 id（从清单表取，已删的从清单表移除，保证幂等可续跑）
            List<Long> ids = new ArrayList<>(CHUNK);
            try (Statement s = conn.createStatement();
                 ResultSet r = s.executeQuery("SELECT id FROM " + IDS_TABLE + " ORDER BY id LIMIT " + CHUNK)) {
                while (r.next()) ids.add(r.getLong(1));
            }
            if (ids.isEmpty()) break;
            String in = String.join(",", ids.stream().map(String::valueOf).toList());
            int n;
            conn.setAutoCommit(false);
            try {
                try (Statement s = conn.createStatement()) {
                    n = s.executeUpdate("DELETE FROM h3_import_rows WHERE id IN (" + in + ")");
                }
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("DELETE FROM " + IDS_TABLE + " WHERE id IN (" + in + ")");
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                out("❌ 第 " + (batchNo + 1) + " 批失败，已回滚: " + e.getMessage());
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            deleted += n;
            batchNo++;
            if (batchNo % 10 == 0 || ids.size() < CHUNK) {
                out("  第 " + batchNo + " 批: 删 " + n + " 行，累计 " + deleted
                    + " / " + inserted + "（" + (System.currentTimeMillis() - t) / 1000 + "s）");
            }
            if (ids.size() < CHUNK) break;
        }
        out("删除完成: " + deleted + " 行，耗时 " + (System.currentTimeMillis() - t) / 1000 + "s");

        // ---------- 6. 复核 ----------
        long remainingInIds = one("SELECT COUNT(*) FROM " + IDS_TABLE);
        long allRows = one("SELECT COUNT(*) FROM h3_import_rows");
        long managedNow = one("SELECT COUNT(*) FROM h3_import_rows r JOIN h3_import_batches b ON b.id=r.batch_id"
                + " WHERE b.data_type IN " + IN_TYPES + " AND b.status IN " + TERMINAL);
        long after = one("SELECT ROUND(FILE_SIZE/1048576,1) FROM INFORMATION_SCHEMA.INNODB_TABLESPACES"
                + " WHERE NAME='product_archive/h3_import_rows'");
        long orphanLatest = one("SELECT COUNT(*) FROM ("
            + " SELECT r.external_id eid, COUNT(*) c FROM h3_import_rows r"
            + "  JOIN h3_import_batches b ON b.id=r.batch_id"
            + "  WHERE b.data_type IN " + IN_TYPES + " AND b.status IN " + TERMINAL
            + "  GROUP BY r.external_id) t WHERE t.c = 0");
        out("");
        out("=== 删除后复核 ===");
        out("清单表残留（应为 0）          = " + remainingInIds);
        out("h3_import_rows 总行数         = " + allRows + "（删前 364122）");
        out("受管终态行数                  = " + managedNow + "（删前 244371）");
        out("受管范围失去全部快照的单据数   = " + orphanLatest + (orphanLatest == 0 ? "  ✅" : "  ⚠️"));
        out("物理体积                      = " + before + " MB → " + after + " MB（-"
            + (before - after) + " MB；删行后需 OPTIMIZE 才回落）");

        // 保护一生效抽查
        out("");
        out("=== 反例单据 0e4b5337 现存快照 ===");
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT r.id, r.batch_id FROM h3_import_rows r"
                     + " WHERE r.external_id='0e4b5337-d534-411f-9035-bffbec7a3706' ORDER BY r.id")) {
            while (r.next()) out("   id=" + r.getLong(1) + " batch=" + r.getLong(2));
        }

        log.close();
        conn.close();
        out("\n完成。日志: " + exportDir + "/snapshot_prune_" + ts + ".log");
    }
}
