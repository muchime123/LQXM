<template>
  <div class="purchase-orders-page">
    <header class="page-head">
      <div>
        <h2>采购订单</h2>
        <p>销售订单里的「外采」明细在这里落地：选供应商、定履约方式，到货入库或由供应商直发客户。</p>
      </div>
      <el-button v-if="canManage" type="primary" @click="openCreate()">新建采购订单</el-button>
    </header>

    <el-card v-if="demands.length" class="demand-card" shadow="never">
      <template #header>
        <div class="demand-head">
          <span>待采购需求 <el-tag size="small" type="warning">{{ demands.length }}</el-tag></span>
          <el-button link type="primary" @click="demandOpen = !demandOpen">{{ demandOpen ? '收起' : '展开' }}</el-button>
        </div>
      </template>
      <el-table v-if="demandOpen" :data="demands" size="small" border>
        <el-table-column prop="orderNo" label="销售订单" width="140" />
        <el-table-column prop="customerName" label="客户" min-width="150" />
        <el-table-column prop="productName" label="外采产品" min-width="200" />
        <el-table-column prop="specification" label="规格要求" min-width="160" show-overflow-tooltip />
        <el-table-column label="数量" width="110"><template #default="{ row }">{{ qty(row.quantity) }} {{ row.unit }}</template></el-table-column>
        <el-table-column prop="dueDate" label="期望交期" width="120" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canManage" link type="primary" @click="openCreate(row)">建采购单</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="filters">
      <el-input v-model="keyword" placeholder="采购单号、供应商或产品" clearable @keyup.enter="load(1)" />
      <el-select v-model="status" placeholder="全部状态" clearable @change="load(1)">
        <el-option v-for="(label, value) in PURCHASE_STATUS_TEXT" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="fulfillmentMode" placeholder="全部履约方式" clearable @change="load(1)">
        <el-option v-for="(label, value) in FULFILLMENT_MODE_TEXT" :key="value" :label="label" :value="value" />
      </el-select>
      <el-button @click="load(1)">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="poNo" label="采购单号" width="150" />
      <el-table-column prop="supplierName" label="供应商" min-width="180" show-overflow-tooltip />
      <el-table-column label="来源销售订单" width="150">
        <template #default="{ row }">
          <el-button v-if="row.salesOrderNo && row.salesOrderItemId" link type="primary" @click="openSalesOrder(row)">{{ row.salesOrderNo }}</el-button>
          <span v-else class="tip">未关联</span>
        </template>
      </el-table-column>
      <el-table-column label="履约方式" width="115">
        <template #default="{ row }">
          <el-tag :type="row.fulfillmentMode === 'DROP_SHIP' ? 'warning' : ''" size="small">
            {{ FULFILLMENT_MODE_TEXT[row.fulfillmentMode as PurchaseFulfillmentMode] || row.fulfillmentMode }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="到货进度" width="130">
        <template #default="{ row }">
          <span v-if="row.fulfillmentMode === 'DROP_SHIP'" class="tip">直发客户</span>
          <span v-else>{{ qty(row.receivedQuantity) }} / {{ qty(row.orderedQuantity) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="120"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ PURCHASE_STATUS_TEXT[row.status as PurchaseOrderStatus] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }">
        <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination class="pager" background layout="total, prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="load" />

    <!-- ==================== 建单 ==================== -->
    <el-drawer v-model="createDrawer" title="新建采购订单" size="min(1080px, 96vw)">
      <div class="form-body">
        <el-alert v-if="!form.salesOrderItemId" type="info" :closable="false"
          title="这笔采购不关联销售订单（如备货采购）。若来自销售需求，请从上方「待采购需求」点「建采购单」，以便客户与交期带入。" />
        <el-alert v-else type="success" :closable="false"
          :title="`已关联销售订单 ${form.salesOrderNo || ''} / ${form.customerName || ''}。产品、规格、单位和数量沿用已确认需求；可在同一采购单分批到货。`" />
        <el-descriptions v-if="form.salesOrderItemId && demandConfig" title="销售确认的外采要求" :column="2" border size="small" style="margin: 12px 0">
          <el-descriptions-item label="型号">{{ demandConfig.model || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="品牌">{{ demandConfig.brand || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="技术要求">{{ demandConfig.technicalRequirements || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="验收要求">{{ demandConfig.acceptanceCriteria || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="包装要求">{{ demandConfig.packagingRequirements || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="交付要求">{{ demandConfig.deliveryRequirements || '未指定' }}</el-descriptions-item>
        </el-descriptions>

        <el-form label-position="top" class="head-form">
          <el-form-item label="供应商">
            <el-input v-model="form.supplierName" placeholder="填写供应商名称；新 ERP 采购无需先导入氚云供应商" maxlength="190" @input="form.supplierId = null" />
          </el-form-item>
          <el-form-item label="履约方式">
            <el-radio-group v-model="form.fulfillmentMode" @change="onModeChange">
              <el-radio-button value="WAREHOUSE">到货入库</el-radio-button>
              <el-radio-button value="DROP_SHIP">供应商直发客户</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.fulfillmentMode === 'WAREHOUSE'" label="默认入库仓库">
            <el-select v-model="form.warehouseId" clearable placeholder="到货时可再指定" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="预计到货日期">
            <el-date-picker v-model="form.expectedArrivalDate" type="date" value-format="YYYY-MM-DD" clearable style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" maxlength="1000" /></el-form-item>
        </el-form>

        <div class="line-heading">
          <h3>采购明细</h3>
          <el-button v-if="!form.salesOrderItemId" @click="addLine">添加明细</el-button>
        </div>
        <p class="tip">到货入库需要产品身份。可选已有产品，或为这件外采品建立本地产品身份；供应商直发不经过本地库存。</p>
        <el-table :data="form.lines" border>
          <el-table-column label="入库产品档案" min-width="220"><template #default="{ row }">
            <el-select v-model="row.salesProductId" filterable remote clearable :remote-method="searchProducts" :loading="productsLoading" placeholder="输入产品名称或编号搜索" style="width:100%">
              <el-option v-for="p in productOptionsFor(row)" :key="p.id" :value="p.id" :label="`${p.productName} · ${p.specification || '无规格'} · ${p.productCode}`" />
            </el-select>
            <el-button v-if="form.fulfillmentMode === 'WAREHOUSE' && !row.salesProductId" link type="primary" :loading="productCreating" @click="createLocalProduct(row)">没有匹配产品？建立本地产品身份</el-button>
          </template></el-table-column>
          <el-table-column label="产品名称" min-width="220"><template #default="{ row }">
            <el-input v-model="row.productName" :disabled="!!form.salesOrderItemId" maxlength="500" placeholder="采购的产品名称" />
          </template></el-table-column>
          <el-table-column label="规格" min-width="180"><template #default="{ row }">
            <el-input v-model="row.specification" :disabled="!!form.salesOrderItemId" maxlength="1000" placeholder="规格 / 型号" />
          </template></el-table-column>
          <el-table-column label="数量" width="120"><template #default="{ row }">
            <el-input-number v-model="row.quantity" :disabled="!!form.salesOrderItemId" :min="0.000001" :precision="3" :controls="false" />
          </template></el-table-column>
          <el-table-column label="单位" width="90"><template #default="{ row }">
            <el-input v-model="row.unit" :disabled="!!form.salesOrderItemId" maxlength="40" />
          </template></el-table-column>
          <el-table-column label="采购单价" width="130"><template #default="{ row }">
            <el-input-number v-model="row.purchasePrice" :min="0" :precision="2" :controls="false" />
          </template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ $index }">
            <el-button v-if="!form.salesOrderItemId" link type="danger" @click="form.lines.splice($index, 1)">删除</el-button>
          </template></el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="createDrawer = false">关闭</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-drawer>

    <!-- ==================== 详情 ==================== -->
    <el-drawer v-model="detailDrawer" :title="detail ? `采购订单 ${detail.head.poNo}` : '采购订单'" size="min(1080px, 96vw)">
      <div v-if="detail" class="form-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="供应商">{{ detail.head.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="履约方式">
            <el-tag :type="detail.head.fulfillmentMode === 'DROP_SHIP' ? 'warning' : ''" size="small">
              {{ FULFILLMENT_MODE_TEXT[detail.head.fulfillmentMode] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.head.status)" size="small">{{ PURCHASE_STATUS_TEXT[detail.head.status] }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="入库仓库">{{ detail.head.warehouseName || (detail.head.fulfillmentMode === 'DROP_SHIP' ? '直发客户，不入仓' : '未指定') }}</el-descriptions-item>
          <el-descriptions-item label="采购预计到货">{{ detail.head.expectedArrivalDate || '未填' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.head.salesOrderItemId" label="当前销售需求交期">{{ detail.head.salesDemandDeliveryDate || '未填' }} · 第 {{ detail.head.salesOrderRevisionNo || 1 }} 版</el-descriptions-item>
          <el-descriptions-item label="采购金额">{{ money(detail.head.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="关联销售订单">
            <el-button v-if="detail.head.salesOrderNo" link type="primary" @click="openSalesOrder(detail.head)">{{ detail.head.salesOrderNo }}</el-button>
            <span v-else>未关联</span>
          </el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.head.customerName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="建单人">{{ detail.head.createdByName }}</el-descriptions-item>
          <el-descriptions-item label="审批状态">
            <el-tag :type="approvalTag(detail.head.approvalStatus)" size="small">
              {{ APPROVAL_STATUS_TEXT[detail.head.approvalStatus || 'NONE'] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提交审批">
            {{ detail.head.approvalSubmittedAt ? formatTime(detail.head.approvalSubmittedAt) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="审批决定">
            {{ detail.head.approvalDecidedAt ? formatTime(detail.head.approvalDecidedAt) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.head.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-if="detailPurchaseConfig" title="销售确认的外采要求" :column="2" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="型号">{{ detailPurchaseConfig.model || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="品牌">{{ detailPurchaseConfig.brand || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="技术要求">{{ detailPurchaseConfig.technicalRequirements || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="验收要求">{{ detailPurchaseConfig.acceptanceCriteria || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="包装要求">{{ detailPurchaseConfig.packagingRequirements || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="交付要求">{{ detailPurchaseConfig.deliveryRequirements || '未指定' }}</el-descriptions-item>
        </el-descriptions>

        <div class="line-heading"><h3>采购明细</h3></div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="lineNo" label="#" width="50" />
          <el-table-column prop="productName" label="产品名称" min-width="200" show-overflow-tooltip />
          <el-table-column prop="specification" label="规格" min-width="150" show-overflow-tooltip />
          <el-table-column label="数量" width="110"><template #default="{ row }">{{ qty(row.quantity) }} {{ row.unit }}</template></el-table-column>
          <el-table-column label="采购单价" width="110"><template #default="{ row }">{{ money(row.purchasePrice) }}</template></el-table-column>
          <el-table-column label="已到货" width="100"><template #default="{ row }">
            <span :class="{ 'warn-text': Number(row.receivedQuantity || 0) < Number(row.quantity) }">{{ qty(row.receivedQuantity) }}</span>
          </template></el-table-column>
        </el-table>

        <div v-if="detail.receipts.length" class="line-heading"><h3>到货记录</h3></div>
        <el-table v-if="detail.receipts.length" :data="detail.receipts" border size="small">
          <el-table-column prop="receiptNo" label="到货单号" width="150" />
          <el-table-column prop="warehouseName" label="入库仓库" width="130" />
          <el-table-column prop="receivedAt" label="到货时间" width="170" />
          <el-table-column prop="receivedByName" label="经办人" width="100" />
          <el-table-column label="状态" width="90"><template #default="{ row }">
            <el-tag :type="row.status === 'POSTED' ? 'success' : 'info'" size="small">{{ row.status === 'POSTED' ? '已入库' : '已冲正' }}</el-tag>
          </template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{ row }">
            <el-button v-if="canManage && row.status === 'POSTED'" link type="danger" @click="doReverseReceipt(row)">冲正</el-button>
          </template></el-table-column>
        </el-table>

        <el-alert v-if="detail.dropShipOutbound" class="drop-alert" type="success" :closable="false"
          :title="`已生成代发出库单 ${detail.dropShipOutbound.outboundNo}（${detail.dropShipOutbound.status === 'POSTED' ? '已过账' : '草稿'}）。过账不扣本地库存。`" />

        <div v-if="detail.approvals?.length" class="line-heading"><h3>审批记录</h3></div>
        <el-table v-if="detail.approvals?.length" :data="detail.approvals" border size="small">
          <el-table-column label="动作" width="100"><template #default="{ row }">
            <el-tag :type="row.action === 'APPROVE' ? 'success' : row.action === 'REJECT' ? 'danger' : 'info'" size="small">
              {{ approvalActionText(row.action) }}
            </el-tag>
          </template></el-table-column>
          <el-table-column prop="actorName" label="操作人" width="100" />
          <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
          <el-table-column prop="comment" label="意见" min-width="200" show-overflow-tooltip />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="detailDrawer = false">关闭</el-button>
        <el-button v-if="detail?.head.salesOrderId && detail?.head.salesOrderItemId && auth.can('purchase_order.manage')" type="warning" :loading="saving" @click="reportExecutionException">登记履约风险／可行交期</el-button>
        <el-button v-if="canCancel" link type="danger" @click="doCancel">取消订单</el-button>
        <el-button v-if="canClose" link type="warning" @click="doClose">结案</el-button>
        <el-button v-if="canApprove && headStatus === 'PENDING_APPROVAL'" link type="danger" @click="doReject">驳回</el-button>
        <el-button v-if="canSubmit" type="primary" :loading="saving" @click="doSubmit">提交审批</el-button>
        <el-button v-if="canApprove && headStatus === 'PENDING_APPROVAL'" type="primary" :loading="saving" @click="doApprove">审批通过</el-button>
        <el-button v-if="canReceive" type="success" :loading="saving" @click="openReceive">到货入库</el-button>
        <el-button v-if="canDropShip" type="warning" :loading="saving" @click="doDropShip">生成代发出库单</el-button>
        <!-- 代发的推进在采购侧：货由供应商直发客户，销售看不到货，让销售去点过账是语义错位 -->
        <el-button v-if="canPostDropShip" type="success" :loading="saving" @click="doPostDropShip">代发过账</el-button>
        <el-button v-if="canReverseDropShip" link type="danger" @click="doReverseDropShip">冲销代发出库单</el-button>
      </template>
    </el-drawer>

    <!-- ==================== 到货入库 ==================== -->
    <el-dialog v-model="receiveDialog" title="到货入库" width="640px">
      <p class="tip">本次到货数量会写入本地库存账（可分批到货）。已发出的货不能靠冲正退回，系统会因库存不足直接拒绝。</p>
      <el-form label-position="top">
        <el-form-item label="入库仓库">
          <el-select v-model="receiveForm.warehouseId" placeholder="请选择仓库" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="到货说明"><el-input v-model="receiveForm.remark" maxlength="1000" /></el-form-item>
      </el-form>
      <el-table :data="receiveForm.items" border size="small">
        <el-table-column prop="productName" label="产品" min-width="200" show-overflow-tooltip />
        <el-table-column label="未到货" width="110"><template #default="{ row }">{{ qty(row.pending) }} {{ row.unit }}</template></el-table-column>
        <el-table-column label="本次到货" width="150"><template #default="{ row }">
          <el-input-number v-model="row.quantity" :min="0" :max="row.pending" :precision="3" :controls="false" />
        </template></el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="receiveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doReceive">确认入库</el-button>
      </template>
    </el-dialog>
  </div>
    <SalesExecutionExceptionDialog ref="exceptionDialog" />
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { notify } from '@/composables/ui/useToast'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { newRequestKey } from '@/api/salesOutbound'
import { type PurchaseDemandConfig } from '@/api/salesOrder'
import SalesExecutionExceptionDialog from '@/components/sales/SalesExecutionExceptionDialog.vue'
import {
  fetchPurchaseOrders, fetchPurchaseOrder, fetchPurchaseDemands, searchPurchaseProducts, createPurchaseProduct,
  fetchPurchaseWarehouses, createPurchaseOrder, cancelPurchaseOrder,
  submitPurchaseOrderApproval, approvePurchaseOrder, rejectPurchaseOrder,
  receivePurchaseOrder, reversePurchaseReceipt, createDropShipOutbound,
  postDropShipOutbound, reverseDropShipOutbound, closePurchaseOrder,
  PURCHASE_STATUS_TEXT, FULFILLMENT_MODE_TEXT, APPROVAL_ACTION_TEXT, APPROVAL_STATUS_TEXT,
  type PurchaseOrder, type PurchaseOrderDetail, type PurchaseOrderLine, type PurchaseDemand,
  type PurchaseFulfillmentMode, type PurchaseOrderStatus, type ApprovalStatus,
} from '@/api/purchaseOrder'

const router = useRouter()
const exceptionDialog = ref<InstanceType<typeof SalesExecutionExceptionDialog>>()
const route = useRoute()
const auth = useCurrentUser()
const canManage = computed(() => auth.can('purchase_order.manage'))

const keyword = ref('')
const status = ref(typeof route.query.status === 'string' && route.query.status in PURCHASE_STATUS_TEXT ? route.query.status : '')
const fulfillmentMode = ref(['WAREHOUSE', 'DROP_SHIP'].includes(String(route.query.fulfillmentMode)) ? String(route.query.fulfillmentMode) : '')
const page = ref(1)
const total = ref(0)
const items = ref<PurchaseOrder[]>([])
const loading = ref(false)
const saving = ref(false)

const demands = ref<PurchaseDemand[]>([])
const demandConfig = ref<PurchaseDemandConfig | null>(null)
const detailPurchaseConfig = computed<PurchaseDemandConfig | null>(() => detail.value?.head.purchaseConfigJson ? JSON.parse(detail.value.head.purchaseConfigJson) as PurchaseDemandConfig : null)
const demandOpen = ref(true)

const productOptions=ref<Array<{id:number;productCode:string;productName:string;specification:string;unit:string}>>([])
const productsLoading=ref(false)
const productCreating=ref(false)
async function searchProducts(term:string) {
  if(term.trim().length<2){productOptions.value=[];return}
  productsLoading.value=true
  try{productOptions.value=await searchPurchaseProducts(term)}catch{productOptions.value=[]}finally{productsLoading.value=false}
}
function productOptionsFor(line:PurchaseOrderLine) {
  if(!line.salesProductId || productOptions.value.some(p=>p.id===line.salesProductId))return productOptions.value
  return [{id:line.salesProductId,productCode:'已选产品',productName:line.productName,specification:line.specification || '',unit:line.unit},...productOptions.value]
}
async function createLocalProduct(line: PurchaseOrderLine) {
  if (!line.productName.trim() || !line.unit.trim()) { notify.warning('请先填写产品名称和单位'); return }
  productCreating.value = true
  try {
    const product = await createPurchaseProduct({
      productName: line.productName.trim(), specification: line.specification?.trim() || '', unit: line.unit.trim()
    })
    line.salesProductId = product.id
    productOptions.value = [product, ...productOptions.value.filter(item => item.id !== product.id)]
    notify.success(`已建立本地产品身份 ${product.productCode}`)
  } catch (e) { notify.errorWithDetail('建立本地产品身份失败', e) }
  finally { productCreating.value = false }
}
const warehouses = ref<Array<{ id: number; warehouseCode: string; warehouseName: string }>>([])

const createDrawer = ref(false)
const detailDrawer = ref(false)
const detail = ref<PurchaseOrderDetail | null>(null)
const receiveDialog = ref(false)

const form = reactive({
  supplierId: null as number | null,
  supplierName: '',
  warehouseId: null as number | null,
  fulfillmentMode: 'WAREHOUSE' as PurchaseFulfillmentMode,
  salesOrderItemId: null as number | null,
  salesOrderNo: '',
  customerName: '',
  expectedArrivalDate: '',
  remark: '',
  lines: [] as PurchaseOrderLine[],
})
const receiveForm = reactive({
  warehouseId: null as number | null,
  remark: '',
  items: [] as Array<{ poItemId: number; productName: string; unit: string; pending: number; quantity: number }>,
})
const saveKey = ref('')

const headStatus = computed<PurchaseOrderStatus | undefined>(() => detail.value?.head.status)
const isDropShip = computed(() => detail.value?.head.fulfillmentMode === 'DROP_SHIP')
// 审批关口（#35）：「谁能开单」（manage）与「谁能批」（approve）是两个权限，
// 所以提交按钮与审批按钮的可见性分开算 —— 别用一个 canManage 覆盖两者。
const canApprove = computed(() => auth.can('purchase_order.approve'))
const canSubmit = computed(() => canManage.value && headStatus.value === 'DRAFT')
const canCancel = computed(() => canManage.value && (headStatus.value === 'DRAFT'
  || headStatus.value === 'PENDING_APPROVAL' || headStatus.value === 'CONFIRMED'))
// 结案：部分到货后决定不再追的出口。「全部到货」由收货动作自动结案，不需要人工点；
// 而未到货的单要终止应该走「取消订单」（取消是减损动作，不增加支出授权）。
const canClose = computed(() => canManage.value && headStatus.value === 'RECEIVING')
// 到货入库只属于「入库型」；代发的货从不进本地仓，永远不出现入库按钮。
const canReceive = computed(() => canManage.value && !isDropShip.value
  && (headStatus.value === 'CONFIRMED' || headStatus.value === 'RECEIVING'))
const canDropShip = computed(() => canManage.value && headStatus.value === 'CONFIRMED'
  && isDropShip.value && !detail.value?.dropShipOutbound)
// 代发出库单的「过账 / 冲销」也在采购侧：货由供应商直发客户，履行主体是采购。
// 此前过账端点要 sales_order.manage，只有采购权限的人会卡在草稿态等人接力 —— 修的就是这个跨岗断层。
const dropShipStatus = computed(() => detail.value?.dropShipOutbound?.status)
const canPostDropShip = computed(() => canManage.value && isDropShip.value && dropShipStatus.value === 'DRAFT')
const canReverseDropShip = computed(() => canManage.value && isDropShip.value && dropShipStatus.value === 'POSTED')

function money(n?: number | null) { return n == null ? '待定' : `¥${Number(n).toFixed(2)}` }
function qty(n?: number | null) { return Number(n ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 6 }) }
function statusTag(s: PurchaseOrderStatus) {
  if (s === 'CLOSED') return 'success'
  if (s === 'RECEIVING') return 'warning'
  if (s === 'PENDING_APPROVAL') return 'warning'
  if (s === 'CANCELLED') return 'info'
  if (s === 'CONFIRMED') return 'primary'
  return 'info'
}
function approvalTag(s?: ApprovalStatus) {
  if (s === 'APPROVED') return 'success'
  if (s === 'PENDING') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}
function approvalActionText(a: string) {
  return (APPROVAL_ACTION_TEXT as Record<string, string>)[a] || a
}
/** 后端回的是 "2026-09-24T18:04:01"，列表里 秒 就够用。 */
function formatTime(v?: string | null) {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}

async function load(p = page.value) {
  page.value = p; loading.value = true
  try {
    const data = await fetchPurchaseOrders({
      keyword: keyword.value, status: status.value, fulfillmentMode: fulfillmentMode.value, page: p, size: 20,
    })
    items.value = data.items; total.value = data.total
  } catch (e) { notify.errorWithDetail('加载采购订单失败', e) }
  finally { loading.value = false }
}

async function loadDemands() {
  try { demands.value = await fetchPurchaseDemands() }
  catch { demands.value = [] }
}

async function ensureWarehouses() {
  if (warehouses.value.length) return
  try { warehouses.value = await fetchPurchaseWarehouses() }
  catch (e) { notify.errorWithDetail('加载仓库失败', e) }
}

function addLine() {
  form.lines.push({ productName: '', specification: '', unit: '个', quantity: 1, purchasePrice: null, salesProductId: null })
}

function onModeChange() {
  if (form.fulfillmentMode === 'DROP_SHIP') form.warehouseId = null
}

async function openCreate(demand?: PurchaseDemand) {
  demandConfig.value=demand?.purchaseConfigJson ? JSON.parse(demand.purchaseConfigJson) as PurchaseDemandConfig : null
  saveKey.value = newRequestKey()
  Object.assign(form, {
    supplierId: null, supplierName: '', warehouseId: null, fulfillmentMode: 'WAREHOUSE',
    salesOrderItemId: demand?.itemId ?? null, salesOrderNo: demand?.orderNo ?? '',
    customerName: demand?.customerName ?? '', expectedArrivalDate: '', remark: '', lines: [],
  })
  if (demand) {
    form.lines.push({
      productName: demand.productName, specification: demand.specification ?? '',
      unit: demand.unit, quantity: Number(demand.quantity), purchasePrice: null,
      salesProductId: demand.salesProductId || null, remark: demand.remark ?? '',
    })
  } else addLine()
  await ensureWarehouses()
  createDrawer.value = true
}

async function save() {
  if (saving.value) return
  if (!form.supplierName.trim()) { notify.warning('请选择或填写供应商'); return }
  if (form.fulfillmentMode === 'DROP_SHIP' && !form.salesOrderItemId) {
    notify.warning('供应商直发（代发）必须关联销售订单明细，否则无法确定客户'); return
  }
  if(form.fulfillmentMode==='WAREHOUSE' && form.lines.some(l=>!l.salesProductId)){
    notify.warning('到货入库型采购必须先关联产品档案；若没有匹配产品，请先确认库存产品身份');return
  }
  if (!form.lines.length || form.lines.some(l => !l.productName.trim() || !l.unit.trim() || !Number(l.quantity))) {
    notify.warning('请补齐明细的产品、单位和数量'); return
  }
  saving.value = true
  try {
    const id = await createPurchaseOrder({
      supplierId: form.supplierId, supplierName: form.supplierName.trim(),
      warehouseId: form.fulfillmentMode === 'DROP_SHIP' ? null : form.warehouseId,
      fulfillmentMode: form.fulfillmentMode, salesOrderItemId: form.salesOrderItemId,
      expectedArrivalDate: form.expectedArrivalDate || null, remark: form.remark,
      lines: form.lines.map(l => ({
        productName: l.productName.trim(), specification: l.specification || '',
        salesProductId: l.salesProductId || null, unit: l.unit.trim(),
        quantity: l.quantity, purchasePrice: l.purchasePrice ?? null, remark: l.remark || '',
      })),
    }, saveKey.value)
    notify.success('采购订单草稿已保存')
    createDrawer.value = false
    await load(1); await loadDemands(); await openDetail(id)
  } catch (e) { notify.errorWithDetail('保存采购订单失败', e) }
  finally { saving.value = false }
}

async function openDetail(id: number) {
  try {
    detail.value = await fetchPurchaseOrder(id)
    await ensureWarehouses()
    detailDrawer.value = true
  } catch (e) { notify.errorWithDetail('读取采购订单失败', e) }
}

async function doSubmit() {
  if (!detail.value || saving.value) return
  saving.value = true
  try {
    await submitPurchaseOrderApproval(detail.value.head.id, newRequestKey())
    notify.success('已提交审批，等待审批人处理')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('提交审批失败', e) }
  finally { saving.value = false }
}

async function doApprove() {
  if (!detail.value || saving.value) return
  saving.value = true
  try {
    await approvePurchaseOrder(detail.value.head.id, '审批通过', newRequestKey())
    notify.success('已审批通过，采购订单转为已确认')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('审批失败', e) }
  finally { saving.value = false }
}

async function doReject() {
  if (!detail.value || saving.value) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('驳回后订单退回草稿，建单人可修改后重新提交。', '驳回采购订单', {
      inputPlaceholder: '请填写驳回原因（必填）', confirmButtonText: '确认驳回',
      inputValidator: (v: string) => (v && v.trim() ? true : '驳回原因必填'),
    })
    reason = result.value
  } catch { return }
  saving.value = true
  try {
    await rejectPurchaseOrder(detail.value.head.id, reason.trim(), newRequestKey())
    notify.success('已驳回，订单退回草稿')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('驳回失败', e) }
  finally { saving.value = false }
}

async function doCancel() {
  if (!detail.value || saving.value) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('取消后该采购订单不再占用销售需求，可重新建单。', '取消采购订单', {
      inputPlaceholder: '请填写取消原因', confirmButtonText: '确认取消',
    })
    reason = result.value
  } catch { return }
  saving.value = true
  try {
    await cancelPurchaseOrder(detail.value.head.id, reason, newRequestKey())
    notify.success('采购订单已取消')
    await load(); await loadDemands(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('取消采购订单失败', e) }
  finally { saving.value = false }
}

/**
 * 结案（履行中 → 已结案）：已到货的部分照常发货，剩余不再追。
 *
 * 这是部分到货的唯一出口 —— 已经进过货的单不能「取消」（那会让账实分叉），
 * 而全部到货由收货动作自动结案。做完结案后该销售明细**仍记为已占用**：
 * 这张单是这批货的实际来源，出库来源解析要靠它；要追加数量属于需求变更。
 */
async function doClose() {
  if (!detail.value || saving.value) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '结案用于「已经到过一部分货，剩余不再追」。已到货部分仍可正常从本单办理发货；剩余数量需要追加时请走销售订单的需求变更。',
      '采购订单结案',
      { inputPlaceholder: '请填写结案原因（可选）', confirmButtonText: '确认结案' })
    reason = result.value
  } catch { return }
  saving.value = true
  try {
    await closePurchaseOrder(detail.value.head.id, reason, newRequestKey())
    notify.success('采购订单已结案')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('采购订单结案失败', e) }
  finally { saving.value = false }
}

function openReceive() {
  if (!detail.value) return
  receiveForm.warehouseId = detail.value.head.warehouseId ?? warehouses.value[0]?.id ?? null
  receiveForm.remark = ''
  receiveForm.items = detail.value.items
    .filter(line => Number(line.pendingQuantity ?? 0) > 0)
    .map(line => ({
      poItemId: line.id as number, productName: line.productName, unit: line.unit,
      pending: Number(line.pendingQuantity ?? 0), quantity: Number(line.pendingQuantity ?? 0),
    }))
  if (!receiveForm.items.length) { notify.warning('该采购订单没有待入库的明细'); return }
  receiveDialog.value = true
}

async function doReceive() {
  if (!detail.value || saving.value) return
  if (!receiveForm.warehouseId) { notify.warning('请选择入库仓库'); return }
  const lines = receiveForm.items.filter(i => Number(i.quantity) > 0)
  if (!lines.length) { notify.warning('请填写本次到货数量'); return }
  saving.value = true
  try {
    await receivePurchaseOrder(detail.value.head.id, {
      warehouseId: receiveForm.warehouseId,
      items: lines.map(i => ({ poItemId: i.poItemId, quantity: Number(i.quantity) })),
      remark: receiveForm.remark,
    }, newRequestKey())
    notify.success('到货已入本地库存账')
    receiveDialog.value = false
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('到货入库失败', e) }
  finally { saving.value = false }
}

async function doReverseReceipt(receipt: { id: number; receiptNo: string }) {
  if (!detail.value || saving.value) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(`将把 ${receipt.receiptNo} 的到货从库存账上反向冲回（已发出的货会被拒绝）。`, '冲正到货', {
      inputPlaceholder: '请填写冲正原因', confirmButtonText: '确认冲正',
    })
    reason = result.value
  } catch { return }
  saving.value = true
  try {
    await reversePurchaseReceipt(detail.value.head.id, receipt.id, reason, newRequestKey())
    notify.success('到货已冲正')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('冲正到货失败', e) }
  finally { saving.value = false }
}

async function doDropShip() {
  if (!detail.value || saving.value) return
  try {
    await ElMessageBox.confirm('将按采购明细生成一张销售出库草稿（客户取自销售订单）。该出库单过账时**不扣本地库存**，因为它由供应商直发客户。', '生成代发出库单')
  } catch { return }
  saving.value = true
  try {
    const id = await createDropShipOutbound(detail.value.head.id, newRequestKey())
    notify.success(`已生成代发出库单 #${id}，可在本页直接「代发过账」`)
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('生成代发出库单失败', e) }
  finally { saving.value = false }
}

/** 代发过账（**采购侧**推进）：只落状态与审计，不扣本地库存。 */
async function doPostDropShip() {
  if (!detail.value || saving.value) return
  const no = detail.value.dropShipOutbound?.outboundNo ?? ''
  try {
    await ElMessageBox.confirm(
      `将代发出库单 ${no} 过账。代发的货由供应商直发客户、**不进本地仓**，所以过账只确认发货，不扣减本地库存。`,
      '代发过账')
  } catch { return }
  saving.value = true
  try {
    await postDropShipOutbound(detail.value.head.id, newRequestKey())
    notify.success('代发出库单已过账')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('代发过账失败', e) }
  finally { saving.value = false }
}

/** 代发冲销（**采购侧**推进）：真实语义是「要求供应商别发／召回」；同样不碰本地库存。 */
async function doReverseDropShip() {
  if (!detail.value || saving.value) return
  const no = detail.value.dropShipOutbound?.outboundNo ?? ''
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `冲销代发出库单 ${no}。代发不过库存账，冲销也不会回补库存 —— 这条记录就是给供应商的「别发／召回」指令。`,
      '冲销代发出库单',
      {
        confirmButtonText: '确认冲销', cancelButtonText: '取消',
        inputPlaceholder: '请填写冲销原因（必填）',
        inputValidator: (v: string) => (v && v.trim() ? true : '冲销原因不能为空'),
        type: 'warning',
      })
    reason = result.value
  } catch { return }
  saving.value = true
  try {
    await reverseDropShipOutbound(detail.value.head.id, reason.trim(), newRequestKey())
    notify.success('代发出库单已冲销')
    await load(); await openDetail(detail.value.head.id)
  } catch (e) { notify.errorWithDetail('冲销代发出库单失败', e) }
  finally { saving.value = false }
}

function openSalesOrder(row: { salesOrderId?: number | null }) {
  if (row.salesOrderId) void router.push(`/sales-orders/${row.salesOrderId}`)
}
async function reportExecutionException() {
  const head = detail.value?.head
  if (head?.salesOrderId && head.salesOrderItemId) await exceptionDialog.value?.open(head.salesOrderId, head.salesOrderItemId, true)
}

watch(() => [route.query.status, route.query.fulfillmentMode, route.query.view], async () => {
  status.value = typeof route.query.status === 'string' && route.query.status in PURCHASE_STATUS_TEXT ? route.query.status : ''
  fulfillmentMode.value = ['WAREHOUSE', 'DROP_SHIP'].includes(String(route.query.fulfillmentMode)) ? String(route.query.fulfillmentMode) : ''
  if (route.query.view === 'demands') demandOpen.value = true
  await load(1)
})
if (route.query.view === 'demands') demandOpen.value = true
await Promise.all([load(), loadDemands()])
watch(() => [route.query.purchaseId, route.query.demandItemId, route.query.action], async () => {
  const purchaseId = Number(route.query.purchaseId)
  if (Number.isSafeInteger(purchaseId) && purchaseId > 0) {
    await openDetail(purchaseId)
    if (route.query.action === 'receive' && detail.value?.head.id === purchaseId && canReceive.value) openReceive()
    return
  }
  const requestedDemandId = Number(route.query.demandItemId)
  if (Number.isSafeInteger(requestedDemandId) && requestedDemandId > 0) {
    await loadDemands()
    const match = demands.value.find(d => d.itemId === requestedDemandId)
    if (match && canManage.value) await openCreate(match)
  }
}, { immediate: true })

</script>

<style scoped>
.purchase-orders-page { padding: 22px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.page-head h2 { margin: 0 0 6px; }
.page-head p, .tip { color: var(--el-text-color-secondary); margin: 0; }
.demand-card { margin-bottom: 16px; }
.demand-head { display: flex; align-items: center; justify-content: space-between; }
.filters { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.filters .el-input { max-width: 280px; }
.filters .el-select { width: 160px; }
.pager { justify-content: flex-end; margin-top: 16px; }
.form-body { padding: 0 10px; }
.head-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; margin-top: 15px; }
.line-heading { display: flex; align-items: center; justify-content: space-between; margin: 18px 0 10px; }
.line-heading h3 { margin: 0; }
.sub-input { margin-top: 6px; }
.warn-text { color: var(--el-color-warning); }
.drop-alert { margin-top: 16px; }
</style>
