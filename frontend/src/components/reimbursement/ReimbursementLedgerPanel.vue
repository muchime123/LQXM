<template>
  <ListPageLayout class="reimb-ledger-panel" content-label="日常报销单列表">
    <template #summary>
      <MetricGrid :columns="3">
        <MetricCard
          title="累计有效报销单"
          :value="stats === null ? '—' : formatInt(stats.totalValidClaims)"
          unit="单"
          hint="统计口径：已通过 + 审批中，排除拒绝 / 撤销 / 终止"
          :loading="statsLoading"
        />
        <MetricCard
          title="本月核定报销额"
          :value="stats === null ? '—' : formatMoney(stats.thisMonthAmount)"
          unit="元"
          hint="当前自然月已通过报销的核定金额合计"
          tone="success"
          :loading="statsLoading"
        />
        <MetricCard
          title="待员工名册匹配"
          :value="stats === null ? '—' : formatInt(stats.awaitingMappingCount)"
          unit="单"
          :hint="
            stats && stats.awaitingMappingCount > 0
              ? '提单人姓名未在员工名册中匹配到，可在员工名册完善后重新同步 / 导入'
              : '报销单提单人均已对上员工名册'
          "
          :tone="stats && stats.awaitingMappingCount > 0 ? 'warning' : 'default'"
          :loading="statsLoading"
        />
      </MetricGrid>
    </template>

    <template #notice>
      <el-alert
        v-if="stats && stats.awaitingMappingCount > 0"
        type="warning"
        :closable="false"
        show-icon
        title="部分报销单尚未匹配到员工名册"
        description="这类报销单的提单人列显示为「未映射」，按人追溯会缺口径。请核对员工名册中是否已有该员工（姓名需与钉钉一致），补齐后切换到「钉钉同步」页签重新同步或导入即可自动对上。"
      />
    </template>

    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        search-placeholder="报销单号 / 标题 / 提单人"
        search-mode="submit"
        query-button
        :fields="toolbarFields"
        :field-values="filters"
        :batch-enabled="false"
        @search="applyFilters"
        @filter-change="handleToolbarFilterChange"
        @clear="handleResetFilters"
      >
        <template #actions>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="发生日期起"
            end-placeholder="发生日期止"
            value-format="YYYY-MM-DD"
            style="width: 270px"
            @change="applyFilters"
          />
        </template>
      </SearchToolbar>
    </template>

    <DataState
      :state="dataState"
      :skeleton="dataState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="10"
      :refreshing="loading && rows.length > 0"
      error-title="报销台账加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的数据。"
      empty-text="暂无报销数据"
      empty-hint="数据入口在本页「钉钉同步」页签：可手动触发「日常报销」审批同步，或上传 OA 后台导出的 Excel。"
      filtered-empty-text="没有符合条件的数据"
      filtered-empty-hint="请调整关键字或筛选条件后重试。"
      @retry="loadList"
    >
      <LqTable
        :data="rows"
        row-key="id"
        density="compact"
        class="reimb-ledger-table"
        @row-click="openDetail"
      >
        <el-table-column label="单号" width="190">
          <template #default="{ row }"><span class="mono">{{ row.claimNo || '—' }}</span></template>
        </el-table-column>
        <el-table-column
          label="标题"
          min-width="220"
          show-overflow-tooltip
          prop="title"
        />
        <el-table-column label="提单人" width="110">
          <template #default="{ row }">
            <span v-if="row.submitterName">{{ row.submitterName }}</span>
            <el-tooltip v-else content="员工名册未匹配到该姓名" placement="top">
              <el-tag size="small" type="warning" effect="plain">未映射</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="部门" width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.submitterDepartment || '—' }}</template>
        </el-table-column>
        <el-table-column label="费用分类" width="110">
          <template #default="{ row }">{{ row.expenseCategory || '—' }}</template>
        </el-table-column>
        <el-table-column label="审批状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="dark" :type="statusTagType(row.approvalStatus)">
              {{ row.approvalStatus || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发生日" width="120" align="center">
          <template #default="{ row }">
            {{ shortDate(row.claimDate) || (row.createdAt ? shortDate(row.createdAt) : '—') }}
          </template>
        </el-table-column>
        <el-table-column label="审批完成日" width="120" align="center">
          <template #default="{ row }">{{ shortDate(row.approvalFinishedDate) || '—' }}</template>
        </el-table-column>
        <el-table-column label="申请金额" width="130" align="right">
          <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="核定金额" width="130" align="right">
          <template #default="{ row }">
            <b class="text-success">{{ formatMoney(row.approvedAmount ?? row.totalAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
      </LqTable>
    </DataState>

    <template #pagination>
      <LqPagination :pagination="pagination" @change="loadList" />
    </template>

    <!-- ==================== 报销单明细抽屉（只读） ==================== -->
    <el-drawer
      v-model="detailVisible"
      size="min(920px, 96vw)"
      destroy-on-close
    >
      <template #header="{ close, titleId, titleClass }">
        <div class="ledger-drawer__header">
          <div>
            <h3 :id="titleId" :class="titleClass" class="ledger-drawer__claim">
              <span class="mono">{{ detail ? detail.claimNo : '报销单明细' }}</span>
            </h3>
            <p v-if="detail" class="ledger-drawer__title">{{ detail.title }}</p>
          </div>
          <el-button
            :icon="Close"
            circle
            text
            :aria-label="'关闭'"
            @click="close"
          />
        </div>
      </template>

      <template v-if="detailLoading">
        <div class="ledger-drawer__center">
          <el-icon class="is-loading" :size="22"><Loading /></el-icon>
          正在加载报销单明细…
        </div>
      </template>
      <template v-else-if="detailError">
        <div class="ledger-drawer__center ledger-drawer__center--error">
          <p>{{ detailError }}</p>
          <el-button
            type="primary"
            plain
            size="small"
            @click="loadDetail"
          >重试</el-button>
        </div>
      </template>
      <div v-else-if="detail" class="ledger-drawer__body">
        <section class="ledger-section">
          <h4 class="ledger-section__title">单据信息</h4>
          <div class="ledger-section__grid">
            <div class="ledger-field"><span>提单人</span><strong>{{ detail.submitterName || '未映射' }}</strong></div>
            <div class="ledger-field"><span>部门</span><strong>{{ detail.submitterDepartment || '—' }}</strong></div>
            <div class="ledger-field"><span>费用分类</span><strong>{{ detail.expenseCategory || '—' }}</strong></div>
            <div class="ledger-field">
              <span>审批状态</span>
              <strong>
                <el-tag size="small" effect="dark" :type="statusTagType(detail.approvalStatus)">
                  {{ detail.approvalStatus || '—' }}
                </el-tag>
              </strong>
            </div>
            <div class="ledger-field">
              <span>发生日</span>
              <strong>
                {{ shortDate(detail.claimDate) || (detail.createdAt ? shortDate(detail.createdAt) : '—') }}
              </strong>
            </div>
            <div class="ledger-field">
              <span>审批完成日</span><strong>{{ shortDate(detail.approvalFinishedDate) || '—' }}</strong>
            </div>
          </div>
          <div v-if="detail.remark" class="ledger-remark">
            <span>备注</span>
            <p>{{ detail.remark }}</p>
          </div>
        </section>

        <section class="ledger-section">
          <h4 class="ledger-section__title">金额</h4>
          <div class="ledger-section__grid">
            <div class="ledger-field"><span>申请金额</span><strong>{{ formatMoney(detail.totalAmount) }}</strong></div>
            <div class="ledger-field">
              <span>核定金额</span><strong class="text-success">{{ formatMoney(detail.approvedAmount ?? detail.totalAmount) }}</strong>
            </div>
            <div class="ledger-field"><span>币种</span><strong>{{ detail.currency || '—' }}</strong></div>
          </div>
        </section>

        <section class="ledger-section">
          <h4 class="ledger-section__title">费用明细</h4>
          <div v-if="detail.items && detail.items.length" class="ledger-items-scroll">
            <el-table
              :data="detail.items"
              size="small"
              row-key="id"
              max-height="340"
            >
              <el-table-column
                type="index"
                label="#"
                width="46"
                align="center"
              />
              <el-table-column label="日期" width="110" align="center">
                <template #default="{ row }">{{ shortDate(row.itemDate) || '—' }}</template>
              </el-table-column>
              <el-table-column label="费用类型" width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.expenseType || '—' }}</template>
              </el-table-column>
              <el-table-column label="用途" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.itemDesc || '—' }}</template>
              </el-table-column>
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="税额" width="110" align="right">
                <template #default="{ row }">{{ formatMoney(row.taxAmount) }}</template>
              </el-table-column>
              <el-table-column label="发票号" width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.invoiceNo || '—' }}</template>
              </el-table-column>
              <el-table-column label="票据类型" width="110">
                <template #default="{ row }">{{ row.receiptType || '—' }}</template>
              </el-table-column>
              <el-table-column label="备注" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ row.remark || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
          <div v-else class="ledger-items-empty">本报销单无费用明细行</div>
        </section>

        <section class="ledger-section ledger-section--source">
          <h4 class="ledger-section__title">同步来源</h4>
          <div class="ledger-section__grid">
            <div class="ledger-field">
              <span>钉钉审批单号</span><strong class="mono">{{ detail.externalId || '—' }}</strong>
            </div>
            <div class="ledger-field">
              <span>来源提交人 UserId</span><strong class="mono">{{ detail.dingtalkUserId || '—' }}</strong>
            </div>
            <div class="ledger-field"><span>来源创建时间</span><strong>{{ dateTime(detail.sourceCreatedAt) }}</strong></div>
            <div class="ledger-field"><span>来源更新时间</span><strong>{{ dateTime(detail.sourceUpdatedAt) }}</strong></div>
            <div class="ledger-field"><span>最近同步时间</span><strong>{{ dateTime(detail.lastSyncedAt) }}</strong></div>
            <div class="ledger-field"><span>本地入库时间</span><strong>{{ dateTime(detail.createdAt) }}</strong></div>
          </div>
          <div v-if="detail.attachmentInfo" class="ledger-remark">
            <span>附件信息</span>
            <p>{{ detail.attachmentInfo }}</p>
          </div>
        </section>
      </div>
    </el-drawer>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { Close, Loading } from '@element-plus/icons-vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import LqTable from '@/components/common/LqTable.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import SearchToolbar, { type ToolbarField } from '@/components/common/SearchToolbar.vue'
import { useListFilters } from '@/composables/list/useListFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import { notify } from '@/composables/ui/useToast'
import {
  APPROVAL_STATUS_OPTIONS,
  EXPENSE_CATEGORY_OPTIONS,
  fetchExpenseDetail,
  fetchExpenses,
  fetchExpenseStats,
  type ClaimDetail,
  type ClaimRow,
  type ExpenseStats
} from '@/api/expense/expenses'

// ========== 筛选 / 分页 ==========
const { filters, resetFilters } = useListFilters({
  keyword: '',
  status: '',
  category: ''
})

const toolbarFields = computed<ToolbarField[]>(() => [
  {
    key: 'status',
    label: '审批状态',
    options: APPROVAL_STATUS_OPTIONS.map(o => ({ value: o.value, label: o.label })),
    clearable: true,
    width: 150
  },
  {
    key: 'category',
    label: '费用分类',
    options: EXPENSE_CATEGORY_OPTIONS,
    clearable: true,
    filterable: true,
    width: 150
  }
])

const dateRange = ref<[string, string] | null>(null)

function handleToolbarFilterChange(key: string, value: unknown) {
  ;(filters as Record<string, unknown>)[key] = value ?? ''
  applyFilters()
}

function applyFilters() {
  resetPage()
  loadList()
}

function handleResetFilters() {
  resetFilters()
  dateRange.value = null
  applyFilters()
}

const pagination = useListPagination({ size: 20 })
const { resetPage, getPageParams, updateFromResponse } = pagination

// ========== 列表加载 ==========
const loading = ref(false)
const rows = ref<ClaimRow[]>([])
const listError = ref('')
const hasLoadedList = ref(false)

const hasActiveFilters = computed(
  () =>
    Boolean(String(filters.keyword || '').trim()) ||
    Boolean(filters.status) ||
    Boolean(filters.category) ||
    Boolean(dateRange.value)
)

const dataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoadedList.value) return 'loading'
  if (listError.value && rows.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (rows.value.length === 0) return hasActiveFilters.value ? 'filtered-empty' : 'empty'
  return 'ready'
})

async function loadList() {
  loading.value = true
  listError.value = ''
  try {
    const { page, size } = getPageParams()
    const r = await fetchExpenses({
      keyword: String(filters.keyword || '').trim() || undefined,
      status: filters.status || undefined,
      category: filters.category || undefined,
      dateFrom: dateRange.value?.[0] || undefined,
      dateTo: dateRange.value?.[1] || undefined,
      page,
      size
    })
    rows.value = r.items
    updateFromResponse({ totalElements: r.total, number: r.page, size: r.size })
    hasLoadedList.value = true
  } catch (e: any) {
    listError.value = e?.message || '报销台账加载失败，请稍后重试。'
    notify.error(listError.value)
  } finally {
    loading.value = false
  }
}

// ========== 顶部指标 ==========
const statsLoading = ref(false)
const stats = ref<ExpenseStats | null>(null)

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await fetchExpenseStats()
  } catch {
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}

// ========== 明细抽屉（只读） ==========
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<ClaimDetail | null>(null)

function openDetail(row: ClaimRow) {
  detail.value = null
  detailError.value = ''
  detailVisible.value = true
  loadDetail(row.id)
}

async function loadDetail(id?: number) {
  const claimId = id ?? detail.value?.id
  if (!claimId) return
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await fetchExpenseDetail(claimId)
  } catch (e: any) {
    detailError.value = e?.message || '报销单明细加载失败。'
  } finally {
    detailLoading.value = false
  }
}

// ========== 生命周期（面板随 Tab 懒挂载/缓存） ==========
let firstActivated = true
onActivated(() => {
  // 从「钉钉同步」切回时刷新；首次挂载已由下方 loadStats/loadList 拉取，避免重复请求
  if (firstActivated) {
    firstActivated = false
    return
  }
  loadStats()
  loadList()
})

loadStats()
loadList()

// ========== 展示辅助 ==========
const STATUS_TYPE: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  info: 'info'
}

function statusTagType(label: string): 'success' | 'warning' | 'danger' | 'info' {
  if (!label) return 'info'
  const hit = APPROVAL_STATUS_OPTIONS.find(o => o.label === label)
  return (hit && hit.type && STATUS_TYPE[hit.type]) || 'info'
}

const moneyFormatter = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const intFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })

function formatMoney(value: number | string | null | undefined): string {
  const n = Number(value ?? '')
  return Number.isFinite(n) ? moneyFormatter.format(n) : '—'
}

function formatInt(value: number | null | undefined): string {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? intFormatter.format(n) : '—'
}

function shortDate(value?: string | null): string {
  if (!value) return ''
  return String(value).slice(0, 10)
}

function dateTime(value?: string | null): string {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 0.9em;
}

.text-success {
  color: var(--color-success-text, #16a34a);
}

.reimb-ledger-table :deep(.el-table__row) {
  cursor: pointer;
}

/* ===== 明细抽屉 ===== */
.ledger-drawer__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm, 8px);
  width: 100%;
}

.ledger-drawer__claim {
  margin: 0;
  font-size: var(--fs-xl, 20px);
  font-weight: var(--fw-semibold, 600);
}

.ledger-drawer__title {
  margin: 6px 0 0;
  color: var(--color-text-secondary, #64748b);
  font-size: var(--fs-sm, 13px);
}

.ledger-drawer__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg, 16px);
}

.ledger-drawer__center {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 0;
  color: var(--color-text-secondary, #64748b);
}

.ledger-drawer__center--error {
  flex-direction: column;
  color: var(--color-danger-text, #dc2626);
}

.ledger-section {
  padding: 14px 16px;
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: var(--radius-lg, 10px);
}

.ledger-section--source {
  background: var(--color-surface-alt, #f8fafc);
}

.ledger-section__title {
  margin: 0 0 12px;
  font-size: var(--fs-sm, 13px);
  font-weight: var(--fw-semibold, 600);
  color: var(--color-text-secondary, #64748b);
}

.ledger-section__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px 24px;
}

.ledger-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ledger-field > span {
  color: var(--color-text-muted, #94a3b8);
  font-size: var(--fs-xs, 12px);
}

.ledger-field > strong {
  overflow: hidden;
  color: var(--color-text-heading, #0f172a);
  font-weight: var(--fw-medium, 500);
  font-size: var(--fs-sm, 13px);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ledger-remark {
  display: flex;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--color-border-light, #e2e8f0);
}

.ledger-remark > span {
  flex: 0 0 auto;
  color: var(--color-text-muted, #94a3b8);
  font-size: var(--fs-xs, 12px);
}

.ledger-remark > p {
  margin: 0;
  color: var(--color-text-secondary, #475569);
  font-size: var(--fs-sm, 13px);
  white-space: pre-wrap;
  word-break: break-all;
}

.ledger-items-scroll {
  margin: -4px;
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: var(--radius-md, 8px);
  overflow: hidden;
}

.ledger-items-empty {
  padding: 20px;
  text-align: center;
  color: var(--color-text-muted, #94a3b8);
  font-size: var(--fs-sm, 13px);
}
</style>
