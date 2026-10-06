<template>
  <el-drawer v-model="visible" title="生产任务单详情" size="min(1050px, 96vw)" destroy-on-close>
    <div v-loading="loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="error" @click="load">重新加载</el-button>
      <template v-if="detail">
        <h2>{{ detail.order.orderNo }} · {{ detail.order.productNameSnapshot }}</h2>
        <el-alert v-if="orderStatus === 'PENDING_APPROVAL'" title="已提交审批，等待老板审批；审批通过前车间看不到这张单，也不能排产。" type="warning" :closable="false" />
        <el-alert v-else-if="orderStatus === 'DRAFT'" :title="approvalStatus === 'REJECTED' ? '上次提交被驳回，请按审批意见修改后重新提交。' : '草稿：尚未提交审批，车间看不到这张单。'" :type="approvalStatus === 'REJECTED' ? 'error' : 'info'" :closable="false" />
        <div v-if="approvalStatus !== 'NONE'" class="approval-meta">
          <el-tag :type="approvalTag(approvalStatus)" size="small">{{ approvalStatusText(approvalStatus) }}</el-tag>
          <span v-if="detail.order.approvalSubmittedAt" class="detail-note">提交 {{ formatTime(detail.order.approvalSubmittedAt) }}</span>
          <span v-if="detail.order.approvalDecidedAt" class="detail-note">决定 {{ formatTime(detail.order.approvalDecidedAt) }}</span>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="审批依据" v-if="detail.order.approvalBasis === 'SALES_ORDER'">沿用已确认销售订单 {{ detail.salesDemand?.orderNo }}，未重复审批</el-descriptions-item>
          <el-descriptions-item label="审批状态"><el-tag :type="approvalTag(approvalStatus)" size="small">{{ approvalStatusText(approvalStatus) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="任务来源">{{ detail.order.externalSource === 'H3YUN' ? '氚云导入' : detail.order.externalSource || '系统创建（非氚云导入）' }}</el-descriptions-item>
          <el-descriptions-item label="氚云工单号">{{ detail.order.h3WorkOrderNo || '未关联' }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.order.customerNameSnapshot || '—' }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ detail.order.salespersonNameSnapshot || '—' }}</el-descriptions-item>
          <el-descriptions-item label="车间 / 制程">{{ detail.order.workshopType || '—' }} / {{ detail.order.processTemplateNameSnapshot || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="制程品类">{{ detail.order.processCategorySnapshot || detail.order.productNameSnapshot }}</el-descriptions-item>
          <el-descriptions-item label="产品身份键">{{ detail.order.skuKeySnapshot || '非标或待确定' }}</el-descriptions-item>
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
        <h3>完工检验记录</h3>
        <el-table :data="detail.inspections" border empty-text="暂无检验记录">
          <el-table-column prop="inspectionNo" label="检验单号" width="150" />
          <el-table-column label="结论" width="100"><template #default="{ row }">{{ row.inspectionResult === 'PASSED' ? '合格' : '不合格' }}</template></el-table-column>
          <el-table-column prop="qualifiedQuantity" label="合格数量" width="100" />
          <el-table-column prop="unqualifiedQuantity" label="不合格数量" width="110" />
          <el-table-column prop="inspectorNameSnapshot" label="检验人" width="110" />
          <el-table-column prop="inspectedAt" label="检验时间" width="170" />
          <el-table-column prop="remark" label="说明" min-width="140" />
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
        <template v-if="detail.approvals?.length">
          <h3>审批记录</h3>
          <el-table :data="detail.approvals" border size="small" empty-text="暂无审批记录">
            <el-table-column label="动作" width="105"><template #default="{ row }">
              <el-tag :type="row.action === 'APPROVE' ? 'success' : row.action === 'REJECT' ? 'danger' : 'info'" size="small">
                {{ approvalActionText(row.action) }}
              </el-tag>
            </template></el-table-column>
            <el-table-column prop="actorName" label="操作人" width="115" />
            <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="意见" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.comment || '—' }}</template></el-table-column>
          </el-table>
        </template>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="canManage && detail?.salesDemand" type="warning" @click="reportDeliveryRisk">登记履约风险／可行交期</el-button>
      <el-button v-if="canManage && orderStatus === 'DRAFT'" type="primary" :loading="acting" @click="submit">提交审批</el-button>
      <el-button v-if="canApprove && orderStatus === 'PENDING_APPROVAL'" type="success" :loading="acting" @click="approve">审批通过</el-button>
      <el-button v-if="canApprove && orderStatus === 'PENDING_APPROVAL'" type="danger" :loading="acting" @click="reject">驳回</el-button>
    </template>
  </el-drawer>
  <SalesExecutionExceptionDialog ref="exceptionDialog" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import SalesExecutionExceptionDialog from '@/components/sales/SalesExecutionExceptionDialog.vue'
import { ElMessageBox } from 'element-plus'
import { fetchManufacturingOrder, submitManufacturingOrderApproval, approveManufacturingOrder, rejectManufacturingOrder, newRequestKey, type ManufacturingDetail, type ManufacturingOperation, type ExecutionBoardItem, type WorkReport, type AllWorkReport } from '@/api/manufacturing'
import { APPROVAL_STATUS_TEXT, APPROVAL_ACTION_TEXT, type ApprovalStatus } from '@/api/approval'
import { notify } from '@/composables/ui/useToast'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { processProgress, operationStatusLabel } from '@/utils/manufacturingProgress'
import { boxShapeLabel } from '@/utils/boxSize'
const visible = defineModel<boolean>({ default: false })
const props = defineProps<{ orderId: number | null; refreshKey?: number }>()
const emit = defineEmits<{ report: [row: ExecutionBoardItem]; reverse: [row: AllWorkReport]; changed: [] }>()
const { can } = useCurrentUser()
const canReport = computed(() => can('manufacturing.report'))
const canReverse = computed(() => can('manufacturing.report.override'))
/**
 * 提交（`manufacturing.manage`）与审批（`manufacturing.approve`）是**两个独立权限**（决策 #35）：
 * 有建单权 ≠ 有审批权，所以两类按钮各按各的权限显示，不能合成一个「能管这个单子」的判断。
 */
const canManage = computed(() => can('manufacturing.manage'))
const canApprove = computed(() => can('manufacturing.approve'))
const exceptionDialog = ref<InstanceType<typeof SalesExecutionExceptionDialog>>()
async function reportDeliveryRisk() {
  const demand = detail.value?.salesDemand
  if (demand) await exceptionDialog.value?.open(demand.orderId, demand.itemId, true)
}
const acting = ref(false)
const orderStatus = computed(() => detail.value?.order.status || '')
/** 审批态独立于业务状态：驳回后 status 回到草稿，只有它记得被驳回过。 */
const approvalStatus = computed<ApprovalStatus>(() => detail.value?.order.approvalStatus || 'NONE')
const detail = ref<ManufacturingDetail | null>(null), loading = ref(false), error = ref('')
let sequence = 0
const progress = computed(() => processProgress(detail.value?.operations || []))
const configs = computed(() => {
  const raw = detail.value?.order.productConfigJson
  if (!raw) return []
  let values: Record<string, unknown>
  try { values = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return [{ label: '产品配置', value: '配置格式异常，请核对来源' }] }
  // 顺序与建单抽屉一致：形状在前，尺寸在后。
  // 只展示 boxSize / boxShapeNote / boxSizeNote —— 方形那两个数值（boxLengthMm/boxWidthMm）
  // 与圆形直径是 boxSize 的构成项，给后端判「常规/订做」用的，不重复铺给人看。
  const labels: Record<string,string> = { boxShape: '盒子形状', boxSize: '盒子尺寸', boxShapeNote: '盒子形状说明', boxSizeNote: '尺寸描述', boxHeight: '盒子高度', moldArrangement: '模具排列', boxMaterial: '盒子材质', voltage: '电压', plug: '插头', brand: '品牌', optionalConfigs: '可选配置', configNote: '配置说明', extraMold: '额外模具' }
  return Object.entries(labels).filter(([key]) => values[key] !== undefined && values[key] !== null && values[key] !== '').map(([key,label]) => ({ label, value: key === 'boxShape' ? boxShapeLabel(values[key]) : Array.isArray(values[key]) ? (values[key] as unknown[]).join('、') : typeof values[key] === 'boolean' ? values[key] ? '是' : '否' : String(values[key]) }))
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
/** 后端回的是 "2026-09-27T10:04:01"，秒就够用（与销售 / 采购视图同口径）。 */
function formatTime(v?: string | null) {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}
// 表格插槽里的 row 是 any，直接索引字面量 Record 会触发 noImplicitAny；统一走查表函数。
function approvalStatusText(s?: string | null) {
  return (APPROVAL_STATUS_TEXT as Record<string, string>)[s || 'NONE'] || '未提交'
}
function approvalActionText(a?: string | null) {
  return (APPROVAL_ACTION_TEXT as Record<string, string>)[a || ''] || a || ''
}
function approvalTag(s?: ApprovalStatus) {
  if (s === 'APPROVED') return 'success'
  if (s === 'PENDING') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}
/** 提交审批（草稿 → 待审批）。提交前车间看不到这张单，也不占排产。 */
async function submit() {
  if (acting.value || !props.orderId) return
  try { await ElMessageBox.confirm('提交后这张单进入待审批，审批期间车间看不到、也不能排产。请先核对产线、制程、数量和交期。', '提交审批') }
  catch { return }
  acting.value = true
  try {
    await submitManufacturingOrderApproval(props.orderId, newRequestKey())
    notify.success('已提交审批，等待老板审批'); await load(); emit('changed')
  } catch (e) { notify.errorWithDetail('提交审批失败', e) }
  finally { acting.value = false }
}
/**
 * 审批通过。落哪个态**由单据自身决定**（后端按工序数判断），不在这里假定：
 * 有工序 → 「已下达」（可排产 / 报工）；0 工序 → 「待完工检验」（直接送去检验入库），
 * 否则 0 工序单会卡在「已下达」永远没有工序可报。
 */
async function approve() {
  if (acting.value || !props.orderId) return
  const zeroProcess = !detail.value?.operations?.length
  try { await ElMessageBox.confirm(zeroProcess
    ? '本例没有工序，审批通过后这张单直接进入「待完工检验」。'
    : '审批通过后这张单下达到车间，可以排产、报工。', '审批通过') }
  catch { return }
  acting.value = true
  try {
    await approveManufacturingOrder(props.orderId, undefined, newRequestKey())
    notify.success('已审批通过'); await load(); emit('changed')
  } catch (e) { notify.errorWithDetail('审批失败', e) }
  finally { acting.value = false }
}
/** 驳回（待审批 → 草稿），原因必填：不给理由，建单人不知道该改什么。 */
async function reject() {
  if (acting.value || !props.orderId) return
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因，建单人会看到。', '驳回生产任务单', {
      inputType: 'textarea',
      inputPlaceholder: '例如：产线选错 / 交期不现实 / 数量与销售订单不符',
      inputValidator: v => (String(v ?? '').trim() ? true : '驳回原因必填'),
    })
    comment = String(value ?? '').trim()
  } catch { return }
  acting.value = true
  try {
    await rejectManufacturingOrder(props.orderId, comment, newRequestKey())
    notify.success('已驳回，任务单退回草稿'); await load(); emit('changed')
  } catch (e) { notify.errorWithDetail('驳回失败', e) }
  finally { acting.value = false }
}
// immediate 必须加：从销售订单等页面带 ?taskId= 直达本页时，父组件在 setup 阶段就把
// visible/orderId 设为有效值，子组件挂载时已是终值——非 immediate 的 watch 不会触发，
// load() 一次都不跑，抽屉表现为「无报错的纯空白」（2026-09-28 LQ 实测踩到）。
watch(() => [visible.value, props.orderId, props.refreshKey], load, { immediate: true })
</script>

<style scoped>
h3 { margin-top: 24px; }.detail-note { color: var(--color-text-secondary); font-size: 13px; }
.approval-meta { display: flex; align-items: center; gap: 12px; margin: 10px 0; }
</style>
