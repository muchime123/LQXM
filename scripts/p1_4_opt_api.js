const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/api/business/orders.ts";
let c = fs.readFileSync(p, "utf8");
const OLD = "export async function autoMergeLowConfidence(threshold=60,operator='系统自动合并'){return (await http.post('/h3-facts/match-candidates/auto-merge',null,{params:{threshold,operator}})).data}";
const NEW = "export async function autoMergeLowConfidence(threshold=60,operator='系统自动合并',dryRun=false){return (await http.post('/h3-facts/match-candidates/auto-merge',null,{params:{threshold,operator,dryRun}})).data}";
if (!c.includes(OLD)) { console.error("api not found"); process.exit(1); }
c = c.replace(OLD, NEW);
fs.writeFileSync(p, c, "utf8");
console.log("API updated with dryRun param");
