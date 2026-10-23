package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资金归属主体字典与账户认领（P2-A，2026-10-23）。
 * 设计要点（docs/资金日记账P2设计-A账户管理与B年度期初-20261023.md §2）：
 * <ul>
 *   <li>主体 ≠ 企业法人：entity_type 只是归类标签，程序不做法人推断；</li>
 *   <li>D1：15 组前缀仅作建议（{@link #suggest()}），不自动创建主体、不自动认领；</li>
 *   <li>entity_id NULL = 待认领，不参与任何按主体合计（宁缺勿错）；</li>
 *   <li>账户停用只影响未来操作，不改变历史统计（历史汇总 SQL 不过滤 status）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CashflowEntityService {

    private final JdbcTemplate jdbc;

    private static final Set<String> ENTITY_TYPES = Set.of("COMPANY", "INDIVIDUAL", "OTHER");
    private static final Set<String> ACCOUNT_STATUSES = Set.of("ACTIVE", "INACTIVE");

    /** 主体字典 + 各主体账户计数（含未认领账户数的汇总字段）。 */
    public Map<String, Object> entities() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.id, e.name, e.entity_type AS entityType, e.status, e.note,
                       e.created_at AS createdAt, e.updated_at AS updatedAt,
                       (SELECT COUNT(*) FROM h3_cash_accounts a WHERE a.entity_id = e.id) AS accountCount
                  FROM cashflow_entities e
                 ORDER BY e.name ASC
                """);
        Long unassigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_cash_accounts WHERE external_source='H3YUN' AND entity_id IS NULL",
                Long.class);
        Long totalAccounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_cash_accounts WHERE external_source='H3YUN'", Long.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("unassignedCount", unassigned == null ? 0 : unassigned);
        out.put("totalAccounts", totalAccounts == null ? 0 : totalAccounts);
        return out;
    }

    @Transactional
    public Map<String, Object> create(String name, String entityType, String note) {
        requireType(entityType);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("主体名称不能为空");
        try {
            jdbc.update("INSERT INTO cashflow_entities(name, entity_type, note) VALUES(?,?,?)",
                    name.trim(), entityType, blankToNull(note));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("主体名称已存在：" + name.trim());
        }
        return jdbc.queryForMap("SELECT id, name, entity_type AS entityType, status, note FROM cashflow_entities WHERE name=?", name.trim());
    }

    @Transactional
    public void update(long id, String name, String entityType, String status, String note) {
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT name, entity_type, status, note FROM cashflow_entities WHERE id=?", id);
        String newName = name == null || name.isBlank() ? (String) current.get("name") : name.trim();
        String newType = entityType == null ? (String) current.get("entity_type") : entityType;
        String newStatus = status == null ? (String) current.get("status") : status;
        if (!ENTITY_TYPES.contains(newType)) throw new IllegalArgumentException("非法主体类型：" + newType);
        if (!"ACTIVE".equals(newStatus) && !"INACTIVE".equals(newStatus))
            throw new IllegalArgumentException("非法主体状态：" + newStatus);
        String newNote = note == null ? (String) current.get("note") : blankToNull(note);
        try {
            jdbc.update("UPDATE cashflow_entities SET name=?, entity_type=?, status=?, note=? WHERE id=?",
                    newName, newType, newStatus, newNote, id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("主体名称已存在：" + newName);
        }
    }

    /** 账户简单列表（含 entity_id/status），供认领弹窗与 A-2 筛选使用。 */
    public List<Map<String, Object>> accounts() {
        return jdbc.queryForList("""
                SELECT a.id, a.account_name AS accountName, a.currency,
                       a.entity_id AS entityId, a.status,
                       e.name AS entityName
                  FROM h3_cash_accounts a
                  LEFT JOIN cashflow_entities e ON e.id = a.entity_id
                 WHERE a.external_source='H3YUN'
                 ORDER BY a.account_name ASC
                """);
    }

    /**
     * 建议初值（只建议不落库）：账户名按最长公共前缀（≥2 字）聚组；
     * 组内 ≥2 户才给出建议主体名，单户孤组=识别不出，前端标「待认领」。
     * 不强求 35 户全有建议（LQ 实施顺序调整）。
     */
    public Map<String, Object> suggest() {
        List<String> names = jdbc.queryForList(
                "SELECT account_name FROM h3_cash_accounts WHERE external_source='H3YUN' ORDER BY account_name",
                String.class);
        // 贪心前缀聚组：按名排序后，相邻两户取最长公共前缀（≥2 字）即并入同组
        List<List<String>> groups = new ArrayList<>();
        for (String name : names) {
            if (!groups.isEmpty()) {
                List<String> last = groups.get(groups.size() - 1);
                String lcp = longestCommonPrefix(last.get(last.size() - 1), name);
                if (lcp.length() >= 2) { last.add(name); continue; }
            }
            List<String> g = new ArrayList<>();
            g.add(name);
            groups.add(g);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> suggested = new HashSet<>();
        for (List<String> g : groups) {
            String suggestion = g.size() >= 2 ? longestCommonPrefix(g.get(0), g.get(g.size() - 1)) : null;
            for (String name : g) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("accountName", name);
                item.put("suggestedEntityName", suggestion);
                item.put("groupSize", g.size());
                if (suggestion != null) suggested.add(name);
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(a -> (String) a.get("accountName")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("suggestedCount", suggested.size());
        out.put("unrecognizedCount", names.size() - suggested.size());
        return out;
    }

    /** 批量认领 {accountId → entityId}；entityId=null 表示撤销认领（回到待认领）。 */
    @Transactional
    public Map<String, Object> assignEntities(List<Map<String, Object>> assignments) {
        int assigned = 0;
        int cleared = 0;
        for (Map<String, Object> a : assignments) {
            long accountId = ((Number) a.get("accountId")).longValue();
            Object entityIdObj = a.get("entityId");
            int updated;
            if (entityIdObj == null) {
                updated = jdbc.update(
                        "UPDATE h3_cash_accounts SET entity_id=NULL WHERE id=? AND external_source='H3YUN'", accountId);
                cleared += updated;
            } else {
                long entityId = ((Number) entityIdObj).longValue();
                Integer exists = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cashflow_entities WHERE id=?", Integer.class, entityId);
                if (exists == null || exists == 0)
                    throw new IllegalArgumentException("主体不存在：id=" + entityId);
                updated = jdbc.update(
                        "UPDATE h3_cash_accounts SET entity_id=? WHERE id=? AND external_source='H3YUN'",
                        entityId, accountId);
                assigned += updated;
            }
            if (updated == 0) throw new IllegalArgumentException("账户不存在：id=" + accountId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assigned", assigned);
        out.put("cleared", cleared);
        return out;
    }

    /** 删除主体：仅当没有任何账户认领时允许（防孤儿外键）；有账户先撤销认领。 */
    @Transactional
    public void delete(long id) {
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM h3_cash_accounts WHERE entity_id=?", Integer.class, id);
        if (used != null && used > 0)
            throw new IllegalStateException("主体仍有 " + used + " 个账户认领，先撤销认领再删除");
        jdbc.update("DELETE FROM cashflow_entities WHERE id=?", id);
    }

    /** 账户停用/启用。只影响未来操作（新增流水可选性），不改历史统计。 */
    @Transactional
    public void setAccountStatus(long accountId, String status) {
        if (!ACCOUNT_STATUSES.contains(status)) throw new IllegalArgumentException("非法账户状态：" + status);
        int updated = jdbc.update(
                "UPDATE h3_cash_accounts SET status=? WHERE id=? AND external_source='H3YUN'", status, accountId);
        if (updated == 0) throw new IllegalArgumentException("账户不存在：id=" + accountId);
    }

    private static void requireType(String entityType) {
        if (entityType == null || !ENTITY_TYPES.contains(entityType))
            throw new IllegalArgumentException("非法主体类型：" + entityType + "（允许 COMPANY/INDIVIDUAL/OTHER）");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    static String longestCommonPrefix(String a, String b) {
        int i = 0;
        int max = Math.min(a.length(), b.length());
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }
}
