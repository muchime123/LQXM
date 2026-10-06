<template>
  <ListPageLayout
    class="operation-log-page"
    title="操作日志"
    description="关键增删改操作审计记录。点击行首箭头查看变更摘要。"
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
          <el-radio-group v-model="activeTab" size="small" class="source-switch" @change="handleSourceChange">
            <el-radio-button value="archive">档案运营</el-radio-button>
            <el-radio-button value="erp">ERP 变更</el-radio-button>
          </el-radio-group>
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
      :refreshing="loading && activeRows.length > 0"
      error-title="操作日志加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的日志。"
      empty-text="暂无操作日志"
      empty-hint="系统会在关键增删改操作发生时自动记录。"
      filtered-empty-text="没有符合条件的日志"
      filtered-empty-hint="请调整搜索内容或筛选条件。"
      @retry="loadList"
    >
      <!-- 档案运营：audit_logs（实体类型 = 自由字符串） -->
      <LqTable v-if="activeTab === 'archive'" :data="rows" row-key="id" density="compact">
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

      <!-- ERP 变更：row_change_log（身份 = 被改动的表，带操作人与请求键） -->
      <LqTable v-else :data="erpRows" row-key="id" density="compact">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="changes-pre">{{ prettyChanges(row.changedFields) }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ dateTime(row.changedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ row.operatorName || '-' }}</template>
        </el-table-column>
        <el-table-column label="被改动对象" min-width="240">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ tableLabel(row.targetTable) }}</el-tag>
            <span class="target-id">#{{ row.targetId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="100">
          <template #default="{ row }">
            <StatusTag :type="actionTagType(row.action)" :label="row.action" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="变更原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.changeReason || row.reasonCode || '-' }}</template>
        </el-table-column>
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
import {
  fetchErpChangeLogFacets,
  fetchErpChangeLogs,
  type ErpChangeLogItem
} from '@/api/erpChangeLogs'

type SourceTab = 'archive' | 'erp'

/**
 * 两个数据源的差别不是「换个表查」，而是**身份模型不同**：
 * - 档案运营 audit_logs：身份是 entity_type（自由字符串）
 * - ERP 变更 row_change_log：身份是 target_table（schema 限定表名，物理唯一），且带操作人/请求键
 * 所以不做成同一张表的筛选，而是并列两个页签。
 */
const activeTab = ref<SourceTab>('archive')

// ========== 筛选 / 分页 ==========
const { filters, resetFilters } = useListFilters({
  keyword: '',
  entityType: '',
  action: '',
  targetTable: '',
  operatorName: ''
})

const entityTypeOptions = ref<{ value: string; label: string }[]>([])
const actionOptions = ref<{ value: string; label: string }[]>([])
const targetTableOptions = ref<{ value: string; label: string }[]>([])
const operatorOptions = ref<{ value: string; label: string }[]>([])

const toolbarFields = computed<ToolbarField[]>(() =>
  activeTab.value === 'archive'
    ? [
        { key: 'entityType', label: '实体类型', options: entityTypeOptions.value, filterable: true, clearable: true, width: 190 },
        { key: 'action', label: '操作动作', options: actionOptions.value, filterable: true, clearable: true, width: 210 }
      ]
    : [
        { key: 'targetTable', label: '被改动表', options: targetTableOptions.value, filterable: true, clearable: true, width: 230 },
        { key: 'action', label: '动作', options: actionOptions.value, filterable: true, clearable: true, width: 120 },
        { key: 'operatorName', label: '操作人', options: operatorOptions.value, filterable: true, clearable: true, width: 140 }
      ]
)

const dateRange = ref<[string, string] | null>(null)

function applyDateRange() {
  applyFilters()
}

function handleSourceChange() {
  // 两个源的候选与列不同，切换即重置筛选，避免上一页签的条件悄悄生效
  dateRange.value = null
  resetFilters()
  resetPage()
  loadList()
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
const erpRows = ref<ErpChangeLogItem[]>([])
const total = ref(0)
const listError = ref('')
const hasLoadedList = ref(false)

const activeRows = computed(() => (activeTab.value === 'archive' ? rows.value : erpRows.value))

const hasActiveFilters = computed(() => {
  const shared =
    Boolean(String(filters.keyword || '').trim()) || Boolean(filters.action) || Boolean(dateRange.value)
  return activeTab.value === 'archive'
    ? shared || Boolean(filters.entityType)
    : shared || Boolean(filters.targetTable) || Boolean(filters.operatorName)
})
const dataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoadedList.value) return 'loading'
  if (listError.value && activeRows.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (activeRows.value.length === 0) return hasActiveFilters.value ? 'filtered-empty' : 'empty'
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
  try {
    const f = await fetchErpChangeLogFacets()
    targetTableOptions.value = f.targetTables.map((v) => ({ value: v, label: tableLabel(v) }))
    operatorOptions.value = f.operatorNames.map((v) => ({ value: v, label: v }))
  } catch {
    /* 同上 */
  }
}

async function loadList() {
  loading.value = true
  listError.value = ''
  try {
    const { page, size } = getPageParams()
    if (activeTab.value === 'archive') {
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
    } else {
      const r = await fetchErpChangeLogs({
        keyword: String(filters.keyword || '').trim() || undefined,
        targetTable: filters.targetTable || undefined,
        action: filters.action || undefined,
        operatorName: filters.operatorName || undefined,
        dateFrom: dateRange.value?.[0] || undefined,
        dateTo: dateRange.value?.[1] || undefined,
        page,
        size
      })
      erpRows.value = r.content
      total.value = r.totalElements
      updateFromResponse({ totalElements: r.totalElements, number: r.number ?? page, size: r.size ?? size })
    }
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
  const a = (action || '').toLowerCase()
  if (a.includes('delete') || a === 'remove') return 'danger'
  if (a === 'create' || a.endsWith('_create') || a.includes('add') || a === 'insert') return 'success'
  if (a.includes('update') || a.includes('edit') || a.includes('merge')) return 'warning'
  return 'info'
}

/** 被改动表的可读名：ERP 审计以表名作身份，但表名不该直接糊在界面上 */
const TABLE_LABELS: Record<string, string> = {
  'product_archive.erp_sales_outbounds': '销售出库单',
  'product_archive.manufacturing_orders': '生产工单',
  'product_archive.manufacturing_order_operations': '工单工序',
  'product_archive.manufacturing_work_reports': '生产报工',
  'product_archive.manufacturing_inspections': '生产检验',
  'product_archive.manufacturing_completion_receipts': '完工入库单',
  'product_archive.manufacturing_issues': '生产异常',
  'product_archive.manufacturing_operations': '工序主数据',
  'product_archive.manufacturing_process_templates': '制程模板',
  'product_archive.manufacturing_process_template_rules': '制程推荐规则',
  'product_archive.manufacturing_task_config_items': '任务单配置',
  'product_archive.manufacturing_workshops': '生产车间'
}

function tableLabel(targetTable: string) {
  if (!targetTable) return '-'
  return TABLE_LABELS[targetTable] || targetTable.replace(/^product_archive\./, '')
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

.source-switch {
  margin-right: 8px;
}

.target-id {
  margin-left: 6px;
  color: var(--color-text-secondary, #64748b);
  font-size: 12px;
}
</style>
