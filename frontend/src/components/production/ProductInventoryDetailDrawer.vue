<template>
  <el-drawer v-model="visible" :title="drawerTitle" size="min(880px, 92vw)" direction="rtl">
    <div v-loading="loading" class="inv-detail">
      <el-empty v-if="!loading && !detail" :description="error || '未加载到库存详情'" :image-size="80" />
      <template v-else-if="detail && row">
        <!-- 头部：产品身份 + 本行仓库 -->
        <div class="detail-head">
          <div class="detail-head__left">
            <b class="detail-head__main">{{ row.productName || row.productCode }}</b>
            <el-tag v-if="row.isCustomized" size="small" type="warning" effect="plain">非标</el-tag>
            <el-tag v-if="row.codeConflict" size="small" type="danger" effect="plain">编号冲突</el-tag>
          </div>
          <div class="detail-head__sub">
            编号 {{ row.productCode }} · 仓库 {{ row.warehouseName || '未记录' }}
          </div>
        </div>

        <!-- 数据口径告警：这三类是本行金额不可信的根因，直接摊在详情顶部 -->
        <el-alert
          v-if="!detail.catalogSource"
          class="detail-hint"
          type="warning"
          :closable="false"
          show-icon
          title="产品信息台账里找不到该产品"
          description="仓库明细有这个产品，但按编号、按产品名称都没能在产品信息台账里匹配到对应行：数量有效，但采购价缺失、库存金额无法计算。在氚云补全产品信息后重新导入产品信息台账即可。"
        />
        <el-alert
          v-else-if="!row.purchasePrice"
          class="detail-hint"
          type="warning"
          :closable="false"
          show-icon
          title="采购价缺失"
          description="产品信息已匹配上，但该产品的采购价为空或 0，本行库存金额为空。补价后重新导入产品信息台账。"
        />
        <el-alert
          v-if="row.codeConflict"
          class="detail-hint"
          type="warning"
          :closable="false"
          show-icon
          :title="conflictTitle"
          :description="conflictDescription"
        />

        <!-- 区块一：本行库存概览 -->
        <section class="detail-sec">
          <h4 class="detail-sec__title">库存概览</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="仓库">
              {{ row.warehouseName || '未记录（仓库明细无此产品）' }}
            </el-descriptions-item>
            <el-descriptions-item label="类目">{{ row.category || '—' }}</el-descriptions-item>
            <el-descriptions-item label="产品编号">{{ row.productCode }}</el-descriptions-item>
            <el-descriptions-item label="单位">{{ row.unit || '—' }}</el-descriptions-item>
            <el-descriptions-item label="产品名称" :span="2">{{ row.productName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="规格" :span="2">{{ row.specification || '—' }}</el-descriptions-item>
            <el-descriptions-item label="库存数量">
              <span :class="{ 'qty-negative': Number(row.quantity) < 0 }">{{ fmtNum(row.quantity) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="采购价">{{ money(row.purchasePrice) }}</el-descriptions-item>
            <el-descriptions-item label="库存金额" :span="2">
              <span class="amt-strong">{{ money(row.stockAmount) }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <p class="sec-note">
            本行 = 仓库明细（仓库 × 数量）× 产品信息台账（名称 / 规格 / 单位 / 采购价）的合并结果；
            库存金额 = 数量 × 采购价，是计算值而非台账原值。
          </p>
        </section>

        <!-- 区块二：两份来源台账行 -->
        <section class="detail-sec">
          <h4 class="detail-sec__title">来源台账</h4>
          <div class="source-grid">
            <div class="source-card">
              <div class="source-card__head">
                <span class="source-card__name">产品信息台账</span>
                <el-button
                  v-if="detail.catalogSource"
                  link
                  type="primary"
                  size="small"
                  @click="openLedger('product')"
                >台账详情 →</el-button>
              </div>
              <el-descriptions v-if="detail.catalogSource" :column="1" border size="small">
                <el-descriptions-item label="名称">{{ detail.catalogSource.productName || '—' }}</el-descriptions-item>
                <el-descriptions-item label="规格">{{ detail.catalogSource.specification || '—' }}</el-descriptions-item>
                <el-descriptions-item label="单位">{{ detail.catalogSource.unit || '—' }}</el-descriptions-item>
                <el-descriptions-item label="采购价">{{ money(detail.catalogSource.purchasePrice) }}</el-descriptions-item>
                <el-descriptions-item label="导入批次">{{ detail.catalogSource.batchId || '—' }}</el-descriptions-item>
                <el-descriptions-item label="源创建时间">{{ fmtTime(detail.catalogSource.sourceCreatedAt) }}</el-descriptions-item>
                <el-descriptions-item label="源修改时间">{{ fmtTime(detail.catalogSource.sourceModifiedAt) }}</el-descriptions-item>
                <el-descriptions-item label="创建人">{{ detail.catalogSource.createdByName || '—' }}</el-descriptions-item>
                <el-descriptions-item label="入库时间">{{ fmtTime(detail.catalogSource.createdAt) }}</el-descriptions-item>
                <el-descriptions-item label="记录 ID">{{ detail.catalogSource.id }}</el-descriptions-item>
              </el-descriptions>
              <p v-else class="source-card__empty">该编号不在产品信息台账 —— 采购价缺失，库存金额无法计算</p>
            </div>

            <div class="source-card">
              <div class="source-card__head">
                <span class="source-card__name">仓库明细</span>
                <el-button
                  v-if="detail.stockSource"
                  link
                  type="primary"
                  size="small"
                  @click="openLedger('stock')"
                >台账详情 →</el-button>
              </div>
              <el-descriptions v-if="detail.stockSource" :column="1" border size="small">
                <el-descriptions-item label="仓库">{{ detail.stockSource.warehouse || '—' }}</el-descriptions-item>
                <el-descriptions-item label="数量">{{ fmtNum(detail.stockSource.quantity) }}</el-descriptions-item>
                <el-descriptions-item label="规格">{{ detail.stockSource.specification || '—' }}</el-descriptions-item>
                <el-descriptions-item label="单位">{{ detail.stockSource.unit || '—' }}</el-descriptions-item>
                <el-descriptions-item label="导入批次">{{ detail.stockSource.batchId || '—' }}</el-descriptions-item>
                <el-descriptions-item label="源创建时间">{{ fmtTime(detail.stockSource.sourceCreatedAt) }}</el-descriptions-item>
                <el-descriptions-item label="源修改时间">{{ fmtTime(detail.stockSource.sourceModifiedAt) }}</el-descriptions-item>
                <el-descriptions-item label="创建人">{{ detail.stockSource.createdByName || '—' }}</el-descriptions-item>
                <el-descriptions-item label="入库时间">{{ fmtTime(detail.stockSource.createdAt) }}</el-descriptions-item>
                <el-descriptions-item label="记录 ID">{{ detail.stockSource.id }}</el-descriptions-item>
              </el-descriptions>
              <p v-else class="source-card__empty">仓库明细无此产品 —— 本行只有产品信息台账的身份与价格</p>
            </div>
          </div>
        </section>

        <!-- 区块三：同编号跨仓库分布（台账单行看不到的产品全貌） -->
        <section class="detail-sec">
          <h4 class="detail-sec__title">
            该产品跨仓库分布
            <span class="detail-sec__count">{{ detail.spreadTotal.rowCount }}</span>
          </h4>
          <el-table v-if="detail.spread.length" :data="detail.spread" size="small" border max-height="320">
            <el-table-column label="仓库" min-width="150">
              <template #default="{ row: r }">
                <span v-if="r.warehouseName">{{ r.warehouseName }}</span>
                <span v-else class="muted">未记录</span>
                <el-tag v-if="isCurrent(r)" size="small" effect="plain" class="cur-tag">本行</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row: r }">{{ fmtNum(r.quantity) }}</template>
            </el-table-column>
            <el-table-column label="采购价" width="110" align="right">
              <template #default="{ row: r }">{{ money(r.purchasePrice) }}</template>
            </el-table-column>
            <el-table-column label="库存金额" width="130" align="right">
              <template #default="{ row: r }">{{ money(r.stockAmount) }}</template>
            </el-table-column>
          </el-table>
          <p v-else class="sec-note">该产品当前没有非零库存记录（本行数量为 0 或负数）。</p>
          <p v-if="detail.spread.length" class="spread-total">
            {{ detail.spreadTotal.rowCount }} 条有货记录 / {{ detail.spreadTotal.warehouseCount }} 个仓库 ·
            金额合计 <b>{{ money(detail.spreadTotal.totalAmount) }}</b>
            <span class="muted">（数量跨单位不相加，故只合计金额）</span>
          </p>
        </section>

        <!-- 区块四：更新明细（按来源台账行回放历次批次的新增/更新；数据源=已确认批次保留的比对结果） -->
        <section class="detail-sec">
          <h4 class="detail-sec__title">
            更新明细
            <span v-if="!historyLoading" class="detail-sec__count">{{ totalHistoryCount }}</span>
          </h4>
          <div v-loading="historyLoading" class="history-body">
            <template v-if="totalHistoryCount">
              <div v-for="grp in historyGroups" :key="grp.key" class="history-group">
                <div class="history-group__head">
                  {{ grp.label }}<span class="muted">（{{ grp.entries.length }} 条）</span>
                </div>
                <div class="history-list">
                  <div v-for="(h, hi) in grp.entries" :key="hi" class="history-item">
                    <div class="history-item__head">
                      <el-tag size="small" :type="h.action === 'CREATE' ? 'success' : 'warning'">
                        {{ h.action === 'CREATE' ? '新增' : '更新' }}
                      </el-tag>
                      <span class="history-batch">批次 #{{ h.batchId }}</span>
                      <span class="history-file" :title="h.fileName || ''">{{ h.fileName || '—' }}</span>
                      <span class="history-time">{{ fmtTime(h.batchTime) }}</span>
                    </div>
                    <div v-if="h.changeItems?.length" class="history-changes">
                      <div v-for="(c, ci) in h.changeItems" :key="ci" class="history-change">
                        <b>{{ c.path }}</b>
                        <span class="history-change__old">{{ c.oldValue ?? '空' }}</span>
                        <span class="history-change__arrow">→</span>
                        <span class="history-change__new">{{ c.newValue ?? '空' }}</span>
                      </div>
                    </div>
                    <div v-else-if="h.changes" class="history-changes">{{ h.changes }}</div>
                  </div>
                </div>
              </div>
            </template>
            <p v-else-if="!historyLoading" class="sec-note">
              没有更新明细：该行只导入过一次、之后各批次无变化（未变化的行不入史）。
            </p>
          </div>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchProductInventoryRowDetail,
  type InventoryRowDetail,
  type InventorySpreadRow,
  type ProductInventoryBalance
} from '@/api/productInventory'
import { fetchLedgerRowHistory, type LedgerRowHistoryEntry } from '@/api/business/orders'

const props = defineProps<{ modelValue: boolean; row: ProductInventoryBalance | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()
const router = useRouter()

const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const row = computed(() => props.row)

const detail = ref<InventoryRowDetail | null>(null)
const loading = ref(false)
const error = ref('')
const historyLoading = ref(false)
const catalogHistory = ref<LedgerRowHistoryEntry[]>([])
const stockHistory = ref<LedgerRowHistoryEntry[]>([])

const drawerTitle = computed(() =>
  props.row ? `库存详情 · ${props.row.productName || props.row.productCode}` : '库存详情'
)

// 氚云侧是按产品名称关联的，编号是人工填写的文本（写错不影响氚云自身流转）。
// 编号与名称所指产品不一致时以名称匹配为准，这里把差异摊开，让人回氚云订正。
const codeRewritten = computed(() =>
  !!props.row?.resolvedCode && props.row.resolvedCode !== props.row.productCode
)
const conflictTitle = computed(() => codeRewritten.value ? '氚云录入的编号与该行名称不符' : '该编号在两张台账里指向不同产品')
const conflictDescription = computed(() => codeRewritten.value
  ? `氚云按产品名称关联，编号是人工填写的文本。该行名称在产品信息里对应编号「${props.row?.resolvedCode}」，`
    + '采购价 / 类目已按它取用；建议在氚云订正编号。'
  : '编号撞号：本行名称取自仓库明细、采购价取自产品信息台账里同编号的另一个产品，金额仅供参考，请人工核对。')

const historyGroups = computed(() =>
  [
    { key: 'product', label: '产品信息台账', entries: catalogHistory.value },
    { key: 'stock', label: '仓库明细台账', entries: stockHistory.value }
  ].filter(g => g.entries.length)
)
const totalHistoryCount = computed(() => catalogHistory.value.length + stockHistory.value.length)

/** 分布表中标记「当前查看的这一行」：仓库 + 单位共同定位 */
const isCurrent = (r: InventorySpreadRow) =>
  !!props.row && r.warehouseId === props.row.warehouseId && r.unit === props.row.unit

const money = (v: unknown) =>
  v == null || v === ''
    ? '—'
    : `¥${Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const fmtNum = (v: unknown) => Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
const fmtTime = (v: unknown) => {
  if (!v) return '—'
  const s = String(v).replace('T', ' ')
  return s.length > 19 ? s.slice(0, 19) : s
}

async function load() {
  const r = props.row
  if (!r) return
  loading.value = true
  error.value = ''
  detail.value = null
  catalogHistory.value = []
  stockHistory.value = []
  try {
    const d = await fetchProductInventoryRowDetail({
      warehouseId: r.warehouseId,
      productCode: r.productCode,
      unit: r.unit
    })
    detail.value = d
    loading.value = false
    void loadHistory(d)
  } catch (e) {
    error.value = (e as Error).message || '库存详情加载失败'
    loading.value = false
  }
}

/** 更新明细：两个来源台账行各自按业务键回放（任一侧失败只影响该分组，不阻断详情） */
async function loadHistory(d: InventoryRowDetail) {
  const jobs: Array<Promise<void>> = []
  if (d.catalogSource?.id) {
    jobs.push(
      fetchLedgerRowHistory('product', d.catalogSource.id)
        .then(res => { catalogHistory.value = res.entries ?? [] })
        .catch(() => { catalogHistory.value = [] })
    )
  }
  if (d.stockSource?.id) {
    jobs.push(
      fetchLedgerRowHistory('stock', d.stockSource.id)
        .then(res => { stockHistory.value = res.entries ?? [] })
        .catch(() => { stockHistory.value = [] })
    )
  }
  if (!jobs.length) return
  historyLoading.value = true
  try {
    await Promise.all(jobs)
  } finally {
    historyLoading.value = false
  }
}

/** 跳转氚云导入中心台账，并以本行编号作初始搜索词定位 */
function openLedger(kind: 'product' | 'stock') {
  const r = props.row
  if (!r) return
  visible.value = false
  router.push({ path: '/h3-imports', query: { tab: 'ledger', ledger: kind, keyword: r.productCode } })
}

watch(
  () => [props.modelValue, props.row?.productCode, props.row?.warehouseId, props.row?.unit],
  () => { if (props.modelValue) void load() }
)
</script>

<style scoped>
.inv-detail { display: flex; flex-direction: column; gap: 14px; min-width: 0; }

.detail-head {
  display: flex; flex-direction: column; gap: 4px;
  padding-bottom: 10px; border-bottom: 1px solid var(--color-border, #e2e8f0);
}
.detail-head__left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.detail-head__main { font-size: 16px; color: var(--color-text, #1f2937); }
.detail-head__sub { font-size: 12px; color: var(--color-text-muted, #64748b); }

.detail-hint { margin: 0; }

.detail-sec { display: flex; flex-direction: column; gap: 8px; }
.detail-sec__title {
  display: flex; align-items: center; gap: 8px; margin: 0;
  font-size: 14px; font-weight: 600; color: var(--color-text, #1f2937);
}
.detail-sec__count {
  font-size: 12px; font-weight: 500; color: var(--color-text-muted, #64748b);
  background: var(--color-fill, #f1f5f9); border-radius: 10px; padding: 1px 8px;
}
.sec-note { font-size: 12px; color: var(--color-text-muted, #64748b); margin: 0; line-height: 1.7; }

.source-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.source-card {
  border: 1px solid var(--color-border, #e2e8f0); border-radius: 10px;
  padding: 10px 12px; min-width: 0;
}
.source-card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.source-card__name { font-size: 13px; font-weight: 600; color: var(--color-text, #1f2937); }
.source-card__empty { font-size: 12px; color: var(--color-warning, #d97706); margin: 0; line-height: 1.7; }

.amt-strong { font-weight: 600; color: var(--color-primary, #0f766e); }
.muted { color: var(--color-text-muted, #94a3b8); }
.qty-negative { color: var(--color-danger, #dc2626); font-weight: 600; }
.cur-tag { margin-left: 6px; }
.spread-total { font-size: 12px; color: var(--color-text, #1f2937); margin: 0; }
.spread-total b { color: var(--color-primary, #0f766e); }

.history-body { min-height: 40px; }
.history-group + .history-group { margin-top: 12px; }
.history-group__head { font-size: 13px; font-weight: 600; margin-bottom: 6px; color: var(--color-text, #1f2937); }
.history-list { display: flex; flex-direction: column; gap: 8px; }
.history-item {
  border: 1px solid var(--color-border, #e2e8f0); border-radius: 8px; padding: 8px 10px;
  background: var(--color-surface-alt, #f8fafc);
}
.history-item__head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; }
.history-batch { color: var(--color-text, #1f2937); font-weight: 500; }
.history-file { color: var(--color-text-muted, #64748b); max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-time { color: var(--color-text-muted, #64748b); margin-left: auto; }
.history-changes { margin-top: 6px; font-size: 12px; color: var(--color-text, #1f2937); line-height: 1.8; }
.history-change { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.history-change__old { color: var(--color-text-muted, #94a3b8); text-decoration: line-through; }
.history-change__arrow { color: var(--color-border, #cbd5e1); }
.history-change__new { color: var(--color-success, #16a34a); font-weight: 600; }

@media (max-width: 768px) {
  .source-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
