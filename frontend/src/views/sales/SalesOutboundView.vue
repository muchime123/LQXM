<template>
  <div class="outbound-page">
    <!-- 统计卡片：口径为当前筛选条件下的全量（后端聚合），与分页无关 -->
    <section class="stats-grid">
      <div class="stat-card">
        <span class="stat-card__label">出库单数</span>
        <span class="stat-card__value">{{ total }}</span>
        <span class="stat-card__hint" v-if="draftCount > 0">含草稿 {{ draftCount }}</span>
      </div>
      <div class="stat-card stat-card--accent">
        <span class="stat-card__label">本页出库金额</span>
        <span class="stat-card__value">¥{{ fmtMoney(pageAmount) }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">已过账</span>
        <span class="stat-card__value">{{ postedCount }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">已冲销</span>
        <span class="stat-card__value">{{ reversedCount }}</span>
      </div>
    </section>

    <section class="panel">
      <div class="toolbar">
        <div class="toolbar__left">
          <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width:130px" @change="load(1)">
            <el-option value="DRAFT" label="草稿" />
            <el-option value="POSTED" label="已过账" />
            <el-option value="REVERSED" label="已冲销" />
          </el-select>
          <el-select v-model="warehouseId" clearable placeholder="全部仓库" style="width:130px" @change="load(1)">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" />
          </el-select>
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
            start-placeholder="开始日期" end-placeholder="结束日期" style="width:240px" @change="load(1)" />
          <el-input v-model="keyword" clearable placeholder="单号 / 客户 / 订单号 / 产品" style="width:220px"
            @keyup.enter="load(1)" @clear="load(1)">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" plain @click="load(1)">查询</el-button>
        </div>
        <div class="toolbar__right">
          <el-button v-if="canManage" type="primary" @click="openCreate()">新建出库单</el-button>
        </div>
      </div>

      <!-- 口径说明：这张表是 ERP 自建单据，与氚云台账不是同一批数据 -->
      <div class="scope-note">
        <span><b>ERP 正式出库单</b>（<code>erp_sales_outbounds</code>）：可录单、过账即扣库存、冲销回补</span>
        <span class="scope-note__sep">·</span>
        <span class="scope-note__quiet">与「氚云导入中心 → 销售出库关联」里的历史台账<b>不是同一批数据</b>，两者不混算</span>
        <span class="scope-note__sep">·</span>
        <span class="scope-note__quiet">流程：草稿 → 过账（扣减库存）→ 冲销（回补库存，终态）</span>
      </div>

      <el-table :data="items" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="outboundNo" label="出库单号" width="150" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS_TAG[row.status] || 'info'" effect="plain">
              {{ STATUS_TEXT[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="170" show-overflow-tooltip />
        <el-table-column label="业务员" width="90">
          <template #default="{ row }">
            <span>{{ row.salespersonName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="仓库" width="90">
          <template #default="{ row }">
            <span>{{ row.warehouseName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="出库日期" width="110">
          <template #default="{ row }">
            <span>{{ shortDate(row.outboundDate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.totalAmount != null">¥{{ fmtMoney(row.totalAmount) }}</span>
            <span v-else class="cell-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="明细" width="70" align="right">
          <template #default="{ row }">
            <span>{{ row.itemCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canManage && row.status === 'DRAFT'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canManage && row.status === 'DRAFT'" link type="success" @click="doPost(row)">过账</el-button>
            <el-button v-if="canManage && row.status === 'POSTED'" link type="danger" @click="doReverse(row)">冲销</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-box">
            <p>还没有销售出库单</p>
            <p class="empty-box__hint" v-if="canManage">
              点右上角「新建出库单」开始；明细只能从<b>可售库存</b>里选 —— 选不到就说明该先下生产任务。
            </p>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination background layout="total, sizes, prev, pager, next" :total="total" :current-page="page"
          :page-size="size" :page-sizes="[20, 50, 100]" @current-change="(p: number) => load(p)"
          @size-change="(s: number) => { size = s; load(1) }" />
      </div>
    </section>

    <!-- ==================== 建单 / 改单弹窗 ==================== -->
    <el-dialog v-model="formOpen" :title="editingId ? '编辑销售出库单' : '新建销售出库单'" width="900px" top="6vh"
      :close-on-click-modal="false" @closed="onFormClosed">
      <el-form label-width="90px">
        <div class="form-grid">
          <el-form-item label="客户名称" required>
            <el-input v-model="form.customerName" placeholder="请输入客户名称" maxlength="300" />
          </el-form-item>
          <el-form-item label="发货仓库" required>
            <el-select v-model="form.warehouseId" placeholder="请选择仓库" style="width:100%" @change="onWarehouseChange">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" />
            </el-select>
          </el-form-item>
          <el-form-item label="出库日期" required>
            <el-date-picker v-model="form.outboundDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
          <el-form-item label="关联订单号">
            <el-input v-model="form.salesOrderRef" placeholder="选填，合同号 / 订单号" maxlength="100" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>

      <div class="lines-head">
        <span class="lines-head__title">出库明细</span>
        <span class="lines-head__hint">只能从「可售库存」选货 —— 这是「有货直接出库 / 无货下生产任务」的分叉点</span>
        <el-button size="small" type="primary" plain @click="openPicker(-1)">添加明细</el-button>
      </div>
      <el-table :data="form.lines" size="small" border>
        <el-table-column label="#" width="50">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="产品" min-width="220" show-overflow-tooltip>
          <template #default="{ row, $index }">
            <span v-if="row.productName">{{ row.productName }}</span>
            <span v-else class="cell-muted">未选择</span>
            <el-button link type="primary" size="small" style="margin-left:8px" @click="openPicker($index)">
              {{ row.productId ? '更换' : '选择' }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="规格" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.specification || '—' }}</template>
        </el-table-column>
        <el-table-column label="单位" width="70">
          <template #default="{ row }">{{ row.unit || '—' }}</template>
        </el-table-column>
        <el-table-column label="可用库存" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.available != null" :class="{ 'qty-low': Number(row.quantity) > Number(row.available) }">
              {{ fmtNum(row.available) }}
            </span>
            <span v-else class="cell-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="出库数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" :precision="6" :controls="false" size="small"
              style="width:100%" />
          </template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0" :precision="4" :controls="false" size="small"
              style="width:100%" placeholder="选填" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="form.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">{{ editingId ? '保存' : '建单' }}</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 选货弹窗（可售库存） ==================== -->
    <el-dialog v-model="pickerOpen" title="选择出库产品" width="760px" append-to-body>
      <div class="picker-bar">
        <el-select v-model="pickerWarehouseId" placeholder="仓库" style="width:130px" @change="loadStock()">
          <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" />
        </el-select>
        <el-input v-model="stockKeyword" clearable placeholder="编号 / 名称 / 规格" style="width:220px"
          @keyup.enter="loadStock()" @clear="loadStock()">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" plain @click="loadStock()">查询</el-button>
      </div>
      <el-table :data="stockRows" v-loading="stockLoading" height="380" size="small" highlight-current-row
        @row-dblclick="(row: AvailableStock) => pickStock(row)">
        <el-table-column prop="warehouseName" label="仓库" width="90" />
        <el-table-column prop="productCode" label="编号" width="130" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="specification" label="规格" width="110" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="可用库存" width="110" align="right">
          <template #default="{ row }"><span class="qty-ok">{{ fmtNum(row.available) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="pickStock(row)">选择</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-box">
            <p>没有可售库存</p>
            <p class="empty-box__hint">这说明该产品当前<b>没有现货</b>（不在库存余额里）—— 正确做法是先下生产任务，完工入库后再出库。</p>
          </div>
        </template>
      </el-table>
      <template #footer>
        <span class="picker-hint">双击行可直接选择</span>
        <el-button @click="pickerOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 详情抽屉 ==================== -->
    <el-drawer v-model="detailOpen" :title="detail?.head?.outboundNo || '出库单详情'" size="620px">
      <div v-if="detail" class="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="STATUS_TAG[detail.head.status] || 'info'" effect="plain">
              {{ STATUS_TEXT[detail.head.status] || detail.head.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="出库日期">{{ shortDate(detail.head.outboundDate) }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.head.customerName }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ detail.head.salespersonName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发货仓库">{{ detail.head.warehouseName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="关联订单号">{{ detail.head.salesOrderRef || '—' }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            <span v-if="detail.head.totalAmount != null">¥{{ fmtMoney(detail.head.totalAmount) }}</span>
            <span v-else class="cell-muted">未填单价</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建人">{{ detail.head.createdByName || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail__block">
          <h4>明细</h4>
          <el-table :data="detail.items" size="small" border>
            <el-table-column prop="lineNo" label="#" width="46" />
            <el-table-column prop="productName" label="产品" min-width="160" show-overflow-tooltip />
            <el-table-column prop="specification" label="规格" width="100" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="60" />
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">{{ fmtNum(row.quantity) }}</template>
            </el-table-column>
            <el-table-column label="单价" width="90" align="right">
              <template #default="{ row }">
                <span v-if="row.unitPrice != null">¥{{ fmtMoney(row.unitPrice) }}</span>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="detail__block" v-if="detail.head.status !== 'DRAFT'">
          <h4>流程留痕</h4>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="过账" v-if="detail.head.postedAt">
              {{ shortTime(detail.head.postedAt) }} · {{ detail.head.postedByName || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="冲销" v-if="detail.head.reversedAt">
              {{ shortTime(detail.head.reversedAt) }} · {{ detail.head.reversedByName || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="冲销原因" v-if="detail.head.reversalReason">
              {{ detail.head.reversalReason }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="detail__actions" v-if="canManage">
          <el-button v-if="detail.head.status === 'DRAFT'" type="success" @click="doPost(detail.head)">过账</el-button>
          <el-button v-if="detail.head.status === 'POSTED'" type="danger" @click="doReverse(detail.head)">冲销</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { notify } from '@/composables/ui/useToast'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import {
  fetchSalesOutbounds, fetchSalesOutbound, fetchSalesOutboundWarehouses, fetchAvailableStock,
  createSalesOutbound, updateSalesOutbound, postSalesOutbound, reverseSalesOutbound, newRequestKey,
  type SalesOutbound, type SalesOutboundItem, type AvailableStock
} from '@/api/salesOutbound'

const auth = useCurrentUser()
const canManage = computed(() => auth.can('sales_order.manage'))

const STATUS_TEXT: Record<string, string> = { DRAFT: '草稿', POSTED: '已过账', REVERSED: '已冲销' }
const STATUS_TAG: Record<string, 'info' | 'success' | 'danger'> = { DRAFT: 'info', POSTED: 'success', REVERSED: 'danger' }

const keyword = ref('')
const statusFilter = ref<string>()
const warehouseId = ref<number>()
const dateRange = ref<[string, string] | null>(null)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<SalesOutbound[]>([])
const warehouses = ref<Array<{ id: number; warehouseCode: string; warehouseName: string }>>([])
const loading = ref(false)

const draftCount = computed(() => items.value.filter(r => r.status === 'DRAFT').length)
const postedCount = computed(() => items.value.filter(r => r.status === 'POSTED').length)
const reversedCount = computed(() => items.value.filter(r => r.status === 'REVERSED').length)
const pageAmount = computed(() => items.value.reduce((s, r) => s + (Number(r.totalAmount) || 0), 0))

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const res = await fetchSalesOutbounds({
      keyword: keyword.value,
      status: statusFilter.value || '',
      warehouseId: warehouseId.value,
      dateFrom: dateRange.value?.[0] || '',
      dateTo: dateRange.value?.[1] || '',
      page: p,
      size: size.value
    })
    items.value = res.items || []
    total.value = res.total || 0
  } catch (e) {
    notify.errorWithDetail('加载销售出库单失败', e)
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const loadWarehouses = async () => {
  try { warehouses.value = await fetchSalesOutboundWarehouses() } catch { warehouses.value = [] }
}

// ==================== 建单 / 改单 ====================

interface LineDraft {
  productId?: number
  productCode?: string
  productName: string
  specification?: string
  unit: string
  quantity: number | undefined
  unitPrice?: number | null
  remark?: string
  /** 选货时带出的可用库存，仅用于界面提示（真正校验在后端过账时做） */
  available?: number
}

const formOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formKey = ref('')
const form = reactive<{
  customerName: string
  warehouseId?: number
  outboundDate: string
  salesOrderRef: string
  remark: string
  lines: LineDraft[]
}>({
  customerName: '',
  warehouseId: undefined,
  outboundDate: today(),
  salesOrderRef: '',
  remark: '',
  lines: []
})

function today() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const openCreate = () => {
  editingId.value = null
  form.customerName = ''
  form.warehouseId = warehouses.value.length === 1 ? warehouses.value[0].id : undefined
  form.outboundDate = today()
  form.salesOrderRef = ''
  form.remark = ''
  form.lines = []
  formKey.value = newRequestKey()
  formOpen.value = true
}

const openEdit = async (row: SalesOutbound) => {
  try {
    const d = await fetchSalesOutbound(row.id)
    editingId.value = row.id
    form.customerName = d.head.customerName || ''
    form.warehouseId = d.head.warehouseId
    form.outboundDate = shortDate(d.head.outboundDate) === '—' ? today() : shortDate(d.head.outboundDate)
    form.salesOrderRef = d.head.salesOrderRef || ''
    form.remark = d.head.remark || ''
    form.lines = (d.items || []).map((it: SalesOutboundItem) => ({
      productId: it.salesProductId,
      productCode: it.productCode,
      productName: it.productName,
      specification: it.specification,
      unit: it.unit,
      quantity: Number(it.quantity),
      unitPrice: it.unitPrice ?? null,
      remark: it.remark
    }))
    formKey.value = newRequestKey()
    formOpen.value = true
  } catch (e) {
    notify.errorWithDetail('读取出库单失败', e)
  }
}

// 成功提交后丢弃请求键；关闭弹窗不改键（同一次编辑的重试复用同一键，后端才认得出重放）
const onFormClosed = () => { saving.value = false }

const onWarehouseChange = () => {
  // 换仓库后原有明细的可用库存不再适用，清掉提示值让用户重新确认
  form.lines.forEach(l => { l.available = undefined })
}

const submitForm = async () => {
  if (!form.customerName.trim()) { notify.warning('请填写客户名称'); return }
  if (!form.warehouseId) { notify.warning('请选择发货仓库'); return }
  const lines = form.lines.filter(l => l.productId)
  if (lines.length === 0) { notify.warning('请至少添加一行出库明细'); return }
  for (let i = 0; i < lines.length; i++) {
    const q = Number(lines[i].quantity)
    if (!q || q <= 0) { notify.warning(`第 ${i + 1} 行「${lines[i].productName}」的出库数量必须大于 0`); return }
  }
  const payload = {
    customerName: form.customerName.trim(),
    warehouseId: form.warehouseId,
    outboundDate: form.outboundDate,
    salesOrderRef: form.salesOrderRef.trim(),
    remark: form.remark.trim(),
    lines: lines.map(l => ({
      productId: l.productId as number,
      quantity: Number(l.quantity),
      unitPrice: l.unitPrice ?? null,
      remark: l.remark
    }))
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateSalesOutbound(editingId.value, payload, formKey.value)
      notify.success('已保存')
    } else {
      await createSalesOutbound(payload, formKey.value)
      notify.success('已创建草稿，确认无误后点「过账」才会扣减库存')
    }
    formOpen.value = false
    formKey.value = '' // 本次动作完成，下一次提交必须换新键
    await load(editingId.value ? page.value : 1)
  } catch (e) {
    notify.errorWithDetail('保存失败', e)
  } finally {
    saving.value = false
  }
}

// ==================== 选货 ====================

const pickerOpen = ref(false)
const pickerIndex = ref(-1)
const pickerWarehouseId = ref<number>()
const stockKeyword = ref('')
const stockRows = ref<AvailableStock[]>([])
const stockLoading = ref(false)

const openPicker = (index: number) => {
  pickerIndex.value = index
  pickerWarehouseId.value = form.warehouseId
  stockKeyword.value = ''
  pickerOpen.value = true
  loadStock()
}

const loadStock = async () => {
  stockLoading.value = true
  try {
    stockRows.value = await fetchAvailableStock({
      keyword: stockKeyword.value,
      warehouseId: pickerWarehouseId.value,
      limit: 200
    })
  } catch (e) {
    notify.errorWithDetail('加载可售库存失败', e)
    stockRows.value = []
  } finally {
    stockLoading.value = false
  }
}

const pickStock = (row: AvailableStock) => {
  const line: LineDraft = {
    productId: row.canonicalProductId,
    productCode: row.productCode,
    productName: row.productName,
    specification: row.specification,
    unit: row.unit,
    quantity: 1,
    unitPrice: null,
    available: Number(row.available)
  }
  if (pickerIndex.value >= 0) {
    const old = form.lines[pickerIndex.value]
    form.lines[pickerIndex.value] = { ...line, quantity: old?.quantity || 1, unitPrice: old?.unitPrice ?? null }
  } else {
    form.lines.push(line)
  }
  // 若弹窗里换了仓库，视为用户确认了新的发货仓
  if (pickerWarehouseId.value && pickerWarehouseId.value !== form.warehouseId) form.warehouseId = pickerWarehouseId.value
  pickerOpen.value = false
}

// ==================== 详情 / 过账 / 冲销 ====================

const detailOpen = ref(false)
const detail = ref<{ head: SalesOutbound; items: SalesOutboundItem[] } | null>(null)

const openDetail = async (row: SalesOutbound) => {
  try {
    detail.value = await fetchSalesOutbound(row.id)
    detailOpen.value = true
  } catch (e) {
    notify.errorWithDetail('读取详情失败', e)
  }
}

const doPost = async (row: SalesOutbound) => {
  try {
    await ElMessageBox.confirm(
      `过账后将按明细扣减「${row.warehouseName || '所选仓库'}」的库存并写入库存流水，且不能直接修改（只能冲销）。确定过账？`,
      '过账确认', { type: 'warning', confirmButtonText: '确定过账', cancelButtonText: '取消' }
    )
  } catch { return }
  const key = newRequestKey()
  try {
    await postSalesOutbound(row.id, key)
    notify.success('已过账，库存已扣减')
    detailOpen.value = false
    await load()
  } catch (e) {
    notify.errorWithDetail('过账失败', e)
  }
}

const doReverse = async (row: SalesOutbound) => {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('请填写冲销原因（会写入审计留痕）', '冲销确认', {
      type: 'warning', confirmButtonText: '确定冲销', cancelButtonText: '取消',
      inputPlaceholder: '例如：客户取消订单 / 开错单据', inputValidator: (v: string) => (v && v.trim() ? true : '冲销原因不能为空')
    })
    reason = res.value
  } catch { return }
  const key = newRequestKey()
  try {
    await reverseSalesOutbound(row.id, reason.trim(), key)
    notify.success('已冲销，库存已回补')
    detailOpen.value = false
    await load()
  } catch (e) {
    notify.errorWithDetail('冲销失败', e)
  }
}

// ==================== 格式化 ====================

const fmtNum = (v: unknown) => Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
const fmtMoney = (v: unknown) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const shortDate = (s?: string | null) => (s ? String(s).slice(0, 10) : '—')
const shortTime = (s?: string | null) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

loadWarehouses().then(() => load(1))
</script>

<style scoped>
.outbound-page { padding: 16px; display: flex; flex-direction: column; gap: 14px; }

.stats-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stat-card { background: var(--lq-surface, #fff); border: 1px solid var(--lq-border, #e5e7eb); border-radius: 10px;
  padding: 12px 14px; display: flex; flex-direction: column; gap: 4px; }
.stat-card--accent { border-color: var(--lq-primary, #2563eb); }
.stat-card__label { font-size: 12px; color: var(--lq-text-secondary, #6b7280); }
.stat-card__value { font-size: 20px; font-weight: 600; color: var(--lq-text, #111827); }
.stat-card__hint { font-size: 12px; color: var(--lq-text-secondary, #6b7280); }

.panel { background: var(--lq-surface, #fff); border: 1px solid var(--lq-border, #e5e7eb); border-radius: 10px; padding: 14px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.toolbar__left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.toolbar__right { display: flex; align-items: center; gap: 8px; }

.scope-note { font-size: 12px; color: var(--lq-text-secondary, #6b7280); background: var(--lq-surface-subtle, #f9fafb);
  border-radius: 8px; padding: 8px 10px; margin-bottom: 10px; line-height: 1.7; }
.scope-note__sep { margin: 0 6px; }
.scope-note__quiet { color: var(--lq-text-secondary, #6b7280); }

.cell-muted { color: var(--lq-text-secondary, #9ca3af); }
.qty-low { color: #dc2626; font-weight: 600; }
.qty-ok { color: #16a34a; font-weight: 600; }

.pager { display: flex; justify-content: flex-end; margin-top: 12px; }

.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 16px; }
.lines-head { display: flex; align-items: center; gap: 10px; margin: 6px 0 8px; }
.lines-head__title { font-weight: 600; }
.lines-head__hint { font-size: 12px; color: var(--lq-text-secondary, #6b7280); flex: 1; }

.picker-bar { display: flex; gap: 8px; margin-bottom: 10px; }
.picker-hint { float: left; font-size: 12px; color: var(--lq-text-secondary, #6b7280); line-height: 32px; }

.empty-box { padding: 24px 12px; text-align: center; color: var(--lq-text-secondary, #6b7280); }
.empty-box__hint { font-size: 12px; margin-top: 6px; line-height: 1.7; }

.detail { display: flex; flex-direction: column; gap: 14px; }
.detail__block h4 { margin: 0 0 8px; font-size: 14px; }
.detail__actions { display: flex; gap: 10px; }
</style>
