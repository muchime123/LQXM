const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/views/governance/MatchingCenterView.vue";
let c = fs.readFileSync(p, "utf8");

// 1) Add import
const OLD_IMP = "  batchDecideMatchCandidates,";
const NEW_IMP = "  batchDecideMatchCandidates,\n  autoMergeLowConfidence,";
if (!c.includes(OLD_IMP)) { console.error("import anchor missing"); process.exit(1); }
c = c.replace(OLD_IMP, NEW_IMP);
console.log("1 import ok");

// 2) Add state + function after batchDecide function (before loadCandidates)
const OLD_FUNC = `async function loadCandidates() {`;
const NEW_FUNC = `const autoMerging = ref(false), autoMergeResult = ref<Record<string, any> | null>(null)
async function runAutoMerge() {
  const ok = await askConfirm({
    title: '低置信度候选自动合并',
    message: '对 <60% 的低置信度候选，按生产任务分组取最高分自动确认，其余驳回。已有关联的跳过。是否继续？',
    confirmText: '执行自动合并',
    danger: true
  })
  if (!ok) return
  autoMerging.value = true
  autoMergeResult.value = null
  try {
    const r: any = await autoMergeLowConfidence(60, '于洋洋')
    autoMergeResult.value = r
    const msg = '自动合并完成：确认 ' + r.autoConfirmed + '，驳回 ' + r.autoRejected + '，跳过 ' + r.skipped + '（共 ' + r.totalProcessed + ' 条，' + r.groups + ' 组）'
    notify.success(msg)
    await loadCandidates()
  } catch (e: any) { notify.error(e?.message || '自动合并失败') }
  finally { autoMerging.value = false }
}
async function loadCandidates() {`;
if (!c.includes(OLD_FUNC)) { console.error("func anchor missing"); process.exit(2); }
c = c.replace(OLD_FUNC, NEW_FUNC);
console.log("2 func ok");

// 3) Add auto-merge button to batch bar (after <60% button, before batch-sep)
const OLD_BAR = `          <el-button link type="warning" :disabled="!lowConfCandidates().length" @click="selectByConfidence(null, 60)"><60%（{{ lowConfCandidates().length }}）</el-button>
          <span class="batch-sep"></span>`;
const NEW_BAR = `          <el-button link type="warning" :disabled="!lowConfCandidates().length" @click="selectByConfidence(null, 60)"><60%（{{ lowConfCandidates().length }}）</el-button>
          <span class="batch-sep"></span>
          <el-button size="small" type="warning" plain :loading="autoMerging" @click="runAutoMerge">低置信度自动合并</el-button>
          <span v-if="autoMergeResult" class="auto-merge-result">
            自动确认 {{ autoMergeResult.autoConfirmed }} · 驳回 {{ autoMergeResult.autoRejected }} · 跳过 {{ autoMergeResult.skipped }}
          </span>`;
if (!c.includes(OLD_BAR)) { console.error("bar anchor missing"); process.exit(3); }
c = c.replace(OLD_BAR, NEW_BAR);
console.log("3 bar ok");

// 4) Add style for auto-merge-result
const OLD_STYLE = `.batch-bar .batch-sep { width: 1px; height: 16px; background: var(--color-border); margin: 0 4px; }`;
const NEW_STYLE = `.batch-bar .batch-sep { width: 1px; height: 16px; background: var(--color-border); margin: 0 4px; }
.auto-merge-result { font-size: 12px; color: #64748b; padding: 2px 8px; border-radius: 6px; background: #fef3c7; }`;
if (!c.includes(OLD_STYLE)) { console.error("style anchor missing"); process.exit(4); }
c = c.replace(OLD_STYLE, NEW_STYLE);
console.log("4 style ok");

fs.writeFileSync(p, c, "utf8");
console.log("DONE MatchingCenterView P1-4 patched");
