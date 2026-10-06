<template>
  <div class="demand-page">
    <header class="page-head"><div><h2>待下达生产需求</h2><p>订单已完成履约评审和确认；引用原需求下达生产，不再重复评审。</p></div><el-button @click="load">刷新</el-button></header>
    <el-alert type="info" :closable="false" title="此处只接收已确认销售订单的自产明细。下达后生成关联生产任务，客户、产品、配置、数量和交期直接沿用订单快照。" class="tip" />
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column label="销售订单" width="150"><template #default="{ row }"><el-button v-if="canReadOrders" link type="primary" @click="openSalesOrder(row.orderId)">{{ row.orderNo }}</el-button><span v-else>{{ row.orderNo }}</span></template></el-table-column>
      <el-table-column prop="customerName" label="客户" min-width="150" />
      <el-table-column prop="productName" label="产品名称" min-width="180" />
      <el-table-column prop="workshopName" label="目标产线" width="150" />
      <el-table-column label="数量" width="110"><template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template></el-table-column>
      <el-table-column prop="dueDate" label="期望交期" width="125" />
      <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row)">查看并下达</el-button></template></el-table-column>
    </el-table>

    <el-drawer v-model="drawer" title="下达生产任务" size="min(720px, 96vw)">
      <template v-if="selected">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="销售订单"><el-button v-if="canReadOrders" link type="primary" @click="openSalesOrder(selected.orderId)">{{ selected.orderNo }}</el-button><span v-else>{{ selected.orderNo }}</span></el-descriptions-item>
          <el-descriptions-item label="客户">{{ selected.customerName }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ selected.productName }}</el-descriptions-item>
          <el-descriptions-item label="目标产线">{{ selected.workshopName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ selected.quantity }} {{ selected.unit }}</el-descriptions-item>
          <el-descriptions-item label="交期">{{ selected.dueDate || '待确认' }}</el-descriptions-item>
          <el-descriptions-item label="规格要求" :span="2">{{ selected.specification || '—' }}</el-descriptions-item>
          <el-descriptions-item label="业务备注" :span="2">{{ selected.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <h3>销售确认的产品配置</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item v-for="entry in configEntries" :key="entry.label" :label="entry.label">{{ entry.value }}</el-descriptions-item>
        </el-descriptions>
        <h3>执行制程</h3>
        <el-alert type="info" :closable="false" title="执行制程由“任务单产品与配置”中该产品绑定的制程自动确定；未单独绑定时使用产线默认制程，无需在此重复选择。" />
      </template>
      <template #footer><el-button @click="drawer = false">关闭</el-button><el-button v-if="canManage" type="warning" :loading="accepting" @click="reportException">登记条件变化</el-button><el-button v-if="canManage" type="primary" :loading="accepting" @click="accept">生成生产任务</el-button></template>
    </el-drawer>
    <SalesExecutionExceptionDialog ref="exceptionDialog" @saved="onExceptionSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import SalesExecutionExceptionDialog from '@/components/sales/SalesExecutionExceptionDialog.vue'
import { fetchPendingProductionDemands, acceptProductionDemand, type ProductionDemand,
  type ProductionDemandConfig } from '@/api/salesOrder'

const auth = useCurrentUser()
const router = useRouter()
const route = useRoute()
const canManage = computed(() => auth.can('manufacturing.manage'))
const canReadOrders = computed(() => ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'].some(permission => auth.can(permission)))
function openSalesOrder(id: number) { void router.push(`/sales-orders/${id}`) }
const exceptionDialog = ref<InstanceType<typeof SalesExecutionExceptionDialog>>()
const rows = ref<ProductionDemand[]>([])
const loading = ref(false)
const accepting = ref(false)
const drawer = ref(false)
const selected = ref<ProductionDemand | null>(null)
const configEntries = computed(() => {
  if (!selected.value) return []
  let c: ProductionDemandConfig
  try { c = JSON.parse(selected.value.productionConfigJson) as ProductionDemandConfig }
  catch { return [] }
  return [
    { label: '尺寸', value: c.boxSize || c.boxSizeNote || '—' }, { label: '盒高', value: c.boxHeight },
    { label: '模具排列', value: c.moldArrangement }, { label: '形状', value: c.boxShapeNote || c.boxShape },
    { label: '材质', value: c.boxMaterial }, { label: '电压', value: c.voltage },
    { label: '插头', value: c.plug }, { label: '品牌', value: c.brand },
    { label: '可选配置', value: c.optionalConfigs?.join('、') || '无' },
    { label: '额外模具', value: c.extraMold ? '需要' : '不需要' },
    { label: '说明', value: c.configNote || '无' }
  ]
})
async function load() {
  loading.value = true
  try { rows.value = await fetchPendingProductionDemands() }
  catch (e) { notify.errorWithDetail('加载待下达生产需求失败', e) }
  finally { loading.value = false }
}
async function open(row: ProductionDemand) {
  selected.value = row
  drawer.value = true
}
async function accept() {
  if (!selected.value || accepting.value) return
  try { await ElMessageBox.confirm('依据已评审并确认的销售需求生成生产任务？该任务沿用销售订单确认依据，无需再次提交需求审批。', '下达生产任务') }
  catch { return }
  accepting.value = true
  try {
    const taskId = await acceptProductionDemand(selected.value.orderId, selected.value.itemId)
    notify.success(`已下达生产任务 MO${String(taskId).padStart(8, '0')}`)
    drawer.value = false
    await load()
    void router.push({ path: '/local-manufacturing-execution', query: { taskId: String(taskId), sourceOrderId: String(selected.value?.orderId || '') } })
  } catch (e) { notify.errorWithDetail('接单失败', e) }
  finally { accepting.value = false }
}

async function reportException() {
  if (selected.value) await exceptionDialog.value?.open(selected.value.orderId, selected.value.itemId, true)
}
async function onExceptionSaved() { drawer.value = false; await load() }

await load()
watch(() => route.query.itemId, value => { const row = rows.value.find(item => item.itemId === Number(value)); if (row) void open(row) }, { immediate: true })
</script>

<style scoped>
.demand-page { padding: 22px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0 0 6px; }
.page-head p { margin: 0; color: var(--el-text-color-secondary); }
.tip { margin-bottom: 16px; }
h3 { margin: 22px 0 12px; }
</style>
