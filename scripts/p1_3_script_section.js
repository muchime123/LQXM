const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/views/governance/MatchingCenterView.vue";
let c = fs.readFileSync(p, "utf8");

// Step 1: SCRIPT imports/head
const OLD = `<script setup lang="ts">
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
const NEW = `<script setup lang="ts">
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
type ValidTab = typeof VALID_TABS[number]
const activeTab = ref<ValidTab>('production-outbound')`;
if (!c.includes(OLD)) { console.error("step1 anchor missing"); process.exit(1); }
c = c.replace(OLD, NEW); console.log("1 head ok");

// Step 2: confidence helpers + selection + selectByConfidence before `toggleSelectAll` block
const OLD2 = `const selectedCandidates = ref<MatchCandidate[]>([]), batchDeciding = ref(false)
function toggleSelectAll() {
  selectedCandidates.value = selectedCandidates.value.length === candidates.value.length ? [] : [...candidates.value]
}`;
const NEW2 = `const selectedCandidates = ref<MatchCandidate[]>([]), batchDeciding = ref(false)
function confidenceLevel(v: number) { return v >= 80 ? 'high' : v >= 60 ? 'mid' : 'low' }
function filterByConf<T extends { confidence?: number }>(arr: T[], minInc: number | null, maxExc: number | null): T[] {
  return arr.filter(r => {
    const v = Number(r.confidence ?? 0)
    return (minInc == null || v >= minInc) && (maxExc == null || v < maxExc)
  })
}
function selectByRange<T extends { id: number }>(list: T[], selectedRef: { value: T[] }, minInc: number | null, maxExc: number | null, getConf: (t: T) => number) {
  const sel = list.filter(r => {
    const v = getConf(r)
    return (minInc == null || v >= minInc) && (maxExc == null || v < maxExc)
  })
  const allIn = sel.length > 0 && sel.every(r => selectedRef.value.some(s => s.id === r.id))
  if (allIn) {
    const off = new Set(sel.map(r => r.id))
    selectedRef.value = selectedRef.value.filter(s => !off.has(s.id))
    return
  }
  const m = new Map(selectedRef.value.map(s => [s.id, s]))
  sel.forEach(r => m.set(r.id, r))
  selectedRef.value = Array.from(m.values())
}
const highConfCandidates = () => filterByConf(candidates.value, 80, null)
const midConfCandidates  = () => filterByConf(candidates.value, 60, 80)
const lowConfCandidates  = () => filterByConf(candidates.value, null, 60)
const selectedHighCount = () => filterByConf(selectedCandidates.value, 80, null).length
function selectByConfidence(minInc: number | null, maxExc: number | null) {
  selectByRange(candidates.value, selectedCandidates, minInc, maxExc, (r: MatchCandidate) => Number(r.confidence ?? 0))
}
function toggleSelectAll() {
  selectedCandidates.value = selectedCandidates.value.length === candidates.value.length ? [] : [...candidates.value]
}`;
if (!c.includes(OLD2)) { console.error("step2 missing"); process.exit(2); }
c = c.replace(OLD2, NEW2); console.log("2 helpers ok");

// Step 3: patch batchDecide notify output
const OLD3 = "    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed)";
const NEW3 = "    const _rh = filterByConf(selectedCandidates.value, 80, null).length\n    const _suf = _rh ? '（高置信度 ' + _rh + ' 条）' : ''\n    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + _suf)";
if (!c.includes(OLD3)) { console.error("step3 anchor missing"); process.exit(3); }
c = c.replace(OLD3, NEW3); console.log("3 prod notify ok");

// Step 4: Receipt state + helpers + batchDecideReceipt + syncReceiptSelection BEFORE loadReceiptCandidates
const OLD4 = `const receiptCandidates = ref<ReceiptMatchCandidate[]>([]), receiptCandidateStatus = ref('PENDING')
async function loadReceiptCandidates() {`;
const NEW4 = `const receiptCandidates = ref<ReceiptMatchCandidate[]>([]), receiptCandidateStatus = ref('PENDING')
const selectedReceiptCandidates = ref<ReceiptMatchCandidate[]>([]), receiptBatchDeciding = ref(false)
const receiptHighConf = () => filterByConf(receiptCandidates.value, 80, null)
const receiptMidConf  = () => filterByConf(receiptCandidates.value, 60, 80)
const receiptLowConf  = () => filterByConf(receiptCandidates.value, null, 60)
function toggleReceiptSelectAll() {
  selectedReceiptCandidates.value = selectedReceiptCandidates.value.length === receiptCandidates.value.length ? [] : [...receiptCandidates.value]
}
function selectReceiptByConfidence(minInc: number | null, maxExc: number | null) {
  selectByRange(receiptCandidates.value, selectedReceiptCandidates, minInc, maxExc, (r: ReceiptMatchCandidate) => Number(r.confidence ?? 0))
}
function syncReceiptSelection(seg: 'high' | 'mid' | 'low', rows: ReceiptMatchCandidate[]) {
  const minMax = seg === 'high' ? [80, null] as const : seg === 'mid' ? [60, 80] as const : [null, 60] as const
  const segAll = filterByConf(receiptCandidates.value, minMax[0], minMax[1])
  const segIds = new Set(segAll.map(r => r.id))
  const kept = selectedReceiptCandidates.value.filter(r => !segIds.has(r.id))
  const byId = new Map<number, ReceiptMatchCandidate>()
  ;[...kept, ...rows].forEach(r => byId.set(r.id, r))
  selectedReceiptCandidates.value = Array.from(byId.values())
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
    const r: any = await batchDecideReceiptMatchCandidates(selectedReceiptCandidates.value.map((c: ReceiptMatchCandidate) => c.id), confirmed)
    const _rh = filterByConf(selectedReceiptCandidates.value, 80, null).length
    const _rsuf = _rh ? '（高置信度 ' + _rh + ' 条）' : ''
    notify.success('批量' + (confirmed ? '确认' : '驳回') + '：成功 ' + r.success + '，失败 ' + r.failed + _rsuf)
    await loadReceiptCandidates()
    selectedReceiptCandidates.value = []
  } catch (e: any) { notify.error(e?.message || '批量操作失败') }
  finally { receiptBatchDeciding.value = false }
}
async function loadReceiptCandidates() {`;
if (!c.includes(OLD4)) { console.error("step4 anchor missing"); process.exit(4); }
c = c.replace(OLD4, NEW4); console.log("4 receipt state ok");

// Step 5: Clear selection on reload
const OLD5 = `async function loadReceiptCandidates() {
  try { receiptCandidates.value = await fetchReceiptMatchCandidates(receiptCandidateStatus.value) }
  catch (e: any) { notify.error(e.message || '收款候选加载失败') }
}`;
const NEW5 = `async function loadReceiptCandidates() {
  try { receiptCandidates.value = await fetchReceiptMatchCandidates(receiptCandidateStatus.value) }
  catch (e: any) { notify.error(e.message || '收款候选加载失败') }
  finally { selectedReceiptCandidates.value = [] }
}`;
if (!c.includes(OLD5)) { console.error("step5 anchor missing"); process.exit(5); }
c = c.replace(OLD5, NEW5); console.log("5 loadReceipt ok");

// Step 6: onMounted → route.query.tab sync
const OLD6 = `onMounted(() => { loadCandidates(); loadMatchFeatures(); loadReceiptCandidates() })`;
const NEW6 = `onMounted(() => {
  const t = String(route.query.tab || '') as ValidTab | ''
  if (VALID_TABS.includes(t as ValidTab)) activeTab.value = t as ValidTab
  nextTick(() => { loadCandidates(); loadMatchFeatures(); loadReceiptCandidates() })
})`;
if (!c.includes(OLD6)) { console.error("step6 mount missing"); process.exit(6); }
c = c.replace(OLD6, NEW6); console.log("6 mount ok");

// Step 7: Prod batch bar upgrade
const OLD7 = `        <div v-if="candidateStatus === 'PENDING'" class="batch-bar">
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
const NEW7 = `        <div v-if="candidateStatus === 'PENDING'" class="batch-bar">
          <el-button link type="primary" @click="toggleSelectAll">全选</el-button>
          <el-button link type="success" :disabled="!highConfCandidates().length" @click="selectByConfidence(80, null)">≥80% 一键勾选（{{ highConfCandidates().length }}）</el-button>
          <el-button link :disabled="!midConfCandidates().length" @click="selectByConfidence(60, 80)">60~79%（{{ midConfCandidates().length }}）</el-button>
          <el-button link type="warning" :disabled="!lowConfCandidates().length" @click="selectByConfidence(null, 60)"><60%（{{ lowConfCandidates().length }}）</el-button>
          <span class="batch-sep"></span>
          <span class="batch-count">已选 <b>{{ selectedCandidates.length }}</b> 条<template v-if="selectedHighCount()"> · 高置信 <em>{{ selectedHighCount() }}</em></template></span>
          <el-button size="small" type="primary" :disabled="!selectedCandidates.length" :loading="batchDeciding" @click="batchDecide(true)">批量确认</el-button>
          <el-button size="small" type="danger" plain :disabled="!selectedCandidates.length" :loading="batchDeciding" @click="batchDecide(false)">批量驳回</el-button>
        </div>`;
if (!c.includes(OLD7)) { console.error("step7 batch-bar missing"); process.exit(7); }
c = c.replace(OLD7, NEW7); console.log("7 prod bar ok");

// Step 8: Prod candidate confidence cell with level tag & colored bar
const OLD8 = `              <div class="confidence"><span :style="{ width: Math.max(4, row.confidence) + '%' }"></span></div>
              <small>{{ row.confidence }}% 置信度</small></template`;
const NEW8 = `              <div class="confidence" :class="['conf--' + confidenceLevel(row.confidence)]"><span :style="{ width: Math.max(4, row.confidence) + '%' }"></span></div>
              <small>{{ row.confidence }}% 置信度
                <el-tag v-if="row.confidence >= 80" type="success" size="small" effect="plain" style="margin-left:4px">高</el-tag>
                <el-tag v-else-if="row.confidence >= 60" type="warning" size="small" effect="plain" style="margin-left:4px">中</el-tag>
                <el-tag v-else type="info" size="small" effect="plain" style="margin-left:4px">低</el-tag>
              </small></template`;
if (!c.includes(OLD8)) { console.error("step8 prod conf cell missing"); process.exit(8); }
c = c.replace(OLD8, NEW8); console.log("8 prod conf ok");

// Step 12: Style — insert batch-bar / segment / badge / conf classes
const OLD_ST = `.cell-sub {
  color: var(--color-text-muted);
  font-size: 12px;
  margin-top: 5px;
}`;
const NEW_ST = `.batch-bar {
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
.conf-segment--mid  { border-left-color: #f59e0b; background: #fffbeb; color: #78350f; }
.conf-segment--mid  .dot { background: #f59e0b; }
.conf-segment--low  { border-left-color: #94a3b8; background: #f8fafc; color: #334155; }
.conf-segment--low  .dot { background: #94a3b8; }
.confidence.conf--high span { background: #16a34a; }
.confidence.conf--mid  span { background: #f59e0b; }
.confidence.conf--low  span { background: #94a3b8; }
.inline-badge :deep(.el-badge__content) { transform: translateX(6px) translateY(-6px); }
.cell-sub {
  color: var(--color-text-muted);
  font-size: 12px;
  margin-top: 5px;
}`;
if (!c.includes(OLD_ST)) { console.error("step12 style anchor missing"); process.exit(12); }
c = c.replace(OLD_ST, NEW_ST); console.log("12 styles ok");

fs.writeFileSync(p, c, "utf8");
console.log("ALL SCRIPT STEPS DONE");
