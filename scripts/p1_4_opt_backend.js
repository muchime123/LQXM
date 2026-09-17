const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/service/BusinessOrderMatchingService.java";
let c = fs.readFileSync(p, "utf8");

const OLD = `    @Transactional
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
    }`;

const NEW = `    @Transactional
    public Map<String, Object> autoMergeLowConfidence(int threshold, String operator, boolean dryRun) {
        String user = (operator == null || operator.isBlank()) ? "系统自动合并" : operator;

        // 1. 查询所有低置信度 PENDING 候选，按生产任务分组、confidence 降序
        List<Map<String, Object>> lowConfCandidates = jdbc.queryForList("""
            SELECT id, production_order_id, sales_outbound_id, confidence
            FROM business_match_candidates
            WHERE status='PENDING' AND confidence < ?
            ORDER BY production_order_id, confidence DESC
            """, threshold);

        if (lowConfCandidates.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("autoConfirmed", 0); empty.put("autoRejected", 0);
            empty.put("skipped", 0); empty.put("totalProcessed", 0);
            empty.put("threshold", threshold); empty.put("groups", 0); empty.put("dryRun", dryRun);
            return empty;
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
        List<Map<String, Object>> previewConfirmed = new ArrayList<>();
        List<Map<String, Object>> previewRejected = new ArrayList<>();

        for (Map.Entry<Long, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> groupCandidates = entry.getValue();
            Map<String, Object> best = groupCandidates.get(0);
            Long prodId = ((Number) best.get("production_order_id")).longValue();
            Long outboundId = ((Number) best.get("sales_outbound_id")).longValue();

            if (linkedPids.contains(prodId) || linkedOids.contains(outboundId)) {
                skipped += groupCandidates.size();
                continue;
            }

            if (dryRun) {
                if (previewConfirmed.size() < 20) previewConfirmed.add(best);
                autoConfirmed++;
                for (int i = 1; i < groupCandidates.size(); i++) {
                    if (previewRejected.size() < 20) previewRejected.add(groupCandidates.get(i));
                    autoRejected++;
                }
            } else {
                try {
                    doDecide(((Number) best.get("id")).longValue(), true, user, "P1-4 低置信度自动合并（最高分候选）");
                    autoConfirmed++;
                } catch (RuntimeException e) { skipped++; }
                for (int i = 1; i < groupCandidates.size(); i++) {
                    Long candId = ((Number) groupCandidates.get(i).get("id")).longValue();
                    try {
                        doDecide(candId, false, user, "P1-4 低置信度自动合并（同组非最高分驳回）");
                        autoRejected++;
                    } catch (RuntimeException e) { skipped++; }
                }
            }
        }

        int totalProcessed = autoConfirmed + autoRejected + skipped;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("autoConfirmed", autoConfirmed);
        result.put("autoRejected", autoRejected);
        result.put("skipped", skipped);
        result.put("totalProcessed", totalProcessed);
        result.put("threshold", threshold);
        result.put("groups", groups.size());
        result.put("dryRun", dryRun);
        if (dryRun) {
            result.put("previewConfirmed", previewConfirmed);
            result.put("previewRejected", previewRejected);
        }
        return result;
    }`;

if (!c.includes(OLD)) { console.error("old method not found"); process.exit(1); }
c = c.replace(OLD, NEW);
fs.writeFileSync(p, c, "utf8");
console.log("autoMergeLowConfidence updated with dryRun parameter");
