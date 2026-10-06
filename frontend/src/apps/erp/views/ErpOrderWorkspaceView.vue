<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  fetchSalesOrder,
  fetchSalesOrderReadiness,
  fetchSalesDeliveryOptions,
  fetchSalesFulfillmentReviewStatus,
  fetchSalesExecutionExceptions,
  fetchSalesOrderChanges,
  requestSalesFulfillmentReview,
  submitSalesOrderApproval,
  approveSalesOrder,
  rejectSalesOrder,
  type SalesOrderLine,
  type SalesDeliveryOption,
  type SalesOrderReadiness,
  type SalesExecutionException,
  type SalesOrderChange,
  type OrderPreparationSource
} from '@/api/salesOrder'
import { newRequestKey } from '@/api/salesOutbound'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import OrderDeliveryDialog from '@/components/sales/OrderDeliveryDialog.vue'
import SalesExecutionExceptionDialog from '@/components/sales/SalesExecutionExceptionDialog.vue'
import '@/apps/erp/styles/erp-workspace.css'
const route = useRoute(),
  router = useRouter()
const { can } = useCurrentUser()
const detail = ref<Awaited<ReturnType<typeof fetchSalesOrder>>>()
const loading = ref(false),
  error = ref('')
const readiness = ref<SalesOrderReadiness>()
const reviews = ref<Awaited<ReturnType<typeof fetchSalesFulfillmentReviewStatus>>>([])
const exceptions = ref<SalesExecutionException[]>([]),
  changes = ref<SalesOrderChange[]>([])
const sectionErrors = ref<Record<string, string>>({})
const sources = ref<Record<number, { options?: SalesDeliveryOption[]; error?: string }>>({})
const deliveryOpen = ref(false),
  deliveryLine = ref<SalesOrderLine | null>(null)
const exceptionDialog = ref<InstanceType<typeof SalesExecutionExceptionDialog>>()
const action = ref<'review' | 'submit' | 'approve' | 'reject'>()
const actionComment = ref(''),
  actionError = ref(''),
  saving = ref(false)
let attempt: { payload: string; key: string } | undefined
const modes: Record<string, string> = {
  STOCK: '现货准备',
  MAKE: '自产准备',
  BUY: '外采准备',
  UNDECIDED: '准备方式待定'
}
const statuses: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  CONFIRMED: '已确认',
  RELEASED: '已下达',
  SCHEDULED: '已排产',
  IN_PRODUCTION: '生产中',
  PENDING_INSPECTION: '待完工检验',
  PENDING_REINSPECTION: '待复检',
  PENDING_RECEIPT: '待入库',
  PARTIALLY_RECEIVED: '部分入库',
  REWORK: '返工中',
  COMPLETED: '任务已完成',
  RECEIVING: '采购收货中',
  CLOSED: '已关闭',
  POSTED: '已过账',
  REVERSED: '已冲销',
  CANCELLED: '已作废'
}
const approvalActions: Record<string, string> = { SUBMIT: '提交审批', APPROVE: '审批通过', REJECT: '驳回' }
const actionTitles = { review: '送履约评审', submit: '提交订单审批', approve: '审批通过', reject: '驳回订单' }
const allDelivered = computed(
  () =>
    !!detail.value?.items.length &&
    detail.value.items.every(line => Number(line.shippedQuantity || 0) >= Number(line.quantity))
)
const draftCount = computed(
  () => new Set((detail.value?.shipments || []).filter(row => row.status === 'DRAFT').map(row => row.outboundId)).size
)
const risks = computed(() => exceptions.value.filter(row => row.status === 'OPEN'))
const supplyReady = computed(() =>
  !!detail.value?.items.length && detail.value.head.status === 'CONFIRMED' &&
  detail.value.items.every(line =>
    !held(line) && !sources.value[line.id || 0]?.error &&
    Number(line.shippedQuantity || 0) + available(line) >= Number(line.quantity)
  )
)
const flow = computed(() => [
  {
    title: '客户需求',
    note: detail.value?.head.status === 'DRAFT' ? '完善配置与交付约定' : '查看已记录的产品与约定',
    done: detail.value?.head.status !== 'DRAFT'
  },
  {
    title: '履约确认',
    note:
      detail.value?.head.status === 'CONFIRMED'
        ? '已审批确认'
        : detail.value?.head.status === 'PENDING_APPROVAL'
          ? '等待审批决定'
          : '生产 / 采购评审后提交审批',
    done: detail.value?.head.status === 'CONFIRMED'
  },
  {
    title: '供应准备',
    note: supplyReady.value ? '各明细已有实际交付或足量可发来源' : '按明细核对生产、采购和可发来源',
    done: supplyReady.value
  },
  {
    title: '发货交付',
    note: allDelivered.value
      ? '本单明细均已发货'
      : draftCount.value
        ? `${draftCount.value} 张草稿待核对过账`
        : '按准备来源分批交付',
    done: allDelivered.value
  }
])
function pending(line: SalesOrderLine) {
  return Math.max(0, Number(line.allocatedQuantity || 0) - Number(line.shippedQuantity || 0))
}
function remaining(line: SalesOrderLine) {
  return Math.max(0, Number(line.quantity) - Number(line.allocatedQuantity || 0))
}
function held(line: SalesOrderLine) {
  return risks.value.some(row => row.itemId === line.id && row.pauseFollowup !== false && row.pauseFollowup !== 0)
}
function documents(line: SalesOrderLine) {
  const prep = detail.value?.preparation
  return line.supplyMode === 'MAKE'
    ? prep?.production.filter(row => row.itemId === line.id) || []
    : line.supplyMode === 'BUY'
      ? prep?.purchases.filter(row => row.itemId === line.id) || []
      : []
}
function activeDocuments(line: SalesOrderLine) {
  return documents(line).filter(row => row.status !== 'CANCELLED')
}
function available(line: SalesOrderLine) {
  return (sources.value[line.id || 0]?.options || []).reduce((sum, row) => sum + Number(row.availableQuantity), 0)
}
function progress(line: SalesOrderLine) {
  if (Number(line.shippedQuantity || 0) >= Number(line.quantity)) return '已发货'
  if (held(line)) return '后续办理已暂停'
  if (detail.value?.head.status !== 'CONFIRMED')
    return detail.value?.head.status === 'PENDING_APPROVAL' ? '待订单审批' : '待需求确认'
  if (pending(line) > 0) return `草稿待发 ${pending(line)} ${line.unit}`
  if (sources.value[line.id || 0]?.error) return '可发来源未获取'
  if (available(line) > 0)
    return sources.value[line.id || 0]?.options?.length === 1
      ? `当前可发 ${Math.min(available(line), remaining(line))} ${line.unit}`
      : '已有可发来源，可分批安排'
  const doc = activeDocuments(line)[0]
  if (doc?.fulfillmentMode === 'DROP_SHIP') return '供应商直发跟进中'
  if (doc) return statuses[doc.status] || doc.status
  if (line.manufacturingOrderId) return '生产任务已建立'
  return line.supplyMode === 'MAKE'
    ? '待下达 / 等待交仓'
    : line.supplyMode === 'BUY'
      ? '待采购 / 等待到货'
      : '暂无可发库存'
}
function canOpen(line: SalesOrderLine) {
  return line.supplyMode === 'MAKE'
    ? can([
        'manufacturing.read.self',
        'manufacturing.read.department',
        'manufacturing.read.all',
        'manufacturing.report',
        'manufacturing.report.override',
        'manufacturing.inspect',
        'manufacturing.receive'
      ])
    : can(['purchase_order.read.self', 'purchase_order.read.department', 'purchase_order.read.all'])
}
function openDocument(line: SalesOrderLine, doc: OrderPreparationSource) {
  void router.push({
    path: line.supplyMode === 'MAKE' ? '/local-manufacturing-execution' : '/purchase-orders',
    query: line.supplyMode === 'MAKE' ? { taskId: doc.id, sourceOrderId: detail.value?.head.id } : { purchaseId: doc.id, sourceOrderId: detail.value?.head.id }
  })
}
function edit(section = '') {
  if (detail.value) void router.push({ path: '/sales-orders', query: { editOrderId: detail.value.head.id, section } })
}
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  sectionErrors.value = {}
  sources.value = {}
  readiness.value = undefined
  reviews.value = []
  exceptions.value = []
  changes.value = []
  try {
    const id = Number(route.params.id)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('订单编号无效')
    detail.value = await fetchSalesOrder(id)
    const jobs: Promise<void>[] = []
    async function section(name: string, job: () => Promise<void>) {
      try {
        await job()
      } catch (e) {
        sectionErrors.value[name] = e instanceof Error ? e.message : '加载失败'
      }
    }
    jobs.push(
      section('协作记录', async () => {
        const [e, c] = await Promise.all([fetchSalesExecutionExceptions(id), fetchSalesOrderChanges(id)])
        exceptions.value = e
        changes.value = c
      })
    )
    jobs.push(
      section('履约评审', async () => {
        reviews.value = await fetchSalesFulfillmentReviewStatus(id)
      })
    )
    if (detail.value.head.status === 'DRAFT')
      jobs.push(
        section('提交条件', async () => {
          readiness.value = await fetchSalesOrderReadiness(id)
        })
      )
    if (detail.value.head.status === 'CONFIRMED')
      for (const line of detail.value.items) {
        if (!line.id || remaining(line) <= 0) continue
        jobs.push(
          (async () => {
            try {
              sources.value[line.id!] = { options: await fetchSalesDeliveryOptions(id, line.id!) }
            } catch (e) {
              sources.value[line.id!] = { error: e instanceof Error ? e.message : '来源核对失败' }
            }
          })()
        )
      }
    await Promise.all(jobs)
  } catch (e) {
    detail.value = undefined
    error.value = e instanceof Error ? e.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}
function openDelivery(line: SalesOrderLine) {
  deliveryLine.value = line
  deliveryOpen.value = true
}
function onCreated(id: number) {
  void router.push({ path: '/sales-outbounds', query: { outboundId: id, sourceOrderId: detail.value?.head.id } })
}
function openAction(value: NonNullable<typeof action.value>) {
  if (loading.value || saving.value) return
  action.value = value
  actionComment.value = ''
  actionError.value = ''
  attempt = undefined
}
async function perform() {
  if (!detail.value || !action.value || saving.value) return
  if (action.value === 'reject' && !actionComment.value.trim()) {
    actionError.value = '请填写驳回原因'
    return
  }
  const id = detail.value.head.id,
    kind = action.value
  const payload = JSON.stringify([id, kind, actionComment.value.trim()])
  if (attempt?.payload !== payload) attempt = { payload, key: newRequestKey() }
  saving.value = true
  actionError.value = ''
  try {
    if (kind === 'review') await requestSalesFulfillmentReview(id, attempt!.key)
    if (kind === 'submit') await submitSalesOrderApproval(id, attempt!.key)
    if (kind === 'approve') await approveSalesOrder(id, actionComment.value.trim(), attempt!.key)
    if (kind === 'reject') await rejectSalesOrder(id, actionComment.value.trim(), attempt!.key)
    action.value = undefined
    await load()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '操作失败，请重试'
  } finally {
    saving.value = false
  }
}
await load()
watch(() => route.params.id, () => { void load() })
</script>
<template>
  <div class="erp-workspace erp-order">
    <header class="erp-header">
      <div>
        <span class="erp-eyebrow">利强 ERP · 同一条订单，同一份进度</span>
        <h1>{{ detail ? `${detail.head.customerName} · 订单协作` : '订单协作' }}</h1>
        <p v-if="detail">
          {{ detail.head.orderNo }} · 业务员：{{ detail.head.salespersonName || '未指定' }} · 客户约定交期：{{
            detail.head.requestedDeliveryDate || '未约定'
          }}
        </p>
      </div>
      <div class="erp-actions">
        <el-button @click="router.push('/sales-orders')">返回订单</el-button
        ><el-button :loading="loading" @click="load">刷新进度</el-button
        ><el-button v-if="detail && can('sales_order.manage')" @click="edit()">{{
          detail.head.status === 'DRAFT' ? '编辑需求' : '订单办理记录'
        }}</el-button>
      </div>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="detail">
      <nav class="erp-flow" aria-label="订单履约流程">
        <div v-for="(step, index) in flow" :key="step.title" :class="{ done: step.done }">
          <small>0{{ index + 1 }}</small
          ><strong>{{ step.title }}</strong
          ><span>{{ step.note }}</span>
        </div>
      </nav>
      <div v-if="risks.length || sectionErrors['协作记录']" class="erp-callout risk">
        <span>{{
          sectionErrors['协作记录']
            ? '协作记录未获取，操作前请重试核对。'
            : `有 ${risks.length} 项未解决事项；请核对哪些仅提醒、哪些暂停办理。`
        }}</span>
      </div>
      <section class="erp-panel">
          <div class="erp-section-heading">
            <h2>每项需求的交付准备</h2>
            <el-tag>{{ statuses[detail.head.status] }}</el-tag>
          </div>
          <p class="erp-muted">自产、现货和外采并行准备，发货按产品明细推进。生产完成与实际入仓分别核对。</p>
          <div class="erp-demand-list">
            <article v-for="line in detail.items" :key="line.id" class="erp-demand" :data-line="line.id">
              <div>
                <span class="erp-eyebrow">{{ modes[line.supplyMode] }}</span>
                <h3>{{ line.productName }}</h3>
                <p>{{ line.specification || '未填写规格' }}</p>
              </div>
              <div class="erp-demand-quantity">
                <strong>{{ line.quantity }} {{ line.unit }}</strong
                ><span
                  >已发 {{ line.shippedQuantity || 0 }} · 待发
                  {{ Math.max(0, line.quantity - (line.shippedQuantity || 0)) }}</span
                ><span v-if="pending(line)">其中草稿待发 {{ pending(line) }} {{ line.unit }}</span>
              </div>
              <div>
                <el-tag :type="held(line) ? 'danger' : available(line) > 0 ? 'success' : 'info'">{{
                  progress(line)
                }}</el-tag>
                <p v-if="activeDocuments(line)[0]">
                  {{ activeDocuments(line)[0].documentNo }} ·
                  {{
                    activeDocuments(line)[0].fulfillmentMode === 'DROP_SHIP'
                      ? '供应商直接发客户'
                      : activeDocuments(line)[0].workshopName || '到仓准备'
                  }}
                </p>
                <p v-if="sources[line.id || 0]?.error" class="erp-error">{{ sources[line.id || 0]?.error }}</p>
              </div>
              <div class="erp-demand-actions">
                <el-button
                  v-if="detail.head.status === 'CONFIRMED' && line.id && line.supplyMode === 'MAKE' && !activeDocuments(line).length && !line.manufacturingOrderId && !held(line) && can('manufacturing.manage')"
                  type="primary" plain
                  @click="router.push({ path: '/production-demands', query: { itemId: line.id, sourceOrderId: detail.head.id } })"
                >下达生产任务 →</el-button>
                <el-button
                  v-if="detail.head.status === 'CONFIRMED' && line.id && line.supplyMode === 'BUY' && !activeDocuments(line).length && !held(line) && can('purchase_order.manage')"
                  type="primary" plain
                  @click="router.push({ path: '/purchase-orders', query: { demandItemId: line.id, sourceOrderId: detail.head.id } })"
                >办理采购 →</el-button>
                <el-button
                  v-if="activeDocuments(line)[0]?.fulfillmentMode === 'DROP_SHIP' && canOpen(line)"
                  type="primary"
                  @click="openDocument(line, activeDocuments(line)[0])"
                  >查看代发进度</el-button
                >
                <el-button
                  v-if="
                    detail.head.status === 'CONFIRMED' &&
                    can('sales_order.manage') &&
                    remaining(line) > 0 &&
                    activeDocuments(line)[0]?.fulfillmentMode !== 'DROP_SHIP'
                  "
                  type="primary"
                  :disabled="loading || held(line) || !!sectionErrors['协作记录']"
                  @click="openDelivery(line)"
                  >核对并安排发货</el-button
                ><el-button
                  v-if="activeDocuments(line)[0] && canOpen(line)"
                  link
                  type="primary"
                  @click="openDocument(line, activeDocuments(line)[0])"
                  >查看{{ line.supplyMode === 'MAKE' ? '生产任务' : '采购单' }} →</el-button
                ><el-button
                  v-else-if="line.manufacturingOrderId && canOpen(line)"
                  link
                  type="primary"
                  @click="
                    router.push({
                      path: '/local-manufacturing-execution',
                      query: { taskId: line.manufacturingOrderId, sourceOrderId: detail?.head.id }
                    })
                  "
                  >查看生产任务 →</el-button
                ><el-button
                  v-if="
                    detail.head.status === 'CONFIRMED' &&
                    can('sales_order.manage') &&
                    !risks.some(row => row.itemId === line.id)
                  "
                  link
                  type="warning"
                  @click="exceptionDialog?.open(detail.head.id, line.id!, false)"
                  >登记协作事项</el-button
                >
              </div>
            </article>
          </div>
        </section>
        <div class="erp-queue-grid erp-order-summary">
          <section class="erp-panel">
            <h2>交付约定</h2>
            <dl class="erp-summary">
              <div>
                <dt>客户承诺交期</dt>
                <dd>{{ detail.head.requestedDeliveryDate || '未约定' }}</dd>
              </div>
              <div>
                <dt>订单金额</dt>
                <dd>
                  {{
                    detail.head.totalAmount == null
                      ? '未填写'
                      : `¥${Number(detail.head.totalAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
                  }}
                </dd>
              </div>
            </dl>
            <p class="erp-muted">{{ detail.head.remark || '尚无补充约定' }}</p>
          </section>
        </div>
      <section class="erp-panel">
        <h2>确认的产品与价格</h2>
        <el-table :data="detail.items"
          ><el-table-column prop="productName" label="产品" min-width="180" /><el-table-column
            prop="specification"
            label="配置快照"
            min-width="230" /><el-table-column prop="quantity" label="数量" width="100" /><el-table-column
            prop="unit"
            label="单位"
            width="80" /><el-table-column label="单价" width="120"
            ><template #default="{ row }">{{
              row.unitPrice == null ? '未填写' : row.unitPrice
            }}</template></el-table-column
          ><el-table-column label="金额" width="130"
            ><template #default="{ row }">{{ row.amount == null ? '未填写' : row.amount }}</template></el-table-column
          ><el-table-column prop="requestedDeliveryDate" label="明细交期" width="140"
        /></el-table>
        <p class="erp-muted">
          业务员：{{ detail.head.salespersonName || '未指定' }} · {{ detail.head.remark || '无补充备注' }}
        </p>
        <el-button v-if="can('sales_order.manage')" @click="edit()">{{
          detail.head.status === 'DRAFT' ? '编辑产品与约定' : '查看约定与办理变更'
        }}</el-button>
      </section>
      <section class="erp-panel">
        <h2>按原需求追踪准备来源</h2>
        <p class="erp-muted">
          这里只显示本单关联任务与采购单。可发数量来自实际入仓来源与剩余需求核对；供应商直发在采购单办理，不虚构本地库存。
        </p>
        <article v-for="line in detail.items" :key="line.id" class="erp-preparation">
          <div class="erp-section-heading">
            <h3>{{ line.productName }} · {{ modes[line.supplyMode] }}</h3>
            <el-tag>{{ progress(line) }}</el-tag>
          </div>
          <p v-if="!documents(line).length">
            {{ line.supplyMode === 'STOCK' ? '按正式可售库存核对仓库。' : '暂未查询到关联准备单据。' }}
          </p>
          <div v-for="doc in documents(line)" :key="doc.id" class="erp-source-row">
            <div>
              <strong>{{ doc.documentNo }}</strong>
              <p>
                {{ statuses[doc.status] || doc.status }} · 计划日期 {{ doc.dueDate || '未安排' }} ·
                {{ doc.fulfillmentMode === 'DROP_SHIP' ? '供应商直发客户' : doc.workshopName || '采购到仓' }}
              </p>
            </div>
            <el-button v-if="canOpen(line)" link type="primary" @click="openDocument(line, doc)"
              >打开来源单据 →</el-button
            >
          </div>
          <div v-for="(source, index) in sources[line.id || 0]?.options || []" :key="index" class="erp-source-row">
            <div>
              <strong>{{ source.warehouseName }} · {{ source.sourceNo || '现货库存' }}</strong>
              <p>当前来源可发 {{ source.availableQuantity }} {{ source.unit }} · {{ source.productName }}</p>
            </div>
            <el-button
              v-if="can('sales_order.manage')"
              :disabled="loading || held(line) || !!sectionErrors['协作记录']"
              @click="openDelivery(line)"
              >安排本次发货</el-button
            >
          </div>
          <el-alert
            v-if="sources[line.id || 0]?.error"
            :title="sources[line.id || 0]!.error!"
            type="error"
            :closable="false"
          />
        </article>
      </section>
      <section class="erp-panel">
        <h2>分批交付记录</h2>
        <p class="erp-muted">草稿尚未形成实际发货；取消、冲销记录保留用于追溯。</p>
        <el-table :data="detail.shipments || []"
          ><el-table-column label="出库单" min-width="170"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                @click="router.push({ path: '/sales-outbounds', query: { outboundId: row.outboundId, sourceOrderId: detail?.head.id } })"
                >{{ row.outboundNo }}</el-button
              ></template
            ></el-table-column
          ><el-table-column prop="productName" label="产品" min-width="180" /><el-table-column
            prop="quantity"
            label="本次数量"
            width="110"
          /><el-table-column label="状态" width="120"
            ><template #default="{ row }">{{
              row.status === 'DRAFT' ? '草稿待核对' : statuses[row.status] || row.status
            }}</template></el-table-column
          ><el-table-column prop="warehouseName" label="发货仓库" min-width="140" /><el-table-column
            label="准备来源"
            min-width="170"
            ><template #default="{ row }">{{
              row.sourceLotNo || row.sourcePurchaseNo || '现货 / 供应商代发'
            }}</template></el-table-column
          ><template #empty>尚无出库记录，请先核对准备进度</template></el-table
        >
      </section>
      <section class="erp-panel">
          <h2>履约确认与审批</h2>
          <el-alert
            v-for="(message, section) in sectionErrors"
            :key="section"
            :title="`${section}：${message}`"
            type="error"
            :closable="false"
          />
          <div v-if="readiness" class="erp-callout">
            <span>{{
              readiness.submitReady ? '配置及履约评审已就绪，可以提交审批。' : '提交前还需要处理以下事项。'
            }}</span>
          </div>
          <ul v-if="readiness">
            <li v-for="(blocker, index) in [...readiness.configBlockers, ...readiness.reviewBlockers]" :key="index">
              {{ blocker.message }}
            </li>
          </ul>
          <div class="erp-actions">
            <el-button
              v-if="
                can('sales_order.manage') &&
                detail.head.status === 'DRAFT' &&
                detail.items.some(row => ['MAKE', 'BUY'].includes(row.supplyMode))
              "
              :disabled="!readiness?.reviewReady"
              @click="openAction('review')"
              >送履约评审</el-button
            ><el-button
              v-if="can('sales_order.manage') && detail.head.status === 'DRAFT'"
              type="primary"
              :disabled="!readiness?.submitReady"
              @click="openAction('submit')"
              >提交订单审批</el-button
            ><el-button
              v-if="can('sales_order.approve') && detail.head.status === 'PENDING_APPROVAL'"
              type="success"
              @click="openAction('approve')"
              >审批通过</el-button
            ><el-button
              v-if="can('sales_order.approve') && detail.head.status === 'PENDING_APPROVAL'"
              type="danger"
              @click="openAction('reject')"
              >驳回订单</el-button
            >
          </div>
          <el-table :data="reviews" style="margin-top: 16px"
            ><el-table-column prop="productName" label="产品" min-width="180" /><el-table-column
              label="评审意见"
              min-width="200"
              ><template #default="{ row }"
                >{{
                  row.reviewDecision && !row.reviewCurrent
                    ? '意见已失效，需重评'
                    : row.reviewDecision === 'ACCEPTED'
                      ? '可履约'
                      : row.reviewDecision === 'CHANGE_REQUIRED'
                        ? '需调整需求'
                        : '待评审'
                }}
                · {{ row.reviewComment || '无补充意见' }}</template
              ></el-table-column
            ><el-table-column prop="reviewerName" label="评审人" width="120" /></el-table
          ><el-table :data="detail.approvals" style="margin-top: 16px"
            ><el-table-column label="审批动作" width="130"
              ><template #default="{ row }">{{ approvalActions[row.action] || row.action }}</template></el-table-column
            ><el-table-column prop="actorName" label="操作人" width="130" /><el-table-column
              prop="comment"
              label="说明"
              min-width="200" /><el-table-column prop="createdAt" label="时间" min-width="170"
          /></el-table>
        </section>
        <section class="erp-panel erp-order-summary">
          <h2>协作事项与客户变更</h2>
          <el-alert type="info" :closable="false" show-icon
            title="此区为本订单异常与变更的统一概览入口"
            description="下方表格为本订单已登记的协作事项与客户变更（只读概览）。点击底部「处理客户方案与变更 →」按钮跳到订单抽屉办理发起：协商其他数量／交期、客户接受原交期方案、解决问题、重新送复评、登记客户接受并生效、批准/驳回商业变更、客户未接受／撤回等。" />
          <article v-for="item in exceptions" :key="item.id" class="erp-preparation">
            <el-tag
              :type="
                item.status === 'RESOLVED'
                  ? 'success'
                  : item.pauseFollowup === false || item.pauseFollowup === 0
                    ? 'warning'
                    : 'danger'
              "
              >{{
                item.status === 'RESOLVED'
                  ? '已解决'
                  : item.pauseFollowup === false || item.pauseFollowup === 0
                    ? '风险提醒，允许继续'
                    : '暂停后续办理'
              }}</el-tag
            >
            <h3>
              {{ detail.items.find(line => line.id === item.itemId)?.productName || `明细 ${item.itemId}` }} ·
              {{ item.reason }}
            </h3>
            <p>{{ item.proposedAction || '尚未登记处理安排' }} · {{ item.reportedByName }} · {{ item.reportedAt }}</p>
            <p v-if="item.proposedDeliveryDate">
              执行方可行交期：{{ item.proposedDeliveryDate }}，客户订单约定以正式确认结果为准。
            </p>
          </article>
          <p v-if="!exceptions.length && !sectionErrors['协作记录']" class="erp-muted">暂无已登记协作事项</p>
          <el-table v-if="changes.length" :data="changes"
            ><el-table-column prop="id" label="变更号" width="90" /><el-table-column label="客户方案" min-width="230"
              ><template #default="{ row }"
                >数量 {{ row.originalQuantity }} → {{ row.proposedQuantity }} · 交期
                {{ row.proposedDeliveryDate || '不变' }}</template
              ></el-table-column
            ><el-table-column prop="customerConfirmationRef" label="沟通依据" min-width="180" /><el-table-column
              prop="status"
              label="处理状态"
              min-width="130" /></el-table
          ><el-button v-if="can('sales_order.manage') || can('sales_order.approve')" @click="edit('collaboration')"
            >处理客户方案与变更 →</el-button
          >
        </section>
    </template>
    <OrderDeliveryDialog
      v-model="deliveryOpen"
      :order-id="detail?.head.id || 0"
      :line="deliveryLine"
      @created="onCreated"
    />
    <SalesExecutionExceptionDialog ref="exceptionDialog" @saved="load" />
    <el-dialog
      :model-value="!!action"
      :title="action ? actionTitles[action] : ''"
      width="min(520px, 94vw)"
      :show-close="!saving"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      @update:model-value="!saving && (action = undefined)"
      ><p>本次操作针对 {{ detail?.head.orderNo }}，执行后会重新核对订单状态。</p>
      <el-input
        v-if="action === 'approve' || action === 'reject'"
        v-model="actionComment"
        :disabled="saving"
        type="textarea"
        :placeholder="action === 'reject' ? '驳回原因必填' : '审批意见（选填）'"
        maxlength="500"
      /><el-alert v-if="actionError" :title="actionError" type="error" :closable="false" /><template #footer
        ><el-button :disabled="saving" @click="action = undefined">取消</el-button
        ><el-button type="primary" :loading="saving" @click="perform"
          >确认{{ action ? actionTitles[action] : '' }}</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
