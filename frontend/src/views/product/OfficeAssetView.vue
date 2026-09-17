<template>
  <ListPageLayout
    class="office-assets-page"
    title="物品资产"
    description="统一管理资产档案、扫码流转和标签打印。"
    :busy="loading || scanning"
    content-label="物品资产列表"
  >
    <template #summary>
      <MetricGrid :columns="statCards.length" density="compact">
        <MetricCard v-for="item in statCards" :key="item.label" :title="item.label" :value="item.value" />
      </MetricGrid>
    </template>

    <section class="operation-page-layout__station scan-panel" aria-label="扫码工作台">
      <div class="scan-copy"><strong>扫码工作台</strong><span>扫描标签后自动定位资产并打开登记窗口</span></div>
      <el-input
        ref="scanInputRef"
        v-model="scanCode"
        size="large"
        clearable
        aria-label="扫描或输入资产编号"
        placeholder="请扫描二维码或输入资产编号"
        @keyup.enter="handleScan"
        ><template #prepend>扫码</template></el-input
      >
      <el-button type="primary" size="large" :loading="scanning" @click="handleScan">查询</el-button>
    </section>

    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        class="asset-toolbar"
        search-placeholder="搜索编号、名称、序列号、保管人"
        :fields="toolbarFields"
        :field-values="toolbarFieldValues"
        batch-enabled
        :batch-entry-visible="false"
        :batch-mode="batchMode"
        :selected-count="selectedIds.size"
        :models-count="assets.length"
        :is-all-selected="isAllPageSelected"
        @search="loadAssets"
        @filter-change="handleToolbarFilterChange"
        @clear="resetAssetFilters"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="toggleSelectAll"
        @batch-edit="openBatchEditModal"
        @batch-remove="handleBatchDelete"
      >
        <template #actions>
          <el-button v-if="canManageOfficeAsset" type="primary" @click="openCreate">新增物品</el-button>
        </template>
        <template #batch-actions="{ selectedCount }">
          <el-button v-if="canManageOfficeAsset" size="small" :disabled="selectedCount === 0" @click="openPrintDesigner(batchSelectedAssets)"
            >设计并打印</el-button
          >
        </template>
      </SearchToolbar>
    </template>

    <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="resetAssetFilters" />

    <DataState
      class="list-data-panel asset-table-card"
      :state="assetDataState"
      :skeleton="assetDataState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="8"
      :refreshing="loading && assets.length > 0"
      error-title="资产数据加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的资产数据。"
      empty-text="暂无物品资产"
      empty-hint="点击右上角“新增物品”建立第一份资产档案。"
      filtered-empty-text="没有符合条件的物品资产"
      filtered-empty-hint="请调整搜索内容或筛选条件。"
      @retry="loadAssets"
    >
      <template #emptyActions="{ state }">
        <el-button v-if="state === 'filtered-empty'" @click="resetAssetFilters">清除筛选</el-button>
        <el-button v-else-if="canManageOfficeAsset" type="primary" @click="openCreate">新增物品</el-button>
      </template>

      <LqTable
        :data="assets"
        row-key="id"
        :default-sort="{ prop: sortState.field, order: sortState.order }"
        @sort-change="handleSortChange"
        @row-click="onRowClick"
        @row-dblclick="openDetail"
      >
        <el-table-column width="46" align="center">
          <template #header>
            <el-checkbox :model-value="isAllPageSelected" aria-label="全选当前页" @change="toggleSelectAll" />
          </template>
          <template #default="{ row }">
            <el-checkbox
              :model-value="selectedIds.has(row.id)"
              :aria-label="`选择${row.name}`"
              @click.stop
              @change="toggleSelect(row.id)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="assetCode" label="资产编号" width="160" sortable="custom" />
        <el-table-column prop="name" label="物品名称" min-width="150" sortable="custom" /><el-table-column
          prop="category"
          label="分类"
          width="100"
          sortable="custom"
        />
        <el-table-column label="品牌 / 型号" min-width="150"
          ><template #default="{ row }">{{
            [row.brand, row.model].filter(Boolean).join(' / ') || '—'
          }}</template></el-table-column
        >
        <el-table-column prop="department" label="使用部门" width="120" sortable="custom" /><el-table-column
          prop="custodian"
          label="保管人"
          width="100"
        />
        <el-table-column prop="location" label="存放位置" min-width="140" />
        <el-table-column prop="status" label="状态" width="95" sortable="custom"
          ><template #default="{ row }">
            <StatusTag :type="statusType(row.status)" :label="row.status" size="sm" /> </template
        ></el-table-column>
        <el-table-column label="操作" width="230" fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click.stop="openDetail(row)">详情</el-button
            ><el-button v-if="canManageOfficeAsset" link @click.stop="openAction(row)">登记</el-button
            ><el-button v-if="canManageOfficeAsset" link @click.stop="openPrintDesigner([row])">设计打印</el-button
            ><el-button v-if="canManageOfficeAsset" link @click.stop="openEdit(row)">编辑</el-button></template
          ></el-table-column
        >
      </LqTable>
    </DataState>

    <template #pagination>
      <LqPagination :pagination="pagination" @change="loadAssets" />
    </template>

    <LqDialog
      v-model="editorVisible"
      :title="editingId ? '编辑物品资产' : '新增物品资产'"
      size="md"
      mode="form"
      :loading="saving"
      @confirm="saveAsset"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <div class="asset-form-grid">
          <el-form-item label="物品名称" prop="name"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="分类" prop="category"
            ><el-select v-model="form.category" filterable allow-create
              ><el-option v-for="item in categories" :key="item" :value="item" /></el-select
          ></el-form-item>
          <el-form-item label="资产编号"
            ><el-input v-model="form.assetCode" :disabled="!!editingId" placeholder="留空自动生成"
          /></el-form-item>
          <el-form-item label="状态"
            ><el-select v-model="form.status"
              ><el-option v-for="item in statuses" :key="item" :value="item" /></el-select
          ></el-form-item>
          <el-form-item label="品牌"><el-input v-model="form.brand" /></el-form-item
          ><el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
          <el-form-item label="序列号"><el-input v-model="form.serialNumber" /></el-form-item
          ><el-form-item label="使用部门"><el-input v-model="form.department" /></el-form-item>
          <el-form-item label="保管人"><el-input v-model="form.custodian" /></el-form-item
          ><el-form-item label="存放位置"><el-input v-model="form.location" /></el-form-item>
          <el-form-item label="购置日期"> <LqDatePicker v-model="form.purchaseDate" /> </el-form-item
          ><el-form-item label="购置金额"
            ><el-input-number v-model="form.purchasePrice" :min="0" :precision="2"
          /></el-form-item>
          <el-form-item label="供应商"><el-input v-model="form.supplier" /></el-form-item
          ><el-form-item label="质保到期">
            <LqDatePicker v-model="form.warrantyUntil" />
          </el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="form.notes" type="textarea" :rows="3" /></el-form-item>
      </el-form>
    </LqDialog>

    <LqDialog v-model="detailVisible" title="资产详情" size="md" mode="detail" :show-footer="false">
      <template v-if="currentAsset">
        <div class="detail-heading">
          <div>
            <h3>{{ currentAsset.name }}</h3>
            <code>{{ currentAsset.assetCode }}</code>
          </div>
          <StatusTag :type="statusType(currentAsset.status)" :label="currentAsset.status" />
        </div>
        <LqDescriptions :items="assetDescriptions" :column="2" border />
        <h4 class="records-title">流转记录</h4>
        <LqTimeline v-if="records.length"
          ><el-timeline-item
            v-for="record in records"
            :key="record.id"
            :timestamp="formatTime(record.createdAt)"
            placement="top"
            ><strong>{{ record.action }}</strong> · {{ record.operatorName || '未填写操作人' }}
            <p>
              {{ [record.department, record.custodian, record.location, record.notes].filter(Boolean).join(' · ') }}
            </p>
          </el-timeline-item></LqTimeline
        >
        <DataState v-else state="empty" empty-text="暂无流转记录" />
        <div class="detail-actions">
          <el-button v-if="currentAsset && canManageOfficeAsset" @click="openPrintDesigner([currentAsset])">设计并打印</el-button
          ><el-button v-if="currentAsset && canManageOfficeAsset" type="primary" @click="openAction(currentAsset)">登记操作</el-button>
        </div>
      </template>
    </LqDialog>

    <LqDialog
      v-model="actionVisible"
      title="扫码登记 / 资产流转"
      size="sm"
      mode="form"
      :loading="saving"
      @confirm="submitAction"
    >
      <el-form :model="actionForm" label-width="90px"
        ><el-form-item label="操作类型"
          ><el-select v-model="actionForm.action"
            ><el-option v-for="item in actions" :key="item" :value="item" /></el-select></el-form-item
        ><el-form-item label="操作人"><el-input v-model="actionForm.operatorName" /></el-form-item
        ><el-form-item label="使用部门"><el-input v-model="actionForm.department" /></el-form-item
        ><el-form-item label="保管人"><el-input v-model="actionForm.custodian" /></el-form-item
        ><el-form-item label="存放位置"><el-input v-model="actionForm.location" /></el-form-item
        ><el-form-item label="说明"><el-input v-model="actionForm.notes" type="textarea" /></el-form-item
      ></el-form>
    </LqDialog>

    <LqDialog
      v-model="printDesignerVisible"
      title="标签设计与打印"
      size="lg"
      mode="workspace"
      :loading="printing"
      :show-confirm="true"
      confirm-text="保存设置并打印"
      cancel-text="取消"
      @confirm="printDesignedLabels"
    >
      <div class="label-designer">
        <el-form label-position="top" class="label-settings">
          <div class="label-size-row">
            <el-form-item label="标签宽度">
              <UnitField
                v-model="labelConfig.widthMm"
                unit="mm"
                mode="number"
                :min="40"
                :max="80"
                aria-label="标签宽度毫米"
              /> </el-form-item
            ><el-form-item label="标签高度">
              <UnitField
                v-model="labelConfig.heightMm"
                unit="mm"
                mode="number"
                :min="25"
                :max="100"
                aria-label="标签高度毫米"
              /> </el-form-item
            ><el-form-item label="二维码尺寸">
              <UnitField
                v-model="labelConfig.qrSizeMm"
                unit="mm"
                mode="number"
                :min="16"
                :max="30"
                aria-label="二维码尺寸毫米"
              />
            </el-form-item>
          </div>
          <el-form-item label="公司标题"><el-input v-model="labelConfig.companyName" maxlength="30" /></el-form-item>
          <el-form-item label="标签副标题"><el-input v-model="labelConfig.subtitle" maxlength="30" /></el-form-item>
          <el-form-item label="底部提示语"><el-input v-model="labelConfig.footer" maxlength="40" /></el-form-item>
          <el-form-item label="显示内容"
            ><el-checkbox v-model="labelConfig.showDepartment">使用部门</el-checkbox
            ><el-checkbox v-model="labelConfig.showCustodian">保管人</el-checkbox
            ><el-checkbox v-model="labelConfig.showLocation">存放位置</el-checkbox></el-form-item
          >
          <p class="print-help">适配 DL-886BW：推荐 70 × 40 mm。设置会保存在当前浏览器。</p>
        </el-form>
        <div class="label-preview-panel">
          <div class="preview-toolbar">
            <strong>实时预览</strong>
            <span class="preview-toolbar-meta">
              共 {{ printItems.length }} 张
              <button type="button" class="btn-sm" @click="resetLabelConfig">恢复推荐设置</button>
            </span>
          </div>
          <div class="label-preview-stage">
            <article v-if="previewItem" class="preview-label" :style="previewLabelStyle">
              <div class="preview-label-text">
                <b
                  >{{ labelConfig.companyName
                  }}<template v-if="labelConfig.subtitle"> · {{ labelConfig.subtitle }}</template></b
                >
                <h2>{{ previewItem.asset.name }}</h2>
                <p>编号：{{ previewItem.asset.assetCode }}</p>
                <p>{{ previewMeta(previewItem.asset) || '资产信息待完善' }}</p>
                <small>{{ labelConfig.footer }}</small>
              </div>
              <img :src="previewItem.qr" alt="资产二维码" :style="previewQrStyle" /><strong class="preview-code">{{
                previewItem.asset.assetCode
              }}</strong>
            </article>
          </div>
          <el-alert
            title="打印窗口请选择 Gprinter GP-3120TU，缩放 100%，边距为无，并关闭页眉页脚。"
            type="info"
            :closable="false"
          />
        </div>
      </div>
    </LqDialog>

    <FieldBatchEditModal
      :visible="batchEditModal.visible"
      :selected-count="selectedIds.size"
      :field-groups="batchEditFieldGroups"
      :loading="batchEditModal.loading"
      entity-name="物品资产"
      @close="batchEditModal.visible = false"
      @apply="applyBatchEdit"
    />
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import QRCode from 'qrcode'
import { type FormInstance } from 'element-plus'
import LqDialog from '@/components/common/LqDialog.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import SearchToolbar from '@/components/common/SearchToolbar.vue'
import type { ToolbarField } from '@/components/common/SearchToolbar.vue'
import LqTable from '@/components/common/LqTable.vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import ActiveFilterChips, { type ActiveFilter } from '@/components/common/ActiveFilterChips.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import LqDescriptions from '@/components/common/LqDescriptions.vue'
import UnitField from '@/components/common/UnitField.vue'
import LqTimeline from '@/components/common/LqTimeline.vue'
import LqDatePicker from '@/components/common/LqDatePicker.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '@/components/common/FieldBatchEditModal.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { useListPagination } from '@/composables/list/useListPagination'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import {
  batchUpdateOfficeAssets,
  createOfficeAsset,
  deleteOfficeAsset,
  fetchOfficeAssetRecords,
  fetchOfficeAssets,
  fetchOfficeAssetStats,
  performOfficeAssetAction,
  scanOfficeAsset,
  updateOfficeAsset,
  type AssetActionPayload,
  type OfficeAsset,
  type OfficeAssetRecord,
  type OfficeAssetStats
} from '@/api/officeAsset'
const categories = ['电脑设备', '办公家具', '网络设备', '打印设备', '影音设备', '工具器具', '生活电器', '其他物品'],
  statuses = ['在库', '领用中', '维修中', '已报废'],
  actions = ['领用', '归还', '调拨', '盘点', '送修', '维修完成', '报废']
const { can } = useCurrentUser()
/** 是否可维护办公资产（新增/登记/编辑/打印/批量），由 officeAsset.manage 权限控制 */
const canManageOfficeAsset = computed(() => can('officeAsset.manage'))
const loading = ref(false),
  saving = ref(false),
  scanning = ref(false),
  assets = ref<OfficeAsset[]>([]),
  records = ref<OfficeAssetRecord[]>([])
const listError = ref('')
const hasLoadedAssets = ref(false)
const stats = reactive<OfficeAssetStats>({ total: 0, inStock: 0, inUse: 0, repairing: 0, retired: 0 }),
  filters = reactive({ keyword: '', category: '', status: '' })

// ========== 搜索栏筛选字段配置 ==========
const toolbarFields: ToolbarField[] = [
  { key: 'category', label: '分类', options: categories },
  { key: 'status', label: '状态', options: statuses }
]

const toolbarFieldValues = computed(() => ({ category: filters.category, status: filters.status }))

function handleToolbarFilterChange(key: string, value: unknown) {
  if (key === 'category' || key === 'status') filters[key] = (value as string) || ''
  pagination.resetPage()
  loadAssets()
}
const statCards = computed(() => [
  { label: '资产总数', value: stats.total },
  { label: '在库', value: stats.inStock },
  { label: '领用中', value: stats.inUse },
  { label: '维修中', value: stats.repairing },
  { label: '已报废', value: stats.retired }
])
const hasActiveAssetFilters = computed(() => Object.values(filters).some(value => value.trim()))
const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.keyword) chips.push({ key: 'keyword', label: '关键词', valueLabel: filters.keyword })
  if (filters.category) chips.push({ key: 'category', label: '资产分类', valueLabel: filters.category })
  if (filters.status) chips.push({ key: 'status', label: '资产状态', valueLabel: filters.status })
  return chips
})
function removeFilterChip(key: string) {
  if (key === 'keyword') filters.keyword = ''
  else if (key === 'category') filters.category = ''
  else if (key === 'status') filters.status = ''
  pagination.resetPage()
  loadAssets()
}
const assetDataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoadedAssets.value) return 'loading'
  if (listError.value && assets.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (assets.value.length === 0) return hasActiveAssetFilters.value ? 'filtered-empty' : 'empty'
  return 'ready'
})
const pagination = useListPagination()
const sortState = reactive<{
  field: 'assetCode' | 'name' | 'category' | 'status' | 'department' | 'updatedAt'
  direction: 'asc' | 'desc'
  order: 'ascending' | 'descending'
}>({ field: 'updatedAt', direction: 'desc', order: 'descending' })

function handleSortChange({ prop, order }: { prop: string | null; order: 'ascending' | 'descending' | null }) {
  const supported = ['assetCode', 'name', 'category', 'status', 'department', 'updatedAt'] as const
  sortState.field = supported.includes(prop as (typeof supported)[number])
    ? (prop as (typeof supported)[number])
    : 'updatedAt'
  sortState.order = order || 'descending'
  sortState.direction = sortState.order === 'ascending' ? 'asc' : 'desc'
  pagination.resetPage()
  loadAssets()
}

// ========== 批量操作 ==========
const batchSelection = useBatchSelection()
const { batchMode, selectedIds } = batchSelection
const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(assets.value))
const batchSelectedAssets = computed(() => assets.value.filter(asset => selectedIds.has(asset.id)))

/** 根据当前选中数量同步批量模式：有选中自动进入，全部取消自动退出 */
function syncBatchModeWithSelection() {
  if (selectedIds.size > 0) {
    if (!batchMode.value) batchMode.value = true
  } else if (batchMode.value) {
    batchMode.value = false
  }
}
function toggleBatchMode() {
  batchSelection.toggleBatchMode()
}
function toggleSelect(id: number) {
  batchSelection.toggleSelection(id)
  syncBatchModeWithSelection()
}
function toggleSelectAll() {
  batchSelection.toggleSelectAll(assets.value)
  syncBatchModeWithSelection()
}
function onRowClick(row: OfficeAsset) {
  if (batchMode.value) toggleSelect(row.id)
}

// ========== 批量编辑（通用字段 Schema） ==========
const batchEditModal = reactive({ visible: false, loading: false })
const batchEditFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '基本信息',
    fields: [
      { key: 'category', label: '分类', type: 'select', options: categories.map(c => ({ value: c, label: c })) },
      { key: 'status', label: '状态', type: 'select', options: statuses.map(s => ({ value: s, label: s })) },
      { key: 'department', label: '使用部门', type: 'text' },
      { key: 'custodian', label: '保管人', type: 'text' },
      { key: 'location', label: '存放位置', type: 'text' }
    ]
  },
  {
    title: '资产信息',
    fields: [
      { key: 'brand', label: '品牌', type: 'text' },
      { key: 'model', label: '型号', type: 'text' },
      { key: 'serialNumber', label: '序列号', type: 'text' },
      { key: 'supplier', label: '供应商', type: 'text' }
    ]
  },
  {
    title: '购置信息',
    fields: [
      { key: 'purchaseDate', label: '购置日期', type: 'date', full: true },
      { key: 'warrantyUntil', label: '质保到期', type: 'date', full: true },
      { key: 'purchasePrice', label: '购置金额', type: 'number' }
    ]
  },
  {
    title: '补充说明',
    fields: [{ key: 'notes', label: '备注', type: 'textarea', full: true }]
  }
])

function openBatchEditModal() {
  if (selectedIds.size === 0) {
    notify('请先勾选要批量编辑的物品资产', 'warning')
    return
  }
  batchEditModal.visible = true
}

async function applyBatchEdit(payload: Record<string, unknown>) {
  const ids = batchSelection.getSelectedIds().map(Number)
  if (ids.length === 0) return
  batchEditModal.loading = true
  try {
    await batchUpdateOfficeAssets(ids, payload)
    notify(`已更新 ${ids.length} 个物品资产`)
    batchEditModal.visible = false
    batchSelection.clearSelection()
    batchMode.value = false
    await Promise.all([loadAssets(), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量修改失败', err)
  } finally {
    batchEditModal.loading = false
  }
}

async function handleBatchDelete() {
  const ids = batchSelection.getSelectedIds().map(Number)
  const count = ids.length
  const confirmed = await askConfirm({
    title: '批量删除',
    message: `确定要删除选中的 ${count} 个物品资产吗？此操作不可撤销。`,
    confirmText: '批量删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await Promise.all(ids.map(id => deleteOfficeAsset(id)))
    batchSelection.clearSelection()
    batchMode.value = false
    notify(`已删除 ${count} 个物品资产`, 'success')
    await Promise.all([loadAssets(), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量删除失败', err)
  }
}

const scanCode = ref(''),
  scanInputRef = ref(),
  currentAsset = ref<OfficeAsset | null>(null)
const editorVisible = ref(false),
  detailVisible = ref(false),
  actionVisible = ref(false),
  printDesignerVisible = ref(false),
  printing = ref(false),
  editingId = ref<number | null>(null),
  formRef = ref<FormInstance>(),
  printItems = ref<{ asset: OfficeAsset; qr: string }[]>([])
const emptyForm = (): Partial<OfficeAsset> => ({ name: '', category: '', assetCode: '', status: '在库' }),
  form = reactive<Partial<OfficeAsset>>(emptyForm())
const actionForm = reactive<AssetActionPayload>({
  action: '盘点',
  operatorName: '',
  department: '',
  custodian: '',
  location: '',
  notes: ''
})
const rules = {
  name: [{ required: true, message: '请输入物品名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }]
}
const LABEL_CONFIG_KEY = 'office-asset-label-config-v1'
const defaultLabelConfig = () => ({
  widthMm: 70,
  heightMm: 40,
  qrSizeMm: 23,
  companyName: '浙江利强包装',
  subtitle: '物品资产',
  footer: '扫码登记领用、归还、调拨与盘点',
  showDepartment: true,
  showCustodian: false,
  showLocation: true
})
function loadLabelConfig() {
  try {
    return { ...defaultLabelConfig(), ...JSON.parse(localStorage.getItem(LABEL_CONFIG_KEY) || '{}') }
  } catch {
    return defaultLabelConfig()
  }
}
const labelConfig = reactive(loadLabelConfig())
const previewItem = computed(() => printItems.value[0])
const previewLabelStyle = computed(() => ({
  width: labelConfig.widthMm * 5 + 'px',
  height: labelConfig.heightMm * 5 + 'px',
  gridTemplateColumns: '1fr ' + labelConfig.qrSizeMm * 5 + 'px'
}))
const previewQrStyle = computed(() => ({
  width: labelConfig.qrSizeMm * 5 + 'px',
  height: labelConfig.qrSizeMm * 5 + 'px'
}))
async function loadAssets() {
  loading.value = true
  listError.value = ''
  try {
    const data = await fetchOfficeAssets({
      ...filters,
      ...pagination.getPageParams(),
      sort: sortState.field,
      direction: sortState.direction
    })
    assets.value = data.content || []
    pagination.updateFromResponse(data)
    hasLoadedAssets.value = true
  } catch (e: any) {
    listError.value = e?.message || '请检查网络连接后重试。'
    notify.error(e.message || '资产列表加载失败')
  } finally {
    loading.value = false
  }
}
function resetAssetFilters() {
  filters.keyword = ''
  filters.category = ''
  filters.status = ''
  pagination.resetPage()
  loadAssets()
}
async function loadStats() {
  try {
    Object.assign(stats, await fetchOfficeAssetStats())
  } catch {
    /* 页面列表仍可使用 */
  }
}
function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm())
  editorVisible.value = true
}
function openEdit(asset: OfficeAsset) {
  editingId.value = asset.id
  Object.assign(form, asset)
  editorVisible.value = true
}
async function saveAsset() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    const saved = editingId.value ? await updateOfficeAsset(editingId.value, form) : await createOfficeAsset(form)
    editorVisible.value = false
    notify.success(`已保存，资产编号：${saved.assetCode}`)
    await Promise.all([loadAssets(), loadStats()])
  } catch (e: any) {
    notify.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}
async function openDetail(asset: OfficeAsset) {
  currentAsset.value = asset
  detailVisible.value = true
  try {
    records.value = await fetchOfficeAssetRecords(asset.id)
  } catch {
    records.value = []
  }
}
function openAction(asset: OfficeAsset) {
  currentAsset.value = asset
  Object.assign(actionForm, {
    action: '盘点',
    operatorName: '',
    department: asset.department || '',
    custodian: asset.custodian || '',
    location: asset.location || '',
    notes: ''
  })
  actionVisible.value = true
}
async function submitAction() {
  if (!currentAsset.value) return
  saving.value = true
  try {
    const saved = await performOfficeAssetAction(currentAsset.value.id, actionForm)
    actionVisible.value = false
    currentAsset.value = saved
    notify.success(`${actionForm.action}登记成功`)
    await Promise.all([loadAssets(), loadStats()])
    if (detailVisible.value) records.value = await fetchOfficeAssetRecords(saved.id)
  } catch (e: any) {
    notify.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}
async function handleScan() {
  const code = scanCode.value.trim()
  if (!code) return
  scanning.value = true
  try {
    const asset = await scanOfficeAsset(code)
    scanCode.value = ''
    openAction(asset)
    notify.success(`已识别：${asset.name}`)
  } catch (e: any) {
    notify.error(e.message || '未找到该资产')
  } finally {
    scanning.value = false
    nextTick(() => scanInputRef.value?.focus())
  }
}
async function openPrintDesigner(items: OfficeAsset[]) {
  if (!items.length) return
  try {
    printItems.value = await Promise.all(
      items.map(async asset => ({
        asset,
        qr: await QRCode.toDataURL(`ASSET:${asset.assetCode}`, { errorCorrectionLevel: 'M', margin: 1, width: 300 })
      }))
    )
    printDesignerVisible.value = true
  } catch {
    notify.error('标签生成失败')
  }
}
function previewMeta(asset: OfficeAsset) {
  const values: string[] = []
  if (labelConfig.showDepartment && asset.department) values.push(asset.department)
  if (labelConfig.showCustodian && asset.custodian) values.push(asset.custodian)
  if (labelConfig.showLocation && asset.location) values.push(asset.location)
  return values.join(' · ')
}
function resetLabelConfig() {
  Object.assign(labelConfig, defaultLabelConfig())
}
function escapeHtml(value: unknown) {
  return String(value ?? '').replace(
    /[&<>"']/g,
    char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[char] || char
  )
}
async function printDesignedLabels() {
  if (!printItems.value.length) return
  printing.value = true
  try {
    localStorage.setItem(LABEL_CONFIG_KEY, JSON.stringify(labelConfig))
    const width = Math.max(40, Math.min(80, Number(labelConfig.widthMm) || 70)),
      height = Math.max(25, Math.min(100, Number(labelConfig.heightMm) || 40)),
      qrSize = Math.max(16, Math.min(30, Number(labelConfig.qrSizeMm) || 23))
    const labels = printItems.value
      .map(
        ({ asset, qr }) =>
          `<article class="label"><div class="text"><b>${escapeHtml(labelConfig.companyName)}${labelConfig.subtitle ? ' · ' + escapeHtml(labelConfig.subtitle) : ''}</b><h2>${escapeHtml(asset.name)}</h2><p>编号：${escapeHtml(asset.assetCode)}</p><p>${escapeHtml(previewMeta(asset) || '资产信息待完善')}</p><small>${escapeHtml(labelConfig.footer)}</small></div><img src="${qr}" alt="二维码"><strong class="code">${escapeHtml(asset.assetCode)}</strong></article>`
      )
      .join('')
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>资产标签</title><style>*{box-sizing:border-box}html,body{margin:0;padding:0;background:#fff;color:#000;font-family:"Microsoft YaHei",Arial,sans-serif}.label{width:${width}mm;height:${height}mm;padding:3.2mm 3.5mm;display:grid;grid-template-columns:minmax(0,1fr) ${qrSize}mm;grid-template-rows:1fr auto;column-gap:2.5mm;overflow:hidden;break-after:page;page-break-after:always}.label:last-child{break-after:auto;page-break-after:auto}.text{min-width:0}.text b{font-size:9pt;white-space:nowrap}.text h2{font-size:15pt;line-height:1.15;margin:2.2mm 0 1.5mm;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.text p{font-size:9pt;margin:1mm 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.text small{display:block;margin-top:1.5mm;font-size:6.5pt}.label img{width:${qrSize}mm;height:${qrSize}mm;align-self:start}.code{grid-column:2;font-size:7.5pt;text-align:center;white-space:nowrap}@page{size:${width}mm ${height}mm;margin:0}@media print{html,body{width:${width}mm}}</style></head><body>${labels}</body></html>`
    const frame = document.createElement('iframe')
    frame.style.cssText = 'position:fixed;width:0;height:0;border:0;right:0;bottom:0'
    document.body.appendChild(frame)
    const doc = frame.contentDocument
    if (!doc || !frame.contentWindow) throw new Error('无法创建打印文档')
    doc.open()
    doc.write(html)
    doc.close()
    await Promise.all(
      Array.from(doc.images).map(image =>
        image.complete
          ? Promise.resolve()
          : new Promise<void>(resolve => {
              image.onload = image.onerror = () => resolve()
            })
      )
    )
    frame.contentWindow.focus()
    frame.contentWindow.print()
    frame.contentWindow.onafterprint = () => frame.remove()
    window.setTimeout(() => frame.isConnected && frame.remove(), 60000)
    printDesignerVisible.value = false
  } catch (error: any) {
    notify.error(error.message || '标签打印失败')
  } finally {
    printing.value = false
  }
}
function statusType(status: string): string {
  return ({ 在库: 'success', 领用中: 'primary', 维修中: 'warning', 已报废: 'default' } as Record<string, string>)[
    status
  ]
}
function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
const assetDescriptions = computed(() => {
  const a = currentAsset.value
  if (!a) return []
  return [
    { label: '分类', value: a.category },
    { label: '品牌型号', value: [a.brand, a.model].filter(Boolean).join(' / ') },
    { label: '使用部门', value: a.department },
    { label: '保管人', value: a.custodian },
    { label: '存放位置', value: a.location },
    { label: '序列号', value: a.serialNumber, mono: true },
    { label: '上次盘点', value: formatTime(a.lastInventoryAt) },
    { label: '购置日期', value: a.purchaseDate }
  ]
})
onMounted(async () => {
  await Promise.all([loadAssets(), loadStats()])
  nextTick(() => scanInputRef.value?.focus())
})
</script>

<style scoped>
.office-assets-page {
  width: 100%;
}
.scan-panel,
.asset-toolbar {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.scan-panel {
  display: grid;
  grid-template-columns: minmax(220px, 0.7fr) minmax(320px, 1.4fr) auto;
  gap: var(--space-section-gap);
  align-items: center;
  padding: 18px;
  border-color: var(--color-primary);
}
.scan-copy {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}
.scan-copy span {
  font-size: 12px;
  color: var(--color-text-muted);
}
.asset-toolbar :deep(.el-select) {
  width: 140px;
}
.asset-table-card {
  min-width: 0;
}
.asset-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.asset-form-grid :deep(.el-select),
.asset-form-grid :deep(.el-date-editor),
.asset-form-grid :deep(.el-input-number) {
  width: 100%;
}
.detail-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 18px;
}
.detail-heading h3 {
  margin: 0 0 6px;
}
.records-title {
  margin: 24px 0 16px;
}
.el-timeline p {
  margin: 5px 0;
  color: var(--color-text-muted);
}
.label-designer {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: var(--space-section-gap-wide);
}
.label-settings {
  padding-right: 22px;
  border-right: 1px solid var(--color-border);
}
.label-size-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-control-gap);
}

.label-size-row :deep(.el-input-number) {
  width: 100%;
}
.print-help {
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-muted);
}
.label-preview-panel {
  min-width: 0;
}
.preview-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.preview-toolbar span {
  color: var(--color-text-muted);
}
.preview-toolbar-meta {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap);
}

.label-preview-stage {
  min-height: 270px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  margin-bottom: 16px;
  background: var(--color-surface-alt);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  overflow: auto;
}
.preview-label {
  box-sizing: border-box;
  padding: 16px 18px;
  display: grid;
  grid-template-rows: 1fr auto;
  column-gap: var(--space-card-gap);
  overflow: hidden;
  color: var(--color-text-heading);
  background: var(--color-text-on-primary);
  box-shadow: 0 3px 14px rgba(0, 0, 0, 0.18);
}
.preview-label-text {
  min-width: 0;
}
.preview-label-text b {
  font-size: 13px;
  white-space: nowrap;
}
.preview-label-text h2 {
  font-size: 22px;
  line-height: 1.15;
  margin: 11px 0 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.preview-label-text p {
  font-size: 13px;
  margin: 5px 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.preview-label-text small {
  display: block;
  margin-top: 8px;
  font-size: 10px;
}
.preview-label img {
  align-self: start;
}
.preview-code {
  grid-column: 2;
  font-size: 10px;
  text-align: center;
  white-space: nowrap;
}
@media (max-width: 1000px) {
  .asset-stats {
    grid-template-columns: repeat(3, 1fr);
  }
  .scan-panel {
    grid-template-columns: 1fr;
  }
  .label-designer {
    grid-template-columns: 1fr;
  }
  .label-settings {
    padding-right: 0;
    border-right: 0;
  }
}
.operation-page-layout__station {
  min-width: 0;
  margin-bottom: var(--space-section-gap);
}
</style>
