const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/controller/H3FactImportController.java";
let c = fs.readFileSync(p, "utf8");

const OLD = `    /** V160 A3：评分特征配置（线上调优）。 */
    @GetMapping("/match-features")
    public List<Map<String, Object>> matchFeatures() {
        return matchingService.listMatchFeatures();
    }`;
const NEW = `    /** P1-4：低置信度候选自动合并（按生产任务分组取最高分确认，其余驳回）。 */
    @PostMapping("/match-candidates/auto-merge")
    public Map<String, Object> autoMergeLowConfidence(@RequestParam(defaultValue = "60") int threshold,
                                                       @RequestParam(defaultValue = "系统自动合并") String operator) {
        return matchingService.autoMergeLowConfidence(threshold, operator);
    }

    /** V160 A3：评分特征配置（线上调优）。 */
    @GetMapping("/match-features")
    public List<Map<String, Object>> matchFeatures() {
        return matchingService.listMatchFeatures();
    }`;
if (!c.includes(OLD)) { console.error("controller anchor not found"); process.exit(1); }
c = c.replace(OLD, NEW);
fs.writeFileSync(p, c, "utf8");
console.log("auto-merge endpoint added to H3FactImportController");
