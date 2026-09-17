<template>
  <ListPageLayout class="dingtalk-panel" content-label="钉钉同步运行记录">
    <!-- 同步操作 + 汇总指标 -->
    <template #summary>
      <div class="dt-actions">
        <el-button :icon="Refresh" :loading="statusLoading" @click="loadAll">刷新状态</el-button>
        <el-button :icon="Upload" :disabled="syncing" @click="importVisible = true">Excel 导入</el-button>
        <span class="dt-sync-range">
          <span class="dt-sync-range__label">同步范围</span>
          <el-select v-model="syncDaysBack" :disabled="syncing" style="width: 116px">
            <el-option
v-for="d in SYNC_RANGE_OPTIONS"
:key="d.value"
:label="d.label"
:value="d.value" />
          </el-select>
        </span>
        <el-button
type="primary"
:icon="Link"
:disabled="!status?.enabled || !status?.configured || syncing"
          :loading="syncing"
@click="doSync">立即同步</el-button>
      </div>
      <MetricGrid :columns="4">
        <!-- 状态卡：点击查看详情 -->
        <article class="dt-status-card" @click="connDetailVisible = true">
          <div class="dt-status-card__header">
            <span class="dt-dot" :class="statusClass"></span>
            <span class="dt-status-card__title">集成状态</span>
          </div>
          <div class="dt-status-card__value" :class="statusClass">{{ statusText }}</div>
          <div class="dt-status-card__meta">
            {{ statusHint }}
            <span v-if="status" class="dt-status-card__link">详情 ›</span>
          </div>
        </article>

        <MetricCard
title="累计报销单据"
:value="status?.stats.totalClaims"
unit="单"
hint="expense_claims 全量"
          :loading="!status"
:empty="status?.stats.totalClaims === 0" />
        <MetricCard
title="本月核定通过"
:value="status?.stats.thisMonthApproved"
unit="单"
          hint="钉钉审批通过 · 本月"
tone="success"
:loading="!status"
:empty="status?.stats.thisMonthApproved === 0" />
        <MetricCard
title="同步运行记录"
:value="status?.stats.totalRuns"
unit="次"
          :hint="status?.stats.failedRuns ? `失败 ${status.stats.failedRuns}` : '每次含成功/失败/映射数'"
          :tone="status?.stats.failedRuns ? 'danger' : undefined"
          :loading="!status"
:empty="status?.stats.totalRuns === 0" />
      </MetricGrid>
    </template>

    <!-- 使用说明 -->
    <template #notice>
      <div class="dt-notice">
        <p>
          两种入库方式：<strong>API 同步</strong> 拉取钉钉「日常报销」审批流单据，或
          <strong>Excel 导入</strong> 上传钉钉 OA 后台导出的「日常报销」Excel。
          均按审批单主键幂等去重；同步范围默认最近 7 天，可在上方选择 7 / 15 / 30 天。
          入库结果切换到上方「报销台账」页签检索与核对明细。
          未匹配员工身份时写入 <code>employee_id IS NULL</code>，
          <router-link to="/system/employees" class="dt-notice__link">请去员工名册补钉钉映射 →</router-link>
        </p>
      </div>
    </template>

    <!-- ===== 同步记录 ===== -->
    <DataState
      :state="runsState"
      :skeleton="runsState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="5"
      :refreshing="runsLoading && runs.length > 0"
      error-title="同步记录加载失败"
      :error-message="runsError"
      empty-text="暂无同步记录"
      empty-hint="点击上方「立即同步」或「Excel 导入」后将在这里显示运行记录。"
      @retry="reloadRuns"
    >
      <LqTable :data="runs" row-key="id" @row-click="openRunDetail">
        <el-table-column label="#" width="48" align="center">
          <template #default="{ $index }">{{ (runsPage - 1) * runsSize + $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" type="info" size="small">{{ syncTypeLabel(row.syncType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="runStatusTag(row.status).type" effect="dark">{{ runStatusTag(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="210">
          <template #default="{ row }">
            <div>{{ TRIGGER_OPTIONS[row.triggeredBy] ?? row.triggeredBy }}</div>
            <small class="dt-muted">{{ row.triggeredByName || '—' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="220" align="center">
          <template #default="{ row }">{{ fmtDT(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ durationText(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column
label="拉取"
width="100"
align="right"
prop="fetchedCount" />
        <el-table-column label="新增" width="100" align="right">
          <template #default="{ row }"><span class="dt-success">{{ row.createdCount }}</span></template>
        </el-table-column>
        <el-table-column label="更新" width="100" align="right">
          <template #default="{ row }"><span class="dt-info">{{ row.updatedCount }}</span></template>
        </el-table-column>
        <el-table-column label="失败" width="60" align="right">
          <template #default="{ row }">
            <span :class="row.failedCount > 0 ? 'dt-danger' : 'dt-muted'">{{ row.failedCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="映射缺失" width="80" align="right">
          <template #default="{ row }">
            <span :class="row.mappingMissed > 0 ? 'dt-warning' : 'dt-muted'">{{ row.mappingMissed }}</span>
          </template>
        </el-table-column>
        <el-table-column
label="部门映射"
width="130"
align="center"
show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="(row.deptMappingsCreated ?? 0) + (row.deptMappingsUpgraded ?? 0) > 0">
              新增 <span class="dt-success">{{ row.deptMappingsCreated ?? 0 }}</span>
              · 升级 <span class="dt-info">{{ row.deptMappingsUpgraded ?? 0 }}</span>
            </span>
            <span v-else class="dt-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="错误摘要" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorSummary" class="dt-danger">{{ row.errorSummary }}</span>
            <span v-else class="dt-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button
link
type="primary"
size="small"
@click.stop="openRunDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </LqTable>
    </DataState>

    <template #pagination>
      <LqPagination
        :pagination="runsPagination"
        :page-sizes="[10, 20, 50]"
        @change="reloadRuns"
      />
    </template>

    <!-- ===== 同步详情 Drawer ===== -->
    <el-drawer
      v-model="runDrawer.visible"
      :title="runDrawer.data ? `同步详情 · #${runDrawer.data.summary.id}` : '同步详情'"
      size="min(880px, 94vw)"
      destroy-on-close
      direction="rtl"
    >
      <div v-loading="runDrawer.loading" class="dt-run-detail">
        <template v-if="runDrawer.data">
          <header class="dt-run-detail__head">
            <div>
              <el-tag :type="runStatusTag(runDrawer.data.summary.status).type" effect="dark">
                {{ runStatusTag(runDrawer.data.summary.status).label }}
              </el-tag>
              <span style="margin-left:10px">{{ syncTypeLabel(runDrawer.data.summary.syncType) }}</span>
              <span class="dt-muted" style="margin-left:10px">{{ fmtDT(runDrawer.data.summary.startedAt) }}</span>
            </div>
          </header>

          <section class="dt-run-detail__section">
            <h3>同步概览</h3>
            <el-descriptions :column="3" size="small" border>
              <el-descriptions-item label="触发方式">
                {{ TRIGGER_OPTIONS[runDrawer.data.summary.triggeredBy] ?? runDrawer.data.summary.triggeredBy }}
              </el-descriptions-item>
              <el-descriptions-item label="触发人">{{ runDrawer.data.summary.triggeredByName ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ durationText(runDrawer.data.summary.durationMs) }}</el-descriptions-item>
              <el-descriptions-item label="拉取">{{ runDrawer.data.summary.fetchedCount }} 条</el-descriptions-item>
              <el-descriptions-item label="新增 / 更新">
                <span class="dt-success">{{ runDrawer.data.summary.createdCount }}</span> /
                <span class="dt-info">{{ runDrawer.data.summary.updatedCount }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="未变化">{{ runDrawer.data.summary.unchangedCount }} 条</el-descriptions-item>
              <el-descriptions-item label="失败">{{ runDrawer.data.summary.failedCount }} 条</el-descriptions-item>
              <el-descriptions-item label="员工映射缺失">
                <span :class="runDrawer.data.summary.mappingMissed > 0 ? 'dt-warning' : ''">
                  {{ runDrawer.data.summary.mappingMissed }} 条
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="部门映射新增">
                <span :class="runDrawer.data.summary.deptMappingsCreated > 0 ? 'dt-success' : ''">
                  {{ runDrawer.data.summary.deptMappingsCreated }} 个
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="部门映射升级">
                <span :class="runDrawer.data.summary.deptMappingsUpgraded > 0 ? 'dt-info' : ''">
                  {{ runDrawer.data.summary.deptMappingsUpgraded }} 个
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="时间范围">
                {{ runDrawer.data.summary.rangeDateFrom ?? '—' }} ~ {{ runDrawer.data.summary.rangeDateTo ?? '—' }}
              </el-descriptions-item>
            </el-descriptions>
          </section>

          <section v-if="runDrawer.data.summary.errorSummary || runDrawer.data.errorDetails" class="dt-run-detail__section">
            <h3>错误信息</h3>
            <div v-if="runDrawer.data.summary.errorSummary" class="dt-run-detail__error-summary">
              <b>摘要：</b>{{ runDrawer.data.summary.errorSummary }}
            </div>
            <div v-if="runDrawer.data.errorDetails" class="dt-run-detail__error-details">
              <b>详细：</b>
              <pre>{{ runDrawer.data.errorDetails }}</pre>
            </div>
          </section>
        </template>
      </div>
    </el-drawer>

    <!-- 钉钉报销 Excel 导入 -->
    <ExpenseImportDialog v-model:visible="importVisible" @confirmed="handleImportConfirmed" />

    <!-- ===== 连接详情弹窗（点击状态卡打开） ===== -->
    <el-dialog
v-if="status"
v-model="connDetailVisible"
title="连接详情"
width="min(560px, 90vw)"
destroy-on-close>
      <el-descriptions :column="1" border size="default">
        <el-descriptions-item label="集成状态">
          <el-tag :type="runStatusTag(status.connected ? 'SUCCESS' : status.enabled ? 'PARTIAL' : 'FAILED').type" effect="dark" size="small">
            {{ statusText }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="API 版本">
          <el-tag :type="status.useV2Api ? 'primary' : 'warning'" effect="dark" size="small">
            {{ status.useV2Api ? 'v2 · api.dingtalk.com' : 'v1 · oapi.dingtalk.com' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="AppKey"><code>{{ status.appKeyMasked }}</code></el-descriptions-item>
        <el-descriptions-item label="ProcessCode"><code>{{ status.processCodeMasked || '(未配置)' }}</code></el-descriptions-item>
        <el-descriptions-item label="Access Token">
          <template v-if="status.accessTokenStatus === 'OK'">
            <code>{{ status.accessTokenMasked }}</code>
            <el-tag
size="small"
effect="plain"
type="success"
style="margin-left:6px">OK</el-tag>
          </template>
          <span v-else style="color:var(--color-danger)">{{ status.accessTokenStatus === 'FAIL' ? '获取失败' : 'EMPTY' }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="status.lastSyncAt" label="最近同步">
          <span>{{ fmtDT(status.lastSyncAt) }}</span>
          <el-tag
v-if="status.lastSyncStatus"
:type="runStatusTag(status.lastSyncStatus).type"
            effect="plain"
size="small"
style="margin-left:6px">
            {{ runStatusTag(status.lastSyncStatus).label }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue'
import { Refresh, Link, Upload } from '@element-plus/icons-vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import DataState from '@/components/common/DataState.vue'
import LqTable from '@/components/common/LqTable.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import ExpenseImportDialog from '@/components/expense/ExpenseImportDialog.vue'
import { useListPagination } from '@/composables/list/useListPagination'
import type { ExpenseImportResult } from '@/api/expense/expenses'
import {
  fetchDingtalkStatus, triggerReimbursementSync,
  fetchSyncRuns, fetchSyncRunDetail,
  SYNC_STATUS_OPTIONS, TRIGGER_OPTIONS,
  type DingtalkStatus, type SyncRunRow, type SyncRunDetail
} from '@/api/dingtalk'
import { notify } from '@/composables/ui/useToast'

// 本次手动同步的回溯天数（默认 7 天：财务处理报销周期短，也节省钉钉 API 调用配额）
const SYNC_RANGE_OPTIONS = [
  { value: 7, label: '最近 7 天' },
  { value: 15, label: '最近 15 天' },
  { value: 30, label: '最近 30 天' },
]

// ===== 数据 =====
const status = ref<DingtalkStatus | null>(null)
const statusLoading = ref(false)
const syncing = ref(false)
const syncDaysBack = ref(7)

const runs = ref<SyncRunRow[]>([])
const runsLoading = ref(false)
const runsError = ref('')
const runsPage = ref(1)
const runsSize = ref(10)

const runsPagination = useListPagination({ page: 0, size: 10 })

const runDrawer = reactive<{
  visible: boolean
  loading: boolean
  data: SyncRunDetail | null
}>({ visible: false, loading: false, data: null })

// 钉钉报销 Excel 导入
const importVisible = ref(false)
const connDetailVisible = ref(false)
async function handleImportConfirmed(_result: ExpenseImportResult) {
  await Promise.all([loadStatus(), reloadRuns()])
}

// ===== 计算属性 =====
const statusClass = computed(() => {
  if (!status.value) return 'muted'
  if (!status.value.enabled) return 'disabled'
  if (!status.value.configured) return 'warn'
  if (status.value.connected) return 'ok'
  return 'error'
})

const statusText = computed(() => {
  if (!status.value) return '加载中…'
  if (!status.value.enabled) return '未启用'
  if (!status.value.configured) return '未配置凭证'
  if (status.value.connected) return '已连接'
  return '连接异常'
})

const statusHint = computed(() => {
  if (!status.value) return ''
  if (!status.value.enabled) return '钉钉集成未启用，请在后端 .env 配置 DINGTALK_ENABLED=true。'
  if (!status.value.configured) return '已启用但未配置 AppKey / AppSecret，请在后端 .env 填入。'
  if (status.value.connected) return '钉钉开放平台连通正常。'
  return status.value.connectError ? `连通失败：${status.value.connectError}` : '请检查 AppKey / AppSecret 或网络。'
})

const runsState = computed<'ready' | 'loading' | 'error' | 'empty'>(() => {
  if (runsLoading.value) return 'loading'
  if (runsError.value) return 'error'
  if (runs.value.length === 0) return 'empty'
  return 'ready'
})

// ===== 生命周期（面板随 Tab 懒挂载/缓存，挂载即拉取，不依赖 Suspense） =====
let firstActivated = true
onActivated(() => {
  // 从其它 Tab 切回时刷新；首次挂载已由下方 loadAll 拉取，避免重复请求
  if (firstActivated) {
    firstActivated = false
    return
  }
  loadAll()
})

loadAll()

async function loadAll() {
  await Promise.all([loadStatus(), reloadRuns()])
}

async function loadStatus() {
  statusLoading.value = true
  try { status.value = await fetchDingtalkStatus() }
  catch (e: any) { notify.errorWithDetail('钉钉集成状态加载失败', e) }
  finally { statusLoading.value = false }
}

async function reloadRuns() {
  runsLoading.value = true
  runsError.value = ''
  try {
    const r = await fetchSyncRuns({ page: runsPage.value, size: runsSize.value })
    runs.value = r.items
    runsPagination.updateFromResponse({ total: r.total, page: r.page - 1, size: r.size, totalPages: Math.ceil(r.total / r.size) })
  } catch (e: any) {
    runsError.value = e?.message ?? String(e)
    notify.errorWithDetail('同步记录加载失败', e)
  } finally { runsLoading.value = false }
}

runsPagination.onPageChange(runsPagination.pageInfo.page, () => {
  runsPage.value = runsPagination.pageInfo.page + 1
  reloadRuns()
})
runsPagination.onPageSizeChange(runsPagination.pageInfo.size, () => {
  runsSize.value = runsPagination.pageInfo.size
  runsPage.value = 1
  runsPagination.resetPage()
  reloadRuns()
})

// ===== 操作 =====
async function doSync() {
  syncing.value = true
  try {
    const r = await triggerReimbursementSync(syncDaysBack.value)
    notify.success(`${r.message}（运行 #${r.runId}）`)
    await Promise.all([loadStatus(), reloadRuns()])
  } catch (e: any) {
    const code = e?.response?.status
    const msg = e?.response?.data?.message ?? e?.response?.data?.error ?? e?.message ?? String(e)
    if (code === 503) {
      notify.errorWithDetail('钉钉集成未就绪', {
        response: { data: { message: `${msg}\n请联系管理员在后端配置 dingtalk.enabled / app-key / app-secret 并重启服务。` } }
      })
    } else {
      notify.errorWithDetail('同步触发失败', e)
    }
  } finally { syncing.value = false }
}

async function openRunDetail(row: SyncRunRow) {
  runDrawer.visible = true
  runDrawer.loading = true
  runDrawer.data = null
  try { runDrawer.data = await fetchSyncRunDetail(row.id) }
  catch (e: any) { notify.errorWithDetail('同步详情加载失败', e) }
  finally { runDrawer.loading = false }
}

// ===== 工具 =====
function runStatusTag(s: string) {
  return SYNC_STATUS_OPTIONS[s] ?? { label: s, type: 'info' as const }
}
function syncTypeLabel(t: string) {
  if (t === 'DAILY_REIMBURSEMENT' || t === 'REIMBURSEMENTS') return '日常报销'
  return t
}
function durationText(ms: number | null | undefined) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return (ms / 1000).toFixed(1) + 's'
}
function fmtDT(s: string | null) { return s ? s.replace('T', ' ').substring(0, 19) : '—' }
</script>

<style scoped>
/* ===== 面板顶部操作行 ===== */
.dt-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-sm);
  margin-bottom: var(--space-control-gap);
}

/* ===== 自定义状态卡（与 MetricCard 等高，共用 metric-card 样式） ===== */
.dt-status-card {
  min-width: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap);
  padding: var(--card-padding);
  cursor: pointer;
  transition: box-shadow var(--transition-base), border-color var(--transition-base), transform var(--transition-base);
}

.dt-status-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.dt-status-card__header {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md);
}

.dt-status-card__title {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.dt-status-card__value {
  font-size: var(--fs-3xl);
  font-weight: var(--fw-semibold);
  font-variant-numeric: tabular-nums;
  line-height: var(--lh-tight);
  letter-spacing: -0.02em;
}

.dt-status-card__meta {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  line-height: var(--lh-snug);
}

.dt-status-card__link {
  display: inline;
  margin-left: var(--space-sm);
  color: var(--color-text-link);
  font-weight: var(--fw-medium);
}

/* ===== 状态圆点 ===== */
.dt-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dt-dot.ok      { background: var(--color-success); box-shadow: 0 0 0 3px var(--color-success-bg); }
.dt-dot.error   { background: var(--color-danger);  box-shadow: 0 0 0 3px var(--color-danger-bg); }
.dt-dot.warn    { background: var(--color-warning); box-shadow: 0 0 0 3px var(--color-warning-bg); }
.dt-dot.disabled { background: var(--color-text-disabled); box-shadow: 0 0 0 3px var(--color-surface-hover); }
.dt-dot.muted   { background: var(--color-text-faint); }

/* 状态文字颜色跟随圆点 */
.dt-status-card__value.ok      { color: var(--color-success); }
.dt-status-card__value.error   { color: var(--color-danger); }
.dt-status-card__value.warn    { color: var(--color-warning); }
.dt-status-card__value.disabled { color: var(--color-text-disabled); }
.dt-status-card__value.muted   { color: var(--color-text-faint); }

/* ===== 使用说明 ===== */
.dt-notice {
  border: 1px solid var(--color-info-border);
  border-radius: var(--radius-lg);
  background: var(--color-info-bg-soft);
  padding: var(--space-md) var(--space-lg);
}

.dt-notice p {
  margin: 0;
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.dt-notice code {
  background: var(--color-surface);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.dt-notice__link {
  color: var(--color-text-link);
  text-decoration: none;
  font-weight: var(--fw-medium);
}

.dt-notice__link:hover {
  text-decoration: underline;
}

/* ===== 同步范围选择器 ===== */
.dt-sync-range {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
}

.dt-sync-range__label {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  white-space: nowrap;
}

/* ===== 颜色辅助类 ===== */
.dt-success { color: var(--color-success); }
.dt-info    { color: var(--color-info); }
.dt-danger  { color: var(--color-danger); }
.dt-warning { color: var(--color-warning); }
.dt-muted   { color: var(--color-text-faint); }

/* ===== 同步详情 Drawer ===== */
.dt-run-detail__head {
  display: flex;
  justify-content: space-between;
  gap: var(--space-lg-xl);
  padding-bottom: var(--space-lg);
  border-bottom: 1px solid var(--color-border-light);
  margin-bottom: var(--space-lg-xl);
}

.dt-run-detail__section {
  margin-top: var(--space-lg-xl);
}

.dt-run-detail__section h3 {
  margin: 0 0 var(--space-md);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.dt-run-detail__error-summary {
  background: var(--color-danger-bg-soft);
  border: 1px solid var(--color-danger-border);
  color: var(--color-danger-text);
  padding: var(--space-md) var(--space-lg);
  border-radius: var(--radius-lg);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.dt-run-detail__error-details {
  margin-top: var(--space-sm-md);
  background: var(--color-text-heading);
  color: var(--color-surface);
  padding: var(--space-lg);
  border-radius: var(--radius-lg);
  font-size: var(--fs-xs);
  line-height: var(--lh-loose);
  overflow-x: auto;
}

.dt-run-detail__error-details pre {
  margin: var(--space-xs) 0 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: var(--font-mono);
}

.dt-run-detail__error-details b {
  color: var(--ep-danger-3);
}
</style>
