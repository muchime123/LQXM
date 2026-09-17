<template>
  <ListPageLayout
    class="operation-log-page"
    title="操作日志"
    description="产品、客户、公司资产等关键增删改操作审计记录。点击行首箭头查看变更摘要。"
    :busy="loading"
    content-label="操作日志列表"
  >
    <template #eyebrow>系统管理 · AUDIT LOG</template>

    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        search-placeholder="对象名称 / 变更摘要"
        search-mode="submit"
        query-button
        :fields="toolbarFields"
        :field-values="filters"
        :batch-enabled="false"
        @search="applyFilters"
        @filter-change="handleToolbarFilterChange"
        @clear="handleResetFilters"
      >
        <template #actions>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
            @change="applyDateRange"
          />
        </template>
      </SearchToolbar>
    </template>

    <DataState
      :state="dataState"
      :skeleton="dataState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="10"
      :refreshing="loading && rows.length > 0"
      error-title="操作日志加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的日志。"
      empty-text="暂无操作日志"
      empty-hint="系统会在关键增删改操作发生时自动记录。"
      filtered-empty-text="没有符合条件的日志"
      filtered-empty-hint="请调整搜索内容或筛选条件。"
      @retry="loadList"
    >
      <LqTable :data="rows" row-key="id" density="compact">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="changes-pre">{{ prettyChanges(row.changes) }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="实体类型" width="180">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.entityType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="entityId" label="实体 ID" width="90" align="right" />
        <el-table-column label="操作动作" width="220">
          <template #default="{ row }">
            <StatusTag :type="actionTagType(row.action)" :label="row.action" size="sm" />
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="对象名称" min-width="220" show-overflow-tooltip />
      </LqTable>
    </DataState>

    <template #pagination>
      <LqPagination :pagination="pagination" @change="loadList" />
    </template>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import LqTable from '@/components/common/LqTable.vue'
import SearchToolbar, { type ToolbarField } from '@/components/common/SearchToolbar.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { useListFilters } from '@/composables/list/useListFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import { notify } from '@/composables/ui/useToast'
import { fetchAuditLogs, fetchAuditLogFacets, type AuditLogItem } from '@/api/auditLogs'

// ========== 筛选 / 分页 ==========
const { filters, resetFilters } = useListFilters({
  keyword: '',
  entityType: '',
  action: ''
})

const entityTypeOptions = ref<{ value: string; label: string }[]>([])
const actionOptions = ref<{ value: string; label: string }[]>([])

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'entityType', label: '实体类型', options: entityTypeOptions.value, filterable: true, clearable: true, width: 190 },
  { key: 'action', label: '操作动作', options: actionOptions.value, filterable: true, clearable: true, width: 210 }
])

const dateRange = ref<[string, string] | null>(null)

function applyDateRange() {
  applyFilters()
}

function handleToolbarFilterChange(key: string, value: unknown) {
  ;(filters as Record<string, unknown>)[key] = value ?? ''
  applyFilters()
}
function applyFilters() {
  resetPage()
  loadList()
}
function handleResetFilters() {
  resetFilters()
  dateRange.value = null
  applyFilters()
}

const pagination = useListPagination({ size: 20 })
const { resetPage, getPageParams, updateFromResponse } = pagination

// ========== 列表加载 ==========
const loading = ref(false)
const rows = ref<AuditLogItem[]>([])
const total = ref(0)
const listError = ref('')
const hasLoadedList = ref(false)

const hasActiveFilters = computed(
  () =>
    Boolean(String(filters.keyword || '').trim()) ||
    Boolean(filters.entityType) ||
    Boolean(filters.action) ||
    Boolean(dateRange.value)
)
const dataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoadedList.value) return 'loading'
  if (listError.value && rows.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (rows.value.length === 0) return hasActiveFilters.value ? 'filtered-empty' : 'empty'
  return 'ready'
})

async function loadFacets() {
  try {
    const f = await fetchAuditLogFacets()
    entityTypeOptions.value = f.entityTypes.map((v) => ({ value: v, label: v }))
    actionOptions.value = f.actions.map((v) => ({ value: v, label: v }))
  } catch {
    /* 候选加载失败不阻塞列表 */
  }
}

async function loadList() {
  loading.value = true
  listError.value = ''
  try {
    const { page, size } = getPageParams()
    const r = await fetchAuditLogs({
      keyword: String(filters.keyword || '').trim() || undefined,
      entityType: filters.entityType || undefined,
      action: filters.action || undefined,
      dateFrom: dateRange.value?.[0] || undefined,
      dateTo: dateRange.value?.[1] || undefined,
      page,
      size
    })
    rows.value = r.content
    total.value = r.totalElements
    updateFromResponse({ totalElements: r.totalElements, number: r.number ?? page, size: r.size ?? size })
    hasLoadedList.value = true
  } catch (e: any) {
    listError.value = e?.message || '操作日志加载失败，请稍后重试。'
    notify.error(listError.value)
  } finally {
    loading.value = false
  }
}

// ========== 展示辅助 ==========
function actionTagType(action: string): 'success' | 'danger' | 'warning' | 'info' | 'neutral' {
  const a = action.toLowerCase()
  if (a.includes('delete') || a === 'remove') return 'danger'
  if (a === 'create' || a.endsWith('_create') || a.includes('add')) return 'success'
  if (a.includes('update') || a.includes('edit') || a.includes('merge')) return 'warning'
  return 'info'
}

function prettyChanges(changes: string | null) {
  if (!changes) return '（无变更摘要）'
  const s = String(changes)
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

function dateTime(v: string) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}

loadFacets()
loadList()
</script>

<style scoped>
.changes-pre {
  margin: 4px 12px;
  padding: 12px 14px;
  max-height: 320px;
  overflow: auto;
  background: var(--color-bg-secondary, #f8fafc);
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
