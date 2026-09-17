const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/service/BusinessOrderMatchingService.java";
let c = fs.readFileSync(p, "utf8");

const OLD = `    private record MatchFeature(String key, int score, boolean enabled) {
    }
}`;
const NEW = `    private record MatchFeature(String key, int score, boolean enabled) {
    }

    // ===================== P1-4：低置信度候选自动合并策略 =====================

    /**
     * P1-4：低置信度候选自动合并。
     *
     * 策略：对 confidence < threshold（默认 60）的 PENDING 候选，按生产任务分组，
     * 每组只取 confidence 最高的一条自动确认（前提：该生产任务和出库单都尚未关联到任何业务单），
     * 其余同组低置信度候选自动驳回。已有关联的候选跳过。
     *
     * @param threshold 置信度阈值，低于此值才参与自动合并（默认 60）
     * @param operator 操作人标识
     * @return 合并结果统计 { autoConfirmed, autoRejected, skipped, totalProcessed }
     */
    @Transactional
    public Map<String, Object> autoMergeLowConfidence(int threshold, String operator) {
        String user = (operator == null || operator.isBlank()) ? "系统自动合并" : operator;

        // 1. 查询所有低置信度 PENDING 候选，按生产任务分组、confidence 降序
        List<Map<String, Object>> lowConfCandidates = jdbc.queryForList("""
            SELECT id, production_order_id, sales_outbound_id, confidence
            FROM business_match_candidates
            WHERE status='PENDING' AND confidence < ?
            ORDER BY production_order_id, confidence DESC
            """, threshold);

        if (lowConfCandidates.isEmpty()) {
            return Map.of("autoConfirmed", 0, "autoRejected", 0, "skipped", 0, "totalProcessed", 0);
        }

        // 2. 按生产任务分组，每组只保留 confidence 最高的那条
        Map<Long, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : lowConfCandidates) {
            Long pid = ((Number) row.get("production_order_id")).longValue();
            groups.computeIfAbsent(pid, k -> new ArrayList<>()).add(row);
        }

        // 3. 批量查询哪些生产任务/出库单已有关联业务单（跳过已关联的）
        List<Long> allProdIds = new ArrayList<>();
        List<Long> allOutboundIds = new ArrayList<>();
        for (Map<String, Object> row : lowConfCandidates) {
            allProdIds.add(((Number) row.get("production_order_id")).longValue());
            allOutboundIds.add(((Number) row.get("sales_outbound_id")).longValue());
        }
        var linkedPids = batchQueryLinked("PRODUCTION_ORDER", allProdIds);
        var linkedOids = batchQueryLinked("SALES_OUTBOUND", allOutboundIds);

        int autoConfirmed = 0, autoRejected = 0, skipped = 0;

        for (Map.Entry<Long, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> groupCandidates = entry.getValue();
            Map<String, Object> best = groupCandidates.get(0); // confidence 最高
            Long prodId = ((Number) best.get("production_order_id")).longValue();
            Long outboundId = ((Number) best.get("sales_outbound_id")).longValue();

            if (linkedPids.contains(prodId) || linkedOids.contains(outboundId)) {
                // 已有业务单关联，跳过整组（不做自动合并，留人工处理）
                skipped += groupCandidates.size();
                continue;
            }

            // 自动确认 best 候选
            try {
                doDecide(((Number) best.get("id")).longValue(), true, user, "P1-4 低置信度自动合并（最高分候选）");
                autoConfirmed++;
            } catch (RuntimeException e) {
                skipped++;
            }

            // 自动驳回同组其余候选
            for (int i = 1; i < groupCandidates.size(); i++) {
                Long candId = ((Number) groupCandidates.get(i).get("id")).longValue();
                try {
                    doDecide(candId, false, user, "P1-4 低置信度自动合并（同组非最高分驳回）");
                    autoRejected++;
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        }

        int totalProcessed = autoConfirmed + autoRejected + skipped;
        return Map.of("autoConfirmed", autoConfirmed, "autoRejected", autoRejected,
                "skipped", skipped, "totalProcessed", totalProcessed,
                "threshold", threshold, "groups", groups.size());
    }

    /** 批量查询已关联到业务单的 ID 集合。 */
    private java.util.Set<Long> batchQueryLinked(String objectType, List<Long> objectIds) {
        if (objectIds.isEmpty()) return java.util.Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(objectIds.size(), "?"));
        List<Object> params = new ArrayList<>(objectIds);
        params.add(0, objectType);
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT object_id FROM business_order_links WHERE object_type=? AND object_id IN (" + placeholders + ")",
                Long.class, params.toArray()));
    }
}`;
if (!c.includes(OLD)) { console.error("anchor not found"); process.exit(1); }
c = c.replace(OLD, NEW);
fs.writeFileSync(p, c, "utf8");
console.log("autoMergeLowConfidence added to BusinessOrderMatchingService");
