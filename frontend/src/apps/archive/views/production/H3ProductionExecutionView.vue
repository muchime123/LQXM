<template>
  <div class="h3-execution">
    <header class="header">
      <div>
        <h1>车间执行 · 氚云生产任务</h1>
        <p>直接查看已导入工单、制程完成情况和氚云报工记录。整单进度按制程完成标记或完成时间计算，数量单独展示。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>
    <el-alert title="车间按制程报工；质检或跟单复核并办理完工入库。所有本地记录单独留存，氚云来源数据不被改写。" type="info" :closable="false" show-icon />
    <section class="stats-grid">
      <button v-for="card in cards" :key="card.key || 'ALL'" type="button" class="stat-card"
        :class="{ 'stat-card--active': boardState === card.key, 'stat-card--warn': card.warn && card.value > 0 }"
        @click="applyCard(card.key)">
        <span class="stat-card__label">{{ card.label }}</span>
        <span class="stat-card__value">{{ card.value }}</span>
        <span class="stat-card__hint">{{ card.hint }}</span>
      </button>
    </section>
    <section class="panel">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="工单号 / 产品 / 客户" @keyup.enter="search" @clear="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-tag v-if="boardState" closable type="warning" @close="applyCard('')">只看：{{ activeCardLabel }}</el-tag>
      </div>
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column label="生产任务单" min-width="170"><template #default="{ row }"><el-button link type="primary" @click="open(row.id)">{{ row.workOrderNo }}</el-button></template></el-table-column>
        <el-table-column prop="productName" label="产品" min-width="170" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
        <el-table-column prop="productionLine" label="产线" width="130" />
        <el-table-column label="制程完成" width="210"><template #default="{ row }">
          <span>{{ row.completedComponentCount }} / {{ row.componentCount }} 道</span>
          <el-progress v-if="row.componentCount" :percentage="percent(row.completedComponentCount, row.componentCount)" :stroke-width="6" />
          <small v-else>来源未提供制程明细</small>
        </template></el-table-column>
        <el-table-column label="来源数量" width="110"><template #default="{ row }">{{ row.requiredQuantity ?? '—' }}</template></el-table-column>
        <el-table-column label="办理状态" width="125"><template #default="{ row }">
          <el-tag :type="executionState(row).type" size="small">{{ executionState(row).label }}</el-tag>
        </template></el-table-column>
        <el-table-column prop="requiredCompletionDate" label="要求完成" width="120" />
        <el-table-column prop="status" label="来源状态" width="110" />
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="open(row.id)">详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </section>
    <el-drawer v-model="drawer" title="氚云生产任务单" size="min(920px, 96vw)">
      <div v-loading="detailLoading">
        <el-alert v-if="detailError" type="error" :title="detailError" :closable="false" show-icon />
        <template v-if="detail">
          <h2>{{ detail.order.workOrderNo }} · {{ detail.order.productName }}</h2>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="客户">{{ detail.order.customerName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="产线">{{ detail.order.productionLine || '—' }}</el-descriptions-item>
            <el-descriptions-item label="来源状态">{{ detail.order.status || '—' }}</el-descriptions-item>
            <el-descriptions-item label="要求完成">{{ detail.order.requiredCompletionDate || '—' }}</el-descriptions-item>
            <el-descriptions-item label="制程完成">{{ detail.completedComponentCount }} / {{ detail.componentCount }} 道</el-descriptions-item>
            <el-descriptions-item label="数量（来源）">{{ detail.order.completedQuantity ?? '—' }} / {{ detail.order.requiredQuantity ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="生产要求" :span="2">{{ detail.order.productionRequirement || '—' }}</el-descriptions-item>
          </el-descriptions>
          <section class="completion-panel">
            <h3>完工检验与入库</h3>
            <el-alert v-if="Number(detail.order.warehousedQuantity) > 0 || isDone(detail.order.warehousingCompleted)"
              title="氚云来源已记录入库；此工单不能再提交本地报工或重复入库。" type="warning" :closable="false" show-icon />
            <el-alert v-else-if="detail.componentCount === 0 || detail.completedComponentCount !== detail.componentCount"
              title="全部制程完成后，才能由质检或跟单人员办理检验合格入库。" type="info" :closable="false" show-icon />
            <el-alert v-if="rework?.activeRework?.status === 'REWORKING'"
              :title="`检验不合格，${rework.activeRework.componentName}正在返工；完成 ${rework.activeRework.targetQuantity} 后可复检。`"
              type="warning" :closable="false" show-icon />
            <el-alert v-else-if="rework?.activeRework?.status === 'PENDING_REINSPECTION'"
              title="返工报工已完成，等待质检或跟单人员复检。复检合格即办理入库；不合格可再次指定返工制程。"
              type="info" :closable="false" show-icon />
            <el-alert v-if="completionError" :title="completionError" type="error" :closable="false" show-icon />
            <el-alert v-if="reworkError" :title="reworkError" type="error" :closable="false" show-icon />
            <div class="completion-actions" v-if="canInspect">
              <el-button type="warning" :disabled="!canFail()" @click="startFailure">检验不合格 · 退回返工</el-button>
              <el-button type="success" :disabled="!canComplete()" @click="startCompletion">{{ rework?.activeRework?.status === 'PENDING_REINSPECTION' ? '复检合格并入库' : '质检合格并入库' }}</el-button>
            </div>
            <el-table v-loading="completionLoading" :data="completion?.completions || []" stripe class="completion-table">
              <el-table-column prop="inspectedAt" label="检验入库时间" min-width="155" />
              <el-table-column prop="warehouseName" label="仓库" min-width="110" />
              <el-table-column label="实际产出" min-width="220"><template #default="{ row }">
                <div v-for="item in completionItems(row.id)" :key="item.lineNo">
                  {{ item.isPrimary ? '主产品 · ' : '' }}{{ item.productCode }} {{ item.productName }} × {{ item.quantity }} {{ item.unit }}
                  <small v-if="item.transactionNo">流水 {{ item.transactionNo }} · 入账后余额 {{ item.balanceAfter }} {{ item.unit }}</small>
                </div>
              </template></el-table-column>
              <el-table-column prop="inspectedByName" label="办理人" width="95" />
              <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'POSTED' ? 'success' : 'info'">{{ row.status === 'POSTED' ? '已入库' : '已冲正' }}</el-tag></template></el-table-column>
              <el-table-column v-if="canInspect" label="操作" width="85"><template #default="{ row }"><el-button v-if="row.status === 'POSTED'" link type="warning" @click="reverseCompletion(row.id)">冲正</el-button></template></el-table-column>
            </el-table>
          </section>
          <section class="completion-panel">
            <h3>检验退回与返工</h3>
            <el-table v-loading="reworkLoading" :data="rework?.reworks || []" stripe>
              <el-table-column prop="inspectedAt" label="退回时间" min-width="150" />
              <el-table-column prop="componentName" label="返工制程" min-width="135" />
              <el-table-column label="返工数量" width="130"><template #default="{ row }">{{ row.reportedQuantity }} / {{ row.targetQuantity }}</template></el-table-column>
              <el-table-column prop="failureReason" label="不合格原因" min-width="170" show-overflow-tooltip />
              <el-table-column prop="inspectedByName" label="检验人" width="100" />
              <el-table-column label="状态" width="110"><template #default="{ row }">{{ reworkLabel(row.status) }}</template></el-table-column>
              <el-table-column v-if="canReport" label="操作" width="100"><template #default="{ row }"><el-button v-if="row.status === 'REWORKING'" link type="primary" @click="startReworkReport(row)">返工报工</el-button></template></el-table-column>
            </el-table>
            <el-table v-if="rework?.reworkReports.length" :data="rework.reworkReports" stripe>
              <el-table-column prop="reportedAt" label="返工报工时间" min-width="150" />
              <el-table-column prop="quantity" label="数量" width="80" />
              <el-table-column prop="reportedByName" label="报工人" width="100" />
              <el-table-column prop="remark" label="说明" min-width="130" show-overflow-tooltip />
              <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.status === 'VALID' ? '有效' : '已冲正' }}</template></el-table-column>
              <el-table-column v-if="canReverse" label="操作" width="80"><template #default="{ row }"><el-button v-if="row.status === 'VALID' && row.reworkId === rework?.activeRework?.id" link type="warning" @click="reverseRework(row)">冲正</el-button></template></el-table-column>
            </el-table>
          </section>
          <h3>制程明细</h3>
          <el-table :data="detail.components" border stripe>
            <el-table-column prop="sequenceNo" label="序号" width="70" />
            <el-table-column prop="componentName" label="制程 / 零部件" min-width="155" />
            <el-table-column prop="componentSerialNo" label="流水号" min-width="120" />
            <el-table-column label="完成数量" width="150"><template #default="{ row }"><div>{{ row.effectiveQuantity ?? '—' }} / {{ row.requiredQuantity ?? '—' }}</div><small>来源 {{ row.completedQuantity ?? '—' }} · 本地净报工 {{ row.localReportedQuantity ?? 0 }}</small></template></el-table-column>
            <el-table-column label="完成状态" width="135"><template #default="{ row }"><el-tag :type="isDone(row.effectiveCompleted) ? 'success' : 'info'">{{ isDone(row.effectiveCompleted) ? '已完成' : row.activeReworkId ? '返工中' : '未完成' }}</el-tag><small v-if="row.activeReworkId">返工 {{ row.reworkReportedQuantity }} / {{ row.reworkTargetQuantity }}</small></template></el-table-column>
            <el-table-column prop="completedAt" label="完成时间" min-width="160" />
            <el-table-column v-if="canReport" label="操作" width="85"><template #default="{ row }"><el-button link type="primary" :disabled="sourceWarehoused(detail.order) || !!rework?.activeRework || isDone(row.effectiveCompleted) || !row.externalId || !Number(row.requiredQuantity)" @click="startReport(row)">报工</el-button></template></el-table-column>
          </el-table>
          <el-alert v-if="!detail.componentCount" title="来源任务没有制程明细，无法计算工序完成进度。" type="warning" :closable="false" show-icon />
          <h3>本地报工记录（{{ detail.localReports.length }} 条）</h3>
          <el-table :data="detail.localReports" border stripe>
            <el-table-column prop="reportedAt" label="提交时间" min-width="160" />
            <el-table-column prop="componentNameSnapshot" label="制程" min-width="150" />
            <el-table-column prop="quantity" label="数量" width="90" />
            <el-table-column prop="reportedByName" label="报工人" width="110" />
            <el-table-column prop="remark" label="备注" min-width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'VALID' ? 'success' : 'info'">{{ row.status === 'VALID' ? '有效' : '已冲正' }}</el-tag></template></el-table-column>
            <el-table-column v-if="canReverse" label="操作" width="85"><template #default="{ row }"><el-button v-if="row.status === 'VALID'" link type="warning" @click="reverse(row.id)">冲正</el-button></template></el-table-column>
          </el-table>
          <h3>氚云报工记录（{{ detail.reportCount }} 条）</h3>
          <el-table :data="detail.reports" border stripe>
            <el-table-column prop="reportTime" label="报工时间" min-width="160" />
            <el-table-column prop="componentName" label="制程 / 零部件" min-width="150" />
            <el-table-column prop="componentSerial" label="流水号" min-width="120" />
            <el-table-column prop="reportQty" label="来源报工数量" width="125" />
            <el-table-column prop="reporter" label="报工人" width="115" />
            <el-table-column prop="dataStatus" label="来源状态" width="105" />
          </el-table>
          <p class="note">氚云报工按来源工单号关联，最多展示最近 200 条且不可在此冲正。制程有效数量取来源完成数与本地报工基线加净报工数的较大值，避免来源重导后重复累计。</p>
        </template>
      </div>
    </el-drawer>
    <el-dialog v-model="reportDialog" title="按制程报工" width="460px" :close-on-click-modal="!saving">
      <template v-if="reportComponent">
        <p>{{ reportComponent.componentName }} · 已完成 {{ reportComponent.effectiveQuantity ?? 0 }} / {{ reportComponent.requiredQuantity }}</p>
        <el-form label-position="top">
          <el-form-item label="本次完成数量"><el-input-number v-model="reportQuantity" :min="0.0001" :precision="4" :step="1" :max="Math.max(0.0001, Number(reportComponent.requiredQuantity || 0) - Number(reportComponent.effectiveQuantity || 0))" /></el-form-item>
          <el-form-item label="生产说明"><el-input v-model="reportRemark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        </el-form>
      </template>
      <template #footer><el-button @click="reportDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitReport">提交报工</el-button></template>
    </el-dialog>
    <el-dialog v-model="failureDialog" title="检验不合格 · 指定返工" width="500px" :close-on-click-modal="!reworkSaving">
      <el-form label-position="top">
        <el-form-item label="退回制程" required><el-select v-model="failureComponentId" placeholder="请选择制程">
          <el-option v-for="component in detail?.components || []" :key="component.externalId" :value="component.externalId!" :label="`${component.sequenceNo || ''} ${component.componentName || '未命名制程'}`" :disabled="!component.externalId || !Number(component.requiredQuantity)" />
        </el-select></el-form-item>
        <el-form-item label="返工数量" required><el-input-number v-model="failureQuantity" :min="0.0001" :max="failureMaxQuantity" :precision="4" :step="1" /></el-form-item>
        <el-form-item label="不合格原因" required><el-input v-model="failureReason" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="failureDialog = false">取消</el-button><el-button type="warning" :loading="reworkSaving" @click="submitFailure">确认退回返工</el-button></template>
    </el-dialog>
    <el-dialog v-model="reworkDialog" title="返工报工" width="460px" :close-on-click-modal="!reworkSaving">
      <p v-if="reportRework">{{ reportRework.componentName }} · 已报 {{ reportRework.reportedQuantity }} / {{ reportRework.targetQuantity }}</p>
      <el-form label-position="top">
        <el-form-item label="本次返工完成数量" required><el-input-number v-model="reworkQuantity" :min="0.0001" :max="reworkRemaining" :precision="4" :step="1" /></el-form-item>
        <el-form-item label="返工说明"><el-input v-model="reworkRemark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reworkDialog = false">取消</el-button><el-button type="primary" :loading="reworkSaving" @click="submitReworkReport">提交返工报工</el-button></template>
    </el-dialog>
    <el-dialog v-model="completionDialog" title="质检合格并完工入库" width="min(760px, 96vw)" :close-on-click-modal="!completionSaving">
      <template v-if="detail">
        <el-alert title="确认全部制程合格后，选择入库仓库及实际产出的产品。提交会立即增加产品库存；主产品数量须等于工单需求数量。" type="warning" :closable="false" show-icon />
        <el-form label-position="top" class="completion-form">
          <el-form-item label="入库仓库" required>
            <el-select v-model="completionWarehouseId" placeholder="请选择仓库" filterable><el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" /></el-select>
          </el-form-item>
          <div v-for="(line, index) in completionLines" :key="line.key" class="completion-line">
            <div class="line-head"><b>{{ line.primary ? '主产品' : `附加产出 ${index}` }}</b><el-button v-if="!line.primary" link type="danger" @click="completionLines.splice(index, 1)">移除</el-button></div>
            <el-form-item label="实际产品 SKU" required>
              <el-select v-model="line.salesProductId" filterable remote :remote-method="searchProducts" :loading="productsLoading" placeholder="输入至少两个字搜索产品编号或名称">
                <el-option v-for="product in productOptions" :key="product.id" :value="product.id" :label="`${product.productCode} · ${product.productName} ${product.specification || ''} / ${product.unit}`" />
              </el-select>
            </el-form-item>
            <el-form-item label="入库数量" required><el-input-number v-model="line.quantity" :min="0.000001" :precision="6" :step="1" /></el-form-item>
          </div>
          <el-button @click="addCompletionLine">添加配套件 / 模具</el-button>
          <el-form-item label="检验说明"><el-input v-model="completionRemark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        </el-form>
      </template>
      <template #footer><el-button @click="completionDialog = false">取消</el-button><el-button type="success" :loading="completionSaving" @click="submitCompletion">确认合格并入库</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { fetchH3ExecutionDetail, fetchH3ExecutionOrders, fetchH3ExecutionSummary, submitH3ExecutionReport, reverseH3ExecutionReport,
  fetchH3Completion, fetchH3Warehouses, searchH3Products, completeH3Production, reverseH3Completion,
  fetchH3Reworks, failH3Inspection, reportH3Rework, reverseH3ReworkReport,
  type H3CompletionDetail, type H3CompletionItem, type H3Warehouse, type H3ProductOption,
  type H3Rework, type H3ReworkDetail, type H3ReworkReport,
  type H3ExecutionSummary, type H3ExecutionComponent, type H3ExecutionDetail, type H3ExecutionOrder } from '../../../../api/manufacturing/h3Execution'

const route = useRoute()
const { can } = useCurrentUser()
const canReport = can('manufacturing.report')
const canReverse = can('manufacturing.report.override')
const canInspect = can('manufacturing.inspect')
const keyword = ref('')
const boardState = ref('')
const summary = ref<H3ExecutionSummary | null>(null)
const page = ref(1)
const total = ref(0)
const items = ref<H3ExecutionOrder[]>([])
const loading = ref(false)
const error = ref('')
const drawer = ref(false)
const detail = ref<H3ExecutionDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const reportDialog = ref(false)
const reportComponent = ref<H3ExecutionComponent | null>(null)
const reportQuantity = ref(1)
const reportRemark = ref('')
const saving = ref(false)
const completion = ref<H3CompletionDetail | null>(null)
const completionLoading = ref(false)
const completionError = ref('')
const completionDialog = ref(false)
const completionSaving = ref(false)
const rework = ref<H3ReworkDetail | null>(null)
const reworkLoading = ref(false)
const reworkError = ref('')
const reworkSaving = ref(false)
const failureDialog = ref(false)
const failureComponentId = ref('')
const failureQuantity = ref(1)
const failureReason = ref('')
const reworkDialog = ref(false)
const reportRework = ref<H3Rework | null>(null)
const reworkQuantity = ref(1)
const reworkRemark = ref('')
const warehouses = ref<H3Warehouse[]>([])
const completionWarehouseId = ref<number | null>(null)
const completionRemark = ref('')
interface CompletionLine { key: string; salesProductId: number | null; quantity: number; primary: boolean }
const completionLines = ref<CompletionLine[]>([])
const productOptions = ref<H3ProductOption[]>([])
const productsLoading = ref(false)
const percent = (done: number, all: number) => all ? Math.round(100 * done / all) : 0
const isDone = (value: boolean | number | undefined) => value === true || value === 1
const sourceWarehoused = (order: H3ExecutionOrder) => Number(order.warehousedQuantity) > 0 || isDone(order.warehousingCompleted)
function executionState(order: H3ExecutionOrder): { label: string; type: 'success' | 'warning' | 'info' } {
  if (sourceWarehoused(order)) return { label: '来源已入库', type: 'info' }
  if (Number(order.localPostedCompletionCount) > 0) return { label: '本地已入库', type: 'success' }
  if (order.reworkStatus === 'REWORKING') return { label: '返工中', type: 'warning' }
  if (order.reworkStatus === 'PENDING_REINSPECTION') return { label: '待复检', type: 'warning' }
  if (order.componentCount > 0 && order.completedComponentCount === order.componentCount) return { label: '待质检入库', type: 'warning' }
  return { label: '生产中', type: 'info' }
}
const completionItems = (id: number): H3CompletionItem[] => completion.value?.items.filter(item => item.completionId === id) || []
const reworkLabel = (status: H3Rework['status']) => ({ REWORKING: '返工中', PENDING_REINSPECTION: '待复检', REJECTED: '复检退回', RESOLVED: '复检合格' })[status]
const failureMaxQuantity = computed(() => Number(detail.value?.components.find(c => c.externalId === failureComponentId.value)?.requiredQuantity || 0))
const reworkRemaining = computed(() => Math.max(0, Number(reportRework.value?.targetQuantity || 0) - Number(reportRework.value?.reportedQuantity || 0)))
const canComplete = () => !!detail.value && !!completion.value && !completionLoading.value && !completionError.value
  && !!rework.value && !reworkLoading.value && !reworkError.value
  && detail.value.componentCount > 0
  && detail.value.completedComponentCount === detail.value.componentCount
  && !sourceWarehoused(detail.value.order)
  && !['已取消', 'CANCELLED'].includes(detail.value.order.status || '')
  && rework.value.activeRework?.status !== 'REWORKING'
  && !(completion.value?.completions.some(item => item.status === 'POSTED'))
const canFail = () => canComplete()
const cards = computed(() => {
  const stats = summary.value
  return [
    { key: '', label: '全部任务', value: stats?.total ?? 0, hint: '当前搜索范围内' },
    { key: 'NOT_STARTED', label: '待开工', value: stats?.notStarted ?? 0, hint: '尚无任何报工' },
    { key: 'IN_PRODUCTION', label: '生产中', value: stats?.inProduction ?? 0, hint: '已开工未全部完成' },
    { key: 'PENDING_INSPECTION', label: '待质检入库', value: stats?.pendingInspection ?? 0, hint: '制程已全部完成' },
    { key: 'REWORK', label: '返工 / 待复检', value: stats?.rework ?? 0, hint: '存在未闭环返工', warn: true },
    { key: 'WAREHOUSED', label: '已入库', value: stats?.warehoused ?? 0, hint: '来源或本地已入库' },
    { key: 'OVERDUE', label: '已超期', value: stats?.overdue ?? 0, hint: '过要求完成日未入库', warn: true }
  ]
})
const activeCardLabel = computed(() => cards.value.find(card => card.key === boardState.value)?.label || '')
function applyCard(key: string) {
  boardState.value = boardState.value === key ? '' : key
  page.value = 1
  void load()
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [result, stats] = await Promise.all([
      fetchH3ExecutionOrders({ keyword: keyword.value, state: boardState.value, page: page.value, size: 20 }),
      fetchH3ExecutionSummary({ keyword: keyword.value })
    ])
    items.value = result.items
    total.value = result.total
    summary.value = stats
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { loading.value = false }
}
function search() { page.value = 1; void load() }
async function open(id: number) {
  drawer.value = true
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  completion.value = null
  completionError.value = ''
  rework.value = null
  reworkError.value = ''
  try {
    detail.value = await fetchH3ExecutionDetail(id)
    await Promise.all([loadCompletion(id), loadReworks(id)])
  }
  catch (cause) { detailError.value = cause instanceof Error ? cause.message : '详情加载失败' }
  finally { detailLoading.value = false }
}
async function loadReworks(id: number) {
  reworkLoading.value = true
  try { rework.value = await fetchH3Reworks(id) }
  catch (cause) { reworkError.value = cause instanceof Error ? cause.message : '返工记录加载失败' }
  finally { reworkLoading.value = false }
}
function startFailure() {
  if (!detail.value || !canFail()) return
  const component = detail.value.components.find(c => c.externalId === rework.value?.activeRework?.componentExternalId)
    || detail.value.components.find(c => c.externalId && Number(c.requiredQuantity) > 0)
  failureComponentId.value = component?.externalId || ''
  failureQuantity.value = Number(component?.requiredQuantity || 1)
  failureReason.value = ''
  failureDialog.value = true
}
async function submitFailure() {
  if (!detail.value || !canFail() || !failureComponentId.value || !failureReason.value.trim()
      || !Number.isFinite(failureQuantity.value) || failureQuantity.value <= 0
      || failureQuantity.value > failureMaxQuantity.value) {
    ElMessage.error('请选择返工制程、有效数量并填写不合格原因'); return
  }
  reworkSaving.value = true
  try {
    await failH3Inspection(detail.value.order.id, { componentExternalId: failureComponentId.value,
      quantity: failureQuantity.value, reason: failureReason.value.trim() }, crypto.randomUUID())
    failureDialog.value = false
    ElMessage.success('已退回指定制程返工')
    await open(detail.value.order.id)
    await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '检验退回失败') }
  finally { reworkSaving.value = false }
}
function startReworkReport(row: H3Rework) {
  reportRework.value = row
  reworkQuantity.value = Math.min(1, Math.max(0.0001, Number(row.targetQuantity) - Number(row.reportedQuantity)))
  reworkRemark.value = ''
  reworkDialog.value = true
}
async function submitReworkReport() {
  if (!detail.value || !reportRework.value || !Number.isFinite(reworkQuantity.value)
      || reworkQuantity.value <= 0 || reworkQuantity.value > reworkRemaining.value) {
    ElMessage.error('请输入有效的返工完成数量'); return
  }
  reworkSaving.value = true
  try {
    await reportH3Rework(detail.value.order.id, reportRework.value.id,
      { quantity: reworkQuantity.value, remark: reworkRemark.value }, crypto.randomUUID())
    reworkDialog.value = false
    ElMessage.success('返工报工已提交')
    await open(detail.value.order.id)
    await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '返工报工失败') }
  finally { reworkSaving.value = false }
}
async function reverseRework(row: H3ReworkReport) {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('请填写返工报工冲正原因', '冲正返工报工', {
      inputType: 'textarea', inputValidator: text => !!text.trim() || '请填写冲正原因'
    })
    await reverseH3ReworkReport(detail.value.order.id, row.reworkId, row.id, value.trim(), crypto.randomUUID())
    ElMessage.success('返工报工已冲正')
    await open(detail.value.order.id)
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause instanceof Error ? cause.message : '返工报工冲正失败')
  }
}
async function loadCompletion(id: number) {
  completionLoading.value = true
  try { completion.value = await fetchH3Completion(id) }
  catch (cause) { completionError.value = cause instanceof Error ? cause.message : '入库记录加载失败' }
  finally { completionLoading.value = false }
}
async function startCompletion() {
  if (!detail.value || !canComplete()) return
  try {
    warehouses.value = await fetchH3Warehouses()
    completionWarehouseId.value = null
    completionRemark.value = ''
    completionLines.value = [{ key: crypto.randomUUID(), salesProductId: null,
      quantity: Number(detail.value.order.requiredQuantity || 0), primary: true }]
    productOptions.value = []
    completionDialog.value = true
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '仓库加载失败') }
}
function addCompletionLine() {
  completionLines.value.push({ key: crypto.randomUUID(), salesProductId: null, quantity: 1, primary: false })
}
async function searchProducts(keyword: string) {
  if (keyword.trim().length < 2) {
    const selected = new Set(completionLines.value.map(line => line.salesProductId))
    productOptions.value = productOptions.value.filter(product => selected.has(product.id))
    return
  }
  productsLoading.value = true
  try {
    const found = await searchH3Products(keyword.trim())
    const selected = new Set(completionLines.value.map(line => line.salesProductId))
    const retained = productOptions.value.filter(product => selected.has(product.id) && !found.some(item => item.id === product.id))
    productOptions.value = [...retained, ...found]
  }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '产品查询失败') }
  finally { productsLoading.value = false }
}
async function submitCompletion() {
  if (!detail.value || !canComplete()) { ElMessage.error('当前工单不符合完工入库条件，请刷新'); return }
  if (!completionWarehouseId.value) { ElMessage.error('请选择入库仓库'); return }
  if (completionLines.value.some(line => !line.salesProductId || !Number.isFinite(line.quantity) || line.quantity <= 0)) {
    ElMessage.error('请选择每项实际产品并填写有效数量'); return
  }
  const primary = completionLines.value.find(line => line.primary)
  if (!primary || primary.quantity !== Number(detail.value.order.requiredQuantity)) {
    ElMessage.error('主产品数量必须等于工单需求数量'); return
  }
  const ids = completionLines.value.map(line => line.salesProductId)
  if (new Set(ids).size !== ids.length) { ElMessage.error('同一产品不可重复添加'); return }
  const orderId = detail.value.order.id
  try {
    await ElMessageBox.confirm('检验合格后将立即写入所选仓库的产品库存，确定提交吗？', '确认完工入库', { type: 'warning' })
  } catch { return }
  completionSaving.value = true
  try {
    await completeH3Production(orderId, {
      warehouseId: completionWarehouseId.value,
      items: completionLines.value.map(line => ({ salesProductId: line.salesProductId!, quantity: line.quantity, primary: line.primary })),
      remark: completionRemark.value
    }, crypto.randomUUID())
    completionDialog.value = false
    ElMessage.success('检验合格，完工入库已登记并写入产品库存')
    await open(orderId)
    await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '完工入库失败') }
  finally { completionSaving.value = false }
}
async function reverseCompletion(completionId: number) {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('请填写冲正原因。若库存已被后续出库消耗，冲正会被阻止。', '冲正完工入库', {
      inputType: 'textarea', inputValidator: text => !!text.trim() || '请填写冲正原因', confirmButtonText: '确认冲正'
    })
    const orderId = detail.value.order.id
    await reverseH3Completion(orderId, completionId, value.trim(), crypto.randomUUID())
    ElMessage.success('完工入库已冲正，库存同步回退')
    await open(orderId)
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause instanceof Error ? cause.message : '入库冲正失败')
  }
}
function startReport(component: H3ExecutionComponent) {
  reportComponent.value = component
  reportQuantity.value = Math.min(1, Math.max(0.0001, Number(component.requiredQuantity || 0) - Number(component.effectiveQuantity || 0)))
  reportRemark.value = ''
  reportDialog.value = true
}
async function submitReport() {
  if (!detail.value || !reportComponent.value?.externalId || !Number.isFinite(reportQuantity.value) || reportQuantity.value <= 0) return
  saving.value = true
  try {
    await submitH3ExecutionReport(detail.value.order.id, reportComponent.value.externalId,
      { quantity: reportQuantity.value, remark: reportRemark.value }, crypto.randomUUID())
    reportDialog.value = false
    ElMessage.success('报工已提交')
    await open(detail.value.order.id)
    await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '报工失败') }
  finally { saving.value = false }
}
async function reverse(reportId: number) {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('请填写冲正原因，原报工记录会保留', '冲正本地报工', {
      inputType: 'textarea', inputValidator: text => !!text.trim() || '请填写冲正原因', confirmButtonText: '确认冲正'
    })
    await reverseH3ExecutionReport(detail.value.order.id, reportId, value.trim(), crypto.randomUUID())
    ElMessage.success('已冲正')
    await open(detail.value.order.id)
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause instanceof Error ? cause.message : '冲正失败')
  }
}
await load()
const sourceId = Number(route.query.sourceId)
if (Number.isSafeInteger(sourceId) && sourceId > 0) await open(sourceId)
</script>

<style scoped>
.h3-execution { padding: 24px; display: grid; gap: 18px; }
/* 子项默认 min-width:auto 会把轨道撑到宽表格的宽度（统计卡被顶出视口外），归零后表格在面板内自行横向滚动 */
.h3-execution > * { min-width: 0; }
.header { display: flex; align-items: start; justify-content: space-between; gap: 16px; }
.header h1 { margin: 0 0 8px; }
.header p, .note { color: var(--el-text-color-secondary); }
.panel { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 12px; padding: 18px; }
.toolbar { display: flex; gap: 10px; max-width: 520px; margin-bottom: 16px; align-items: center; }

/* ============ 看板统计卡（点一下即筛下方列表） ============ */
.stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
.stat-card {
  display: grid; gap: 4px; text-align: left; cursor: pointer;
  padding: 14px 18px; background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light); border-radius: 12px;
  transition: border-color .15s, transform .15s;
}
.stat-card:hover { border-color: var(--el-color-primary-light-5); transform: translateY(-1px); }
.stat-card--active { border-color: var(--el-color-primary); box-shadow: inset 0 0 0 1px var(--el-color-primary); }
.stat-card__label { font-size: 12px; color: var(--el-text-color-secondary); }
.stat-card__value { font-size: 24px; font-weight: 700; line-height: 1.2; color: var(--el-text-color-primary); }
.stat-card__hint { font-size: 11px; color: var(--el-text-color-placeholder); }
.stat-card--warn .stat-card__value { color: var(--el-color-danger); }
.h3-execution h3 { margin: 24px 0 12px; }
.h3-execution small { display: block; color: var(--el-text-color-secondary); }
.completion-panel { margin-top: 22px; display: grid; gap: 12px; }
.completion-panel h3 { margin: 0; }
.completion-table { margin-top: 4px; }
.completion-form { margin-top: 18px; }
.completion-line { border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px; margin: 12px 0; }
.line-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.completion-form :deep(.el-select) { width: 100%; }
</style>
