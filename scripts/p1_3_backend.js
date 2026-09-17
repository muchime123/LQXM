const fs = require("fs");
// Only steps 2 & 3 (ArrayList already imported in SOService)
// 2) H3FactImportController.java: add /receipt-match-candidates/batch-decision endpoint
{
  const p = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/controller/H3FactImportController.java";
  let c = fs.readFileSync(p, "utf8");
  const oldEnd = `    @PostMapping("/receipt-match-candidates/{id}/decision")
    public Map<String, Object> decideReceiptCandidate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
        String feedback = body.get("feedback") == null ? null : String.valueOf(body.get("feedback"));
        return settlementService.decideReceiptCandidate(id, confirmed, feedback);
    }`;
  const newEnd = `    @PostMapping("/receipt-match-candidates/{id}/decision")
    public Map<String, Object> decideReceiptCandidate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
        String feedback = body.get("feedback") == null ? null : String.valueOf(body.get("feedback"));
        return settlementService.decideReceiptCandidate(id, confirmed, feedback);
    }

    /** P1-3：收款兜底候选批量裁决（逐条事务隔离 + 失败详情）。 */
    @PostMapping("/receipt-match-candidates/batch-decision")
    public Map<String, Object> batchDecideReceiptCandidates(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("ids");
        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
        String feedback = body.get("feedback") == null ? null : String.valueOf(body.get("feedback"));
        return settlementService.batchDecideReceiptCandidates(ids, confirmed, feedback);
    }`;
  if (!c.includes(oldEnd)) { console.error("H3C endpoint anchor missing"); process.exit(3); }
  c = c.replace(oldEnd, newEnd);
  fs.writeFileSync(p, c, "utf8");
  console.log("2) H3C added batch endpoint ok");
}
// 2b) SOService.java: add batchDecideReceiptCandidates method right before `private Map<String, Object> asMap`
{
  const p = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/service/SalesOutboundSettlementService.java";
  let c = fs.readFileSync(p, "utf8");
  const oldHelper = `    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }`;
  const newHelper = `    /** P1-3：批量裁决收款兜底候选；逐条事务隔离，单条失败不影响其余，返回成功/失败详情。 */
    public Map<String, Object> batchDecideReceiptCandidates(List<Long> ids, boolean confirmed, String feedback) {
        int success = 0, failed = 0;
        List<Long> failedIds = new ArrayList<>();
        for (Long id : ids) {
            try { decideReceiptCandidate(id, confirmed, feedback); success++; }
            catch (RuntimeException e) { failed++; failedIds.add(id); }
        }
        return Map.of("total", ids.size(), "success", success, "failed", failed, "failedIds", failedIds);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }`;
  if (!c.includes(oldHelper)) { console.error("SOI helper anchor missing"); process.exit(2); }
  c = c.replace(oldHelper, newHelper);
  fs.writeFileSync(p, c, "utf8");
  console.log("2b) SOI batch method ok");
}
// 3) orders.ts: add batchDecideReceiptMatchCandidates API
{
  const p = "c:/Users/20676/source/LQXM/frontend/src/api/business/orders.ts";
  let c = fs.readFileSync(p, "utf8");
  const oldLine = `export async function decideReceiptMatchCandidate(id:number,confirmed:boolean,feedback?:string){return (await http.post(\`/h3-facts/receipt-match-candidates/\${id}/decision\`,{confirmed,feedback})).data}`;
  const newLine = `export async function decideReceiptMatchCandidate(id:number,confirmed:boolean,feedback?:string){return (await http.post(\`/h3-facts/receipt-match-candidates/\${id}/decision\`,{confirmed,feedback})).data}
export async function batchDecideReceiptMatchCandidates(ids:number[],confirmed:boolean,feedback?:string){return (await http.post('/h3-facts/receipt-match-candidates/batch-decision',{ids,confirmed,feedback})).data}`;
  if (!c.includes(oldLine)) { console.error("orders.ts API anchor missing"); process.exit(4); }
  c = c.replace(oldLine, newLine);
  fs.writeFileSync(p, c, "utf8");
  console.log("3) orders.ts added batch API ok");
}
