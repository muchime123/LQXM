const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/views/governance/MatchingCenterView.vue";
let c = fs.readFileSync(p, "utf8");

// Step 1: SCRIPT imports/head + route/tab types + batchDecideReceiptMatchCandidates import
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

// Step 2: Insert confidence/selection helpers BEFORE toggleSelectAll.
const OLD2 = `const selectedCandidates = ref<MatchCandidate[]>([]), batchDeciding = ref(false)
function toggleSelectAll() {
  selectedCandidates.value = selectedCandidates.value.length === candidates.value.length ? [] : [...candidates.value]
}`;
const NEW2 = `const selectedCandidates = ref<MatchCandidate[]>([]), batchDeciding = ref(false)
function confidenceLevel(v: number): 'high' | 'mid' | 'low' { return v >= 80 ? 'high' : v >= 60 ? 'mid' : 'low' }
function filterByConf<T extends { confidence?: number }>(arr: T[], minInc: number | null, maxExc: number | null): T[] {
  return arr.filter(r => {
    const v = Number(r.confidence ?? 0)
    return (minInc == null || v >= minInc) && (maxExc == null || v < maxExc)
  })
}
function selectByRange<T extends { id: number }>(
  list: T[],
  selectedRef: { value: T[] },
  minInc: number | null,
  maxExc: number | null,
  getConf: (t: T) => number
) {
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
  selectByRange(candidates.value, selectedCandidates as unknown as { value: (MatchCandidate & { id: number })[] }, minInc, maxExc, (r) => Number(r.confidence ?? 0))
}
function toggleSelectAll() {
  selectedCandidates.value = selectedCandidates.value.length === candidates.value.length ? [] : [...candidates.value]
}`;
if (!c.includes(OLD2)) { console.error("step2 missing"); process.exit(2); }
c = c.replace(OLD2, NEW2); console.log("2 helpers ok");

fs.writeFileSync(p, c, "utf8");
console.log("steps 1 & 2 done");
