<template>
  <el-drawer v-model="visible" title="生产任务单详情" size="min(1050px, 96vw)" destroy-on-close>
    <div v-loading="loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="error" @click="load">重新加载</el-button>
      <template v-if="detail">
        <h2>{{ detail.order.orderNo }} · {{ detail.order.productNameSnapshot }}</h2>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务来源">{{ detail.order.externalSource === 'H3YUN' ? '氚云导入' : detail.order.externalSource || '系统创建（非氚云导入）' }}</el-descriptions-item>
          <el-descriptions-item label="氚云工单号">{{ detail.order.h3WorkOrderNo || '未关联' }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.order.customerNameSnapshot || '—' }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ detail.order.salespersonNameSnapshot || '—' }}</el-descriptions-item>
          <el-descriptions-item label="车间 / 制程">{{ detail.order.workshopType || '—' }} / {{ detail.order.processTemplateNameSnapshot || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="任务数量">{{ detail.order.plannedQuantity }} {{ detail.order.unit }}</el-descriptions-item>
          <el-descriptions-item label="要求交期">{{ detail.order.dueDate || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="责任人 / 班组">{{ detail.order.responsibleEmployeeName || '未指定' }} / {{ detail.order.productionTeamName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="规格 / 生产要求" :span="2">{{ [detail.order.specificationSnapshot, detail.order.customRequirement, detail.order.remark].filter(Boolean).join('；') || '—' }}</el-descriptions-item>
          <el-descriptions-item v-for="entry in configs" :key="entry.label" :label="entry.label">{{ entry.value }}</el-descriptions-item>
        </el-descriptions>
        <h3>整单制程进度</h3>
        <p v-if="progress.required">已完成 {{ progress.completed }} / {{ progress.required }} 道必做工序（{{ progress.percent }}%）</p>
        <p v-else>没有必做工序，暂不计算制程百分比。</p>
        <el-progress v-if="progress.percent != null" :percentage="progress.percent" />
        <p class="detail-note">按本单必做工序的完成状态计数。单道工序的数量单独展示，不用整机完工台数代替制程进度。</p>
        <el-table :data="detail.operations" border row-key="id">
          <el-table-column prop="sequenceNo" label="顺序" width="65" />
          <el-table-column prop="operationNameSnapshot" label="工序" min-width="165" />
          <el-table-column label="要求" width="75"><template #default="{ row }">{{ row.required ? '必做' : '选做' }}</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{ row }">{{ operationStatusLabel(row.status) }}</template></el-table-column>
          <el-table-column label="工序已报 / 需求" width="145"><template #default="{ row }">{{ row.reportedQuantity }} / {{ row.requiredQuantity }}</template></el-table-column>
          <el-table-column label="负责人" width="110"><template #default="{ row }">{{ row.assignedEmployeeName || row.assignedTeamName || '未指派' }}</template></el-table-column>
          <el-table-column label="完成时间" width="165"><template #default="{ row }">{{ row.completedAt?.replace('T', ' ') || '—' }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="canReport && ['READY','IN_PROGRESS'].includes(row.status)" link type="primary" @click="report(row)">报工</el-button></template></el-table-column>
        </el-table>
        <h3>报工历史</h3>
        <el-table :data="detail.workReports" border empty-text="暂无报工记录">
          <el-table-column prop="reportNo" label="报工单号" width="140" />
          <el-table-column prop="operationNameSnapshot" label="工序" min-width="150" />
          <el-table-column prop="reportedQuantity" label="本次数量" width="90" />
          <el-table-column prop="reporterNameSnapshot" label="报工人" width="100" />
          <el-table-column prop="reportedAt" label="报工时间" width="170" />
          <el-table-column label="状态" width="85"><template #default="{ row }">{{ row.status === 'VALID' ? '有效' : '已冲正' }}</template></el-table-column>
          <el-table-column label="完成内容 / 冲正原因" min-width="180"><template #default="{ row }">{{ row.reversalReason || row.remark || '—' }}</template></el-table-column>
          <el-table-column v-if="canReverse" label="操作" width="80"><template #default="{ row }"><el-button v-if="row.status === 'VALID'" link type="danger" @click="reverse(row)">冲正</el-button></template></el-table-column>
        </el-table>
        <h3>异常与处理</h3>
        <el-table :data="detail.issues" border empty-text="暂无生产异常">
          <el-table-column prop="operationNameSnapshot" label="工序" min-width="140" />
          <el-table-column prop="description" label="异常描述" min-width="180" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="resolution" label="处理说明" min-width="180" />
        </el-table>
        <h3>完工入库记录</h3>
        <el-table :data="detail.receipts" border empty-text="暂无完工入库记录">
          <el-table-column prop="receiptNo" label="入库单号" min-width="150" />
          <el-table-column prop="warehouseName" label="仓库" min-width="100" />
          <el-table-column prop="quantity" label="入库数量" width="100" />
          <el-table-column prop="completedAt" label="入库时间" min-width="170" />
          <el-table-column prop="status" label="状态" width="100" />
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchManufacturingOrder, type ManufacturingDetail, type ManufacturingOperation, type ExecutionBoardItem, type WorkReport, type AllWorkReport } from '@/api/manufacturing'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { processProgress, operationStatusLabel } from '@/utils/manufacturingProgress'
const visible = defineModel<boolean>({ default: false })
const props = defineProps<{ orderId: number | null; refreshKey?: number }>()
const emit = defineEmits<{ report: [row: ExecutionBoardItem]; reverse: [row: AllWorkReport] }>()
const { can } = useCurrentUser()
const canReport = computed(() => can('manufacturing.report'))
const canReverse = computed(() => can('manufacturing.report.override'))
const detail = ref<ManufacturingDetail | null>(null), loading = ref(false), error = ref('')
let sequence = 0
const progress = computed(() => processProgress(detail.value?.operations || []))
const configs = computed(() => {
  const raw = detail.value?.order.productConfigJson
  if (!raw) return []
  let values: Record<string, unknown>
  try { values = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return [{ label: '产品配置', value: '配置格式异常，请核对来源' }] }
  const labels: Record<string,string> = { boxSize: '盒子尺寸', boxHeight: '盒子高度', moldArrangement: '模具排列', boxShape: '盒子形状', boxMaterial: '盒子材质', voltage: '电压', plug: '插头', brand: '品牌', optionalConfigs: '可选配置', configNote: '配置说明', extraMold: '额外模具' }
  return Object.entries(labels).filter(([key]) => values[key] !== undefined && values[key] !== null && values[key] !== '').map(([key,label]) => ({ label, value: Array.isArray(values[key]) ? (values[key] as unknown[]).join('、') : typeof values[key] === 'boolean' ? values[key] ? '是' : '否' : String(values[key]) }))
})
async function load() {
  const current = ++sequence
  if (!visible.value || !props.orderId) return
  loading.value = true; detail.value = null; error.value = ''
  try { const result = await fetchManufacturingOrder(props.orderId); if (current === sequence) detail.value = result }
  catch (e) { if (current === sequence) error.value = (e as Error).message || '任务详情加载失败' }
  finally { if (current === sequence) loading.value = false }
}
function report(op: ManufacturingOperation) {
  if (!detail.value) return
  const order = detail.value.order
  emit('report', { ...order, operationId: op.id, manufacturingOrderId: order.id, sequenceNo: op.sequenceNo, operationNameSnapshot: op.operationNameSnapshot,
    requiredQuantity: op.requiredQuantity, reportedQuantity: op.reportedQuantity, operationStatus: op.status, orderStatus: order.status,
    taskType: order.taskType || '', priority: order.priority || 'NORMAL', openIssueCount: 0 })
}
function reverse(row: WorkReport) {
  if (!detail.value) return
  const order = detail.value.order
  emit('reverse', { ...row, orderNo: order.orderNo, customerNameSnapshot: order.customerNameSnapshot,
    productNameSnapshot: order.productNameSnapshot, unit: order.unit, workshopType: order.workshopType })
}
watch(() => [visible.value, props.orderId, props.refreshKey], load)
</script>

<style scoped>
h3 { margin-top: 24px; }.detail-note { color: var(--color-text-secondary); font-size: 13px; }
</style>
