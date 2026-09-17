const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/controller/H3FactImportController.java";
let c = fs.readFileSync(p, "utf8");
const OLD = `    /** P1-4：低置信度候选自动合并（按生产任务分组取最高分确认，其余驳回）。 */
    @PostMapping("/match-candidates/auto-merge")
    public Map<String, Object> autoMergeLowConfidence(@RequestParam(defaultValue = "60") int threshold,
                                                       @RequestParam(defaultValue = "系统自动合并") String operator) {
        return matchingService.autoMergeLowConfidence(threshold, operator);
    }`;
const NEW = `    /** P1-4：低置信度候选自动合并（按生产任务分组取最高分确认，其余驳回）。 */
    @PostMapping("/match-candidates/auto-merge")
    public Map<String, Object> autoMergeLowConfidence(@RequestParam(defaultValue = "60") int threshold,
                                                       @RequestParam(defaultValue = "系统自动合并") String operator,
                                                       @RequestParam(defaultValue = "false") boolean dryRun) {
        return matchingService.autoMergeLowConfidence(threshold, operator, dryRun);
    }`;
if (!c.includes(OLD)) { console.error("controller endpoint not found"); process.exit(1); }
c = c.replace(OLD, NEW);
fs.writeFileSync(p, c, "utf8");
console.log("controller updated with dryRun param");
