<template>
  <ListPageLayout
    class="website-inquiry-page"
    title="询盘管理"
    description="处理官网 Contact 页表单提交的询盘，支持状态流转、跟进记录和批量处理。"
    :busy="loading"
    content-label="询盘列表"
  >
    <template #summary>
      <MetricGrid :columns="statCards.length" density="compact">
        <MetricCard
          v-for="item in statCards"
          :key="item.label"
          :title="item.label"
          :value="item.value"
          :tone="item.tone"
        />
      </MetricGrid>
    </template>

    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        class="inquiry-toolbar"
        search-placeholder="搜索联系人、公司、电话、邮箱、关注产品..."
        :fields="toolbarFields"
        :field-values="toolbarFieldValues"
        batch-enabled
        :batch-entry-visible="false"
        :batch-mode="batchMode"
        :selected-count="selectedIds.size"
        :models-count="inquiries.length"
        :is-all-selected="isAllPageSelected"
        @search="loadInquiries"
        @filter-change="handleFilterChange"
        @clear="resetFilters"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="toggleSelectAll"
      >
        <template #actions>
          <el-button link @click="loadInquiries">刷新</el-button>
        </template>
        <template #batch-actions="{ selectedCount }">
          <el-button size="small" @click="batchUpdateStatus('processed')">标记已联系</el-button>
          <el-button size="small" type="danger" plain @click="batchUpdateStatus('closed')">批量关闭</el-button>
          <span class="batch-hint">已选 {{ selectedCount }} 条</span>
        </template>
      </SearchToolbar>
    </template>

    <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="resetFilters" />

    <DataState
      class="list-data-panel inquiry-table-card"
      :state="dataState"
      :skeleton="dataState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="8"
      :refreshing="loading && inquiries.length > 0"
      error-title="询盘数据加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的数据。"
      empty-text="暂无询盘记录"
      empty-hint="官网 Contact 页提交的询盘会出现在这里。"
      filtered-empty-text="没有符合条件的询盘"
      filtered-empty-hint="请调整搜索内容或筛选条件。"
      @retry="loadInquiries"
    >
      <template #emptyActions="{ state }">
        <el-button v-if="state === 'filtered-empty'" @click="resetFilters">清除筛选</el-button>
      </template>

      <LqTable :data="inquiries" row-key="id" @row-click="openDetail">
        <el-table-column v-if="batchMode" width="46" align="center">
          <template #header>
            <el-checkbox :model-value="isAllPageSelected" aria-label="全选当前页" @change="toggleSelectAll" />
          </template>
          <template #default="{ row }">
            <el-checkbox
              :model-value="selectedIds.has(row.id)"
              :aria-label="`选择${row.name}的询盘`"
              @click.stop
              @change="toggleSelect(row.id)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="联系人" width="120">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="company" label="公司" min-width="160">
          <template #default="{ row }">
            <span v-if="row.company">{{ row.company }}</span>
            <span v-else class="text-tertiary">未填写</span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="140" />
        <el-table-column prop="email" label="邮箱" width="180">
          <template #default="{ row }">
            <span v-if="row.email">{{ row.email }}</span>
            <span v-else class="text-tertiary">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="productFocus" label="关注产品" min-width="140">
          <template #default="{ row }">
            <span v-if="row.productFocus">{{ row.productFocus }}</span>
            <span v-else class="text-tertiary">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="assignee" label="负责人" width="100">
          <template #default="{ row }">
            <span v-if="row.assignee">{{ row.assignee }}</span>
            <span v-else class="text-tertiary">未分配</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="下次联系" width="160">
          <template #default="{ row }">
            <span v-if="row.nextContactAt" :class="{ 'is-overdue': isOverdue(row.nextContactAt) }">
              {{ formatDateTime(row.nextContactAt) }}
            </span>
            <span v-else class="text-tertiary">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">处理</el-button>
          </template>
        </el-table-column>
      </LqTable>
    </DataState>

    <template #pagination>
      <LqPagination :pagination="pagination" @change="loadInquiries" />
    </template>

    <!-- 询盘详情 + 处理弹窗 -->
    <LqDialog
      v-model="detailDialog.visible"
      :title="detailDialog.data ? `询盘处理：${detailDialog.data.name}` : '询盘处理'"
      size="lg"
      mode="form"
      :loading="detailDialog.saving"
      @confirm="saveDetail"
    >
      <div v-if="detailDialog.loading" class="detail-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中...</span>
      </div>
      <div v-else-if="detailDialog.data" class="inquiry-detail-content">
        <!-- 客户信息区 -->
        <div class="detail-section">
          <h4 class="detail-section-title">客户信息</h4>
          <div class="detail-info-grid">
            <div class="detail-info-item">
              <span class="detail-info-label">联系人</span>
              <strong>{{ detailDialog.data.name }}</strong>
            </div>
            <div class="detail-info-item">
              <span class="detail-info-label">公司</span>
              <span>{{ detailDialog.data.company || '未填写' }}</span>
            </div>
            <div class="detail-info-item">
              <span class="detail-info-label">电话</span>
              <span>{{ detailDialog.data.phone }}</span>
            </div>
            <div class="detail-info-item">
              <span class="detail-info-label">邮箱</span>
              <span>{{ detailDialog.data.email || '未填写' }}</span>
            </div>
            <div class="detail-info-item">
              <span class="detail-info-label">关注产品</span>
              <span>{{ detailDialog.data.productFocus || '未填写' }}</span>
            </div>
            <div class="detail-info-item">
              <span class="detail-info-label">来源页面</span>
              <span>{{ detailDialog.data.sourcePage || '未知' }}</span>
            </div>
            <div class="detail-info-item">
              <span class="detail-info-label">提交时间</span>
              <span>{{ formatDateTime(detailDialog.data.createdAt) }}</span>
            </div>
          </div>
        </div>

        <!-- 需求描述 -->
        <div class="detail-section">
          <h4 class="detail-section-title">需求描述</h4>
          <div class="detail-description">{{ detailDialog.data.description }}</div>
        </div>

        <!-- 历史跟进记录 -->
        <div v-if="detailDialog.data.followUpNote" class="detail-section">
          <h4 class="detail-section-title">历史跟进记录</h4>
          <pre class="detail-followup-history">{{ detailDialog.data.followUpNote }}</pre>
        </div>

        <!-- 处理区 -->
        <div class="detail-section">
          <h4 class="detail-section-title">处理操作</h4>
          <el-form label-width="92px">
            <el-form-item label="状态">
              <el-radio-group v-model="detailForm.status">
                <el-radio-button value="pending">待处理</el-radio-button>
                <el-radio-button value="processed">已联系</el-radio-button>
                <el-radio-button value="closed">已关闭</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="负责人">
              <el-input v-model="detailForm.assignee" placeholder="处理人姓名" clearable />
            </el-form-item>
            <el-form-item label="下次联系">
              <el-date-picker
                v-model="detailForm.nextContactAt"
                type="datetime"
                placeholder="选择下次联系时间"
                format="YYYY-MM-DD HH:mm"
                value-format="YYYY-MM-DDTHH:mm:ss"
                clearable
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item v-if="detailForm.status === 'closed'" label="关闭原因">
              <el-input
                v-model="detailForm.closedReason"
                type="textarea"
                :rows="2"
                placeholder="说明关闭原因，如：无效询盘 / 已成交 / 客户取消等"
                maxlength="500"
                show-word-limit
              />
            </el-form-item>
            <el-form-item label="跟进记录">
              <el-input
                v-model="detailForm.followUpNote"
                type="textarea"
                :rows="3"
                placeholder="输入本次跟进内容，保存后追加到历史记录（带时间戳）"
                maxlength="2000"
                show-word-limit
              />
            </el-form-item>
          </el-form>
        </div>
      </div>
    </LqDialog>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import SearchToolbar, { type ToolbarField } from '@/components/common/SearchToolbar.vue'
import ActiveFilterChips, { type ActiveFilter } from '@/components/common/ActiveFilterChips.vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import LqTable from '@/components/common/LqTable.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import LqDialog from '@/components/common/LqDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import { useListPagination } from '@/composables/list/useListPagination'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { Loading } from '@element-plus/icons-vue'
import {
  fetchInquiries,
  fetchInquiryById,
  updateInquiry,
  batchUpdateInquiryStatus,
  fetchInquiryStats
} from '@/api/website/inquiries'
import type {
  WebsiteInquirySummary,
  WebsiteInquiryDetail,
  WebsiteInquiryStats,
  InquiryStatus,
  InquiryStatusFilter
} from '@/types/website'
import { INQUIRY_STATUS_LABELS } from '@/types/website'

const loading = ref(false)
const listError = ref('')
const hasLoaded = ref(false)
const inquiries = ref<WebsiteInquirySummary[]>([])
const stats = ref<WebsiteInquiryStats>({ pending: 0, processed: 0, closed: 0, thisMonth: 0, pendingFollowUp: 0 })

const filters = reactive({
  keyword: '',
  status: '' as '' | InquiryStatusFilter
})

const statusFilterOptions = [
  { value: 'pending', label: '待处理' },
  { value: 'processed', label: '已联系' },
  { value: 'closed', label: '已关闭' }
]

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'status', label: '状态', options: statusFilterOptions }
])

const toolbarFieldValues = computed(() => ({
  status: filters.status
}))

function handleFilterChange(key: string, value: unknown) {
  if (key === 'status') filters.status = (value as '' | InquiryStatusFilter) || ''
  pagination.resetPage()
  loadInquiries()
}

const hasActiveFilters = computed(() => filters.keyword.trim() !== '' || filters.status !== '')

const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.keyword) chips.push({ key: 'keyword', label: '关键词', valueLabel: filters.keyword })
  if (filters.status) chips.push({ key: 'status', label: '状态', valueLabel: INQUIRY_STATUS_LABELS[filters.status as InquiryStatus] || filters.status })
  return chips
})

function removeFilterChip(key: string) {
  if (key === 'keyword') filters.keyword = ''
  else if (key === 'status') filters.status = ''
  pagination.resetPage()
  loadInquiries()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  pagination.resetPage()
  loadInquiries()
}

const dataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoaded.value) return 'loading'
  if (listError.value && inquiries.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (inquiries.value.length === 0) return hasActiveFilters.value ? 'filtered-empty' : 'empty'
  return 'ready'
})

const pagination = useListPagination({ size: 20 })

// ===== 批量选择 =====
const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds } = batchSelection
const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(inquiries.value))

function toggleBatchMode() {
  batchSelection.toggleBatchMode()
}
function toggleSelect(id: number) {
  batchSelection.toggleSelection(id)
}
function toggleSelectAll() {
  batchSelection.toggleSelectAll(inquiries.value)
}

const statCards = computed(() => [
  { label: '待处理', value: stats.value.pending, tone: 'warning' as const },
  { label: '已联系', value: stats.value.processed, tone: 'success' as const },
  { label: '已关闭', value: stats.value.closed, tone: 'default' as const },
  { label: '本月新增', value: stats.value.thisMonth, tone: 'primary' as const },
  { label: '待跟进', value: stats.value.pendingFollowUp, tone: 'danger' as const }
])

async function loadInquiries() {
  loading.value = true
  listError.value = ''
  try {
    const [listData, statsData] = await Promise.all([
      fetchInquiries({
        keyword: filters.keyword || undefined,
        status: filters.status || undefined,
        page: pagination.pageInfo.page + 1,
        size: pagination.pageInfo.size
      }),
      fetchInquiryStats()
    ])
    inquiries.value = listData.items || []
    pagination.updateFromResponse({
      total: listData.total,
      totalPages: listData.totalPages,
      page: listData.page - 1
    })
    stats.value = statsData
    hasLoaded.value = true
  } catch (err) {
    listError.value = err instanceof Error ? err.message : '未知错误'
    notify.errorWithDetail('加载询盘列表失败', err)
  } finally {
    loading.value = false
  }
}

// ===== 详情弹窗 =====
const detailDialog = reactive({
  visible: false,
  loading: false,
  saving: false,
  data: null as WebsiteInquiryDetail | null
})

const detailForm = reactive({
  status: 'pending' as InquiryStatus,
  assignee: '',
  nextContactAt: '' as string,
  followUpNote: '',
  closedReason: ''
})

async function openDetail(row: WebsiteInquirySummary) {
  detailDialog.visible = true
  detailDialog.loading = true
  detailDialog.data = null
  try {
    const detail = await fetchInquiryById(row.id)
    detailDialog.data = detail
    detailForm.status = detail.status
    detailForm.assignee = detail.assignee || ''
    detailForm.nextContactAt = detail.nextContactAt || ''
    detailForm.followUpNote = ''
    detailForm.closedReason = detail.closedReason || ''
  } catch (err) {
    notify.errorWithDetail('加载询盘详情失败', err)
    detailDialog.visible = false
  } finally {
    detailDialog.loading = false
  }
}

async function saveDetail() {
  if (!detailDialog.data) return
  detailDialog.saving = true
  try {
    const id = detailDialog.data.id
    const prevStatus = detailDialog.data.status
    await updateInquiry(id, {
      status: detailForm.status !== prevStatus ? detailForm.status : undefined,
      assignee: detailForm.assignee,
      followUpNote: detailForm.followUpNote || undefined,
      nextContactAt: detailForm.nextContactAt || undefined,
      clearNextContactAt: !detailForm.nextContactAt ? true : undefined,
      closedReason: detailForm.status === 'closed' ? detailForm.closedReason : undefined
    })
    notify('询盘处理已保存', 'success')
    detailDialog.visible = false
    loadInquiries()
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    detailDialog.saving = false
  }
}

// ===== 批量操作 =====
async function batchUpdateStatus(status: InquiryStatus) {
  if (selectedIds.size === 0) {
    notify.warning('请先选择询盘')
    return
  }
  const label = INQUIRY_STATUS_LABELS[status]
  const ok = await askConfirm({
    title: `批量${label}`,
    message: `确认将选中的 ${selectedIds.size} 条询盘标记为「${label}」？`,
    confirmText: '确认',
    cancelText: '取消'
  })
  if (!ok) return
  try {
    const result = await batchUpdateInquiryStatus(batchSelection.getSelectedIds(), status)
    notify(`已更新 ${result.updated} 条询盘`, 'success')
    batchSelection.clearSelection()
    loadInquiries()
  } catch (err) {
    notify.errorWithDetail('批量更新失败', err)
  }
}

// ===== 工具函数 =====
function statusLabel(status: InquiryStatus): string {
  return INQUIRY_STATUS_LABELS[status] || status
}

function statusTagType(status: InquiryStatus): 'warning' | 'success' | 'info' {
  if (status === 'pending') return 'warning'
  if (status === 'processed') return 'success'
  return 'info'
}

function formatDateTime(dt: string | null): string {
  if (!dt) return '-'
  const d = new Date(dt)
  if (isNaN(d.getTime())) return dt
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd} ${hh}:${mi}`
}

function isOverdue(dt: string): boolean {
  return new Date(dt).getTime() < Date.now()
}

loadInquiries()
</script>

<style scoped>
.inquiry-toolbar :deep(.batch-hint) {
  margin-left: 8px;
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
}

.text-tertiary {
  color: var(--color-text-tertiary);
}

.is-overdue {
  color: var(--color-danger);
  font-weight: 500;
}

.detail-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  padding: 40px 0;
  color: var(--color-text-secondary);
}

.inquiry-detail-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-section {
  border-top: 1px solid var(--color-border-light);
  padding-top: 16px;
}

.detail-section:first-child {
  border-top: none;
  padding-top: 0;
}

.detail-section-title {
  margin: 0 0 12px 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--color-text-strong);
}

.detail-info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px 20px;
}

.detail-info-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-info-label {
  font-size: var(--fs-xs);
  color: var(--color-text-tertiary);
}

.detail-description {
  padding: 12px 16px;
  background: var(--color-bg-subtle);
  border-radius: var(--radius-md);
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-followup-history {
  padding: 12px 16px;
  background: var(--color-bg-subtle);
  border-radius: var(--radius-md);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-mono, monospace);
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
}
</style>
