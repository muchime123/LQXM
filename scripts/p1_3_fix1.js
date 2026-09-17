const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/views/MatchingCenterView.vue";
let c = fs.readFileSync(p, "utf8");

// 3) Replace batch notify line with high confidence stats — write with explicit concatenation (avoid nested template literals)
const oldBatch = "    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed)";
const newBatch = "    const _high = selectedCandidates.value.filter(r => Number(r.confidence ?? 0) >= 80).length\n    const _suf = _high ? '（高置信度 ' + _high + ' 条）' : ''\n    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + _suf)";
if (!c.includes(oldBatch)) { console.error("batch msg missing"); process.exit(1); }
c = c.replace(oldBatch, newBatch);
console.log("3) msg ok");
// 4) batchDecideReceipt notify — same
const oldReceiptNotify = "    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + (high ? `（高置信度 ${high} 条）` : ''))";
if (c.includes(oldReceiptNotify)) {
  c = c.replace(oldReceiptNotify,
    "    const _rh = selectedReceiptCandidates.value.filter(r => Number(r.confidence ?? 0) >= 80).length\n" +
    "    const _rsuf = _rh ? '（高置信度 ' + _rh + ' 条）' : ''\n" +
    "    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + _rsuf)");
  console.log("4b) receipt notify ok");
}
fs.writeFileSync(p, c, "utf8");
console.log("p1_3_fix1 done");
