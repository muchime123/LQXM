<template>
  <ListPageLayout class="media-view" title="自媒体账号" description="维护账号资料、运营状态和发布数据">
    <!-- 平台统计卡片 -->
    <template #summary>
      <MediaAccountStats :stats="stats" :active-platform="filters.platform" @select-platform="onPlatformFilter" />
    </template>

    <template #notice>
      <div v-if="stats.staleAccountCount > 0" class="stale-banner">
        <span class="stale-banner-icon">⚠</span>
        <span class="stale-banner-text"
          ><strong>{{ stats.staleAccountCount }}</strong> 个账号超过 <strong>14 天</strong>未更新</span
        >
        <button type="button" class="stale-banner-btn" @click="openAnalyticsDrawer()">查看</button>
      </div>
    </template>

    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        class="media-list-toolbar"
        search-placeholder="搜索账号、ID、备注"
        search-mode="debounce"
        :fields="toolbarFields"
        :field-values="toolbarFieldValues"
        :clear-visible="hasActiveFilters || Boolean(queryTag)"
        batch-enabled
        :batch-entry-visible="false"
        :batch-mode="batchMode"
        :selected-count="selectedIds.size"
        :models-count="accounts.length"
        :is-all-selected="isAllPageSelected"
        @search="handleKeywordChange"
        @filter-change="handleToolbarFilterChange"
        @clear="resetMediaFilters"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="toggleSelectAll"
        @batch-edit="openBatchFieldEdit"
        @batch-remove="handleBatchDeleteAccounts"
      >
        <template #extra>
          <el-button :type="showOnlyStale ? 'primary' : 'default'" :plain="showOnlyStale" @click="toggleStaleFilter">
            待关注
          </el-button>
        </template>
        <template #actions>
          <div class="table-header-right">
            <label v-if="canManageMedia" class="row-btn import-btn">
              导入
              <input type="file" accept=".xlsx,.xls" @change="handleImport" />
            </label>
            <label v-if="canManageMedia" class="row-btn import-btn" :class="{ disabled: updateImportLoading }">
              {{ updateImportLoading ? '解析中...' : '导入更新' }}
              <input type="file" accept=".xlsx,.xls" :disabled="updateImportLoading" @change="handleUpdateImport" />
            </label>
            <el-button v-if="canManageMedia" :loading="publishTaskLoading" @click="openPublishTaskForFilters">发布任务</el-button>
            <LqDropdown
              v-if="canManageMedia"
              :items="[
                { command: 'analytics', label: '数据分析' },
                { command: 'platform-config', label: '平台配置' },
                { command: 'template', label: '下载导入模板' },
                { command: 'export', label: '导出账号' }
              ]"
              @command="handleToolbarCommand"
            >
              <template #trigger><el-button>更多操作</el-button></template>
            </LqDropdown>
            <el-button v-if="canManageMedia" type="primary" @click="openNewEditor">新建账号</el-button>
          </div>
        </template>
        <template v-if="canManageMedia" #batch-actions>
          <el-button type="primary" :loading="batchLoading" @click="openBatchUpdateForSelected">登记更新</el-button>
          <el-button :loading="assignOwnerLoading" @click="openAssignOwnerForSelected">分配发布人</el-button>
          <el-button :loading="publishTaskLoading" @click="openPublishTaskForSelected">创建发布任务</el-button>
        </template>
      </SearchToolbar>
    </template>

    <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="resetMediaFilters" />

    <!-- 账号表格 -->
    <DataState
      :state="accountDataState"
      :skeleton="accountDataState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="8"
      :refreshing="loading && accounts.length > 0"
      error-title="账号数据加载失败"
      :error-message="accountError"
      stale-message="刷新失败，当前仍显示上一次成功加载的账号数据"
      empty-text="暂无自媒体账号"
      empty-hint="可以通过右上角“新建账号”添加第一个账号"
      filtered-empty-text="没有符合条件的账号"
      filtered-empty-hint="请调整筛选条件或清除筛选"
      @retry="loadAccounts(0)"
    >
      <template #emptyActions="{ state }">
        <el-button v-if="state === 'filtered-empty'" @click="resetMediaFilters">清除筛选</el-button>
        <el-button v-else-if="canManageMedia" type="primary" @click="openNewEditor">新建账号</el-button>
      </template>

      <LqTable
        ref="mediaTableRef"
        :data="accounts"
        :row-class-name="mediaRowClassName"
        :default-sort="defaultTableSort"
        @sort-change="handleSortChange"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" align="center" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <StatusTag dot :type="mediaStatusTagType(statusClass(row))" :label="statusTitle(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="platform" label="平台" width="140" align="center" sortable="custom">
          <template #default="{ row }">
            <span :class="['platform-badge', platformClass(row.platform)]">{{ row.platform }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="accountName" label="账号名称" min-width="240" sortable="custom">
          <template #default="{ row }">
            <div class="account-name-cell">
              <div class="account-name-row">
                <a
                  v-if="row.link"
                  class="account-link"
                  :href="row.link"
                  target="_blank"
                  rel="noopener"
                  :title="row.link"
                  @click.stop
                >
                  <strong>{{ row.accountName }}</strong>
                  <span class="external-mark">↗</span>
                </a>
                <strong v-else>{{ row.accountName }}</strong>
                <span v-if="row.websiteOfficial" class="official-badge" title="已标记为官网官方展示账号">官网</span>
              </div>
              <span v-if="row.tags" class="item-tags"
                ><span v-for="t in tagList(row.tags)" :key="t" class="tag-badge">{{ t }}</span></span
              >
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="followersCount" label="粉丝数" width="100" align="right" sortable="custom">
          <template #default="{ row }">
            <strong v-if="row.followersCount">{{ formatNum(parseNum(row.followersCount)) }}</strong>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateCount" label="总更新" width="90" align="right" sortable="custom">
          <template #default="{ row }"
            ><strong>{{ row.updateCount || 0 }}</strong></template
          >
        </el-table-column>
        <el-table-column prop="weekUpdates" label="本周" width="80" align="right" sortable="custom">
          <template #default="{ row }">{{ row.weekUpdates || 0 }}</template>
        </el-table-column>
        <el-table-column prop="lastUpdatedAt" label="最近更新" width="150" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.lastUpdatedAt" :class="{ 'stale-text': isStale(row) }" :title="updateTitle(row)">{{
              row.lastUpdatedAt
            }}</span>
            <span v-else class="empty-asset">从未更新</span>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="运营" width="90" sortable="custom">
          <template #default="{ row }">{{ row.operator || '-' }}</template>
        </el-table-column>
        <el-table-column prop="publishOwner" label="发布" width="90" sortable="custom">
          <template #default="{ row }">{{ row.publishOwner || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="280" align="center">
          <template #default="{ row }">
            <button type="button" class="row-btn accent" @click="quickAddUpdate(row)">＋更新</button>
            <button type="button" class="row-btn" @click="openDetail(row)">详情</button>
            <button type="button" class="row-btn" @click="openEditor(row)">编辑</button>
            <button type="button" class="row-btn danger" @click="removeAccount(row)">删除</button>
          </template>
        </el-table-column>
      </LqTable>
    </DataState>

    <template #pagination>
      <LqPagination :pagination="pagination" @change="loadAccounts()" />
    </template>

    <!-- 账号详情弹窗 -->
    <MediaAccountDetailModal
      v-model:visible="detailModalVisible"
      :account="detailModalAccount"
      :updates="detailModalUpdates"
      @close="detailModalVisible = false"
      @edit="openEditorFromDetail"
      @add-update="openAddUpdateFromDetail"
      @edit-update="openEditUpdate"
      @remove-update="removeUpdate"
    />

    <!-- 编辑账号弹窗 -->
    <MediaAccountEditorModal
      :visible="editorModalVisible"
      :account="editorAccount"
      :platforms="platforms"
      :operators="operators"
      :publish-owners="publishOwners"
      :saving="editorSaving"
      @update:visible="val => (editorModalVisible = val)"
      @close="closeEditor"
      @save="onEditorSave"
    />

    <!-- 更新记录弹窗 -->
    <UpdateRecordFormDialog
      v-model="updateModal.visible"
      :is-edit="!updateIsNew"
      :account="updateModalAccount"
      :form-data="updateForm"
      :saving="updateModal.saving"
      @close="closeUpdateModal"
      @save="handleUpdateFormSave"
    />

    <!-- 批量更新弹窗 -->
    <BatchUpdateRecordDialog
      v-model="batchModalVisible"
      :batch-modal="{
        targetLabel: batchTargetLabel,
        targetCount: batchTargetCount,
        filterText: batchFilterText,
        scope: batchScope
      }"
      :form-data="batchForm"
      :accounts="accounts"
      :saving="batchLoading"
      @save="submitBatchUpdate"
    />

    <!-- 批量发布任务弹窗 -->
    <BatchPublishTaskDialog
      v-model="publishTaskModalVisible"
      :target-label="publishTaskTargetLabel"
      :target-count="publishTaskTargetCount"
      :filter-text="batchFilterText"
      :publish-owners="publishOwners"
      :form-data="publishTaskForm"
      :saving="publishTaskLoading"
      @save="submitPublishTask"
    />

    <!-- 分配发布负责人弹窗 -->
    <AssignOwnerDialog
      v-model="assignOwnerModalVisible"
      :target-label="assignOwnerTargetLabel"
      :target-count="assignOwnerTargetCount"
      :filter-text="batchFilterText"
      :scope="assignOwnerScope"
      :publish-owners="publishOwners"
      :form-data="assignOwnerForm"
      :loading="assignOwnerLoading"
      @save="submitAssignOwner"
    />
    <!-- 导入更新预览 -->
    <ImportUpdatePreviewModal
      v-model="updateImportPreviewVisible"
      :rows="updateImportRows"
      :accounts="updateImportAccounts"
      :summary="updateImportSummary"
      :confirming="updateImportConfirming"
      @close="updateImportPreviewVisible = false"
      @confirm-import="handleConfirmImport"
    />
    <!-- 平台配置弹窗 -->
    <MediaPlatformConfigModal v-model:visible="platformConfigModalVisible" @saved="onPlatformConfigUpdated" />

    <FieldBatchEditModal
      :visible="batchFieldEdit.visible"
      :selected-count="selectedIds.size"
      :field-groups="batchFieldGroups"
      :loading="batchFieldEdit.loading"
      entity-name="自媒体账号"
      @close="batchFieldEdit.visible = false"
      @apply="applyBatchFieldEdit"
    />

    <!-- 数据分析抽屉 -->
    <MediaAnalyticsPanel v-model:visible="analyticsDrawerVisible" :platforms="platforms" @open-detail="openDetail" />
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import SearchToolbar from '@/components/common/SearchToolbar.vue'
import type { ToolbarField } from '@/components/common/SearchToolbar.vue'
import LqDropdown from '@/components/common/LqDropdown.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '@/components/common/FieldBatchEditModal.vue'
import DataState from '@/components/common/DataState.vue'
import LqTable from '@/components/common/LqTable.vue'
import MediaAccountEditorModal from '@/components/media/MediaAccountEditorModal.vue'
import MediaAccountStats from '@/components/media/MediaAccountStats.vue'
import MediaAnalyticsPanel from '@/components/media/MediaAnalyticsPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import ImportUpdatePreviewModal from '@/components/media/ImportUpdatePreviewModal.vue'
import MediaAccountDetailModal from '@/components/media/MediaAccountDetailModal.vue'
import MediaPlatformConfigModal from '@/components/media/MediaPlatformConfigModal.vue'
import UpdateRecordFormDialog from '@/components/media/UpdateRecordFormDialog.vue'
import BatchUpdateRecordDialog from '@/components/media/BatchUpdateRecordDialog.vue'
import BatchPublishTaskDialog from '@/components/media/BatchPublishTaskDialog.vue'
import AssignOwnerDialog from '@/components/media/AssignOwnerDialog.vue'
import {
  addAccountUpdate,
  assignPublishOwner,
  batchDeleteMediaAccounts,
  batchUpdateMediaAccounts,
  batchTodayUpdate,
  createMediaAccount,
  createPublishTask,
  confirmMediaUpdateImport,
  deleteAccountUpdate,
  deleteMediaAccount,
  editAccountUpdate,
  exportMediaAccounts,
  fetchAccountAnalytics,
  fetchMediaAccount,
  fetchMediaAccounts,
  fetchMediaAccountStats,
  fetchOperators,
  fetchPublishOwners,
  importMediaAccounts,
  previewMediaUpdateImport,
  updateMediaAccount
} from '@/api/media'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { useListFilters } from '@/composables/list/useListFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import LqPagination from '@/components/common/LqPagination.vue'
import ActiveFilterChips from '@/components/common/ActiveFilterChips.vue'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { useModalForm } from '@/composables/ui/useModalForm'
import { notify } from '@/composables/ui/useToast'
import { buildBatchToast } from '@/composables/common/useBatchFlow'
import { createAbortController } from '@/utils/common/abort'
import { formatDate } from '@/utils/common/dateUtils'
import { downloadBlob } from '@/utils/ui/downloadBlob'
import { downloadMediaTemplate } from '@/utils/export/exportMediaTemplate'
import { tagList } from '@/utils/media/mediaAccountUtils'
import { loadPlatformConfigs, platformClass, refreshPlatformConfigs } from '@/utils/media/platformUtils'
import type { PlatformConfig } from '@/utils/media/platformUtils'

import { formatNum, isStale, parseNum, statusClass, statusTitle, updateTitle } from '@/utils/media/mediaAccountUtils'
import { buildImportToast } from '@/composables/common/useImportFlow'

/* ==================== 状态管理 ==================== */
const analyticsDrawerVisible = ref(false)

const { can } = useCurrentUser()
/** 是否可维护自媒体账号（新建/登记/分配/发布任务/批量），由 media.manage 权限控制 */
const canManageMedia = computed(() => can('media.manage'))

// 列表状态
const loading = ref(false)
const accountError = ref('')
const accountDataState = computed(() => {
  if (loading.value && accounts.value.length === 0) return 'loading'
  if (accountError.value && accounts.value.length > 0) return 'stale'
  if (accountError.value) return 'error'
  if (accounts.value.length === 0) {
    return hasActiveFilters.value || Boolean(queryTag.value) || showOnlyStale.value ? 'filtered-empty' : 'empty'
  }
  return 'ready'
})
const accounts = ref<any[]>([])
const operators = ref<string[]>([])
const publishOwners = ref<string[]>([])
const platforms = ref<PlatformConfig[]>([]) // 平台配置对象数组 [{name, icon, color, cssClass, bg}]
const platformConfigModalVisible = ref(false) // 平台配置弹窗
const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds, getSelectedIds } = batchSelection

/** 清除选择：同步清除 selectedIds 与 el-table 视觉选择 */
function clearSelection() {
  batchSelection.clearSelection()
  mediaTableRef.value?.clearSelection()
}

const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(accounts.value))

function toggleBatchMode() {
  batchSelection.toggleBatchMode()
  if (!batchMode.value) mediaTableRef.value?.clearSelection()
}

function toggleSelectAll() {
  mediaTableRef.value?.toggleAllSelection?.()
}

const batchFieldEdit = reactive({ visible: false, loading: false })
const batchFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '账号资料',
    fields: [
      {
        key: 'platform',
        label: '平台',
        type: 'select',
        options: platforms.value.map(item => ({ value: item.name, label: item.name }))
      },
      { key: 'accountName', label: '账号名称', type: 'text' },
      { key: 'accountId', label: '平台账号 ID', type: 'text' },
      { key: 'link', label: '主页链接', type: 'text', full: true },
      { key: 'operator', label: '运营人员', type: 'text' },
      { key: 'publishOwner', label: '发布负责人', type: 'text' },
      { key: 'websiteOfficial', label: '官网官方展示', type: 'switch' },
      { key: 'tags', label: '标签', type: 'text', full: true },
      { key: 'note', label: '备注', type: 'textarea', full: true }
    ]
  },
  {
    title: '账号指标',
    fields: [
      { key: 'followersCount', label: '粉丝数', type: 'number' },
      { key: 'likesCount', label: '获赞数', type: 'number' },
      { key: 'videoCount', label: '作品数', type: 'number' },
      { key: 'lastUpdatedAt', label: '最近更新日期', type: 'date' }
    ]
  }
])

function openBatchFieldEdit() {
  if (selectedIds.size === 0) return
  batchFieldEdit.visible = true
}

async function applyBatchFieldEdit(fields: Record<string, unknown>) {
  batchFieldEdit.loading = true
  try {
    const ids = getSelectedIds().map(Number)
    await batchUpdateMediaAccounts(ids, fields)
    notify(`已更新 ${ids.length} 个自媒体账号`)
    batchFieldEdit.visible = false
    clearSelection()
    batchMode.value = false
    await Promise.all([loadAccounts(0), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量修改账号失败', err)
  } finally {
    batchFieldEdit.loading = false
  }
}

async function handleBatchDeleteAccounts() {
  const ids = getSelectedIds().map(Number)
  if (ids.length === 0) return
  const ok = await askConfirm({
    title: '批量删除自媒体账号',
    message: `确认删除已选的 ${ids.length} 个账号？相关更新记录也可能受影响。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await batchDeleteMediaAccounts(ids)
    notify(`已删除 ${ids.length} 个自媒体账号`)
    clearSelection()
    batchMode.value = false
    await Promise.all([loadAccounts(0), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量删除账号失败', err)
  }
}
const stats = reactive({
  totalAccounts: 0,
  updatesToday: 0,
  updatesThisWeek: 0,
  updatesThisMonth: 0,
  staleAccountCount: 0,
  platforms: []
})

// 分页
const pagination = useListPagination({ size: 100 })
const { pageInfo, updateFromResponse, resetPage } = pagination
// 筛选
const { filters, hasActiveFilters, resetFilters } = useListFilters({
  keyword: '',
  platform: '',
  operator: '',
  publishOwner: ''
})

const queryTag = ref('') // 当前筛选的标签
const selectedOperators = ref<string[]>([])
const showOnlyStale = ref(false)
const sortState = reactive({ field: 'lastUpdatedAt', direction: 'desc' })
const batchLoading = ref(false)

// 搜索栏筛选字段配置
const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'platform', label: '平台', options: platforms.value.map(p => p.name) },
  { key: 'operators', label: '运营', options: operators.value, multiple: true },
  { key: 'publishOwner', label: '发布人', options: publishOwners.value }
])

const toolbarFieldValues = computed(() => ({
  platform: filters.platform,
  operators: selectedOperators.value,
  publishOwner: filters.publishOwner
}))

// 请求取消控制器
let accountsAbortController: AbortController | null = null
let analyticsAbortController: AbortController | null = null
let detailAbortController: AbortController | null = null

// 最近更新时间线/导入更新预览状态
const updateImportLoading = ref(false)
const updateImportConfirming = ref(false)
const updateImportPreviewVisible = ref(false)
const updateImportRows = ref<any[]>([])
const updateImportAccounts = ref<any[]>([])
const updateImportSummary = reactive({
  totalRows: 0,
  matched: 0,
  unmatched: 0,
  duplicates: 0
})

// 详情弹窗状态（独立 ref，避免深层响应式）
const detailModalVisible = ref(false)
const detailModalAccount = ref<any>({})
const detailModalUpdates = ref<any[]>([])
const detailModalAnalyticsData = ref<any>(null)

// 编辑弹窗状态
const editorModalVisible = ref(false)
const editorAccount = ref<any>({})
const editorSaving = ref(false)

// 更新记录弹窗状态
const updateModalRecord = ref<any>(null)
const updateModalAccount = ref<any>({})
const {
  modal: updateModal,
  isNew: updateIsNew,
  openAdd: _openUpdateAdd,
  openEdit: _openUpdateEdit,
  closeModal: closeUpdateModal,
  markDirty: markUpdateDirty,
  handleSave: handleUpdateSave
} = useModalForm({
  defaultForm: () => ({}),
  dirtyKey: '更新记录编辑'
})

// 账号活跃状态 -> StatusTag 类型映射
function mediaStatusTagType(level: string) {
  if (level === 'active') return 'tag-ok'
  if (level === 'normal') return 'tag-info'
  if (level === 'warning') return 'tag-warn'
  if (level === 'stale') return 'tag-danger'
  return 'tag-neutral' // never / 未知
}

// 表格引用
const mediaTableRef = ref<any>()
// el-table 默认排序（与 sortState 初始值保持一致）
const defaultTableSort = { prop: sortState.field, order: 'descending' }

/** el-table 排序变更：将 {prop, order} 映射到 sortState */
function handleSortChange({ prop, order }: { prop: string | null; order: 'ascending' | 'descending' | null }) {
  sortState.field = prop || 'lastUpdatedAt'
  sortState.direction = !order ? 'desc' : order === 'ascending' ? 'asc' : 'desc'
  loadAccounts(0)
}

/** el-table 选择变更：同步到 selectedIds */
function handleSelectionChange(selection: any[]) {
  selectedIds.clear()
  selection.forEach(row => row.id != null && selectedIds.add(row.id))
  batchMode.value = selection.length > 0
}

/** el-table 行样式：超期未更新账号高亮 */
function mediaRowClassName({ row }: { row: any }) {
  return isStale(row) ? 'stale-row' : ''
}

// 更新记录表单
const updateForm = reactive({ updateType: '更新', updateDate: formatDate(), title: '', contentUrl: '', note: '' })
const batchModalVisible = ref(false)
const batchScope = ref('filters')
const batchForm = reactive({
  updateDate: formatDate(),
  title: '',
  contentUrl: '',
  note: '',
  skipExisting: true
})
const batchTargetCount = computed(() => (batchScope.value === 'selected' ? selectedIds.size : pageInfo.total))
const batchTargetLabel = computed(() =>
  batchScope.value === 'selected' ? `将为已选 ${selectedIds.size} 个账号记录更新` : `将为当前筛选结果记录更新`
)
const batchFilterText = computed(() => {
  if (batchScope.value === 'selected') return '只处理当前手动勾选的账号'
  const parts = []
  if (filters.platform) parts.push(`平台：${filters.platform}`)
  if (selectedOperators.value.length) parts.push(`运营：${selectedOperators.value.join('、')}`)
  if (filters.publishOwner) parts.push(`发布人：${filters.publishOwner}`)
  if (filters.keyword) parts.push(`搜索：${filters.keyword}`)
  return parts.length ? parts.join(' / ') : '全部账号'
})
function resetBatchForm() {
  Object.assign(batchForm, {
    updateDate: formatDate(),
    title: '',
    contentUrl: '',
    note: '',
    skipExisting: true
  })
}

// 发布任务
const publishTaskLoading = ref(false)
const publishTaskModalVisible = ref(false)
const publishTaskScope = ref('filters')
const publishTaskForm = reactive({
  taskName: '',
  publishDate: formatDate(),
  publishOwner: '',
  publishSoftware: '',
  contentTitle: '',
  note: '',
  skipExisting: true
})
const publishTaskTargetCount = computed(() =>
  publishTaskScope.value === 'selected' ? selectedIds.size : pageInfo.total
)
const publishTaskTargetLabel = computed(() =>
  publishTaskScope.value === 'selected'
    ? `将为已选 ${selectedIds.size} 个账号生成发布记录`
    : '将为当前筛选结果生成发布记录'
)
function resetPublishTaskForm() {
  Object.assign(publishTaskForm, {
    taskName: '',
    publishDate: formatDate(),
    publishOwner: '',
    publishSoftware: '',
    contentTitle: '',
    note: '',
    skipExisting: true
  })
}

// 分配发布负责人
const assignOwnerLoading = ref(false)
const assignOwnerModalVisible = ref(false)
const assignOwnerScope = ref('filters')
const assignOwnerForm = reactive({
  publishOwner: ''
})
const assignOwnerTargetCount = computed(() =>
  assignOwnerScope.value === 'selected' ? selectedIds.size : pageInfo.total
)
const assignOwnerTargetLabel = computed(() =>
  assignOwnerScope.value === 'selected' ? `将为已选 ${selectedIds.size} 个账号设置负责人` : '将为当前筛选结果设置负责人'
)
function resetAssignOwnerForm() {
  assignOwnerForm.publishOwner = ''
}
watch(
  () => updateModal.visible,
  val => {
    if (val) {
      if (!updateIsNew.value && updateModalRecord.value) {
        Object.assign(updateForm, {
          updateType: updateModalRecord.value.updateType || '更新',
          updateDate: updateModalRecord.value.updateDate,
          title: updateModalRecord.value.title || '',
          contentUrl: updateModalRecord.value.contentUrl || '',
          note: updateModalRecord.value.note || ''
        })
      } else {
        Object.assign(updateForm, {
          updateType: '更新',
          updateDate: formatDate(),
          title: '',
          contentUrl: '',
          note: ''
        })
      }
    }
  }
)
function handleUpdateFormSave() {
  if (!updateForm.updateDate) {
    notify('请选择更新日期', 'warning')
    return
  }
  saveUpdate({ ...updateForm, updateType: updateForm.updateType || '更新' })
}

/* ==================== 工具函数 ==================== */
const handleKeywordChange = (keyword: string) => {
  filters.keyword = keyword
  resetPage()
  loadAccounts(0)
}

function handleFilterChange() {
  resetPage()
  loadAccounts(0)
}

function handleToolbarCommand(command: string | number) {
  if (command === 'analytics') openAnalyticsDrawer()
  else if (command === 'platform-config') platformConfigModalVisible.value = true
  else if (command === 'template') downloadTemplate()
  else if (command === 'export') handleExport()
}

function onPlatformFilter(platform: string) {
  filters.platform = platform
  resetPage()
  loadAccounts(0)
}

function syncOperatorFilter() {
  filters.operator = selectedOperators.value.join(',')
  resetPage()
  loadAccounts(0)
}

function handleToolbarFilterChange(key: string, value: unknown) {
  if (key === 'operators') {
    selectedOperators.value = Array.isArray(value) ? (value as string[]) : []
    syncOperatorFilter()
  } else if (key === 'platform' || key === 'publishOwner') {
    filters[key as 'platform' | 'publishOwner'] = (value || '') as never
    handleFilterChange()
  }
}

const activeFilterChips = computed(() => {
  const chips = []
  if (filters.keyword) chips.push({ key: 'keyword', label: '关键词', valueLabel: filters.keyword })
  if (filters.platform) chips.push({ key: 'platform', label: '平台', valueLabel: filters.platform })
  if (selectedOperators.value && selectedOperators.value.length) {
    chips.push({ key: 'operator', label: '运营人', valueLabel: selectedOperators.value.join('、') })
  }
  if (filters.publishOwner) chips.push({ key: 'publishOwner', label: '发布负责人', valueLabel: filters.publishOwner })
  if (queryTag.value) chips.push({ key: 'queryTag', label: '标签', valueLabel: queryTag.value })
  if (showOnlyStale.value) chips.push({ key: 'showOnlyStale', label: '待关注', valueLabel: '只看超 14 天未更新' })
  return chips
})
function removeFilterChip(key: string) {
  if (key === 'keyword') filters.keyword = ''
  else if (key === 'platform') filters.platform = ''
  else if (key === 'operator') {
    selectedOperators.value = []
    filters.operator = ''
  } else if (key === 'publishOwner') filters.publishOwner = ''
  else if (key === 'queryTag') queryTag.value = ''
  else if (key === 'showOnlyStale') showOnlyStale.value = false
  resetPage()
  loadAccounts(0)
}

function resetMediaFilters() {
  resetFilters()
  selectedOperators.value = []
  queryTag.value = ''
  showOnlyStale.value = false
  resetPage()
  loadAccounts(0)
}

function toggleStaleFilter() {
  showOnlyStale.value = !showOnlyStale.value
  resetPage()
  loadAccounts(0)
}

/* ==================== 数据加载 ==================== */
async function loadAccounts(pageNum = 0) {
  if (accountsAbortController) {
    accountsAbortController.abort()
  }
  accountsAbortController = createAbortController()
  const thisController = accountsAbortController
  loading.value = true
  accountError.value = ''
  try {
    const params: Record<string, any> = {
      platform: filters.platform || undefined,
      operator: filters.operator || undefined,
      publishOwner: filters.publishOwner || undefined,
      keyword: filters.keyword || undefined,
      page: pageNum,
      size: pageInfo.size
    }
    if (sortState.field) {
      params.sort = sortState.field
      params.direction = sortState.direction
    }
    const res = await fetchMediaAccounts(params, { signal: thisController.signal })
    if (thisController.signal.aborted) return

    // 前端标签/待关注筛选
    if (queryTag.value || showOnlyStale.value) {
      const tagLower = queryTag.value.toLowerCase()
      accounts.value = (res.content || []).filter((a: any) => {
        const tagMatched =
          !queryTag.value ||
          (a.tags &&
            tagList(a.tags)
              .map(t => t.toLowerCase())
              .includes(tagLower))
        const staleMatched = !showOnlyStale.value || isStale(a)
        return tagMatched && staleMatched
      })
      // 前端二次筛选时显示当前查询结果中的匹配数
      pageInfo.page = 0
      pageInfo.pages = 1
      pageInfo.total = accounts.value.length
    } else {
      accounts.value = res.content
      updateFromResponse(res)
    }
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') return
    accountError.value = err instanceof Error ? err.message : '请检查网络连接后重试'
    notify.errorWithDetail('加载账号失败', err)
  } finally {
    if (accountsAbortController === thisController) {
      loading.value = false
    }
  }
}

async function loadStats() {
  try {
    const res = await fetchMediaAccountStats()
    stats.totalAccounts = res.totalAccounts
    stats.updatesToday = res.updatesToday
    stats.updatesThisWeek = res.updatesThisWeek
    stats.updatesThisMonth = res.updatesThisMonth
    stats.staleAccountCount = res.staleAccountCount
    stats.platforms = res.platforms
  } catch (err) {
    console.warn('加载统计失败', err)
  }
}

async function loadOperatorsList() {
  try {
    operators.value = await fetchOperators()
  } catch (err) {
    console.warn('加载运营人员列表失败', err)
  }
}

async function loadPublishOwnersList() {
  try {
    publishOwners.value = await fetchPublishOwners()
  } catch (err) {
    console.warn('加载发布负责人列表失败', err)
  }
}

async function loadPlatformsList() {
  try {
    const list = await loadPlatformConfigs()
    if (list && list.length) {
      platforms.value = list
    }
  } catch (err) {
    console.warn('加载平台配置失败', err)
  }
}

/** 平台配置更新后刷新 */
async function onPlatformConfigUpdated() {
  const list = await refreshPlatformConfigs()
  if (list && list.length) {
    platforms.value = list
  }
  await loadStats()
  await loadAccounts(0)
}

async function loadAll() {
  await Promise.all([loadAccounts(0), loadStats(), loadOperatorsList(), loadPublishOwnersList(), loadPlatformsList()])
}

/* ==================== 账号 CRUD ==================== */
function openNewEditor() {
  const defaultPlatform = platforms.value.length > 0 ? platforms.value[0].name : '抖音'
  editorAccount.value = {
    platform: defaultPlatform,
    accountName: '',
    accountId: '',
    link: '',
    note: '',
    operator: '',
    publishOwner: '',
    tags: '',
    followersCount: '',
    likesCount: '',
    videoCount: '',
    websiteOfficial: false
  }
  editorModalVisible.value = true
}

function openEditor(acct: any) {
  const source = acct?.account || acct || {}
  editorAccount.value = {
    id: source.id,
    platform: source.platform || '',
    accountName: source.accountName || '',
    accountId: source.accountId || '',
    link: source.link || '',
    note: source.note || '',
    operator: source.operator || '',
    publishOwner: source.publishOwner || '',
    tags: source.tags || '',
    followersCount: source.followersCount ?? '',
    likesCount: source.likesCount ?? '',
    videoCount: source.videoCount ?? '',
    websiteOfficial: !!source.websiteOfficial
  }
  editorModalVisible.value = true
}

async function openEditorFromDetail() {
  const account = { ...(detailModalAccount.value || {}) }
  detailModalVisible.value = false
  await nextTick()
  openEditor(account)
}

function closeEditor() {
  editorModalVisible.value = false
}

async function onEditorSave(formData: any) {
  editorSaving.value = true
  try {
    const isNew = !editorAccount.value.id
    if (isNew) {
      await createMediaAccount(formData)
    } else {
      const targetId = editorAccount.value.id
      if (!targetId) throw new Error('缺少账号 ID')
      await updateMediaAccount(targetId, formData)
    }
    editorModalVisible.value = false
    await loadAll()
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    editorSaving.value = false
  }
}

async function removeAccount(acct: any) {
  const ok = await askConfirm({
    title: '删除媒体账号',
    message: `确定删除账号 "${acct.accountName}"？`,
    confirmText: '删除',
    danger: true
  })
  if (!ok) return
  try {
    await deleteMediaAccount(acct.id)
    notify('账号已删除')
    await loadAll()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

/* ==================== 账号详情 ==================== */
async function openDetail(acct: any) {
  if (detailAbortController) detailAbortController.abort()
  detailAbortController = createAbortController()
  const thisController = detailAbortController

  detailModalAccount.value = acct
  detailModalUpdates.value = []
  detailModalAnalyticsData.value = null
  detailModalVisible.value = true
  try {
    const [detail, analytics] = await Promise.all([
      fetchMediaAccount(acct.id, { signal: thisController.signal }),
      fetchAccountAnalytics(acct.id, 90, { signal: thisController.signal })
    ])
    if (thisController.signal.aborted) return
    detailModalUpdates.value = detail.recentUpdates || []
    detailModalAccount.value = { ...detailModalAccount.value, ...detail.account }
    detailModalAnalyticsData.value = analytics
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') return
    console.warn('加载详情失败', err)
    notify.errorWithDetail('加载账号详情失败', err)
  }
}

/* ==================== 更新记录操作 ==================== */
function quickAddUpdate(acct: any) {
  updateModalAccount.value = acct
  updateModalRecord.value = null
  _openUpdateAdd()
  markUpdateDirty()
}

async function refreshAccountRow(accountId: number) {
  try {
    const detail = await fetchMediaAccount(accountId)
    const updated = detail.account
    const idx = accounts.value.findIndex((a: any) => a.id === accountId)
    if (idx >= 0) {
      accounts.value[idx] = { ...accounts.value[idx], ...updated }
    }
    // 同步更新详情弹窗数据
    if (detailModalAccount.value?.id === accountId) {
      detailModalAccount.value = { ...detailModalAccount.value, ...updated }
      detailModalUpdates.value = detail.recentUpdates || detailModalUpdates.value
    }
  } catch (e) {
    console.warn('刷新账号信息失败', e)
  }
}

function openAddUpdateFromDetail() {
  updateModalAccount.value = detailModalAccount.value
  updateModalRecord.value = null
  _openUpdateAdd()
  markUpdateDirty()
}

function openEditUpdate(record: any) {
  updateModalAccount.value = detailModalAccount.value
  updateModalRecord.value = record
  _openUpdateEdit(record)
  markUpdateDirty()
}

async function saveUpdate(formData: any) {
  await handleUpdateSave(
    async () => {
      const accountId = updateModalAccount.value?.id
      if (updateIsNew.value) {
        await addAccountUpdate(accountId, formData)
      } else {
        await editAccountUpdate(updateModalRecord.value.id, formData)
      }
      if (accountId) {
        await refreshAccountRow(accountId)
      }
      await loadStats()
    },
    '',
    '保存失败'
  )
}

async function removeUpdate(record: any) {
  const ok = await askConfirm({
    title: '删除更新记录',
    message: `确定要删除更新记录「${record.title || '未填写标题'}」吗？此操作不可撤销。`,
    confirmText: '删除',
    danger: true
  })
  if (ok !== true) return
  try {
    await deleteAccountUpdate(record.id)
    // 刷新账号详情和列表
    if (detailModalAccount.value?.id) {
      await refreshAccountRow(detailModalAccount.value.id)
    }
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

/* ==================== Excel 导入/导出 & 批量操作 ==================== */
function openBatchUpdateForSelected() {
  if (selectedIds.size === 0) return
  batchScope.value = 'selected'
  resetBatchForm()
  batchModalVisible.value = true
}

async function submitBatchUpdate() {
  if (!batchForm.updateDate) {
    notify('请选择更新日期', 'warning')
    return
  }
  if (batchTargetCount.value === 0) {
    notify('没有可批量更新的账号', 'warning')
    return
  }
  batchLoading.value = true
  try {
    const payload =
      batchScope.value === 'selected'
        ? { accountIds: getSelectedIds() }
        : {
            platform: filters.platform || undefined,
            operator: filters.operator || undefined,
            publishOwner: filters.publishOwner || undefined,
            keyword: filters.keyword || undefined
          }
    const result = await batchTodayUpdate({
      ...payload,
      updateDate: batchForm.updateDate,
      updateType: '更新',
      title: batchForm.title || undefined,
      contentUrl: batchForm.contentUrl || undefined,
      note: batchForm.note || undefined,
      skipExisting: batchForm.skipExisting
    })
    const summary = { affected: result.updated }
    const toast = buildBatchToast(summary, '批量记录')
    notify(toast.message || `批量记录完成：新增${result.updated} 条更新记录`, toast.level)
    batchModalVisible.value = false
    if (batchScope.value === 'selected') clearSelection()
    await loadAll()
  } catch (err) {
    notify.errorWithDetail('批量记录失败', err)
  } finally {
    batchLoading.value = false
  }
}

function openPublishTaskForFilters() {
  publishTaskScope.value = 'filters'
  resetPublishTaskForm()
  publishTaskModalVisible.value = true
}

function openPublishTaskForSelected() {
  if (selectedIds.size === 0) return
  publishTaskScope.value = 'selected'
  resetPublishTaskForm()
  publishTaskModalVisible.value = true
}

async function submitPublishTask() {
  if (!publishTaskForm.taskName?.trim()) {
    notify('请填写发布任务名称', 'warning')
    return
  }
  if (!publishTaskForm.publishDate) {
    notify('请选择发布日期', 'warning')
    return
  }
  if (!publishTaskForm.publishOwner) {
    notify('请选择账号归属发布负责人', 'warning')
    return
  }
  if (publishTaskTargetCount.value === 0) {
    notify('没有可记录发布任务的账号', 'warning')
    return
  }
  publishTaskLoading.value = true
  try {
    const payload = {
      platform: filters.platform || undefined,
      operator: filters.operator || undefined,
      publishOwner: filters.publishOwner || undefined,
      keyword: filters.keyword || undefined
    }
    const result = await createPublishTask({
      ...payload,
      taskName: publishTaskForm.taskName,
      publishDate: publishTaskForm.publishDate,
      publishOwner: publishTaskForm.publishOwner || undefined,
      publishSoftware: publishTaskForm.publishSoftware || undefined,
      contentTitle: publishTaskForm.contentTitle || publishTaskForm.taskName,
      note: publishTaskForm.note || undefined,
      skipExisting: publishTaskForm.skipExisting
    })
    const summary = {
      affected: result.createdUpdateCount || 0,
      custom: { 覆盖账号: result.accountCount || 0 }
    }
    const toast = buildBatchToast(summary, '发布任务')
    notify(toast.message || `发布任务已保存：覆盖 ${result.accountCount || 0} 个账号`, toast.level)
    publishTaskModalVisible.value = false
    await loadAll()
  } catch (err) {
    notify.errorWithDetail('保存发布任务失败', err)
  } finally {
    publishTaskLoading.value = false
  }
}

function openAssignOwnerForSelected() {
  if (selectedIds.size === 0) return
  assignOwnerScope.value = 'selected'
  resetAssignOwnerForm()
  assignOwnerModalVisible.value = true
}

async function submitAssignOwner() {
  if (!assignOwnerForm.publishOwner?.trim()) {
    notify('请填写发布负责人', 'warning')
    return
  }
  if (assignOwnerTargetCount.value === 0) {
    notify('没有可分配的账号', 'warning')
    return
  }
  assignOwnerLoading.value = true
  try {
    const result = await assignPublishOwner({
      accountIds: getSelectedIds(),
      publishOwner: assignOwnerForm.publishOwner.trim()
    })
    notify(`已分配${result.updated || 0} 个账号给 ${assignOwnerForm.publishOwner.trim()}`)
    assignOwnerModalVisible.value = false
    clearSelection()
    await loadAll()
  } catch (err) {
    notify.errorWithDetail('分配发布负责人失败', err)
  } finally {
    assignOwnerLoading.value = false
  }
}
async function handleImport(e: any) {
  const file = e.target.files[0]
  if (!file) return
  try {
    const result = await importMediaAccounts(file)
    const count = Array.isArray(result) ? result.length : (result?.count ?? 0)
    const summary = { created: count }
    const toast = buildImportToast(summary)
    notify(toast.message || `成功导入 ${count} 个账号`, toast.level)
    await loadAll()
  } catch (err) {
    notify.errorWithDetail('导入失败', err)
  }
  e.target.value = ''
}

async function handleUpdateImport(e: any) {
  const file = e.target.files[0]
  if (!file) return
  updateImportLoading.value = true
  try {
    const result = await previewMediaUpdateImport(file)
    updateImportRows.value = (result.rows || []).map((row: any) => ({
      ...row,
      enabled: !!row.matchedAccountId && !row.duplicate
    }))
    updateImportAccounts.value = result.accounts || []
    Object.assign(updateImportSummary, {
      totalRows: result.totalRows || 0,
      matched: result.matched || 0,
      unmatched: result.unmatched || 0,
      duplicates: result.duplicates || 0
    })
    updateImportPreviewVisible.value = true
  } catch (err) {
    notify.errorWithDetail('导入更新预览失败', err)
  } finally {
    updateImportLoading.value = false
    e.target.value = ''
  }
}

async function handleConfirmImport(rows: any[]) {
  updateImportConfirming.value = true
  try {
    const result = await confirmMediaUpdateImport({ rows })
    /** R1 配方：将业务 result 映射为 ImportResultSummary，统一推导语义与 level */
    const summary = {
      created: result.imported ?? 0,
      skipped: result.skipped ?? 0,
      custom: {
        未匹配: result.unmatched ?? 0,
        失败: result.failed ?? 0
      },
      errors: result.failed > 0 ? [`${result.failed} 条记录导入失败`] : undefined
    }
    const toast = buildImportToast(summary)
    notify(toast.message || '导入更新完成', toast.level)
    updateImportPreviewVisible.value = false
    await loadAll()
    // 分析面板子组件会自行重新加载数据
  } catch (err) {
    notify.errorWithDetail('确认导入更新失败', err)
  } finally {
    updateImportConfirming.value = false
  }
}

function downloadTemplate() {
  downloadMediaTemplate()
}

async function handleExport() {
  try {
    const params = {
      platform: filters.platform || undefined,
      operator: filters.operator || undefined,
      publishOwner: filters.publishOwner || undefined,
      keyword: filters.keyword || undefined
    }

    const blob = await exportMediaAccounts(params)
    downloadBlob(blob, `自媒体账号_${formatDate()}.xlsx`)
  } catch (err) {
    notify.errorWithDetail('导出失败', err)
  }
}

/* ==================== 分析报表 ==================== */
function openAnalyticsDrawer() {
  analyticsDrawerVisible.value = true
}

/* ==================== 生命周期 ==================== */
await loadAll()

onBeforeUnmount(() => {
  if (accountsAbortController) accountsAbortController.abort()
  if (analyticsAbortController) analyticsAbortController.abort()
  if (detailAbortController) detailAbortController.abort()
  accountsAbortController = null
  analyticsAbortController = null
  detailAbortController = null
})
</script>

<style scoped>
@import '@/styles/media-account-view.css';

.account-name-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.account-name-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.official-badge {
  align-items: center;
  background: linear-gradient(135deg, #1677ff 0%, #00c2a2 100%);
  border-radius: 10px;
  color: #fff;
  display: inline-flex;
  font-size: 10px;
  font-weight: 700;
  height: 18px;
  letter-spacing: 0.2px;
  line-height: 18px;
  padding: 0 7px;
}
</style>
