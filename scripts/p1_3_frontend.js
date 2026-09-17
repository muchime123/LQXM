const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/views/governance/MatchingCenterView.vue";
let c = fs.readFileSync(p, "utf8");

// 1) SCRIPT imports + refs + onMounted (route.query.tab)
const oldHead = `<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import {
  fetchMatchCandidates,
  decideMatchCandidate,
  rebuildBusinessMatches,
  batchDecideMatchCandidates,
  fetchMatchFeatures,
  updateMatchFeatures,
  fetchReceiptMatchCandidates,
  decideReceiptMatchCandidate
} from '@/api/business'
import type { MatchCandidate, MatchFeature, ReceiptMatchCandidate } from '@/api/business'

const activeTab = ref('production-outbound')`;
const newHead = `<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import {
  fetchMatchCandidates,
  decideMatchCandidate,
  rebuildBusinessMatches,
  batchDecideMatchCandidates,
  fetchMatchFeatures,
  updateMatchFeatures,
  fetchReceiptMatchCandidates,
  decideReceiptMatchCandidate,
  batchDecideReceiptMatchCandidates
} from '@/api/business'
import type { MatchCandidate, MatchFeature, ReceiptMatchCandidate } from '@/api/business'

const route = useRoute()
const VALID_TABS = ['production-outbound', 'receipt-candidates', 'features'] as const
const activeTab = ref<typeof VALID_TABS[number]>('production-outbound')`;
if (!c.includes(oldHead)) { console.error("head anchor missing"); process.exit(1); }
c = c.replace(oldHead, newHead); console.log("1 ok");

// 2) confidence segment helpers + toggleSelectAll
const oldSel = `const selectedCandidates = ref<MatchCandidate[]>([]), batchDeciding = ref(false)
function toggleSelectAll() {
  selectedCandidates.value = selectedCandidates.value.length === candidates.value.length ? [] : [...candidates.value]
}`;
const newSel = `const selectedCandidates = ref<MatchCandidate[]>([]), batchDeciding = ref(false)
function highConf(arr: any[]){ return arr.filter(r => Number(r.confidence ?? 0) >= 80) }
function midConf(arr: any[]){ return arr.filter(r => { const v = Number(r.confidence ?? 0); return v >= 60 && v < 80 }) }
function lowConf(arr: any[]){ return arr.filter(r => Number(r.confidence ?? 0) < 60) }
function selectByRange(list: any[], stateRef: any, minInc: number | null, maxExc: number | null) {
  const sel = list.filter(r => {
    const v = Number(r.confidence ?? 0)
    return (minInc == null || v >= minInc) && (maxExc == null || v < maxExc)
  })
  const allIn = sel.length > 0 && sel.every(r => stateRef.value.some((s: any) => s.id === r.id))
  if (allIn) {
    const off = new Set(sel.map((r: any) => r.id))
    stateRef.value = stateRef.value.filter((s: any) => !off.has(s.id))
    return
  }
  const m = new Map(stateRef.value.map((s: any) => [s.id, s]))
  sel.forEach((r: any) => m.set(r.id, r))
  stateRef.value = Array.from(m.values())
}
const highConfCandidates = () => highConf(candidates.value)
const midConfCandidates = () => midConf(candidates.value)
const lowConfCandidates = () => lowConf(candidates.value)
const selectedHighCount = () => highConf(selectedCandidates.value).length
function selectByConfidence(minInc: number | null, maxExc: number | null) {
  selectByRange(candidates.value, selectedCandidates, minInc, maxExc)
}
function toggleSelectAll() {
  selectedCandidates.value = selectedCandidates.value.length === candidates.value.length ? [] : [...candidates.value]
}`;
if (!c.includes(oldSel)) { console.error("toggleSelectAll missing"); process.exit(2); }
c = c.replace(oldSel, newSel); console.log("2 ok");

// 3) batchDecide notify — already fixed — reapply if not
const oldBatch = "    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed)";
const newBatch = "    const _high = highConf(selectedCandidates.value).length\n    const _suf = _high ? '（高置信度 ' + _high + ' 条）' : ''\n    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + _suf)";
if (c.includes(oldBatch)) c = c.replace(oldBatch, newBatch);
console.log("3 ok");

// 4) Receipt state + helpers + batch + sync
const oldRc = `const receiptCandidates = ref<ReceiptMatchCandidate[]>([]), receiptCandidateStatus = ref('PENDING')
async function loadReceiptCandidates() {`;
const newRc = `const receiptCandidates = ref<ReceiptMatchCandidate[]>([]), receiptCandidateStatus = ref('PENDING')
const selectedReceiptCandidates = ref<ReceiptMatchCandidate[]>([]), receiptBatchDeciding = ref(false)
const receiptHighConf = () => highConf(receiptCandidates.value)
const receiptMidConf = () => midConf(receiptCandidates.value)
const receiptLowConf = () => lowConf(receiptCandidates.value)
function toggleReceiptSelectAll() {
  selectedReceiptCandidates.value = selectedReceiptCandidates.value.length === receiptCandidates.value.length ? [] : [...receiptCandidates.value]
}
function selectReceiptByConfidence(minInc: number | null, maxExc: number | null) {
  selectByRange(receiptCandidates.value, selectedReceiptCandidates, minInc, maxExc)
}
async function batchDecideReceipt(confirmed: boolean) {
  if (!selectedReceiptCandidates.value.length) { notify.warning('请先勾选候选'); return }
  const ok = await askConfirm({
    title: confirmed ? '批量确认收款分配' : '批量驳回候选',
    message: confirmed ? '确认分配 ' + selectedReceiptCandidates.value.length + ' 条候选到对应出库单？' : '驳回 ' + selectedReceiptCandidates.value.length + ' 条候选？',
    confirmText: confirmed ? '批量确认' : '批量驳回',
    danger: !confirmed
  })
  if (!ok) return
  receiptBatchDeciding.value = true
  try {
    const r: any = await batchDecideReceiptMatchCandidates(selectedReceiptCandidates.value.map((c: any) => c.id), confirmed)
    const _rh = receiptHighConf().filter(rr => selectedReceiptCandidates.value.some(s => s.id === rr.id)).length
    const _rsuf = _rh ? '（高置信度 ' + _rh + ' 条）' : ''
    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + _rsuf)
    await loadReceiptCandidates()
    selectedReceiptCandidates.value = []
  } catch (e: any) { notify.error(e.message || '批量操作失败') }
  finally { receiptBatchDeciding.value = false }
}
function syncReceiptSelection(seg: 'high'|'mid'|'low', rows: ReceiptMatchCandidate[]) {
  const listFn = seg === 'high' ? receiptHighConf : seg === 'mid' ? receiptMidConf : receiptLowConf
  const segAll = listFn()
  const segIds = new Set(segAll.map((r: any) => r.id))
  const kept = selectedReceiptCandidates.value.filter(r => !segIds.has(r.id))
  const byId = new Map<number, ReceiptMatchCandidate>()
  ;[...kept, ...rows].forEach(r => byId.set(r.id, r))
  selectedReceiptCandidates.value = Array.from(byId.values())
}
async function loadReceiptCandidates() {`;
if (!c.includes(oldRc)) { console.error("rc anchor missing"); process.exit(4); }
c = c.replace(oldRc, newRc); console.log("4 ok");

// 5) Clear selected on receipt reload
const oldLoad = `async function loadReceiptCandidates() {
  try { receiptCandidates.value = await fetchReceiptMatchCandidates(receiptCandidateStatus.value) }
  catch (e: any) { notify.error(e.message || '收款候选加载失败') }
}`;
const newLoad = `async function loadReceiptCandidates() {
  try { receiptCandidates.value = await fetchReceiptMatchCandidates(receiptCandidateStatus.value) }
  catch (e: any) { notify.error(e.message || '收款候选加载失败') }
  finally { selectedReceiptCandidates.value = [] }
}`;
if (!c.includes(oldLoad)) { console.error("loadReceipt missing"); process.exit(5); }
c = c.replace(oldLoad, newLoad); console.log("5 ok");

// 6) onMounted tab sync
const oldMount = `onMounted(() => { loadCandidates(); loadMatchFeatures(); loadReceiptCandidates() })`;
const newMount = `onMounted(() => {
  const t = String(route.query.tab || '') as any
  if (VALID_TABS.includes(t)) activeTab.value = t
  nextTick(() => { loadCandidates(); loadMatchFeatures(); loadReceiptCandidates() })
})`;
if (!c.includes(oldMount)) { console.error("mount missing"); process.exit(6); }
c = c.replace(oldMount, newMount); console.log("6 ok");

// ========= TEMPLATE ============
// 7) Prod batch bar
const oldBar = `        <div v-if="candidateStatus === 'PENDING'" class="batch-bar">
          <el-button link type="primary" @click="toggleSelectAll">全选</el-button>
          <span class="batch-count">已选 {{ selectedCandidates.length }} 条</span>
          <el-button
            size="small"
            type="primary"
            :disabled="!selectedCandidates.length"
            :loading="batchDeciding"
            @click="batchDecide(true)"
            >批量确认</el-button
          >
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="!selectedCandidates.length"
            :loading="batchDeciding"
            @click="batchDecide(false)"
            >批量驳回</el-button
          >
        </div>`;
const newBar = `        <div v-if="candidateStatus === 'PENDING'" class="batch-bar">
          <el-button link type="primary" @click="toggleSelectAll">全选</el-button>
          <el-button link type="success" :disabled="!highConfCandidates().length" @click="selectByConfidence(80, null)">≥80% 一键勾选（{{ highConfCandidates().length }}）</el-button>
          <el-button link :disabled="!midConfCandidates().length" @click="selectByConfidence(60, 80)">60~79%（{{ midConfCandidates().length }}）</el-button>
          <el-button link type="warning" :disabled="!lowConfCandidates().length" @click="selectByConfidence(null, 60)"><60%（{{ lowConfCandidates().length }}）</el-button>
          <span class="batch-sep"></span>
          <span class="batch-count">已选 <b>{{ selectedCandidates.length }}</b> 条<template v-if="selectedHighCount()"> · 高置信 <em>{{ selectedHighCount() }}</em></template></span>
          <el-button size="small" type="primary" :disabled="!selectedCandidates.length" :loading="batchDeciding" @click="batchDecide(true)">批量确认</el-button>
          <el-button size="small" type="danger" plain :disabled="!selectedCandidates.length" :loading="batchDeciding" @click="batchDecide(false)">批量驳回</el-button>
        </div>`;
if (!c.includes(oldBar)) { console.error("bar missing"); process.exit(7); }
c = c.replace(oldBar, newBar); console.log("7 ok");

// 8) Prod confidence cell — tag color
const oldProdConf = `              <div class="confidence"><span :style="{ width: Math.max(4, row.confidence) + '%' }"></span></div>
              <small>{{ row.confidence }}% 置信度</small></template`;
const newProdConf = `              <div class="confidence" :class="{ high: row.confidence >= 80, mid: row.confidence >= 60 && row.confidence < 80, low: row.confidence < 60 }">
                <span :style="{ width: Math.max(4, row.confidence) + '%' }"></span>
              </div>
              <small>
                {{ row.confidence }}% 置信度
                <el-tag v-if="row.confidence >= 80" type="success" size="small" effect="plain" style="margin-left:4px">高</el-tag>
                <el-tag v-else-if="row.confidence >= 60" type="warning" size="small" effect="plain" style="margin-left:4px">中</el-tag>
                <el-tag v-else type="info" size="small" effect="plain" style="margin-left:4px">低</el-tag>
              </small></template`;
if (!c.includes(oldProdConf)) { console.error("prod conf missing"); process.exit(8); }
c = c.replace(oldProdConf, newProdConf); console.log("8 ok");

// 9) Receipt panel HEAD: add batch bar + segmented tables for PENDING status
const oldPanel = `      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>收款金额兜底候选</h2>
            <p>未解析收款按「客户一致 + 90 天窗口 + 金额近似」生成候选，人工确认后回填分配。</p>
          </div>
          <el-radio-group v-model="receiptCandidateStatus" @change="loadReceiptCandidates"
            ><el-radio-button value="PENDING">待确认</el-radio-button
            ><el-radio-button value="CONFIRMED">已确认</el-radio-button
            ><el-radio-button value="REJECTED">已驳回</el-radio-button></el-radio-group
          >
        </div>
        <el-table :data="receiptCandidates" stripe>
          <el-table-column label="收款单" min-width="180"`;
const newPanel = `      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>收款金额兜底候选</h2>
            <p>未解析收款按「客户一致 + 90 天窗口 + 金额近似」生成候选，人工确认后回填分配。≥80% 建议一键批量确认。</p>
          </div>
          <el-radio-group v-model="receiptCandidateStatus" @change="loadReceiptCandidates"
            ><el-radio-button value="PENDING">待确认 <el-badge v-if="receiptCandidateStatus === 'PENDING'" :value="receiptHighConf().length" type="success" class="inline-badge" /></el-radio-button
            ><el-radio-button value="CONFIRMED">已确认</el-radio-button
            ><el-radio-button value="REJECTED">已驳回</el-radio-button></el-radio-group
          >
        </div>
        <div v-if="receiptCandidateStatus === 'PENDING'" class="batch-bar">
          <el-button link type="primary" @click="toggleReceiptSelectAll">全选</el-button>
          <el-button link type="success" :disabled="!receiptHighConf().length" @click="selectReceiptByConfidence(80, null)">≥80% 一键勾选（{{ receiptHighConf().length }}）</el-button>
          <el-button link :disabled="!receiptMidConf().length" @click="selectReceiptByConfidence(60, 80)">60~79%（{{ receiptMidConf().length }}）</el-button>
          <el-button link type="warning" :disabled="!receiptLowConf().length" @click="selectReceiptByConfidence(null, 60)"><60%（{{ receiptLowConf().length }}）</el-button>
          <span class="batch-sep"></span>
          <span class="batch-count">已选 <b>{{ selectedReceiptCandidates.length }}</b> 条</span>
          <el-button size="small" type="primary" :disabled="!selectedReceiptCandidates.length" :loading="receiptBatchDeciding" @click="batchDecideReceipt(true)">批量确认分配</el-button>
          <el-button size="small" type="danger" plain :disabled="!selectedReceiptCandidates.length" :loading="receiptBatchDeciding" @click="batchDecideReceipt(false)">批量驳回</el-button>
        </div>
        <table-cols-split-anchor/>`;
// Use a different approach: replace just the panel head + batch-bar line, keep column template intact. Then wrap segmented tables around column.
if (!c.includes(oldPanel)) { console.error("rc panel missing"); process.exit(9); }
c = c.replace(oldPanel, newPanel); console.log("9 ok");

// Replace `<table-cols-split-anchor/>` with segmented wrapper for PENDING. Find the column region start.
const TABLE_COL_OPEN = `<el-table :data="receiptCandidates" stripe>
          <el-table-column label="收款单" min-width="180"`;
const TABLE_COL_START = `<table-cols-split-anchor/>` + TABLE_COL_OPEN;
const newStart = `<template v-if="receiptCandidateStatus === 'PENDING'">
          <div v-if="receiptHighConf().length" class="conf-segment conf-segment--high"><span class="dot"></span><b>高置信度 ≥80%</b>（{{ receiptHighConf().length }} 条 · 建议一键确认）</div>
          <el-table v-if="receiptHighConf().length" :data="receiptHighConf()" stripe @selection-change="syncReceiptSelection('high', $event)">
            <el-table-column type="selection" width="44" />
            <RC-COLS-PLACEHOLDER/>
          </el-table>
          <div v-if="receiptMidConf().length" class="conf-segment conf-segment--mid"><span class="dot"></span><b>中置信度 60~79%</b>（{{ receiptMidConf().length }} 条 · 建议人工复核）</div>
          <el-table v-if="receiptMidConf().length" :data="receiptMidConf()" stripe @selection-change="syncReceiptSelection('mid', $event)">
            <el-table-column type="selection" width="44" />
            <RC-COLS-PLACEHOLDER/>
          </el-table>
          <div v-if="receiptLowConf().length" class="conf-segment conf-segment--low"><span class="dot"></span><b>低置信度 <60%</b>（{{ receiptLowConf().length }} 条 · 建议逐条核对）</div>
          <el-table v-if="receiptLowConf().length" :data="receiptLowConf()" stripe @selection-change="syncReceiptSelection('low', $event)">
            <el-table-column type="selection" width="44" />
            <RC-COLS-PLACEHOLDER/>
          </el-table>
        </template>
        <el-table v-else :data="receiptCandidates" stripe>
          <RC-COLS-PLACEHOLDER-NOSEL/>`;
if (!c.includes(TABLE_COL_START)) { console.error("table split anchor missing"); process.exit(10); }
// First: extract RC column template (everything from `<el-table :data="receiptCandidates"` through to the matching `</el-table>` before `<el-empty>`)
// Capture the entire shared receipt column region.
const colsOpen = TABLE_COL_OPEN;
const colsClose = `        </el-table>
        <el-empty v-if="!receiptCandidates.length" description="当前没有金额兜底候选" />`;
const i1 = c.indexOf(colsOpen);
const i2 = c.indexOf(colsClose);
if (i1 < 0 || i2 < 0) { console.error("rc cols not found i1=",i1,"i2=",i2); process.exit(11); }
const colsText = c.substring(i1, i2 + colsClose.length);
// colsText has form: `<el-table :data="receiptCandidates" stripe>\n          <el-table-column ... </el-table-column>\n        </el-table>\n<el-empty...>`
// Strip first line `<el-table ...>` and final `</el-table>` + empty
let colsShared = colsText.substring(colsText.indexOf("\n") + 1);
const closeIdx = colsShared.lastIndexOf("        </el-table>");
colsShared = colsShared.substring(0, closeIdx);
// colsShared now is only the columns — plus we need to add type="selection" only for v-else case? No: PENDING case already has it in wrapper. For v-else (CONFIRMED / REJECTED), status is non-PENDING so selection isn't needed.
// Build RC_COL_WITHOUT_SEL = colsShared as-is (does not contain <el-table-column type="selection")
const colsNoSel = colsShared;
// Build RC_COL_WITH_SEL = prepend selection column for v-else but wait NO: v-else = not PENDING, don't show selection.
const before = c.substring(0, i1);
const after = c.substring(i2 + colsClose.length);
const emptyLine = `        <el-empty v-if="!receiptCandidates.length" description="当前没有金额兜底候选" />`;
const wrapper =
`<template v-if="receiptCandidateStatus === 'PENDING'">
          <div v-if="receiptHighConf().length" class="conf-segment conf-segment--high"><span class="dot"></span><b>高置信度 ≥80%</b>（${'{{ receiptHighConf().length }}'} 条 · 建议一键确认）</div>
          <el-table v-if="receiptHighConf().length" :data="receiptHighConf()" stripe @selection-change="syncReceiptSelection('high', $event)">
            <el-table-column type="selection" width="44" />
            ${colsNoSel}
          </el-table>
          <div v-if="receiptMidConf().length" class="conf-segment conf-segment--mid"><span class="dot"></span><b>中置信度 60~79%</b>（${'{{ receiptMidConf().length }}'} 条 · 建议人工复核）</div>
          <el-table v-if="receiptMidConf().length" :data="receiptMidConf()" stripe @selection-change="syncReceiptSelection('mid', $event)">
            <el-table-column type="selection" width="44" />
            ${colsNoSel}
          </el-table>
          <div v-if="receiptLowConf().length" class="conf-segment conf-segment--low"><span class="dot"></span><b>低置信度 <60%</b>（${'{{ receiptLowConf().length }}'} 条 · 建议逐条核对）</div>
          <el-table v-if="receiptLowConf().length" :data="receiptLowConf()" stripe @selection-change="syncReceiptSelection('low', $event)">
            <el-table-column type="selection" width="44" />
            ${colsNoSel}
          </el-table>
        </template>
        <el-table v-else :data="receiptCandidates" stripe>
          ${colsNoSel}
        </el-table>
${emptyLine}`;
c = before + wrapper + after;
console.log("10 rc wrapper ok");

// 11) Confidence tags for receipt table rows — patch label 置信度 cell
const oldRcConf = `          <el-table-column label="置信度" width="90"
            ><template #default="{ row }">{{ row.confidence }}%</template></el-table-column
          >`;
const newRcConf = `          <el-table-column label="置信度" width="130"
            ><template #default="{ row }">
              {{ row.confidence }}%
              <el-tag v-if="row.confidence >= 80" type="success" size="small" effect="plain" style="margin-left:4px">高</el-tag>
              <el-tag v-else-if="row.confidence >= 60" type="warning" size="small" effect="plain" style="margin-left:4px">中</el-tag>
              <el-tag v-else type="info" size="small" effect="plain" style="margin-left:4px">低</el-tag>
            </template></el-table-column
          >`;
if (!c.includes(oldRcConf)) { console.error("rc conf cell missing"); process.exit(12); }
c = c.replace(oldRcConf, newRcConf); console.log("11 ok");

// 12) Style enhancement
const oldStyle = `.cell-sub {
  color: var(--color-text-muted);
  font-size: 12px;
  margin-top: 5px;
}`;
const newStyle = `.queue-bar {
  display: flex; flex-wrap: wrap; gap: 10px;
  margin-bottom: 14px; padding: 12px 14px; border-radius: var(--radius-lg);
  border: 1px solid var(--color-border); background: var(--color-surface);
}
.queue-chip {
  display: flex; align-items: center; gap: 6px; padding: 6px 12px;
  border-radius: 999px; border: 1px solid var(--color-border);
  cursor: pointer; color: #334155; font-size: 13px;
  background: #fff; transition: all 0.15s ease;
}
.queue-chip:hover { border-color: var(--color-primary); }
.queue-chip.active {
  border-color: var(--color-primary); color: #fff; background: var(--color-primary);
}
.queue-chip--muted { color: var(--color-text-muted); background: transparent; cursor: default; }
.queue-chip .q-num {
  min-width: 18px; padding: 0 6px; height: 18px; line-height: 18px; border-radius: 999px;
  background: var(--color-primary); color: #fff; font-size: 11px; font-weight: 700; text-align: center;
}
.queue-chip.active .q-num { background: #fff; color: var(--color-primary); }
.inline-badge :deep(.el-badge__content) { transform: translateX(6px) translateY(-6px); }
.batch-bar {
  display: flex; align-items: center; flex-wrap: wrap; gap: 10px;
  padding: 10px 14px; border-radius: var(--radius-lg);
  background: linear-gradient(180deg, #f8fafc, #fff);
  border: 1px solid var(--color-border); margin-bottom: 12px;
}
.batch-bar .batch-count { font-size: 13px; color: #475569; }
.batch-bar .batch-count b { color: var(--color-primary); font-weight: 700; }
.batch-bar .batch-count em { color: #16a34a; font-style: normal; font-weight: 700; }
.batch-bar .batch-sep { width: 1px; height: 16px; background: var(--color-border); margin: 0 4px; }
.conf-segment {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px; margin: 18px 0 8px; border-radius: 8px;
  font-size: 13px; color: #334155;
  border-left: 3px solid var(--color-border); background: #f8fafc;
}
.conf-segment b { font-weight: 700; }
.conf-segment .dot { width: 8px; height: 8px; border-radius: 999px; background: #cbd5e1; }
.conf-segment--high { border-left-color: #16a34a; background: #f0fdf4; color: #14532d; }
.conf-segment--high .dot { background: #16a34a; }
.conf-segment--mid { border-left-color: #f59e0b; background: #fffbeb; color: #78350f; }
.conf-segment--mid .dot { background: #f59e0b; }
.conf-segment--low { border-left-color: #94a3b8; background: #f8fafc; color: #334155; }
.conf-segment--low .dot { background: #94a3b8; }
.confidence.high span { background: #16a34a; }
.confidence.mid span { background: #f59e0b; }
.confidence.low span { background: #94a3b8; }
.cell-sub {
  color: var(--color-text-muted);
  font-size: 12px;
  margin-top: 5px;
}`;
if (!c.includes(oldStyle)) { console.error("style anchor missing"); process.exit(13); }
c = c.replace(oldStyle, newStyle); console.log("12 ok");

fs.writeFileSync(p, c, "utf8");
console.log("DONE MatchingCenterView patched");
