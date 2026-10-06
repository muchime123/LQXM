<template>
  <div class="fulfillment-review-page">
    <OrderFulfillmentSteps current="review" />
    <header class="page-head">
      <div><h2>销售需求履约评审</h2><p>确认前只做一次需求可行性评审；确认后直接下达。下面仅处理客户提出不同方案时的变更确认。</p></div>
      <el-button @click="load">刷新</el-button>
    </header>
    <el-tabs v-model="mode" @tab-change="onModeChange">
      <el-tab-pane v-if="canProduction" label="车间 · 自产" name="production" />
      <el-tab-pane v-if="canPurchase" label="采购 · 外采" name="purchase" />
    </el-tabs>
    <el-alert title="评审基于当前订单明细。销售修改产品配置、数量或交期后，原意见会自动失效，必须重新评审。" type="info" :closable="false" />
    <el-table v-loading="loading" :data="rows" border style="margin-top: 14px">
      <el-table-column prop="orderNo" label="销售订单" width="150" />
      <el-table-column prop="customerName" label="客户" min-width="150" />
      <el-table-column label="项目" min-width="230"><template #default="{ row }">{{ row.lineNo }}. {{ row.productName }}<div class="muted">{{ row.specification || '—' }}</div></template></el-table-column>
      <el-table-column label="数量" width="120"><template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template></el-table-column>
      <el-table-column label="要求交期" width="120"><template #default="{ row }">{{ row.requestedDeliveryDate || row.orderDeliveryDate || '未填写' }}</template></el-table-column>
      <el-table-column label="当前评审" width="130"><template #default="{ row }"><el-tag :type="reviewType(row)">{{ reviewLabel(row) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" @click="open(row)">查看并评审</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="暂无已送评的销售需求" />
    <h3>客户新方案的执行可行性确认</h3>
    <el-table :data="changeRows" border>
      <el-table-column prop="orderNo" label="销售订单" width="150" />
      <el-table-column prop="customerName" label="客户" min-width="140" />
      <el-table-column prop="productName" label="产品" min-width="160" />
      <el-table-column label="数量" width="125"><template #default="{ row }">{{ row.originalQuantity }} → {{ row.proposedQuantity }}</template></el-table-column>
      <el-table-column label="交期" min-width="190"><template #default="{ row }">{{ row.originalDeliveryDate || row.orderDeliveryDate || '未填' }} → {{ row.proposedDeliveryDate || row.orderDeliveryDate || '未填' }}</template></el-table-column>
      <el-table-column prop="exceptionReason" label="异常原因" min-width="180" />
      <el-table-column label="操作" width="135"><template #default="{ row }"><el-button link type="primary" @click="reviewChange(row)">复评变更</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && !changeRows.length" description="暂无待复评变更" />

    <el-drawer v-model="drawer" :title="selected ? `${selected.orderNo} · 第 ${selected.lineNo} 项履约评审` : '履约评审'" size="min(680px, 96vw)">
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="客户">{{ selected.customerName }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ selected.productName }}</el-descriptions-item>
          <el-descriptions-item label="规格">{{ selected.specification || '—' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ selected.quantity }} {{ selected.unit }}</el-descriptions-item>
          <el-descriptions-item label="客户期望交期">{{ selected.requestedDeliveryDate || selected.orderDeliveryDate || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="销售备注">{{ selected.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <h3>需求配置</h3>
        <el-descriptions v-if="configRows.length" :column="1" border>
          <el-descriptions-item v-for="field in configRows" :key="field.key" :label="field.label">{{ field.value }}</el-descriptions-item>
        </el-descriptions>
        <p v-else class="muted">无附加配置</p>
        <el-alert v-if="selected.reviewDecision" :title="`${reviewLabel(selected)}：${selected.reviewComment || '无补充意见'}`" :type="reviewType(selected)" :closable="false" style="margin-top: 16px" />
        <el-form label-position="top" style="margin-top: 20px">
          <el-form-item label="评审结论"><el-radio-group v-model="decision"><el-radio value="ACCEPTED">可按要求执行</el-radio><el-radio value="CHANGE_REQUIRED">需要销售调整</el-radio></el-radio-group></el-form-item>
          <el-form-item label="建议可交日期（如与客户期望不同）"><el-date-picker v-model="proposedDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
          <el-form-item label="评审依据、前提条件或调整原因"><el-input v-model="comment" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="需要调整时必须说明原因；通过但有条件时也请说明" /></el-form-item>
        </el-form>
      </template>
      <template #footer><el-button @click="drawer=false">关闭</el-button><el-button type="primary" :loading="saving" @click="save">提交评审</el-button></template>
    </el-drawer>
    <el-dialog v-model="changeDialog" title="复评已确认订单变更" width="min(760px, 94vw)">
      <template v-if="selectedChange">
        <p>{{ selectedChange.orderNo }} · {{ selectedChange.productName }}</p>
        <p>数量：{{ selectedChange.originalQuantity }} → {{ selectedChange.proposedQuantity }}</p>
        <p>交期：{{ selectedChange.originalDeliveryDate || selectedChange.orderDeliveryDate || '未填' }} → {{ selectedChange.proposedDeliveryDate || selectedChange.orderDeliveryDate || '未填' }}</p>
        <p>异常原因：{{ selectedChange.exceptionReason }}</p>
        <p>客户确认依据：{{ selectedChange.customerConfirmationRef }}</p>
        <h3>受影响的下游单据</h3>
        <el-table :data="selectedChange.downstreamImpact || []" border size="small">
          <el-table-column prop="documentNo" label="单据号" />
          <el-table-column prop="status" label="状态" />
          <el-table-column prop="quantity" label="数量" />
          <el-table-column prop="executedQuantity" label="已入库／收货数量" />
          <el-table-column prop="reportedQuantity" label="各工序报工数量合计" />
          <el-table-column prop="deliveryDate" label="当前交期" />
        </el-table>
        <p class="muted">已有下游单据时，只支持数量不变的交期调整。客户确认生效后更新未完成生产任务的需求交期；排产、采购到货计划和已完成事实保留原值。审批中的单据或已有出库记录需先另行处理。</p>
        <el-checkbox v-if="selectedChange.downstreamImpact?.length" v-model="downstreamConfirmed">已核对全部关联单据，并与相关执行人员确认交期处理安排</el-checkbox>
        <el-input v-model="changeComment" type="textarea" :rows="3" maxlength="1000" placeholder="请说明排产／供应商沟通安排及可履约依据；退回必须写原因" />
      </template>
      <template #footer><el-button @click="changeDialog=false">取消</el-button><el-button type="danger" :loading="saving" @click="submitChangeReview(false)">需要调整</el-button><el-button type="success" :loading="saving" @click="submitChangeReview(true)">复评通过</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OrderFulfillmentSteps from '@/components/sales/OrderFulfillmentSteps.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { decideFulfillmentReview, fetchFulfillmentReviewQueue, fetchSalesChangeReviewQueue,
  reviewSalesOrderChange, type FulfillmentReviewLine, type SalesOrderChangeReviewTask } from '@/api/salesOrder'

const route = useRoute(), router = useRouter()
const auth = useCurrentUser()
const canProduction = computed(() => auth.can('manufacturing.manage'))
const canPurchase = computed(() => auth.can('purchase_order.manage'))
const mode = ref<'production' | 'purchase'>(route.query.area === 'purchase' && canPurchase.value ? 'purchase' : canProduction.value ? 'production' : 'purchase')
const rows = ref<FulfillmentReviewLine[]>([])
const changeRows = ref<SalesOrderChangeReviewTask[]>([])
const loading = ref(false)
const saving = ref(false)
const drawer = ref(false)
const selected = ref<FulfillmentReviewLine | null>(null)
const decision = ref<'ACCEPTED' | 'CHANGE_REQUIRED'>('ACCEPTED')
const comment = ref('')
const proposedDate = ref('')
const changeDialog = ref(false)
const selectedChange = ref<SalesOrderChangeReviewTask | null>(null)
const changeComment = ref('')
const downstreamConfirmed = ref(false)

const fieldLabels: Record<string, string> = {
  workshopId: '产线', productName: '产品名称', baseProductId: '产品档案', specificName: '具体名称',
  boxSize: '尺寸', boxHeight: '盒高', moldArrangement: '模具排列', boxShape: '盒形',
  boxMaterial: '盒材质', voltage: '电压', plug: '插头', brand: '品牌', optionalConfigs: '可选配置',
  extraMold: '额外模具', configNote: '配置说明', model: '型号', technicalRequirements: '技术要求',
  acceptanceCriteria: '验收要求', packagingRequirements: '包装要求', deliveryRequirements: '交付要求',
}
const configRows = computed(() => {
  if (!selected.value) return []
  const raw = mode.value === 'production' ? selected.value.productionConfigJson : selected.value.purchaseConfigJson
  if (!raw) return []
  try {
    return Object.entries(JSON.parse(raw) as Record<string, unknown>)
      .filter(([, value]) => value !== null && value !== '' && !(Array.isArray(value) && !value.length))
      .map(([key, value]) => ({ key, label: fieldLabels[key] || key,
        value: Array.isArray(value) ? value.join('、') : typeof value === 'object' ? JSON.stringify(value) : String(value) }))
  } catch { return [] }
})
function reviewLabel(row: FulfillmentReviewLine) {
  if (!row.reviewDecision) return '待评审'
  if (!row.reviewCurrent) return '已失效，需重评'
  return row.reviewDecision === 'ACCEPTED' ? '评审通过' : '需要调整'
}
function reviewType(row: FulfillmentReviewLine): 'success' | 'danger' | 'warning' | 'info' {
  if (!row.reviewDecision) return 'info'
  if (!row.reviewCurrent) return 'warning'
  return row.reviewDecision === 'ACCEPTED' ? 'success' : 'danger'
}
async function load() {
  loading.value = true
  try {
    const [requirements, changes] = await Promise.all([
      fetchFulfillmentReviewQueue(mode.value), fetchSalesChangeReviewQueue(mode.value),
    ])
    rows.value = requirements
    changeRows.value = changes
  }
  catch (error) { notify.errorWithDetail('加载履约评审失败', error) }
  finally { loading.value = false }
}
function open(row: FulfillmentReviewLine) {
  selected.value = row
  decision.value = row.reviewCurrent && row.reviewDecision ? row.reviewDecision : 'ACCEPTED'
  comment.value = row.reviewCurrent ? row.reviewComment || '' : ''
  proposedDate.value = row.reviewCurrent ? row.proposedDeliveryDate || '' : ''
  drawer.value = true
}
async function save() {
  if (!selected.value) return
  if (decision.value === 'CHANGE_REQUIRED' && !comment.value.trim()) { notify.warning('需要调整时请填写原因'); return }
  saving.value = true
  try {
    await decideFulfillmentReview(mode.value, selected.value.orderId, selected.value.itemId,
      decision.value, comment.value.trim(), proposedDate.value || null)
    notify.success('履约评审已记录')
    drawer.value = false
    await load()
  } catch (error) { notify.errorWithDetail('提交履约评审失败', error) }
  finally { saving.value = false }
}
function reviewChange(row: SalesOrderChangeReviewTask) {
  selectedChange.value = row
  changeComment.value = ''
  downstreamConfirmed.value = false
  changeDialog.value = true
}
async function submitChangeReview(accept: boolean) {
  if (!selectedChange.value) return
  if (!accept && !changeComment.value.trim()) { notify.warning('退回变更必须说明原因'); return }
  if (accept && selectedChange.value.downstreamImpact?.length && (!downstreamConfirmed.value || !changeComment.value.trim())) {
    notify.warning('请核对全部关联单据、勾选确认并填写执行协调安排'); return
  }
  saving.value = true
  try {
    await reviewSalesOrderChange(mode.value, selectedChange.value.changeId, accept, changeComment.value.trim(),
      downstreamConfirmed.value, selectedChange.value.impactToken)
    notify.success(accept ? (Number(selectedChange.value.originalQuantity) === Number(selectedChange.value.proposedQuantity) ? '方案可行，等待销售登记客户确认' : '方案可行，等待商业变更审批') : '方案需调整，已退回销售')
    changeDialog.value = false
    await load()
  } catch (error) { notify.errorWithDetail('复评变更失败', error) }
  finally { saving.value = false }
}
function onModeChange() {
  void router.replace({path: route.path, query: {...route.query, area: mode.value}})
  void load()
}
await load()
watch(() => [route.query.area, route.query.itemId], async () => {
  const next = route.query.area === 'purchase' && canPurchase.value ? 'purchase' : canProduction.value ? 'production' : 'purchase'
  if(mode.value !== next) {mode.value = next; await load()}
  const itemId = Number(route.query.itemId)
  const row = Number.isSafeInteger(itemId) && itemId > 0 ? rows.value.find(item => item.itemId === itemId) : undefined
  if(row) open(row)
}, { immediate: true })
</script>

<style scoped>
.fulfillment-review-page { padding: 22px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 5px; }
.page-head p, .muted { color: var(--el-text-color-secondary); font-size: 12px; }
h3 { margin: 22px 0 12px; }
</style>
