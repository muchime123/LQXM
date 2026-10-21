import java.sql.*;
import java.util.*;

/**
 * Part 3 第 1+2 段执行器（2026-10-20）。**只碰探针残留 + 空洞回收，不删任何业务表。**
 *
 * 第 1 段：DROP 探针残留（_t_latest / _t_keepbatch / h3sc_latest / _h3sc_prune_20261020_ids）
 *          —— CSV 已留档 10.7MB
 * 第 2 段：ALTER ... ROW_FORMAT=DYNAMIC, ALGORITHM=COPY 回收空洞（不动数据、不删表）
 *
 * ⚠️ 每步前后都打 FILE_SIZE，不依赖 TABLES.DATA_LENGTH（删行后不刷新）。
 * ⚠️ 第 2 段前后对比行数，确认「重建不改数据」。
 */
public class _exec_part3_lowrisk {

    static Connection conn;
    static final String S = "product_archive";

    static void q(String title, String sql) {
        System.out.println("### " + title);
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery(sql)) {
            ResultSetMetaData md = r.getMetaData();
            int n = md.getColumnCount();
            while (r.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(i > 1 ? " | " : "").append(r.getString(i));
                System.out.println("   " + sb);
            }
        } catch (Exception e) {
            System.out.println("   ERR " + e.getMessage());
        }
        System.out.println();
    }

    static void exec(String title, String sql) {
        long t0 = System.currentTimeMillis();
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
            System.out.printf("   OK  %-52s %5.1fs%n", title, (System.currentTimeMillis() - t0) / 1000.0);
        } catch (Exception e) {
            System.out.printf("   FAIL %-52s %s%n", title, e.getMessage());
        }
    }

    static long one(String sql) {
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        } catch (Exception e) {
            return -1L;
        }
    }

    static long totalMb() {
        long b = one("SELECT SUM(FILE_SIZE) FROM INFORMATION_SCHEMA.INNODB_TABLESPACES WHERE NAME LIKE '" + S + "/%'");
        return Math.round(b / 1048576.0);
    }

    public static void main(String[] a) throws Exception {
        boolean exec = a.length > 0 && a[0].equals("--exec");
        String url = "jdbc:mysql://127.0.0.1:3306/" + S
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        conn = DriverManager.getConnection(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        System.out.println("== connected, mode=" + (exec ? "EXEC" : "DRY") + " ==");
        System.out.println("   全库当前 " + totalMb() + " MB\n");

        // ---------- 安全前置：确认全是 0 行 + 确认活表在 ----------
        q("P0 残留表精确行数（必须全 0）",
          "SELECT '_t_latest' t, COUNT(*) c FROM _t_latest UNION ALL " +
          "SELECT '_t_keepbatch', COUNT(*) FROM _t_keepbatch UNION ALL " +
          "SELECT '_h3sc_prune_20261020_ids', COUNT(*) FROM _h3sc_prune_20261020_ids");

        q("P0b 活表必须在（receipts 等核心表不能被误伤）",
          "SELECT 'receipts' t, COUNT(*) c FROM receipts UNION ALL " +
          "SELECT 'settlement_outbound_links', COUNT(*) FROM settlement_outbound_links UNION ALL " +
          "SELECT 'sales_outbounds', COUNT(*) FROM sales_outbounds UNION ALL " +
          "SELECT 'production_orders', COUNT(*) FROM production_orders UNION ALL " +
          "SELECT 'customers', COUNT(*) FROM customers");

        if (!exec) {
            q("DRY：将要回收的表当前体积",
              "SELECT SUBSTRING_INDEX(s.NAME,'/',-1) AS t, ROUND(s.FILE_SIZE/1048576,1) AS mb " +
              "FROM INFORMATION_SCHEMA.INNODB_TABLESPACES s " +
              "WHERE s.NAME IN ('" + S + "/receipts','" + S + "/receipt_allocations'," +
              "'" + S + "/h3_reset_identity_snapshots','" + S + "/h3_production_reports'," +
              "'" + S + "/h3_invoice_requests','" + S + "/h3_cashflow_entries'," +
              "'" + S + "/h3_cashier_log','" + S + "/h3_sales_invoices'," +
              "'" + S + "/customer_split_runs','" + S + "/_t_latest','" + S + "/_h3sc_prune_20261020_ids') " +
              "ORDER BY s.FILE_SIZE DESC");
            conn.close();
            System.out.println("== DRY done（加 --exec 才真跑）==");
            return;
        }

        // ---------- 第 1 段：DROP 探针残留 ----------
        System.out.println("---- 第 1 段：DROP 探针残留 ----");
        long before1 = totalMb();
        for (String t : List.of("_t_latest", "_t_keepbatch", "h3sc_latest", "_h3sc_prune_20261020_ids")) {
            exec("DROP " + t, "DROP TABLE IF EXISTS `" + t + "`");
        }
        System.out.println("   第 1 段回收：" + (before1 - totalMb()) + " MB\n");

        // ---------- 第 2 段：空洞回收 ----------
        System.out.println("---- 第 2 段：ALGORITHM=COPY 回收空洞 ----");
        long before2 = totalMb();
        // 行数快照（重建前后必须一致）
        Map<String, Long> rowsBefore = new LinkedHashMap<>();
        for (String t : List.of("receipts", "receipt_allocations", "h3_reset_identity_snapshots",
                "h3_production_reports", "h3_invoice_requests", "h3_cashflow_entries",
                "h3_cashier_log", "h3_sales_invoices", "customer_split_runs")) {
            rowsBefore.put(t, one("SELECT COUNT(*) FROM `" + t + "`"));
        }
        for (String t : rowsBefore.keySet()) {
            long mb = Math.round(one("SELECT FILE_SIZE FROM INFORMATION_SCHEMA.INNODB_TABLESPACES WHERE NAME='" + S + "/" + t + "'") / 1048576.0);
            exec(String.format("%-32s (%d 行, %d MB)", t, rowsBefore.get(t), mb),
                 "ALTER TABLE `" + t + "` ROW_FORMAT=DYNAMIC, ALGORITHM=COPY");
        }
        System.out.println("   第 2 段回收：" + (before2 - totalMb()) + " MB\n");

        // ---------- 核对 ----------
        System.out.println("---- 核对：行数必须完全一致 ----");
        boolean ok = true;
        for (var e : rowsBefore.entrySet()) {
            long after = one("SELECT COUNT(*) FROM `" + e.getKey() + "`");
            boolean same = after == e.getValue();
            if (!same) ok = false;
            System.out.printf("   %-32s %d -> %d  %s%n", e.getKey(), e.getValue(), after, same ? "OK" : "!!! 变了 !!!");
        }
        System.out.println();

        q("核对：受保护活表仍在",
          "SELECT 'receipts' t, COUNT(*) c FROM receipts UNION ALL " +
          "SELECT 'settlement_outbound_links', COUNT(*) FROM settlement_outbound_links UNION ALL " +
          "SELECT 'sales_outbounds', COUNT(*) FROM sales_outbounds UNION ALL " +
          "SELECT 'production_orders', COUNT(*) FROM production_orders UNION ALL " +
          "SELECT 'customers', COUNT(*) FROM customers");

        q("核对：遗留表未被碰（31 张应全部还在，等 LQ 决策）",
          "SELECT COUNT(*) AS 遗留表残留数 FROM information_schema.TABLES " +
          "WHERE TABLE_SCHEMA='" + S + "' AND TABLE_TYPE='BASE TABLE' " +
          " AND (TABLE_NAME REGEXP '^(bak|backup|old|legacy|tmp|temp)_' " +
          "   OR TABLE_NAME REGEXP '_(bak|backup|old|legacy|tmp|temp|before)$' " +
          "   OR TABLE_NAME REGEXP '_(bak|backup|old|legacy|before|archive|copy)_' " +
          "   OR TABLE_NAME REGEXP '^archive_')");

        q("核对：下划线残留应已清空",
          "SELECT SUBSTRING_INDEX(NAME,'/',-1) AS t, ROUND(FILE_SIZE/1048576,2) mb " +
          "FROM INFORMATION_SCHEMA.INNODB_TABLESPACES WHERE NAME LIKE '" + S + "/\\_%'");

        System.out.println("落账：" + totalMb() + " MB（动手前 " + before1 + " MB）");
        System.out.println("== done, rowsUnchanged=" + ok + " ==");
        conn.close();
    }
}
