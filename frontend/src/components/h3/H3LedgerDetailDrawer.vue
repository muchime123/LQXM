<template>
  <el-drawer v-model="visible" :title="`${kindLabel} · 台账详情`" size="50%" direction="rtl" :append-to-body="appendToBody">
    <div v-loading="loading" class="detail-panel">
      <template v-if="!loading && !data">
        <el-empty :description="error || '未加载到详情数据'" :image-size="80" />
      </template>
      <template v-else-if="row">
        <!-- 头部：单号 + 状态 + 副信息 -->
        <div class="detail-head">
          <div class="detail-head__left">
            <b class="detail-head__main">{{ mainText }}</b>
            <el-tag v-if="row.status != null && row.status !== ''" size="small" :type="statusTagType(row.status)">
              {{ row.status }}
            </el-tag>
          </div>
          <div v-if="subtitleText" class="detail-head__sub">{{ subtitleText }}</div>
        </div>
        <el-alert v-if="productionApplicationDateInvalid" class="detail-hint" type="error" :closable="false" show-icon
          title="生产工单申请日期早于任务单提交日期"
          :description="`申请日期 ${row.applicationDate}，任务单提交时间 ${fmtVal(row.sourceCreatedAt, 'dateTime')}（业务员提交生产任务单给车间审批的时间）。请核对业务员填写的申请日期；源台账原值保留。`" />

        <div v-if="props.ledgerType === 'outbound'" class="detail-sec nature-card">
          <div class="nature-card__head">
            <h4 class="detail-sec__title">业务类型识别</h4>
            <el-button v-if="canGovern" link type="primary" @click="openNatureDialog">人工确认</el-button>
          </div>
          <el-alert
            :type="natureAlertType"
            :closable="false"
            show-icon
            :title="natureTitle"
            :description="String(row.businessTypeReason || '未发现退换货特征')"
          />
          <div v-if="row.businessTypeConfirmed" class="nature-card__confirmed">
            已由 {{ row.businessTypeConfirmedBy || '管理员' }} 确认为
            <strong>{{ natureLabel(row.confirmedBusinessType) }}</strong>
            <template v-if="row.originalOutboundNo">，原销售单：{{ row.originalOutboundNo }}</template>
          </div>
        </div>

        <div v-if="props.ledgerType === 'outbound'" class="detail-sec relation-card">
          <div class="relation-card__head">
            <div>
              <h4 class="detail-sec__title">项目内业务关联</h4>
              <p>这里展示本张氚云销售出库已连接到哪些项目档案；氚云原始名称、电话、地址和产品行仍保持不变。</p>
            </div>
            <el-tag :type="relationComplete ? 'success' : 'warning'" effect="plain">
              {{ relationComplete ? '核心关联完整' : '存在待关联项' }}
            </el-tag>
          </div>
          <div class="relation-grid">
            <div class="relation-item">
              <span>客户档案</span>
              <RouterLink v-if="row.customerId" :to="{ path: '/customers', query: { customerId: row.customerId, keyword: row.linkedCustomerName || row.customerName } }">
                {{ row.linkedCustomerName || row.customerName || `客户 #${row.customerId}` }}
              </RouterLink>
              <b v-else class="relation-missing">未关联</b>
              <small>来源客户：{{ row.customerName || '—' }}</small>
            </div>
            <div class="relation-item">
              <span>员工档案</span>
              <RouterLink v-if="row.employeeId" :to="{ path: '/system/employees', query: { keyword: row.linkedEmployeeName || row.salespersonName } }">
                {{ row.linkedEmployeeName || row.salespersonName || `员工 #${row.employeeId}` }}
              </RouterLink>
              <b v-else class="relation-missing">未关联</b>
              <small>来源业务员：{{ row.salespersonName || '—' }}</small>
            </div>
            <div class="relation-item">
              <span>仓库档案</span>
              <RouterLink v-if="row.linkedWarehouseId" :to="{ path: '/product-inventory', query: { warehouse: row.linkedWarehouseName } }">
                {{ row.linkedWarehouseName }}
              </RouterLink>
              <b v-else class="relation-missing">{{ row.warehouse ? '未关联' : '来源未填写' }}</b>
              <small>来源仓库：{{ row.warehouse || '—' }}</small>
            </div>
            <div class="relation-item">
              <span>库存产品</span>
              <b :class="unlinkedProductCount ? 'relation-missing' : 'relation-ok'">
                已关联 {{ linkedProductCount }} / {{ productRowCount }} 行
              </b>
              <small>先关联出库仓库，再按每一条产品明细关联库存产品；仓库＋产品＋单位共同定位库存余额。</small>
            </div>
          </div>
        </div>

        <!-- 收款记录：较早导入的记录未保存出库关联，引导重新导入 -->
        <el-alert
          v-if="missingLinkHint"
          class="detail-hint"
          type="warning"
          :closable="false"
          show-icon
          title="未找到关联的销售出库"
          description="收款记录与销售出库的关联（销售出库编号/销售订单日期）自 V269 起在导入时保存；该记录可能导入较早或文件未含「销售出库编号」列，如需查看请重新导入对应的「收款记录」Excel。"
        />

        <!-- 主记录字段分组 -->
        <div v-for="(sec, si) in sections" :key="'s' + si" class="detail-sec">
          <h4 class="detail-sec__title">{{ sec.title }}</h4>
          <el-descriptions :column="2" border size="small" class="detail-desc">
            <el-descriptions-item
              v-for="(f, fi) in sec.fields"
              :key="fi"
              :label="f.label"
              :span="f.span || 1"
              label-width="118px"
            >
              <span v-if="f.fmt === 'longText' && fmtVal(row[f.key], f.fmt) !== '—'" class="d-long">{{ fmtVal(row[f.key], f.fmt) }}</span>
              <span v-else :class="{ 'source-date-error': productionApplicationDateInvalid && f.key === 'applicationDate' }">{{ fmtVal(row[f.key], f.fmt) }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 子表明细（出库明细 / 制程明细 / 覆盖出库单） -->
        <div v-if="cols" class="detail-sec">
          <h4 class="detail-sec__title">
            {{ colsLabel }}
            <span class="detail-sec__count">{{ rows.length }}</span>
          </h4>
          <el-table v-if="rows.length" :data="rows" size="small" border max-height="360">
            <el-table-column
              v-for="(c, ci) in cols"
              :key="ci"
              :prop="c.prop"
              :label="c.label"
              :width="c.width"
              :min-width="c.minWidth"
              :align="c.align"
              show-overflow-tooltip
            >
              <template #default="{ row: r }">
                {{ fmtVal(r[c.prop], c.fmt) }}
              </template>
            </el-table-column>
          </el-table>
          <p v-else class="detail-empty">{{ emptyText || '无子表明细' }}</p>
        </div>

        <div v-if="cols2" class="detail-sec">
          <h4 class="detail-sec__title">
            {{ cols2Label }}
            <span class="detail-sec__count">{{ rows2.length }}</span>
          </h4>
          <el-table v-if="rows2.length" :data="rows2" size="small" border max-height="360">
            <el-table-column
              v-for="(c, ci) in cols2"
              :key="ci"
              :prop="c.prop"
              :label="c.label"
              :width="c.width"
              :min-width="c.minWidth"
              :align="c.align"
              show-overflow-tooltip
            >
              <template #default="{ row: r }">
                {{ fmtVal(r[c.prop], c.fmt) }}
              </template>
            </el-table-column>
          </el-table>
          <p v-else class="detail-empty">{{ emptyText2 }}</p>
        </div>

        <!-- 更新历史：按业务键回放历次导入批次中该行的新增/更新（数据源=批次保留的 preview_json） -->
        <div class="detail-sec">
          <h4 class="detail-sec__title">
            更新历史
            <span v-if="!historyLoading" class="detail-sec__count">{{ historyEntries.length }}</span>
          </h4>
          <div v-loading="historyLoading" class="history-body">
            <div v-if="historyEntries.length" class="history-list">
              <div v-for="(h, hi) in historyEntries" :key="hi" class="history-item">
                <div class="history-item__head">
                  <el-tag size="small" :type="h.action === 'CREATE' ? 'success' : 'warning'">
                    {{ h.action === 'CREATE' ? '新增' : '更新' }}
                  </el-tag>
                  <span class="history-batch">批次 #{{ h.batchId }}</span>
                  <span class="history-file" :title="h.fileName || ''">{{ h.fileName || '—' }}</span>
                  <span class="history-time">{{ fmtVal(h.batchTime, 'dateTime') }}</span>
                </div>
                <div v-if="h.changeItems?.length" class="history-changes">
                  <div v-for="(c, ci) in h.changeItems" :key="ci" class="history-change">
                    <b>{{ c.path }}</b>
                    <span class="history-change__old">{{ c.oldValue ?? '空' }}</span>
                    <span class="history-change__arrow">→</span>
                    <span class="history-change__new">{{ c.newValue ?? '空' }}</span>
                  </div>
                </div>
                <div v-else-if="h.changes" class="history-changes">{{ h.changes }}</div>
              </div>
            </div>
            <p v-else-if="!historyLoading && !historyError" class="detail-empty">
              没有历史变更记录（该行仅导入一次，之后各批次无变化）
            </p>
            <p v-else-if="historyError" class="detail-empty">{{ historyError }}</p>
          </div>
        </div>
      </template>
    </div>

    <el-dialog v-model="natureDialog" title="确认销售出库业务类型" width="520px" append-to-body>
      <el-form label-width="92px">
        <el-form-item label="业务类型" required>
          <el-radio-group v-model="natureForm.businessType">
            <el-radio-button value="SALE">正常销售</el-radio-button>
            <el-radio-button value="RETURN">退货</el-radio-button>
            <el-radio-button value="EXCHANGE">换货</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="natureForm.businessType !== 'SALE'" label="原销售单">
          <el-select v-model="natureForm.originalOutboundId" clearable filterable placeholder="可选：关联同客户的原销售单" style="width:100%">
            <el-option
              v-for="item in originalCandidates"
              :key="item.id"
              :value="item.id"
              :label="`${item.outboundNo} · ${item.shipDate || '日期未知'} · ¥${Number(item.salesAmount || 0).toFixed(2)}`"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="natureDialog = false">取消</el-button>
        <el-button type="primary" :loading="natureSaving" @click="saveNature">确认并保存</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { H3LedgerKind, LedgerDetailResult } from '@/types/h3Import'
import {
  confirmOutboundBusinessType,
  fetchLedgerRowHistory,
  fetchOutboundOriginalCandidates,
  type LedgerRowHistoryEntry,
  type OutboundOriginalCandidate
} from '@/api/business/orders'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'

type Fmt = 'text' | 'money' | 'num' | 'int' | 'date' | 'dateTime' | 'bool' | 'longText'
interface FDef { key: string; label: string; fmt?: Fmt; span?: number }
interface ChildCol { prop: string; label: string; fmt?: Fmt; width?: number; minWidth?: number; align?: 'right' | 'center' }
interface Cfg {
  titleKey: string
  subtitleKeys: string[]
  sections: Array<{ title: string; fields: FDef[] }>
  cols?: ChildCol[]
  colsLabel?: string
  emptyText?: string
  cols2?: ChildCol[]
  cols2Label?: string
  emptyText2?: string
}

const props = defineProps<{
  modelValue: boolean
  ledgerType: H3LedgerKind
  loading: boolean
  data: LedgerDetailResult | null
  error?: string | null
  appendToBody?: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'updated'): void
}>()

const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const row = computed(() => props.data?.row ?? null)
const productionApplicationDateInvalid = computed(() => props.ledgerType === 'production'
  && !!row.value?.applicationDate && !!row.value?.sourceCreatedAt
  && String(row.value.applicationDate).slice(0, 10) < String(row.value.sourceCreatedAt).slice(0, 10))

// ===== 更新历史（按业务键回放历次批次的 新增/更新；后端扫描批次 preview_json，见 H3LedgerRowHistoryService） =====
const historyLoading = ref(false)
const historyError = ref('')
const historyEntries = ref<LedgerRowHistoryEntry[]>([])
async function loadHistory() {
  const id = Number(props.data?.row?.id)
  historyEntries.value = []
  historyError.value = ''
  if (!props.modelValue || !id || Number.isNaN(id)) return
  historyLoading.value = true
  try {
    const res = await fetchLedgerRowHistory(props.ledgerType, id)
    historyEntries.value = res.entries ?? []
  } catch (e: any) {
    // 历史属增强信息：加载失败不打断详情主体，仅在区块内提示
    historyError.value = (e as Error).message || '更新历史加载失败'
  } finally {
    historyLoading.value = false
  }
}
watch(() => [props.modelValue, props.data], loadHistory)
const rows = computed(() => props.data?.rows ?? [])
const error = computed(() => props.error || '')
const auth = useCurrentUser()
const canGovern = computed(() => auth.can('integration.h3'))
const natureDialog = ref(false)
const natureSaving = ref(false)
const originalCandidates = ref<OutboundOriginalCandidate[]>([])
const natureForm = reactive<{ businessType: 'SALE' | 'RETURN' | 'EXCHANGE'; originalOutboundId: number | null }>({
  businessType: 'SALE',
  originalOutboundId: null
})

function natureLabel(value: unknown) {
  const labels: Record<string, string> = {
    SALE: '正常销售', RETURN: '退货', EXCHANGE: '换货', RETURN_OR_EXCHANGE: '疑似退换货'
  }
  return labels[String(value || '')] || '待识别'
}

const effectiveNature = computed(() => row.value?.businessTypeConfirmed
  ? row.value.confirmedBusinessType
  : row.value?.inferredBusinessType)
const natureTitle = computed(() => {
  const prefix = row.value?.businessTypeConfirmed ? '人工已确认' : '系统推断'
  const confidence = row.value?.businessTypeConfirmed ? '' : `（置信度 ${row.value?.businessTypeConfidence ?? 0}%）`
  return `${prefix}：${natureLabel(effectiveNature.value)}${confidence}`
})
const natureAlertType = computed(() => effectiveNature.value === 'SALE' ? 'success'
  : row.value?.businessTypeConfirmed ? 'warning' : 'error')
const productRows = computed(() => rows.value.filter(r => String(r.lineType || 'PRODUCT') === 'PRODUCT'))
const productRowCount = computed(() => productRows.value.length)
const linkedProductCount = computed(() => productRows.value.filter(r => !!r.salesProductId).length)
const unlinkedProductCount = computed(() => Math.max(0, productRowCount.value - linkedProductCount.value))
const relationComplete = computed(() => !!row.value?.customerId && !!row.value?.employeeId && unlinkedProductCount.value === 0)

async function openNatureDialog() {
  if (!row.value) return
  const effective = String(effectiveNature.value || 'SALE')
  natureForm.businessType = effective === 'RETURN' || effective === 'EXCHANGE' ? effective : 'SALE'
  natureForm.originalOutboundId = row.value.originalOutboundId ? Number(row.value.originalOutboundId) : null
  natureDialog.value = true
  try {
    originalCandidates.value = await fetchOutboundOriginalCandidates(Number(row.value.id))
  } catch {
    originalCandidates.value = []
  }
}

async function saveNature() {
  if (!row.value) return
  natureSaving.value = true
  try {
    await confirmOutboundBusinessType(Number(row.value.id), natureForm.businessType, natureForm.originalOutboundId)
    natureDialog.value = false
    notify.success('业务类型已确认，后续氚云重导不会覆盖')
    emit('updated')
  } catch (e) {
    notify.error((e as Error).message || '业务类型确认失败')
  } finally {
    natureSaving.value = false
  }
}

const KIND_LABEL: Record<H3LedgerKind, string> = {
  outbound: '销售出库',
  production: '生产任务工单',
  receipt: '收款记录',
  settlement: '多订单结清',
  purchase: '采购入库',
  product: '产品信息',
  stock: '仓库明细',
  dealer: '经销商',
  supplier: '供应商',
  otherIn: '其他入库',
  otherOut: '其他出库',
  transfer: '库存调拨',
  invoiceProfile: '开票资料',
  invoiceRequest: '开票申请',
  productionReport: '生产报工',
  invoiceInput: '进项发票',
  invoiceSales: '销项发票',
  supplierPayable: '供应商应付',
  cashflowJournal: '流水日记账',
  cashierLog: '收银通流水'
}
const kindLabel = computed(() => KIND_LABEL[props.ledgerType] || props.ledgerType)

/**
 * 台账三类（产品信息 / 仓库明细 / 经销商）共用的「导入与系统信息」区块。
 *
 * 上半段是氚云全字段导出携带的系统列（数据ID / 数据标题 / 创建人 / 拥有者 / 所属部门 /
 * 数据状态 / 创建时间 / 修改时间）——2026-09-16 起产品信息与仓库明细台账也开始落这些列；
 * 下半段是本项目的导入留痕。两者合起来回答「这行数据从氚云哪条记录来、氚云侧什么时候
 * 建的/改的、什么时候同步进来的」。字段为空的显示「—」（老批次重导前不会自动补）。
 */
const LEDGER_SYSTEM_SECTION = {
  title: '导入与系统信息',
  fields: [
    { key: 'sourceObjectId', label: '外部 ID（氚云数据ID）', span: 2 },
    { key: 'sourceTitle', label: '数据标题', span: 2 },
    { key: 'createdByName', label: '创建人' },
    { key: 'ownerName', label: '拥有者' },
    { key: 'departmentName', label: '所属部门', span: 2 },
    { key: 'dataStatus', label: '数据状态' },
    { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
    { key: 'sourceModifiedAt', label: '源修改时间', fmt: 'dateTime' },
    { key: 'sourceHash', label: '来源哈希', span: 2 },
    { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
    { key: 'batchId', label: '导入批次' },
    { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
    { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
    { key: 'id', label: '记录 ID' }
  ] as FDef[]
}

const CFG: Record<H3LedgerKind, Cfg> = {
  outbound: {
    titleKey: 'outboundNo',
    subtitleKeys: ['customerName', 'dealerName', 'departmentName', 'shipDate'],
    sections: [
      {
        title: '单据信息',
        fields: [
          { key: 'outboundNo', label: '出库单号' },
          { key: 'status', label: '状态' },
          { key: 'externalTitle', label: '外部标题', span: 2 },
          { key: 'departmentName', label: '部门' },
          { key: 'businessOwner', label: '负责人' }
        ]
      },
      {
        title: '客户与交货',
        fields: [
          { key: 'customerName', label: '客户' },
          { key: 'dealerName', label: '经销商' },
          { key: 'salespersonName', label: '业务员' },
          { key: 'region', label: '地区' },
          { key: 'contactPhone', label: '联系电话' },
          { key: 'shippingMethod', label: '运输方式' },
          { key: 'freeShipping', label: '是否包邮', fmt: 'bool' },
          { key: 'freight', label: '运费', fmt: 'money' },
          { key: 'warehouse', label: '仓库' },
          { key: 'warehouseKeeper', label: '仓管员' },
          { key: 'customerAddress', label: '客户地址', span: 2 }
        ]
      },
      {
        title: '日期与金额',
        fields: [
          { key: 'shipDate', label: '发货日期', fmt: 'date' },
          { key: 'businessDate', label: '业务日期', fmt: 'date' },
          { key: 'purchaseAmount', label: '采购金额', fmt: 'money' },
          { key: 'salesAmount', label: '销售金额', fmt: 'money' },
          { key: 'sourceGrossProfit', label: '源毛利', fmt: 'money' },
          { key: 'paymentStatus', label: '收款状态' },
          { key: 'receivedAmount', label: '已收金额', fmt: 'money' },
          { key: 'outstandingAmount', label: '未收金额', fmt: 'money' },
          { key: 'invoicedAmount', label: '已开票金额', fmt: 'money' },
          { key: 'uninvoicedAmount', label: '未开票金额', fmt: 'money' },
          { key: 'invoicer', label: '开票人' }
        ]
      },
      {
        title: '备注',
        fields: [
          { key: 'orderRemark', label: '订单备注', fmt: 'longText', span: 2 },
          { key: 'collectionRemark', label: '收款备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'sourceCreatedBy', label: '源创建人' },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'sequenceNo', label: '序号', width: 64, fmt: 'int' },
      { prop: 'category', label: '类目', width: 96 },
      { prop: 'productCode', label: '产品编号', width: 120 },
      { prop: 'productName', label: '产品名称', minWidth: 170 },
      { prop: 'specification', label: '规格', minWidth: 110 },
      { prop: 'linkedProductName', label: '关联库存产品', minWidth: 170 },
      { prop: 'linkedProductSpecification', label: '库存产品规格', minWidth: 120 },
      { prop: 'unit', label: '单位', width: 60 },
      { prop: 'quantity', label: '数量', width: 90, fmt: 'num', align: 'right' },
      { prop: 'salesUnitPrice', label: '销售单价', width: 100, fmt: 'money', align: 'right' },
      { prop: 'salesTotal', label: '销售金额', width: 110, fmt: 'money', align: 'right' },
      { prop: 'purchaseUnitPrice', label: '采购单价', width: 100, fmt: 'money', align: 'right' },
      { prop: 'purchaseTotal', label: '采购金额', width: 110, fmt: 'money', align: 'right' },
      { prop: 'sourceProfit', label: '源毛利', width: 100, fmt: 'money', align: 'right' },
      { prop: 'stockQuantity', label: '库存数量', width: 90, fmt: 'num', align: 'right' },
      { prop: 'remark', label: '备注', minWidth: 120 }
    ],
    colsLabel: '出库产品明细',
    emptyText: '本出库单无产品明细行'
  },

  production: {
    titleKey: 'workOrderNo',
    subtitleKeys: ['customerName', 'productName', 'planType', 'applicationDate'],
    sections: [
      {
        title: '工单信息',
        fields: [
          { key: 'workOrderNo', label: '工单号' },
          { key: 'status', label: '状态' },
          { key: 'planType', label: '计划类型' },
          { key: 'departmentName', label: '部门' },
          { key: 'orderReceiver', label: '接单人' },
          { key: 'warehouseOperator', label: '仓管员' }
        ]
      },
      {
        title: '客户与产品',
        fields: [
          { key: 'customerName', label: '客户' },
          { key: 'salespersonName', label: '业务员' },
          { key: 'productName', label: '产品', span: 2 }
        ]
      },
      {
        title: '计划与进度',
        fields: [
          { key: 'applicationDate', label: '申请日期', fmt: 'date' },
          { key: 'requiredCompletionDate', label: '要求完成', fmt: 'date' },
          { key: 'expectedCompletionDate', label: '预计完成', fmt: 'date' },
          { key: 'actualCompletionDate', label: '实际完成', fmt: 'date' },
          { key: 'requiredQuantity', label: '需求数量', fmt: 'num' },
          { key: 'completedQuantity', label: '完成数量', fmt: 'num' },
          { key: 'warehousedQuantity', label: '入库数量', fmt: 'num' },
          { key: 'productionProgress', label: '生产进度' },
          { key: 'productionCompleted', label: '生产完成', fmt: 'bool' },
          { key: 'warehousingCompleted', label: '入库完成', fmt: 'bool' }
        ]
      },
      {
        title: '工艺与包装',
        fields: [
          { key: 'boxSize', label: '装箱尺寸' },
          { key: 'boxShape', label: '箱型' },
          { key: 'boxMaterial', label: '箱材质' },
          { key: 'moldLayout', label: '模具排布' },
          { key: 'voltage', label: '电压' },
          { key: 'plugType', label: '插头类型' },
          { key: 'brand', label: '品牌' },
          { key: 'processName', label: '工艺名称' },
          { key: 'productionLine', label: '产线' },
          { key: 'optionalConfiguration', label: '可选配置', span: 2 }
        ]
      },
      {
        title: '生产要求',
        fields: [{ key: 'productionRequirement', label: '生产要求', fmt: 'longText', span: 2 }]
      },
      {
        title: '关联单据与接入',
        fields: [
          { key: 'linkedSalesOrderNo', label: '关联销售单号' },
          { key: 'linkedSalesOrderRef', label: '销售单引用', span: 2 },
          { key: 'productOrderExternalId', label: '产品工单外部 ID' },
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'sourceCreatedBy', label: '源创建人' },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'sequenceNo', label: '序号', width: 64, fmt: 'int' },
      { prop: 'componentName', label: '制程名称', minWidth: 180 },
      { prop: 'componentRef', label: '制程编号', width: 120 },
      { prop: 'requiredQuantity', label: '需求数量', width: 100, fmt: 'num', align: 'right' },
      { prop: 'requirementFactor', label: '需求系数', width: 96, fmt: 'num', align: 'right' },
      { prop: 'stockQuantity', label: '现有库存', width: 100, fmt: 'num', align: 'right' },
      { prop: 'stockUsedQuantity', label: '已用库存', width: 100, fmt: 'num', align: 'right' },
      { prop: 'completedQuantity', label: '完成数量', width: 100, fmt: 'num', align: 'right' },
      { prop: 'completionRatio', label: '完成率', width: 90 },
      { prop: 'completedAt', label: '完成时间', width: 160, fmt: 'dateTime' },
      { prop: 'finalProcess', label: '最后工序', width: 96, fmt: 'bool' },
      { prop: 'completed', label: '是否完成', width: 96, fmt: 'bool' }
    ],
    colsLabel: '零部件制程明细',
    emptyText: '本工单无制程明细行'
  },

  receipt: {
    titleKey: 'receiptNo',
    subtitleKeys: ['customerName', 'dealerName', 'paymentMethod', 'receiptDate'],
    sections: [
      {
        title: '收款信息',
        fields: [
          { key: 'receiptNo', label: '收款单号' },
          { key: 'status', label: '状态' },
          { key: 'receiptDate', label: '收款日期', fmt: 'date' },
          { key: 'paymentMethod', label: '支付方式' },
          { key: 'departmentName', label: '部门' }
        ]
      },
      {
        title: '客户与业务员',
        fields: [
          { key: 'customerName', label: '客户' },
          { key: 'dealerName', label: '经销商' },
          { key: 'salespersonName', label: '业务员' }
        ]
      },
      {
        title: '金额',
        fields: [
          { key: 'receiptAmount', label: '收款金额', fmt: 'money' },
          { key: 'receivableAmount', label: '应收金额', fmt: 'money' },
          { key: 'allocatedAmount', label: '已分配金额', fmt: 'money' },
          { key: 'unallocatedAmount', label: '未分配金额', fmt: 'money' }
        ]
      },
      {
        title: '关联销售出库',
        fields: [
          { key: 'outboundNo', label: '销售出库编号' },
          { key: 'salesOrderDate', label: '销售订单日期', fmt: 'date' },
          { key: 'outboundCustomerName', label: '关联出库客户' },
          { key: 'outboundSalesAmount', label: '关联出库销售额', fmt: 'money' }
        ]
      },
      {
        title: '备注',
        fields: [{ key: 'remark', label: '备注', fmt: 'longText', span: 2 }]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'receiptType', label: '单据类型' },
          { key: 'sourceCreatedBy', label: '源创建人' },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ]
  },

  settlement: {
    titleKey: 'receiptNo',
    subtitleKeys: ['customerName', 'dealerName', 'paymentMethod', 'receiptDate'],
    sections: [
      {
        title: '结清信息',
        fields: [
          { key: 'receiptNo', label: '结清单号' },
          { key: 'status', label: '状态' },
          { key: 'receiptDate', label: '结清日期', fmt: 'date' },
          { key: 'paymentMethod', label: '支付方式' },
          { key: 'departmentName', label: '部门' }
        ]
      },
      {
        title: '客户与业务员',
        fields: [
          { key: 'customerName', label: '客户' },
          { key: 'dealerName', label: '经销商' },
          { key: 'salespersonName', label: '业务员' }
        ]
      },
      {
        title: '金额',
        fields: [
          { key: 'receiptAmount', label: '结清金额', fmt: 'money' },
          { key: 'receivableAmount', label: '应收金额', fmt: 'money' },
          { key: 'unallocatedAmount', label: '未分配金额', fmt: 'money' }
        ]
      },
      {
        title: '备注',
        fields: [{ key: 'remark', label: '备注', fmt: 'longText', span: 2 }]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'receiptType', label: '单据类型' },
          { key: 'sourceCreatedBy', label: '源创建人' },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'sequenceNo', label: '序号', width: 64, fmt: 'int' },
      { prop: 'outboundNo', label: '销售出库编号', minWidth: 160 },
      { prop: 'customerName', label: '客户', minWidth: 150 },
      { prop: 'salesAmount', label: '销售额', width: 120, fmt: 'money', align: 'right' },
      { prop: 'settledAmount', label: '明细结清金额', width: 130, fmt: 'money', align: 'right' }
    ],
    colsLabel: '覆盖的出库单',
    emptyText: '未解析到出库单明细（该结清单未含「出库单明细.销售出库编号」，或导入发生在 V222 上线前）'
  },

  product: {
    titleKey: 'productCode',
    subtitleKeys: ['productName', 'category'],
    sections: [
      {
        title: '台账信息',
        fields: [
          { key: 'productCode', label: '产品编号' },
          { key: 'productName', label: '产品名称', span: 2 },
          { key: 'category', label: '类目' },
          { key: 'unit', label: '单位' },
          { key: 'specification', label: '规格', span: 2 },
          { key: 'purchasePrice', label: '采购价', fmt: 'money' }
        ]
      },
      LEDGER_SYSTEM_SECTION
    ]
  },

  stock: {
    titleKey: 'productCode',
    subtitleKeys: ['productName', 'warehouse'],
    sections: [
      {
        title: '台账信息',
        fields: [
          { key: 'productCode', label: '产品编号' },
          { key: 'warehouse', label: '仓库' },
          { key: 'productName', label: '产品名称', span: 2 },
          { key: 'category', label: '类目' },
          { key: 'unit', label: '单位' },
          { key: 'specification', label: '规格', span: 2 },
          { key: 'quantity', label: '库存数量', fmt: 'num' },
          { key: 'purchasePrice', label: '采购价', fmt: 'money' }
        ]
      },
      LEDGER_SYSTEM_SECTION
    ]
  },

  dealer: {
    titleKey: 'dealerCode',
    subtitleKeys: ['dealerName', 'ownerSalesperson', 'dataStatus'],
    sections: [
      {
        title: '台账信息',
        fields: [
          { key: 'dealerCode', label: '经销商编号' },
          { key: 'dealerName', label: '经销商名称', span: 2 },
          { key: 'contact', label: '联系方式' },
          { key: 'ownerSalesperson', label: '归属业务员' },
          { key: 'address', label: '经销商地址', span: 2 },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      LEDGER_SYSTEM_SECTION
    ]
  },

  purchase: {
    titleKey: 'sourceNo',
    subtitleKeys: ['supplierName', 'supplierNameRaw', 'ownerCompany', 'purchaseDate'],
    sections: [
      {
        title: '单据信息',
        fields: [
          { key: 'sourceNo', label: '采购入库编号' },
          { key: 'dataStatus', label: '数据状态' },
          { key: 'purchaseDate', label: '采购日期', fmt: 'date' },
          { key: 'expectedArrivalDate', label: '预计到货', fmt: 'date' },
          { key: 'ownerCompany', label: '采购主体（所属部门）', span: 2 },
          { key: 'warehouseName', label: '仓库' },
          { key: 'keeper', label: '仓管员' },
          { key: 'paymentStatus', label: '付款状态' }
        ]
      },
      {
        title: '供应商',
        fields: [
          { key: 'supplierName', label: '解析后供应商', span: 2 },
          { key: 'supplierNameRaw', label: '单据引用原文', span: 2 },
          { key: 'contactName', label: '联系人' },
          { key: 'contactPhone', label: '联系电话' },
          { key: 'supplierAddress', label: '供应商地址', span: 2 }
        ]
      },
      {
        title: '金额',
        fields: [
          { key: 'totalAmount', label: '合计采购金额', fmt: 'money' },
          { key: 'totalQuantity', label: '合计采购数量', fmt: 'num' },
          { key: 'unpaidAmount', label: '未付款金额（应付）', fmt: 'money' },
          { key: 'paidAmount', label: '已付款金额', fmt: 'money' },
          { key: 'uninvoicedAmount', label: '未开票金额', fmt: 'money' }
        ]
      },
      {
        title: '备注',
        fields: [
          { key: 'orderRemark', label: '订单备注', fmt: 'longText', span: 2 },
          { key: 'paymentRemark', label: '付款备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'createdByName', label: '源创建人' },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'lineNo', label: '行号', width: 60, fmt: 'int' },
      { prop: 'category', label: '类目', width: 100 },
      { prop: 'productCode', label: '产品编号', width: 120 },
      { prop: 'productName', label: '产品名称', minWidth: 170 },
      { prop: 'spec', label: '规格', minWidth: 110 },
      { prop: 'unit', label: '单位', width: 60 },
      { prop: 'quantity', label: '入库数量', width: 95, fmt: 'num', align: 'right' },
      { prop: 'purchasePrice', label: '采购价格', width: 100, fmt: 'money', align: 'right' },
      { prop: 'amount', label: '采购金额', width: 110, fmt: 'money', align: 'right' },
      { prop: 'stockQuantity', label: '库存数量', width: 90, fmt: 'num', align: 'right' },
      { prop: 'isMold', label: '模具', width: 60, fmt: 'bool' },
      { prop: 'hasProcess', label: '有制程', width: 70 },
      { prop: 'productionOrderNo', label: '生产任务单', width: 130 },
      { prop: 'remark', label: '备注', minWidth: 110 }
    ],
    colsLabel: '采购产品明细',
    emptyText: '本单无产品明细行'
  },

  supplier: {
    titleKey: 'name',
    subtitleKeys: ['supplierStatus', 'departmentName', 'dataStatus'],
    sections: [
      {
        title: '主档信息',
        fields: [
          { key: 'name', label: '供应商名称', span: 2 },
          { key: 'normalizedName', label: '归一化名称（自愈匹配键）', span: 2 },
          { key: 'supplierStatus', label: '合作状态' },
          { key: 'contactName', label: '联系人' },
          { key: 'contactPhone', label: '联系电话' },
          { key: 'address', label: '地址', span: 2 },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '银行信息',
        fields: [
          { key: 'privateBankName', label: '对私银行' },
          { key: 'privateBankAccount', label: '对私账号' },
          { key: 'privateBankBranch', label: '对公开户行', span: 2 },
          { key: 'publicBankName', label: '对公银行' },
          { key: 'publicBankAccount', label: '对公账号' },
          { key: 'publicBankBranch', label: '对公开户支行', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（氚云数据ID）' },
          { key: 'isInternal', label: '内部车间/产线标记', fmt: 'bool' },
          { key: 'isDirtyValue', label: '串名脏值标记', fmt: 'bool' },
          { key: 'sourceTitle', label: '数据标题', span: 2 },
          { key: 'createdByName', label: '创建人' },
          { key: 'ownerName', label: '拥有者' },
          { key: 'departmentName', label: '所属部门', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'rawText', label: '别名引用原文（历史单据上的供应商文本）', minWidth: 260 },
      { prop: 'source', label: '引用来源', width: 130 },
      { prop: 'createdAt', label: '首次命中时间', width: 170, fmt: 'dateTime' }
    ],
    colsLabel: '引用别名',
    emptyText: '暂无别名（采购入库引用本供应商后自动落别名）'
  },

  otherIn: {
    titleKey: 'sourceNo',
    subtitleKeys: ['warehouseName', 'keeper', 'movementDate', 'dataStatus'],
    sections: [
      {
        title: '单据信息',
        fields: [
          { key: 'sourceNo', label: '入库单号' },
          { key: 'dataStatus', label: '数据状态' },
          { key: 'movementDate', label: '入库日期', fmt: 'date' },
          { key: 'warehouseName', label: '入库仓库' },
          { key: 'keeper', label: '仓管员' },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'sourceTitle', label: '数据标题', span: 2 },
          { key: 'createdByName', label: '创建人' },
          { key: 'ownerName', label: '拥有者' },
          { key: 'departmentName', label: '所属部门（业务主体）', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'lineNo', label: '行号', width: 60, fmt: 'int' },
      { prop: 'category', label: '类目', width: 100 },
      { prop: 'productCode', label: '产品编号', width: 120 },
      { prop: 'productName', label: '产品名称', minWidth: 170 },
      { prop: 'spec', label: '规格', minWidth: 110 },
      { prop: 'unit', label: '单位', width: 60 },
      { prop: 'quantity', label: '入库数量', width: 95, fmt: 'num', align: 'right' },
      { prop: 'purchasePrice', label: '采购价格', width: 100, fmt: 'money', align: 'right' },
      { prop: 'stockQuantity', label: '库存数量', width: 90, fmt: 'num', align: 'right' },
      { prop: 'remark', label: '备注', minWidth: 110 }
    ],
    colsLabel: '入库产品明细',
    emptyText: '本单无产品明细行'
  },

  otherOut: {
    titleKey: 'sourceNo',
    subtitleKeys: ['warehouseName', 'keeper', 'salesman', 'movementDate', 'dataStatus'],
    sections: [
      {
        title: '单据信息',
        fields: [
          { key: 'sourceNo', label: '出库单号' },
          { key: 'dataStatus', label: '数据状态' },
          { key: 'movementDate', label: '出库日期', fmt: 'date' },
          { key: 'warehouseName', label: '出库仓库' },
          { key: 'keeper', label: '仓管员' },
          { key: 'salesman', label: '业务员' },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '主表合计',
        fields: [
          { key: 'totalQuantity', label: '合计数量', fmt: 'num' },
          { key: 'totalAmount', label: '合计金额', fmt: 'money' }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'sourceTitle', label: '数据标题', span: 2 },
          { key: 'createdByName', label: '创建人' },
          { key: 'ownerName', label: '拥有者' },
          { key: 'departmentName', label: '所属部门（业务主体）', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'lineNo', label: '行号', width: 60, fmt: 'int' },
      { prop: 'category', label: '类目', width: 100 },
      { prop: 'productCode', label: '产品编号', width: 120 },
      { prop: 'productName', label: '产品名称', minWidth: 170 },
      { prop: 'spec', label: '规格', minWidth: 110 },
      { prop: 'unit', label: '单位', width: 60 },
      { prop: 'quantity', label: '出库数量', width: 95, fmt: 'num', align: 'right' },
      { prop: 'stockQuantity', label: '库存数量', width: 90, fmt: 'num', align: 'right' },
      { prop: 'purchasePrice', label: '采购价格', width: 100, fmt: 'money', align: 'right' },
      { prop: 'amount', label: '采购总价', width: 110, fmt: 'money', align: 'right' },
      { prop: 'remark', label: '备注', minWidth: 110 }
    ],
    colsLabel: '出库产品明细',
    emptyText: '本单无产品明细行'
  },

  transfer: {
    titleKey: 'sourceNo',
    subtitleKeys: ['outWarehouseName', 'inWarehouseName', 'transferDate', 'dataStatus'],
    sections: [
      {
        title: '单据信息',
        fields: [
          { key: 'sourceNo', label: '调拨单号' },
          { key: 'dataStatus', label: '数据状态' },
          { key: 'transferDate', label: '调拨日期', fmt: 'date' },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '调出侧',
        fields: [
          { key: 'outWarehouseName', label: '调出仓库' },
          { key: 'outKeeper', label: '仓管员（调出）' }
        ]
      },
      {
        title: '调入侧',
        fields: [
          { key: 'inWarehouseName', label: '调入仓库' },
          { key: 'inKeeper', label: '仓管员（调入）' }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID' },
          { key: 'sourceTitle', label: '数据标题', span: 2 },
          { key: 'createdByName', label: '创建人' },
          { key: 'ownerName', label: '拥有者' },
          { key: 'departmentName', label: '所属部门（业务主体）', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'lineNo', label: '行号', width: 60, fmt: 'int' },
      { prop: 'category', label: '类目', width: 100 },
      { prop: 'productCode', label: '产品编号', width: 120 },
      { prop: 'productName', label: '产品名称', minWidth: 170 },
      { prop: 'spec', label: '规格', minWidth: 110 },
      { prop: 'unit', label: '单位', width: 60 },
      { prop: 'quantity', label: '调拨数量', width: 95, fmt: 'num', align: 'right' },
      { prop: 'outStockQuantity', label: '调出仓快照', width: 110, fmt: 'num', align: 'right' },
      { prop: 'judgeRaw', label: '超库存标记', width: 100 },
      { prop: 'remark', label: '备注', minWidth: 110 }
    ],
    colsLabel: '调拨产品明细',
    emptyText: '本单无产品明细行'
  },
  invoiceProfile: {
    titleKey: 'customerName',
    subtitleKeys: ['taxNo', 'bankName', 'dataStatus'],
    sections: [
      {
        title: '开票信息',
        fields: [
          { key: 'customerName', label: '户名', span: 2 },
          { key: 'taxNo', label: '税号' },
          { key: 'dataStatus', label: '数据状态' },
          { key: 'address', label: '地址', fmt: 'longText', span: 2 },
          { key: 'phone', label: '电话' },
          { key: 'bankName', label: '开户行' },
          { key: 'bankAccount', label: '银行账号', span: 2 }
        ]
      },
      {
        title: '客户关联',
        fields: [
          { key: 'linkedCustomerName', label: '关联客户档案', span: 2 },
          { key: 'customerId', label: '客户 ID' },
          { key: 'normalizedName', label: '归一化户名', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（数据ID）' },
          { key: 'sourceTitle', label: '数据标题' },
          { key: 'ownerName', label: '拥有者' },
          { key: 'createdByName', label: '创建人' },
          { key: 'departmentName', label: '所属部门（业务主体）', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    emptyText: '开票资料为单表，无子表明细'
  },
  invoiceRequest: {
    titleKey: 'sourceNo',
    subtitleKeys: ['customerName', 'invoiceType', 'requestDate', 'dataStatus'],
    sections: [
      {
        title: '申请信息',
        fields: [
          { key: 'sourceNo', label: '流水号' },
          { key: 'invoiceType', label: '发票类型' },
          { key: 'requestDate', label: '开票日期', fmt: 'date' },
          { key: 'salesperson', label: '业务员' },
          { key: 'customerName', label: '户名', span: 2 },
          { key: 'taxNo', label: '税号', span: 2 },
          { key: 'redflushRef', label: '红冲引用', span: 2 },
          { key: 'dataStatus', label: '数据状态' }
        ]
      },
      {
        title: '金额与收款',
        fields: [
          { key: 'requestAmount', label: '申请金额', fmt: 'money' },
          { key: 'receivedAmount', label: '已收款', fmt: 'money' },
          { key: 'unreceivedAmount', label: '未收款', fmt: 'money' },
          { key: 'outboundRefRaw', label: '出库匹配原文（自由文本，不自动勾稽）', fmt: 'longText', span: 2 },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '开票时点快照（与开票资料的当前值相互独立）',
        fields: [
          { key: 'snapshotAddress', label: '地址', fmt: 'longText', span: 2 },
          { key: 'snapshotPhone', label: '电话' },
          { key: 'snapshotBankName', label: '开户行' },
          { key: 'snapshotBankAccount', label: '银行账号', span: 2 },
          { key: 'profileExternalId', label: '引用开票资料（数据ID）', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（数据ID）' },
          { key: 'sourceTitle', label: '数据标题' },
          { key: 'ownerName', label: '拥有者' },
          { key: 'createdByName', label: '创建人' },
          { key: 'departmentName', label: '所属部门（业务主体）', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'lineNo', label: '行号', width: 60, fmt: 'int' },
      { prop: 'productName', label: '产品名称', minWidth: 200 },
      { prop: 'specModel', label: '规格型号', minWidth: 120 },
      { prop: 'unit', label: '单位', width: 60 },
      { prop: 'quantity', label: '数量', width: 95, fmt: 'num', align: 'right' },
      { prop: 'unitPrice', label: '单价', width: 95, fmt: 'money', align: 'right' },
      { prop: 'amount', label: '金额小计', width: 110, fmt: 'money', align: 'right' }
    ],
    colsLabel: '开票内容',
    emptyText: '本单无开票内容行',
    cols2: [
      { prop: 'rawTitle', label: '原文标题', minWidth: 140 },
      { prop: 'receiptDate', label: '收款日期', width: 110, fmt: 'date' },
      { prop: 'receiptMethod', label: '收款方式', width: 100 },
      { prop: 'receiptAmount', label: '收款金额', width: 120, fmt: 'money', align: 'right' }
    ],
    cols2Label: '收款情况（仅引用，不写收款记录）',
    emptyText2: '本单无收款情况行'
  },
  productionReport: {
    titleKey: 'workOrderNo',
    subtitleKeys: ['componentName', 'reporter', 'reportTime', 'dataStatus'],
    sections: [
      {
        title: '报工信息',
        fields: [
          { key: 'reportTime', label: '报工时间', fmt: 'dateTime' },
          { key: 'workOrderNo', label: '生产工单编号' },
          { key: 'workOrderText', label: '生产任务单原文', fmt: 'longText', span: 2 },
          { key: 'componentName', label: '工序（零部件）' },
          { key: 'componentSerial', label: '零部件流水号' },
          { key: 'reportQty', label: '报工数量' },
          { key: 'orderDemandQty', label: '订单需求数量' },
          { key: 'remainingQty', label: '工单剩余需求数量' },
          { key: 'overFlag', label: '是否超额生产' },
          { key: 'overQty', label: '超额生产数量' },
          { key: 'isCustomQty', label: '是否自定义报工数量' },
          { key: 'hasComponentFlag', label: '生产工单中是否有该零部件' },
          { key: 'dataStatus', label: '数据状态' }
        ]
      },
      {
        title: '人员与产线',
        fields: [
          { key: 'reporter', label: '报工人' },
          { key: 'reporterAssist', label: '报工人（辅助）' },
          { key: 'reporterDept', label: '报工人所属部门', span: 2 },
          { key: 'productionLine', label: '产线' },
          { key: 'orderClerk', label: '接单员' },
          { key: 'stockInClerk', label: '入库员' },
          { key: 'partSerialsText', label: '零部件流水号集合（原文）', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（数据ID）' },
          { key: 'sourceNo', label: '业务键（无单号 → 数据ID兜底）' },
          { key: 'dataTitle', label: '数据标题', span: 2 },
          { key: 'ownerName', label: '拥有者' },
          { key: 'createdByName', label: '创建人' },
          { key: 'departmentName', label: '所属部门（业务主体）', span: 2 },
          { key: 'sourceCreatedAt', label: '源创建时间', fmt: 'dateTime' },
          { key: 'sourceUpdatedAt', label: '源更新时间', fmt: 'dateTime' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    emptyText: '生产报工为单表，无子表明细'
  },
  invoiceInput: {
    titleKey: 'sourceNo',
    subtitleKeys: ['sellerName', 'invoiceKind', 'invoiceStatus', 'invoiceDate'],
    sections: [
      {
        title: '发票信息',
        fields: [
          { key: 'sourceNo', label: '数电发票号码' },
          { key: 'invoiceDate', label: '开票日期', fmt: 'dateTime' },
          { key: 'invoiceKind', label: '发票票种' },
          { key: 'invoiceSource', label: '发票来源' },
          { key: 'invoiceStatus', label: '发票状态' },
          { key: 'isPositive', label: '是否正数发票' },
          { key: 'riskLevel', label: '发票风险等级' },
          { key: 'drawer', label: '开票人' }
        ]
      },
      {
        title: '销方（供应商侧）',
        fields: [
          { key: 'sellerName', label: '销方名称', span: 2 },
          { key: 'sellerTaxNo', label: '销方识别号', span: 2 },
          { key: 'buyerName', label: '购买方名称（我方主体）' },
          { key: 'buyerTaxNo', label: '购方识别号', span: 2 }
        ]
      },
      {
        title: '金额',
        fields: [
          { key: 'amount', label: '金额（不含税）', fmt: 'money' },
          { key: 'taxAmount', label: '税额', fmt: 'money' },
          { key: 'totalAmount', label: '价税合计', fmt: 'money' },
          { key: 'remark', label: '备注', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（数电发票号码）' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    emptyText: '进项发票为单表，无子表明细'
  },
  supplierPayable: {
    titleKey: 'supplierName',
    subtitleKeys: ['monthRaw', 'payMethod', 'payDate', 'remainingAmount'],
    sections: [
      {
        title: '应付信息',
        fields: [
          { key: 'supplierName', label: '供应商（sheet 名）', span: 2 },
          { key: 'monthNo', label: '月份' },
          { key: 'monthRaw', label: '月份原值' },
          { key: 'payableAmount', label: '应付货款', fmt: 'money' },
          { key: 'settledAmount', label: '结算货款', fmt: 'money' },
          { key: 'allowance', label: '折让（独立概念）', fmt: 'money' },
          { key: 'remainingAmount', label: '剩余货款', fmt: 'money' }
        ]
      },
      {
        title: '付款信息',
        fields: [
          { key: 'payDate', label: '付款日期', fmt: 'date' },
          { key: 'payMethod', label: '付款方式' },
          { key: 'payAmount', label: '付款金额', fmt: 'money' }
        ]
      },
      {
        title: '身份反查与导入信息',
        fields: [
          { key: 'resolvedSupplierName', label: 'RK 反查正式身份', span: 2 },
          { key: 'externalId', label: '外部 ID（供应商#月份）' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    emptyText: '供应商应付为单表（尾部 RK/费用明细行不入库）'
  },
  cashflowJournal: {
    titleKey: 'accountName',
    subtitleKeys: ['entryDate', 'summary', 'income', 'expense', 'balance'],
    sections: [
      {
        title: '分录信息',
        fields: [
          { key: 'accountName', label: '账户（sheet 名，源账户列不采信）', span: 2 },
          { key: 'entryDate', label: '日期', fmt: 'date' },
          { key: 'monthLabel', label: '月份（人工标注原值）' },
          { key: 'summary', label: '摘要', span: 2 },
          { key: 'income', label: '收入', fmt: 'money' },
          { key: 'expense', label: '支出', fmt: 'money' },
          { key: 'balance', label: '结存', fmt: 'money' }
        ]
      },
      {
        title: '人工标注字段（原样存，不解读）',
        fields: [
          { key: 'supplierRaw', label: '供应商', span: 2 },
          { key: 'counterpartyRaw', label: '客户 供应商', span: 2 },
          { key: 'reimbursedBy', label: '报销人' },
          { key: 'invoiced', label: '是否开票' }
        ]
      },
      {
        title: '外币折算（仅睿包外币账户，人民币行全空）',
        fields: [
          { key: 'fxRate', label: '汇率' },
          { key: 'fxCny', label: '人民币（折算）', fmt: 'money' },
          { key: 'settleRate', label: '结汇汇率' },
          { key: 'settleCny', label: '人民币（结汇）', fmt: 'money' },
          { key: 'fxGainLoss', label: '汇兑损益', fmt: 'money' }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（账户#日期#收入#支出#结存#序号）', span: 2 },
          { key: 'sourceNo', label: '业务键（无单号，同外部 ID）', span: 2 },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    emptyText: '流水日记账为单表（「上期结存」伪行转写账户期初余额，不入分录）'
  },
  cashierLog: {
    titleKey: 'summary',
    subtitleKeys: ['txTime', 'amount', 'salesperson'],
    sections: [
      {
        title: '交易信息',
        fields: [
          { key: 'txTime', label: '交易完成时间', fmt: 'dateTime' },
          { key: 'status', label: '交易状态' },
          { key: 'amount', label: '交易金额', fmt: 'money' },
          { key: 'fee', label: '订单总扣费（手续费，源值带负号）', fmt: 'money' },
          { key: 'payerOpenid', label: '付款人识别号', span: 2 },
          { key: 'summary', label: '摘要（人工文本：折扣率+客户名+备注）', span: 2 },
          { key: 'salesperson', label: '业务员' }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（时间#金额#付款人识别号）', span: 2 },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    emptyText: '收银通收单流水为单表'
  },
  invoiceSales: {
    titleKey: 'sourceNo',
    subtitleKeys: ['buyerName', 'paymentMethod', 'invoiceStatus', 'invoiceDate'],
    sections: [
      {
        title: '发票信息',
        fields: [
          { key: 'sourceNo', label: '数电发票号码' },
          { key: 'invoiceDate', label: '开票日期', fmt: 'dateTime' },
          { key: 'invoiceKind', label: '发票票种' },
          { key: 'invoiceSource', label: '发票来源' },
          { key: 'invoiceStatus', label: '发票状态' },
          { key: 'isPositive', label: '是否正数发票（否=红字票）' },
          { key: 'riskLevel', label: '发票风险等级' },
          { key: 'drawer', label: '开票人' }
        ]
      },
      {
        title: '购买方（客户侧）',
        fields: [
          { key: 'buyerName', label: '购买方名称', span: 2 },
          { key: 'buyerTaxNo', label: '购方识别号', span: 2 },
          { key: 'sellerName', label: '销方名称（我方主体）' },
          { key: 'sellerTaxNo', label: '销方识别号' },
          { key: 'salesman', label: '业务员' }
        ]
      },
      {
        title: '金额与收款（校验源：收款权威=销售出库）',
        fields: [
          { key: 'amount', label: '金额（不含税）', fmt: 'money' },
          { key: 'taxAmount', label: '税额', fmt: 'money' },
          { key: 'totalAmount', label: '价税合计', fmt: 'money' },
          { key: 'receiptDate', label: '收款日期', fmt: 'date' },
          { key: 'paymentMethod', label: '付款方式（收款渠道）' },
          { key: 'receiptAmount', label: '收款金额', fmt: 'money' },
          { key: 'unreceivedAmount', label: '未收款金额', fmt: 'money' },
          { key: 'remark', label: '备注（业务收款备注）', fmt: 'longText', span: 2 },
          { key: 'remarkPlatform', label: '备注（平台红冲链）', fmt: 'longText', span: 2 }
        ]
      },
      {
        title: '导入与系统信息',
        fields: [
          { key: 'externalId', label: '外部 ID（数电发票号码）' },
          { key: 'sourceHash', label: '来源哈希', span: 2 },
          { key: 'lastSyncedAt', label: '最后同步', fmt: 'dateTime' },
          { key: 'createdAt', label: '入库时间', fmt: 'dateTime' },
          { key: 'updatedAt', label: '更新时间', fmt: 'dateTime' },
          { key: 'id', label: '记录 ID' }
        ]
      }
    ],
    cols: [
      { prop: 'sourceItemId', label: '子行键', width: 150 },
      { prop: 'receiptDate', label: '收款日期', width: 110, fmt: 'date' },
      { prop: 'paymentMethod', label: '付款方式', width: 130 },
      { prop: 'receiptAmount', label: '收款金额', width: 120, fmt: 'money', align: 'right' },
      { prop: 'salesman', label: '业务员', width: 90 },
      { prop: 'remark', label: '备注', minWidth: 160 }
    ],
    colsLabel: '多次收款登记',
    emptyText: '本票无多次收款登记行'
  }
}

const cfg = computed(() => CFG[props.ledgerType] || CFG.product)
const sections = computed(() => cfg.value.sections)
const cols = computed(() => cfg.value.cols || null)
const rows2 = computed(() => props.data?.rows2 ?? [])
const cols2 = computed(() => cfg.value.cols2 || null)
const cols2Label = computed(() => cfg.value.cols2Label || '子表明细')
const emptyText2 = computed(() => cfg.value.emptyText2 || '无子表明细')
const colsLabel = computed(() => cfg.value.colsLabel || '子表明细')
const emptyText = computed(() => cfg.value.emptyText || '')

/** 头部主标题：业务单号字段 + 客户（product/stock/dealer 无单号，展示「编号 + 名称」） */
const mainText = computed(() => {
  const r = row.value
  if (!r) return ''
  const main = fmtVal(r[cfg.value.titleKey], 'text')
  if (props.ledgerType === 'product' || props.ledgerType === 'stock') {
    const name = r.productName
    return name ? `${main} · ${name}` : main
  }
  if (props.ledgerType === 'dealer') {
    const name = r.dealerName
    return name ? `${main} · ${name}` : main
  }
  return main
})
const subtitleText = computed(() => {
  const r = row.value
  if (!r) return ''
  return cfg.value.subtitleKeys
    .map(k => {
      const v = r[k]
      if (v === null || v === undefined || v === '') return ''
      return k === 'shipDate' || k === 'receiptDate' || k === 'applicationDate'
        ? fmtVal(v, 'date')
        : String(v)
    })
    .filter(Boolean)
    .join(' · ')
})
/** 收款记录若详情未解析到出库关联：多为 V269 前导入、当时未保存关联列，提示重新导入对应 Excel。 */
const missingLinkHint = computed(
  () => props.ledgerType === 'receipt' && !props.loading && !!row.value && !row.value.outboundNo
)

function isTrue(v: any) {
  return v === true || v === 1 || v === '1' || String(v).toLowerCase() === 'true'
}
function fmtVal(v: any, fmt?: Fmt): string {
  if (v === null || v === undefined || v === '') return '—'
  switch (fmt) {
    case 'bool':
      return isTrue(v) ? '是' : '否'
    case 'int':
      return Number(v).toLocaleString('zh-CN')
    case 'money':
      return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    case 'num':
      return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
    case 'date':
      return String(v).slice(0, 10)
    case 'dateTime':
      return String(v).replace('T', ' ').slice(0, 19)
    default:
      return String(v)
  }
}
/** 状态标签配色：结清/完成/收讫=绿；部分/进行中/生产中=蓝橙；其余中性。 */
function statusTagType(v: any): 'success' | 'warning' | 'info' | 'primary' {
  if (!v) return 'info'
  return /完成|结清|收讫|成功|已生效|生效/i.test(String(v)) ? 'success' : /部分|进行中|生产中|进行/i.test(String(v)) ? 'warning' : 'primary'
}
</script>

<style scoped>
.detail-panel {
  min-height: 200px;
}
.detail-hint {
  margin-bottom: 16px;
}
.source-date-error {
  color: #b42318;
  font-weight: 700;
}
.detail-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
  padding: 12px 14px;
  background: var(--color-surface-alt, #f6f8fa);
  border: 1px solid var(--color-border-lighter, #eef2f6);
  border-radius: 10px;
}
.detail-head__left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.detail-head__main {
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text-heading, #0f172a);
  word-break: break-all;
}
.detail-head__sub {
  font-size: 12px;
  color: var(--color-text-muted, #64748b);
  word-break: break-all;
}
.detail-sec {
  margin-bottom: 16px;
}
.relation-card { padding: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-bg-color); }
.relation-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.relation-card__head p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.relation-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.relation-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; padding: 10px 12px; border-radius: 8px; background: var(--el-fill-color-light); }
.relation-item > span,.relation-item small { color: var(--el-text-color-secondary); font-size: 12px; }
.relation-item a { color: var(--el-color-primary); font-weight: 600; text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.relation-missing { color: var(--el-color-warning); }
.relation-ok { color: var(--el-color-success); }
@media (max-width: 720px) { .relation-grid { grid-template-columns: 1fr; } }
.nature-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.nature-card__confirmed {
  margin-top: 8px;
  padding: 8px 12px;
  color: var(--color-text-body, #334155);
  background: var(--color-surface-alt, #f8fafc);
  border-radius: 8px;
  font-size: 13px;
}
.detail-sec__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--color-text-heading, #334155);
}
.detail-sec__count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--color-surface-alt, #eef2f6);
  font-size: 12px;
  color: var(--color-text-muted, #64748b);
}
.detail-empty {
  margin: 0;
  padding: 12px;
  font-size: 12px;
  color: var(--color-text-muted, #94a3b8);
  background: var(--color-surface-alt);
  border: 1px dashed var(--color-border, #dbe4ee);
  border-radius: 8px;
  line-height: 1.6;
}
:deep(.detail-desc .el-descriptions__label) {
  color: var(--color-text-muted, #64748b);
}
:deep(.detail-desc .el-descriptions__content) {
  color: var(--color-text-body, #334155);
  overflow-wrap: break-word;
  word-break: break-all;
}
.d-long {
  white-space: pre-wrap;
  line-height: 1.6;
}

/* ===== 更新历史 ===== */
.history-body {
  min-height: 28px;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.history-item {
  padding: 8px 12px;
  border: 1px solid var(--color-border-lighter, #eef2f6);
  border-radius: 8px;
  background: var(--color-surface-alt);
}
.history-item__head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.history-batch {
  font-weight: 600;
  font-size: 13px;
  color: var(--color-text-heading, #0f172a);
}
.history-file {
  font-size: 12px;
  color: var(--color-text-muted, #64748b);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-time {
  font-size: 12px;
  color: var(--color-text-muted, #64748b);
  margin-left: auto;
}
.history-changes {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-body, #334155);
}
.history-change b {
  color: var(--color-text-heading, #0f172a);
  margin-right: 6px;
}
.history-change__old {
  color: var(--color-danger, #b91c1c);
  text-decoration: line-through;
  opacity: 0.75;
}
.history-change__arrow {
  margin: 0 6px;
  color: var(--color-text-muted, #94a3b8);
}
.history-change__new {
  color: var(--color-success, #15803d);
  font-weight: 600;
}
</style>
