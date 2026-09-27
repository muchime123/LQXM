<template>
  <div class="sales-orders-page">
    <OrderFulfillmentSteps current="order" />
    <header class="page-head">
      <div><h2>销售订单</h2><p>先确认客户需求，再按明细办理现货、生产或采购。</p></div>
      <el-button v-if="canManage" type="primary" @click="openCreate">新建订单</el-button>
    </header>
    <div class="filters">
      <el-input v-model="keyword" placeholder="订单号、客户或产品" clearable @keyup.enter="load(1)" />
      <el-select v-model="status" placeholder="全部状态" clearable @change="load(1)">
        <el-option label="草稿" value="DRAFT" /><el-option label="待审批" value="PENDING_APPROVAL" /><el-option label="已确认" value="CONFIRMED" />
      </el-select>
      <el-button @click="load(1)">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="orderNo" label="订单号" min-width="145" />
      <el-table-column prop="customerName" label="客户" min-width="180" />
      <el-table-column prop="salespersonName" label="业务员" width="110" />
      <el-table-column prop="orderDate" label="订单日期" width="130" />
      <el-table-column prop="requestedDeliveryDate" label="期望交期" width="130" />
      <el-table-column prop="itemCount" label="项目数" width="85" />
      <el-table-column label="金额" width="125"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusTag(row.status)">{{ SALES_ORDER_STATUS_TEXT[row.status as SalesOrderStatus] }}</el-tag></template></el-table-column>
      <!-- 驳回后状态回到「草稿」，只看状态看不出被驳回；审批列把它显出来（业务员要按意见改）。 -->
      <el-table-column label="审批" width="90"><template #default="{ row }"><el-tag :type="approvalTag(row.approvalStatus)" size="small">{{ approvalStatusText(row.approvalStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.id)">详情</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pager" background layout="total, prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="load" />

    <el-drawer v-model="drawer" :title="editingId ? `销售订单 ${orderNo}` : '新建销售订单'" size="min(1100px, 96vw)">
      <div class="order-form">
        <el-alert v-if="pending" title="已提交审批，等待老板审批；审批前不能修改，也不能生成出库。" type="warning" :closable="false" />
        <el-alert v-else-if="confirmed" title="订单已确认，需求已锁定；变更需另走留痕流程。" type="success" :closable="false" />
        <el-alert v-else-if="approvalStatus === 'REJECTED'" title="上次提交被驳回，请按审批意见修改后重新提交。" type="error" :closable="false" />
        <div v-if="approvalStatus !== 'NONE'" class="approval-meta">
          <el-tag :type="approvalTag(approvalStatus)" size="small">{{ APPROVAL_STATUS_TEXT[approvalStatus] }}</el-tag>
          <span v-if="submittedAt" class="tip">提交 {{ formatTime(submittedAt) }}</span>
          <span v-if="decidedAt" class="tip">决定 {{ formatTime(decidedAt) }}</span>
        </div>
        <div v-if="approvals.length" class="approval-block">
          <h3>审批记录</h3>
          <el-table :data="approvals" size="small" border>
            <el-table-column label="动作" width="100"><template #default="{ row }">
              <el-tag :type="row.action === 'APPROVE' ? 'success' : row.action === 'REJECT' ? 'danger' : 'info'" size="small">
                {{ approvalActionText(row.action) }}
              </el-tag>
            </template></el-table-column>
            <el-table-column prop="actorName" label="操作人" width="110" />
            <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="意见" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.comment || '—' }}</template></el-table-column>
          </el-table>
        </div>
        <el-form label-position="top" class="order-head-form">
          <el-form-item label="客户名称"><el-input v-model="form.customerName" :disabled="locked" maxlength="300" /></el-form-item>
          <el-form-item label="订单日期"><el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" :disabled="locked" /></el-form-item>
          <el-form-item label="期望交期"><el-date-picker v-model="form.requestedDeliveryDate" type="date" value-format="YYYY-MM-DD" clearable :disabled="locked" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" :disabled="locked" maxlength="1000" /></el-form-item>
        </el-form>
        <div class="line-heading"><h3>订单明细</h3><el-button v-if="!locked" @click="addLine">添加项目</el-button></div>
        <el-table :data="form.lines" border>
          <el-table-column label="产品及配置" min-width="280"><template #default="{ row, $index }">
            <el-input v-model="row.productName" :disabled="locked || row.supplyMode === 'MAKE'"
              :placeholder="row.supplyMode === 'MAKE' ? '点击下方配置自产产品' : '产品名称 / 暂定名称'" />
            <el-input v-model="row.specification" :disabled="locked" placeholder="规格配置要求" class="line-second" />
            <div v-if="row.supplyMode === 'MAKE'" class="line-config">
              <el-button v-if="!locked" link type="primary" @click="openProductionConfig($index)">
                {{ row.productionConfig ? '修改自产配置' : '配置自产产品' }}
              </el-button>
              <span v-if="row.productionConfig" class="tip">{{ productionSummary(row.productionConfig) }}</span>
              <el-button v-if="row.manufacturingOrderId && canReadTasks" link type="primary"
                @click="router.push({ path: '/local-manufacturing-execution', query: { taskId: String(row.manufacturingOrderId) } })">
                查看 MO{{ String(row.manufacturingOrderId).padStart(8, '0') }}
              </el-button>
              <span v-else-if="locked" class="tip">待车间评估接单</span>
            </div>
            <!-- 现货：选不到货 / 数量超过可售库存就是缺口，当场改自产并建生产任务单（09-24 LQ 定） -->
            <div v-if="row.supplyMode === 'STOCK'" class="line-config">
              <el-button v-if="!locked && (!row.salesProductId || stockShortageOf(row) > 0)"
                link type="primary" @click="switchToMake($index)">没有现货？改为自产并配置产品</el-button>
              <span v-if="stockShortageOf(row) > 0" class="tip warn">
                可售库存只有 {{ stockAvailableOf(row) }} {{ row.unit }}，缺口 {{ stockShortageOf(row) }}
              </span>
            </div>
          </template></el-table-column>
          <el-table-column label="供货方式" width="145"><template #default="{ row }"><el-select v-model="row.supplyMode" :disabled="locked" @change="onSupplyModeChange(row)"><el-option label="现货" value="STOCK" /><el-option label="自产" value="MAKE" /><el-option label="外采" value="BUY" /><el-option label="待确定" value="UNDECIDED" /></el-select></template></el-table-column>
          <el-table-column label="产品档案" min-width="250"><template #default="{ row }">
            <!-- 现货只能从「可售库存」里选：有货才选得到。选不到就是「该下生产任务单」的业务信号。 -->
            <el-select v-if="row.supplyMode === 'STOCK'" v-model="row.salesProductId" filterable remote clearable
              :remote-method="searchStock" :loading="stockLoading" :disabled="locked"
              placeholder="从可售库存选（按产品合计可用量）" @change="onStockPicked(row)">
              <el-option v-for="s in stockOptionsFor(row)" :key="s.id" :value="s.id"
                :label="`${s.productName} · ${s.specification || '无规格'} · 可用 ${s.available}${s.unit || ''}`" />
              <template #empty><span class="tip">可售库存里没有匹配的产品 —— 没有现货就该下生产任务单</span></template>
            </el-select>
            <el-select v-else v-model="row.salesProductId" filterable remote clearable :remote-method="searchProducts" :loading="productsLoading" :disabled="locked || row.supplyMode === 'MAKE'" :placeholder="row.supplyMode === 'MAKE' ? '自产配置确定产品' : '可先不选'">
              <el-option v-for="p in productOptionsFor(row)" :key="p.id" :label="`${p.productName} · ${p.productCode || p.id}`" :value="p.id" />
            </el-select>
          </template></el-table-column>
          <el-table-column label="数量" width="110"><template #default="{ row }"><el-input-number v-model="row.quantity" :disabled="locked" :min="0.000001" :precision="3" :controls="false" /></template></el-table-column>
          <el-table-column label="单位" width="90"><template #default="{ row }"><el-input v-model="row.unit" :disabled="locked" /></template></el-table-column>
          <el-table-column label="单价" width="115"><template #default="{ row }"><el-input-number v-model="row.unitPrice" :disabled="locked" :min="0" :precision="2" :controls="false" /></template></el-table-column>
          <el-table-column v-if="confirmed" label="已发 / 待办" width="105"><template #default="{ row }">{{ Number(row.shippedQuantity || 0) }} / {{ Number(row.allocatedQuantity || 0) - Number(row.shippedQuantity || 0) }}</template></el-table-column>
          <el-table-column label="操作" width="115"><template #default="{ row, $index }">
            <el-button v-if="!locked" link type="danger" @click="form.lines.splice($index, 1)">删除</el-button>
            <el-button v-else-if="row.supplyMode === 'STOCK' && row.salesProductId && canManage && Number(row.quantity) > Number(row.allocatedQuantity || 0)" link type="primary" @click="openOutbound(row)">生成出库</el-button>
          </template></el-table-column>
        </el-table>
        <p class="tip">自产、外采和定制需求可以先不关联最终库存产品；确认订单后保留每个项目的独立明细 ID。</p>
      </div>
      <template #footer>
        <el-button @click="drawer = false">关闭</el-button>
        <el-button v-if="canManage && orderStatus === 'DRAFT'" :loading="saving" @click="save">保存草稿</el-button>
        <el-button v-if="canManage && editingId && orderStatus === 'DRAFT'" type="primary" :loading="saving" @click="submit">提交审批</el-button>
        <el-button v-if="canApprove && pending" type="success" :loading="saving" @click="approve">审批通过</el-button>
        <el-button v-if="canApprove && pending" type="danger" :loading="saving" @click="reject">驳回</el-button>
      </template>
    </el-drawer>

    <ProductionTaskOrderDrawer v-model="productionDialog" mode="configure" :initial="configuringLine?.productionConfig" @configured="applyProductionConfig" />

    <el-dialog v-model="outboundDialog" title="从订单明细生成出库草稿" width="440px">
      <p>订单需求将带入出库草稿，库存要到出库过账时才扣减。</p>
      <el-select v-model="selectedWarehouse" placeholder="选择发货仓库" style="width: 100%"><el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" /></el-select>
      <div style="margin-top: 14px"><el-input-number v-model="outboundQuantity" :min="0.001" :precision="3" :controls="false" /> <span class="tip">本次发货数量，可分批</span></div>
      <template #footer><el-button @click="outboundDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="generateOutbound">生成草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { notify } from '@/composables/ui/useToast'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { fetchSalesOutboundWarehouses, fetchAvailableStock, newRequestKey } from '@/api/salesOutbound'
import ProductionTaskOrderDrawer from '@/components/production/ProductionTaskOrderDrawer.vue'
import OrderFulfillmentSteps from '@/components/sales/OrderFulfillmentSteps.vue'
import { fetchSalesOrders, fetchSalesOrder, createSalesOrder, updateSalesOrder,
  submitSalesOrderApproval, approveSalesOrder, rejectSalesOrder,
  SALES_ORDER_STATUS_TEXT,
  createOrderOutboundDraft, searchOrderProducts,
  type SalesOrder, type SalesOrderLine, type SalesOrderStatus, type ProductionDemandConfig } from '@/api/salesOrder'
import { APPROVAL_STATUS_TEXT, APPROVAL_ACTION_TEXT, type ApprovalEntry, type ApprovalStatus } from '@/api/approval'

const router = useRouter()
const route = useRoute()
const auth = useCurrentUser()
const canManage = computed(() => auth.can('sales_order.manage'))
/** 审批是**独立权限**（#35）：有管理权 ≠ 有审批权，故按钮各按各的权限显示。 */
const canApprove = computed(() => auth.can('sales_order.approve'))
const canReadTasks = computed(() => ['manufacturing.manage', 'manufacturing.report', 'manufacturing.inspect', 'manufacturing.report.override', 'manufacturing.receive', 'manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all'].some(permission => auth.can(permission)))
const keyword = ref('')
const status = ref('')
const page = ref(1)
const total = ref(0)
const items = ref<SalesOrder[]>([])
const loading = ref(false)
const drawer = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const orderNo = ref('')
const orderStatus = ref<SalesOrderStatus | ''>('')
const confirmed = computed(() => orderStatus.value === 'CONFIRMED')
const pending = computed(() => orderStatus.value === 'PENDING_APPROVAL')
/** 已提交或已确认都不许改：待审批时改单，等于老板审的不是同一张单。 */
const locked = computed(() => confirmed.value || pending.value)
const approvals = ref<ApprovalEntry[]>([])
/** 审批态是独立于业务状态的一列（驳回后 status 回到草稿，只有它记得被驳回）。 */
const approvalStatus = ref<ApprovalStatus>('NONE')
const submittedAt = ref<string | null>(null)
const decidedAt = ref<string | null>(null)
const form = reactive<{ customerName: string; orderDate: string; requestedDeliveryDate: string; remark: string; lines: SalesOrderLine[] }>({ customerName: '', orderDate: '', requestedDeliveryDate: '', remark: '', lines: [] })
const products = ref<Array<{ id: number; productCode: string; productName: string; specification?: string; unit: string }>>([])
const productsLoading = ref(false)
const warehouses = ref<Array<{ id: number; warehouseName: string }>>([])
const outboundDialog = ref(false)
const outboundItem = ref<SalesOrderLine | null>(null)
const selectedWarehouse = ref<number>()
const outboundQuantity = ref<number>(1)
const saveKey = ref('')
const outboundKey = ref('')
const productionDialog = ref(false)
const configuringLine = ref<SalesOrderLine | null>(null)

function today() { return new Date().toISOString().slice(0, 10) }
function money(n?: number | null) { return n == null ? '待定' : `¥${Number(n).toFixed(2)}` }
function statusTag(s?: string) {
  if (s === 'CONFIRMED') return 'success'
  if (s === 'PENDING_APPROVAL') return 'warning'
  return 'info'
}
function approvalTag(s?: ApprovalStatus) {
  if (s === 'APPROVED') return 'success'
  if (s === 'PENDING') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}
/** 后端回的是 "2026-09-24T18:04:01"，秒就够用（与采购订单视图同口径）。 */
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
function addLine() { form.lines.push({ productName: '', specification: '', supplyMode: 'UNDECIDED', unit: '台', quantity: 1, unitPrice: null }) }
const orderCustomerId = ref<number | null>(null)

function onSupplyModeChange(line: SalesOrderLine) {
  if (line.supplyMode === 'MAKE') { line.salesProductId = null; line.productName = ''; line.available = undefined }
  // 现货：必须从可售库存重选（产品档案里的产品不代表有货）
  else if (line.supplyMode === 'STOCK') {
    line.salesProductId = null; line.productName = ''; line.available = undefined
    void searchStock('')
  }
  if (line.supplyMode !== 'MAKE') line.productionConfig = null
}

function openProductionConfig(index: number) {
  configuringLine.value = form.lines[index] ?? null
  productionDialog.value = !!configuringLine.value
}
function productionSummary(config: ProductionDemandConfig) {
  return [config.boxShape, config.boxSize, config.moldArrangement, ...config.optionalConfigs].filter(Boolean).join(' · ')
}
function applyProductionConfig(config: ProductionDemandConfig, specification: string) {
  if (!configuringLine.value) return
  configuringLine.value.productionConfig = config
  configuringLine.value.productName = config.productName
  configuringLine.value.specification = specification
}
async function load(p = page.value) {
  page.value = p; loading.value = true
  try { const data = await fetchSalesOrders({ keyword: keyword.value, status: status.value, page: p, size: 20 }); items.value = data.items; total.value = data.total }
  catch (e) { notify.errorWithDetail('加载销售订单失败', e) }
  finally { loading.value = false }
}
function openCreate() {
  editingId.value = null; orderNo.value = ''; orderStatus.value = 'DRAFT'
  approvals.value = []; approvalStatus.value = 'NONE'; submittedAt.value = null; decidedAt.value = null
  orderCustomerId.value = null
  saveKey.value = newRequestKey()
  Object.assign(form, { customerName: '', orderDate: today(), requestedDeliveryDate: '', remark: '', lines: [] })
  addLine(); drawer.value = true
}
/** 把订单装载进表单（含明细行 id）：打开详情与建单前共用，避免两处各写一遍。 */
async function loadIntoForm(id: number): Promise<SalesOrderLine[]> {
  const data = await fetchSalesOrder(id)
  editingId.value = id; orderNo.value = data.head.orderNo; orderStatus.value = data.head.status
  approvalStatus.value = data.head.approvalStatus || 'NONE'
  submittedAt.value = data.head.approvalSubmittedAt ?? null
  decidedAt.value = data.head.approvalDecidedAt ?? null
  approvals.value = data.approvals || []
  orderCustomerId.value = data.head.customerId ?? null
  saveKey.value = newRequestKey()
  const lines: SalesOrderLine[] = data.items.map(line => ({ ...line, productionConfig: line.productionConfigJson
    ? JSON.parse(line.productionConfigJson) as ProductionDemandConfig : null }))
  Object.assign(form, { customerName: data.head.customerName, orderDate: String(data.head.orderDate).slice(0, 10),
    requestedDeliveryDate: data.head.requestedDeliveryDate ? String(data.head.requestedDeliveryDate).slice(0, 10) : '', remark: data.head.remark || '', lines })
  // 现货行要给得出「可售库存」才有判定依据；同时把已选产品喂给下拉显示名称
  for (const line of lines) {
    if (line.supplyMode === 'STOCK' && line.salesProductId) {
      stockPicked.value[line.salesProductId] = {
        id: line.salesProductId, productName: line.productName, specification: line.specification || '',
        unit: line.unit, available: Number(line.available ?? 0)
      }
    }
  }
  if (lines.some(line => line.supplyMode === 'STOCK')) void searchStock('')
  return lines
}
async function openDetail(id: number) {
  try { await loadIntoForm(id); drawer.value = true }
  catch (e) { notify.errorWithDetail('读取销售订单失败', e) }
}
function productOptionsFor(line: SalesOrderLine) {
  if (!line.salesProductId || products.value.some(p => p.id === line.salesProductId)) return products.value
  return [{ id: line.salesProductId, productCode: '', productName: line.productName, unit: line.unit }, ...products.value]
}
async function searchProducts(term: string) {
  if (term.trim().length < 2) { products.value = []; return }
  productsLoading.value = true
  try { products.value = await searchOrderProducts(term) }
  catch { products.value = [] }
  finally { productsLoading.value = false }
}

/**
 * 现货明细选品 = **从可售库存里选**（09-24 LQ 定：先生产入库，才在订单里作为现货可选）。
 *
 * 口径与出库扣减完全一致（`product_inventory_balances` 的 canonical 口径，数量 &gt; 0）：
 * 只有真能发货的货才选得到。来源含期初合并入库、完工入库、采购到货入库——
 * 所以「直接下生产任务单 → 完工入库 → 回这里选作现货」这条链是通的。
 *
 * 按产品合计可用量：仓库到出库时才选，订单行只认产品。
 */
interface StockOption { id: number; productName: string; specification?: string; unit: string; available: number }

const stockOptions = ref<StockOption[]>([])
const stockLoading = ref(false)
/** 打开历史订单时已选但不在当前列表里的产品，保证下拉能显示名称。 */
const stockPicked = ref<Record<number, StockOption>>({})

async function searchStock(term: string) {
  stockLoading.value = true
  try {
    const rows = await fetchAvailableStock({ keyword: term.trim(), limit: 200 })
    const merged = new Map<number, StockOption>()
    for (const r of rows) {
      const id = r.canonicalProductId
      const hit = merged.get(id)
      if (hit) hit.available += Number(r.available)
      else merged.set(id, {
        id, productName: r.productName, specification: r.specification,
        unit: r.unit, available: Number(r.available)
      })
    }
    stockOptions.value = [...merged.values()]
  } catch (e) { notify.errorWithDetail('加载可售库存失败', e); stockOptions.value = [] }
  finally { stockLoading.value = false }
}
function stockOptionsFor(line: SalesOrderLine) {
  if (!line.salesProductId || stockOptions.value.some(s => s.id === line.salesProductId)) return stockOptions.value
  const picked = stockPicked.value[line.salesProductId]
  return picked ? [picked, ...stockOptions.value] : stockOptions.value
}
/** 该行当前可售库存（`null` = 库存清单还没加载）。 */
function stockAvailableOf(line: SalesOrderLine) {
  const hit = stockOptions.value.find(s => s.id === line.salesProductId)
  return hit ? hit.available : (line.available ?? null)
}
/** 数量超出可售库存的缺口（`0` = 不缺或还没依据）。 */
function stockShortageOf(line: SalesOrderLine) {
  const available = stockAvailableOf(line)
  if (available == null) return 0
  return Math.max(0, Number(line.quantity) - Number(available))
}
function onStockPicked(line: SalesOrderLine) {
  const picked = stockOptions.value.find(s => s.id === line.salesProductId)
  if (!picked) return
  stockPicked.value[picked.id] = picked
  line.available = picked.available
  line.productName = picked.productName
  if (!line.specification) line.specification = picked.specification || ''
  if (picked.unit) line.unit = picked.unit
}
/** 现货选不到货 / 货不够 → 改为自产并在销售订单内配置。 */
function switchToMake(index: number) {
  const line = form.lines[index]
  if (!line || locked.value || line.supplyMode === 'MAKE') return
  line.supplyMode = 'MAKE'
  onSupplyModeChange(line)
  openProductionConfig(index)
}
function payload() {
  return { customerId: orderCustomerId.value, customerName: form.customerName.trim(), orderDate: form.orderDate,
    requestedDeliveryDate: form.requestedDeliveryDate || null, remark: form.remark,
    lines: form.lines.map(l => ({ id: l.id, productName: l.productName.trim(), specification: l.specification || '',
      supplyMode: l.supplyMode, unit: l.unit.trim(), quantity: l.quantity, unitPrice: l.unitPrice ?? null,
      salesProductId: l.salesProductId || null, requestedDeliveryDate: l.requestedDeliveryDate || null,
      remark: l.remark || '', productionConfig: l.supplyMode === 'MAKE' ? l.productionConfig || null : null })) }
}
async function save() {
  if (saving.value) return
  if (!form.customerName.trim()) { notify.warning('请填写客户'); return }
  if (!form.lines.length || form.lines.some(l => !l.productName.trim() || !l.unit.trim() || !Number(l.quantity))) {
    notify.warning('请补齐产品、单位和数量'); return
  }
  saving.value = true
  try {
    const id = editingId.value ? await updateSalesOrder(editingId.value, payload(), saveKey.value) : await createSalesOrder(payload(), saveKey.value)
    notify.success('订单草稿已保存'); await load(1); await openDetail(id)
  } catch (e) { notify.errorWithDetail('保存销售订单失败', e) }
  finally { saving.value = false }
}
/**
 * 提交审批（草稿 → 待审批）。
 *
 * ⚠️ 提交 **不等于** 审批通过（决策 #35）：原来这里是 `confirmSalesOrder` 一步跳到「已确认」，
 * 等于业务员自己批自己，审批关口形同虚设。现在只能把单子推给老板。
 */
async function submit() {
  if (saving.value || !editingId.value) return
  // 前端先自检，少一次往返报错；后端同样会拦（这里只是体验优化，判据以后端为准）
  if (form.lines.some(line => line.supplyMode === 'UNDECIDED')) { notify.warning('请先确定每项产品的供货方式'); return }
  if (form.lines.some(line => line.supplyMode === 'MAKE' && !line.productionConfig)) { notify.warning('请先配置自产产品'); return }
  try { await ElMessageBox.confirm('提交后订单进入待审批，审批期间不能修改、也不能生成出库。请先核对客户、配置、数量和交期。', '提交审批') }
  catch { return }
  saving.value = true
  try {
    // 先落盘再提交：审批人看到的必须就是当前表单内容
    await updateSalesOrder(editingId.value, payload(), newRequestKey())
    await submitSalesOrderApproval(editingId.value, newRequestKey())
    notify.success('已提交审批，等待老板审批'); await load(); await openDetail(editingId.value)
  } catch (e) { notify.errorWithDetail('提交审批失败', e) }
  finally { saving.value = false }
}

/** 审批通过（待审批 → 已确认）。权限是 `sales_order.approve`，与「提交」是两个独立权限。 */
async function approve() {
  if (saving.value || !editingId.value) return
  try { await ElMessageBox.confirm('审批通过后订单确认，需求锁定，可按明细办理现货出库 / 生产 / 采购。', '审批通过') }
  catch { return }
  saving.value = true
  try {
    await approveSalesOrder(editingId.value, undefined, newRequestKey())
    notify.success('已审批通过'); await load(); await openDetail(editingId.value)
  } catch (e) { notify.errorWithDetail('审批失败', e) }
  finally { saving.value = false }
}

/** 驳回（待审批 → 草稿），原因必填：不给理由，提交人不知道该改什么。 */
async function reject() {
  if (saving.value || !editingId.value) return
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因，提交人会看到。', '驳回销售订单', {
      inputType: 'textarea',
      inputPlaceholder: '例如：单价与报价不符 / 交期无法满足',
      inputValidator: v => (String(v ?? '').trim() ? true : '驳回原因必填'),
    })
    comment = String(value ?? '').trim()
  } catch { return }
  saving.value = true
  try {
    await rejectSalesOrder(editingId.value, comment, newRequestKey())
    notify.success('已驳回，订单退回草稿'); await load(); await openDetail(editingId.value)
  } catch (e) { notify.errorWithDetail('驳回失败', e) }
  finally { saving.value = false }
}
async function openOutbound(line: SalesOrderLine) {
  outboundItem.value = line; selectedWarehouse.value = undefined; outboundKey.value = newRequestKey()
  outboundQuantity.value = Number(line.quantity) - Number(line.allocatedQuantity || 0)
  try { warehouses.value = await fetchSalesOutboundWarehouses() }
  catch (e) { notify.errorWithDetail('加载仓库失败', e); return }
  outboundDialog.value = true
}
async function generateOutbound() {
  if (saving.value) return
  if (!editingId.value || !outboundItem.value?.id || !selectedWarehouse.value || !outboundQuantity.value) { notify.warning('请选择仓库并填写数量'); return }
  saving.value = true
  try {
    const id = await createOrderOutboundDraft(editingId.value, outboundItem.value.id, selectedWarehouse.value, outboundQuantity.value, outboundKey.value)
    notify.success(`已生成出库草稿 #${id}，请核对后过账`)
    outboundDialog.value = false
    void router.push({ path: '/sales-outbounds', query: { outboundId: String(id) } })
  } catch (e) { notify.errorWithDetail('生成出库草稿失败', e) }
  finally { saving.value = false }
}

await load()
watch(() => route.query.orderId, value => {
  const id = Number(value)
  if (Number.isSafeInteger(id) && id > 0) void openDetail(id)
}, { immediate: true })
</script>

<style scoped>
.sales-orders-page { padding: 22px; }
.page-head, .filters, .line-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-head { margin-bottom: 18px; }
.page-head h2, .line-heading h3 { margin: 0 0 6px; }
.page-head p, .tip { color: var(--el-text-color-secondary); margin: 0; }
.filters { justify-content: flex-start; margin-bottom: 16px; }
.filters .el-input { max-width: 300px; }
.filters .el-select { width: 150px; }
.pager { justify-content: flex-end; margin-top: 16px; }
.order-form { padding: 0 10px; }
.approval-meta { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.approval-meta .tip { margin-top: 0; }
.approval-block { margin-top: 16px; }
.approval-block h3 { margin: 0 0 8px; }
.order-head-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; margin-top: 15px; }
.line-heading { margin: 18px 0 10px; }
.line-second { margin-top: 6px; }
.tip { margin-top: 12px; font-size: 13px; }
.tip.warn { color: var(--el-color-warning); }
</style>
