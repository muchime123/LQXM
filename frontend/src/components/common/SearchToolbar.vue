<template>
  <section
    class="search-toolbar"
    :class="{ 'search-toolbar--batch': showBatchBar }"
    :aria-label="ariaLabel"
  >
    <div v-if="!showBatchBar" class="search-toolbar__row">
      <!-- 左侧：搜索 + 筛选 + 清除 + extra -->
      <div class="search-toolbar__filters">
        <el-input
          class="search-toolbar__search"
          :model-value="keyword"
          :placeholder="searchPlaceholder"
          clearable
          :aria-label="`${ariaLabel}搜索`"
          @update:model-value="handleSearchInput"
          @keyup.enter="handleSearchEnter"
          @clear="handleSearchClear"
          @compositionstart="isComposing = true"
          @compositionend="handleCompositionEnd"
        >
          <template #prefix>
            <el-icon aria-hidden="true"><Search /></el-icon>
          </template>
        </el-input>

        <el-button
          v-if="queryButton"
          class="search-toolbar__query"
          :icon="Search"
          @click="handleQueryClick"
        >
          查询
        </el-button>

        <template v-for="field in fields" :key="field.key">
          <el-input
            v-if="field.type === 'text'"
            class="search-toolbar__filter search-toolbar__filter--text"
            :model-value="textFieldValue(field.key)"
            :placeholder="field.placeholder || `输入${field.label || '条件'}...`"
            clearable
            :aria-label="`按${field.label || field.key}搜索`"
            :style="field.width ? { '--search-toolbar-field-width': toWidth(field.width) } : undefined"
            @update:model-value="onTextFilterInput(field.key, $event)"
            @keyup.enter="handleTextFilterCommit(field.key)"
            @compositionstart="onTextCompositionStart(field.key)"
            @compositionend="onTextCompositionEnd(field.key)"
            @clear="clearTextField(field.key)"
          />
          <el-select
            v-else
            class="search-toolbar__filter"
            :model-value="fieldValue(field.key)"
            :multiple="field.multiple"
            :collapse-tags="field.multiple"
            :collapse-tags-tooltip="field.multiple"
            :max-collapse-tags="1"
            :clearable="field.clearable !== false"
            :filterable="field.filterable || false"
            :placeholder="field.placeholder || `全部${field.label || ''}`"
            :aria-label="`按${field.label || field.key}筛选`"
            :style="field.width ? { '--search-toolbar-field-width': toWidth(field.width) } : undefined"
            @change="handleFieldChange(field.key, $event)"
          >
            <el-option
              v-for="option in normalizedOptionsMap.get(field.key) ?? []"
              :key="`${typeof option.value}:${String(option.value)}`"
              :label="option.label"
              :value="option.value"
              :disabled="option.disabled"
            />
          </el-select>
        </template>

        <el-button
          v-if="showClearButton"
          class="search-toolbar__clear"
          text
          @click="handleClear"
        >
          清除筛选
        </el-button>

        <slot name="extra" />
      </div>

      <!-- 右侧：视图切换（可选）+ 操作按钮 -->
      <div v-if="showViewToggle || $slots.actions || showBatchEntry" class="search-toolbar__actions">
        <el-segmented
          v-if="showViewToggle"
          class="search-toolbar__view-toggle"
          size="small"
          :model-value="viewMode ?? ''"
          :options="segmentedOptions"
          :aria-label="viewToggleLabel"
          @update:model-value="handleViewModeChange"
        >
          <template #default="{ item }">
            <span class="search-toolbar__view-option">
              <el-icon class="search-toolbar__view-icon" aria-hidden="true">
                <component :is="resolveViewModeIcon(item)" />
              </el-icon>
              <span>{{ item.label }}</span>
            </span>
          </template>
        </el-segmented>
        <el-button
          v-if="showBatchEntry"
          class="search-toolbar__batch-entry"
          size="small"
          type="primary"
          plain
          :icon="Operation"
          @click="emit('toggle-batch-mode')"
        >
          批量管理
        </el-button>
        <slot name="actions" />
      </div>
    </div>

    <!-- 批量模式替换普通工具栏，避免新增一行推动下方列表 -->
    <SearchToolbarBatchBar
      v-else
      class="search-toolbar__batch-replacement"
      :selected-count="selectedCount"
      :models-count="modelsCount"
      :is-all-selected="isAllSelected"
      @toggle-select-all="emit('toggle-select-all')"
      @edit="emit('batch-edit')"
      @remove="emit('batch-remove')"
      @exit="emit('toggle-batch-mode')"
    >
      <slot name="batch-actions" :selected-count="selectedCount" />
    </SearchToolbarBatchBar>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch, type Component } from 'vue'
import { Collection, Grid, List, Operation, Search } from '@element-plus/icons-vue'
import SearchToolbarBatchBar from './SearchToolbarBatchBar.vue'

export type ToolbarSearchMode = 'debounce' | 'submit'

export interface ToolbarFieldOption {
  value: string | number | boolean
  label: string
  disabled?: boolean
}

export type ToolbarFieldOptionInput = ToolbarFieldOption | string | number | boolean

export interface ToolbarField {
  key: string
  label?: string
  placeholder?: string
  /** 控件类型：select=下拉筛选（默认）；text=自由文本筛选（placeholder 提示输入内容，回车/停顿后触发） */
  type?: 'select' | 'text'
  options?: ToolbarFieldOptionInput[]
  multiple?: boolean
  filterable?: boolean
  clearable?: boolean
  width?: number | string
}

export interface ToolbarViewModeOption {
  value: string | number
  label: string
  icon?: Component
}

const DEFAULT_VIEW_MODE_OPTIONS: ToolbarViewModeOption[] = [
  { value: 'list', label: '列表', icon: List },
  { value: 'card', label: '卡片', icon: Grid }
]

const props = withDefaults(
  defineProps<{
    searchPlaceholder?: string
    searchOnInput?: boolean
    /** 搜索触发模式；设置后优先于 searchOnInput，推荐新调用方使用 */
    searchMode?: ToolbarSearchMode
    debounce?: number
    /** 是否显示“查询”按钮；默认跟随输入防抖即时搜索 */
    queryButton?: boolean
    /** 筛选下拉配置，由 fields 驱动生成 */
    fields?: ToolbarField[]
    /** 各筛选字段当前值，按字段 key 取值 */
    fieldValues?: Record<string, unknown>
    /** 覆盖“清除筛选”按钮显隐；缺省时组件根据筛选值自动判断 */
    clearVisible?: boolean
    viewModeOptions?: ToolbarViewModeOption[]
    viewToggleLabel?: string
    /** 强制控制视图切换显隐；缺省时根据 viewMode/viewModeOptions 自动判断 */
    viewToggleVisible?: boolean
    /* 批量操作栏（可选第二行） */
    batchEnabled?: boolean
    /** 是否显示主动进入批量模式的按钮；表格选择列常驻时应关闭。 */
    batchEntryVisible?: boolean
    batchMode?: boolean
    selectedCount?: number
    modelsCount?: number
    isAllSelected?: boolean
    ariaLabel?: string
  }>(),
  {
    searchPlaceholder: '搜索...',
    searchOnInput: true,
    searchMode: undefined,
    debounce: 300,
    queryButton: false,
    fields: () => [],
    fieldValues: () => ({}),
    clearVisible: undefined,
    viewModeOptions: () => [],
    viewToggleLabel: '切换视图',
    viewToggleVisible: undefined,
    batchEnabled: false,
    batchEntryVisible: true,
    batchMode: false,
    selectedCount: 0,
    modelsCount: 0,
    isAllSelected: false,
    ariaLabel: '列表工具栏'
  }
)

const emit = defineEmits<{
  search: [value: string]
  'filter-change': [key: string, value: unknown]
  clear: []
  'toggle-batch-mode': []
  'toggle-select-all': []
  'batch-edit': []
  'batch-remove': []
}>()

const keyword = defineModel<string>('keyword', { default: '' })
const viewMode = defineModel<string | number>('viewMode')

const isComposing = ref(false)
let debounceTimer: ReturnType<typeof setTimeout> | undefined

function cancelPendingSearch() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = undefined
}

function scheduleSearch(value: string) {
  const searchesOnInput = props.searchMode ? props.searchMode === 'debounce' : props.searchOnInput
  if (!searchesOnInput || isComposing.value) return
  cancelPendingSearch()
  debounceTimer = setTimeout(() => emit('search', value.trim()), props.debounce)
}

function handleSearchInput(value: string) {
  keyword.value = value
  scheduleSearch(value)
}

function handleSearchEnter() {
  if (isComposing.value) return
  cancelPendingSearch()
  emit('search', keyword.value.trim())
}

function handleSearchClear() {
  cancelPendingSearch()
  keyword.value = ''
  emit('search', '')
}

function handleClear() {
  cancelPendingSearch()
  keyword.value = ''
  emit('clear')
}

function handleQueryClick() {
  cancelPendingSearch()
  emit('search', keyword.value.trim())
}

function handleCompositionEnd() {
  isComposing.value = false
  scheduleSearch(keyword.value)
}

const fieldMap = computed(() => {
  const map = new Map<string, ToolbarField>()
  for (const field of props.fields) map.set(field.key, field)
  return map
})

const normalizedOptionsMap = computed(() => {
  const map = new Map<string, ToolbarFieldOption[]>()
  for (const field of props.fields) {
    map.set(
      field.key,
      (field.options ?? []).map(option =>
        option && typeof option === 'object' ? option : { value: option, label: String(option) }
      )
    )
  }
  return map
})

function toWidth(width: number | string): string {
  return typeof width === 'number' ? `${width}px` : width
}

function fieldValue(key: string): unknown {
  const value = props.fieldValues[key]
  if (value !== undefined && value !== null) return value
  const field = fieldMap.value.get(key)
  return field?.multiple ? [] : ''
}

function handleFieldChange(key: string, value: unknown) {
  emit('filter-change', key, value)
}

const textFieldTimers = new Map<string, ReturnType<typeof setTimeout>>()
const textFieldComposing = new Set<string>()
const textMirrors = reactive<Record<string, string>>({})
const textCommitted = reactive<Record<string, string>>({})

/**
 * 文本筛选框采用“本地镜像 + 防抖提交”：输入期间即时更新本地值，停顿/回车/失焦提交。
 * 提交统一 trim，且与上次提交值一致时不重复触发，避免外部回写时抖屏。
 */
function textFieldValue(key: string): string {
  if (!(key in textMirrors)) {
    const value = props.fieldValues[key]
    textMirrors[key] = value == null ? '' : String(value)
    textCommitted[key] = textMirrors[key].trim()
  }
  return textMirrors[key]
}

function commitTextField(key: string) {
  const value = (textMirrors[key] ?? '').trim()
  if (value !== textCommitted[key]) {
    textCommitted[key] = value
    emit('filter-change', key, value)
  }
}

function onTextFilterInput(key: string, raw: string | null | undefined) {
  textMirrors[key] = raw ?? ''
  if (textFieldComposing.has(key)) return
  const timer = textFieldTimers.get(key)
  if (timer) clearTimeout(timer)
  textFieldTimers.set(
    key,
    setTimeout(() => {
      textFieldTimers.delete(key)
      commitTextField(key)
    }, props.debounce)
  )
}

/** 中文/日文输入法组词期间只更新镜像，不触发筛选 */
function onTextCompositionStart(key: string) {
  textFieldComposing.add(key)
}

function onTextCompositionEnd(key: string) {
  if (!textFieldComposing.delete(key)) return
  const timer = textFieldTimers.get(key)
  if (timer) {
    clearTimeout(timer)
    textFieldTimers.delete(key)
  }
  commitTextField(key)
}

/** 文本筛选：回车立即提交 */
function handleTextFilterCommit(key: string) {
  if (textFieldComposing.has(key)) return
  const timer = textFieldTimers.get(key)
  if (timer) {
    clearTimeout(timer)
    textFieldTimers.delete(key)
  }
  commitTextField(key)
}

/** 文本框自带的清空按钮 */
function clearTextField(key: string) {
  textFieldComposing.delete(key)
  const timer = textFieldTimers.get(key)
  if (timer) {
    clearTimeout(timer)
    textFieldTimers.delete(key)
  }
  textMirrors[key] = ''
  if (textCommitted[key]) {
    textCommitted[key] = ''
    emit('filter-change', key, '')
  }
}

function cancelPendingTextFilters() {
  textFieldTimers.forEach(timer => clearTimeout(timer))
  textFieldTimers.clear()
}

// 外部“清除筛选/重置”或其它入口变更字段值时，让镜像跟随外部值
watch(
  () => props.fieldValues,
  values => {
    for (const field of props.fields) {
      if (field.type !== 'text') continue
      const value = values?.[field.key]
      const normalized = value == null ? '' : String(value)
      if ((textMirrors[field.key] ?? '') !== normalized && normalized !== textCommitted[field.key]) {
        textMirrors[field.key] = normalized
        textCommitted[field.key] = normalized.trim()
      }
    }
  },
  { deep: true }
)

const hasActiveFilters = computed(() => {
  if (keyword.value?.trim()) return true
  return props.fields.some(field => {
    const value = props.fieldValues[field.key]
    if (Array.isArray(value)) return value.length > 0
    return value !== '' && value !== null && value !== undefined
  })
})

const showClearButton = computed(() => props.clearVisible ?? hasActiveFilters.value)
const showBatchBar = computed(() => props.batchEnabled && props.batchMode)
const showBatchEntry = computed(() => props.batchEnabled && props.batchEntryVisible && !props.batchMode)

const showViewToggle = computed(
  () =>
    props.viewToggleVisible ??
    ((viewMode.value !== undefined && viewMode.value !== null) || props.viewModeOptions.length > 0)
)

const segmentedOptions = computed(() =>
  props.viewModeOptions.length > 0 ? props.viewModeOptions.map(option => ({ ...option })) : DEFAULT_VIEW_MODE_OPTIONS
)

function resolveViewModeIcon(option: ToolbarViewModeOption): Component {
  if (option.icon) return option.icon

  const value = String(option.value).toLowerCase()
  if (value === 'card' || value === 'browse' || option.label.includes('卡片')) return Grid
  if (value === 'grouped' || option.label.includes('规格族') || option.label.includes('分组')) return Collection
  return List
}

function handleViewModeChange(value: string | number | boolean | undefined) {
  if (value === undefined || typeof value === 'boolean') return
  viewMode.value = value
}

onBeforeUnmount(() => {
  cancelPendingSearch()
  cancelPendingTextFilters()
})
</script>

<style scoped>
/* ===== 通用搜索/筛选工具栏（SearchToolbar） ===== */
.search-toolbar {
  --search-toolbar-row-height: var(--control-height-default);
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap);
  min-height: var(--toolbar-min-height);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
  transition:
    border-color var(--transition-fast),
    background-color var(--transition-fast);
}

/* 批量模式下整体切换为主题色底，形成明显的“编辑状态”信号 */
.search-toolbar--batch {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.search-toolbar__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap);
  min-height: var(--search-toolbar-row-height);
}

.search-toolbar__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap);
  flex: 1 1 auto;
  min-width: 0;
}

.search-toolbar__search {
  flex: 0 1 260px;
  min-width: 200px;
}

.search-toolbar__query {
  flex: 0 0 auto;
}

.search-toolbar__filter {
  flex: 0 0 auto;
  width: var(--search-toolbar-field-width, 150px);
}

.search-toolbar__clear {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  transition: color var(--transition-fast);
}

.search-toolbar__clear:hover {
  color: var(--color-primary);
}

.search-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap);
  margin-left: auto;
  min-width: 0;
}

.search-toolbar__actions > * {
  flex: 0 0 auto;
}

.search-toolbar__batch-entry {
  flex: 0 0 auto;
}

.search-toolbar__batch-replacement {
  flex-wrap: nowrap;
  align-items: center;
  height: var(--search-toolbar-row-height);
  min-width: 0;
  padding-top: 0;
  border-top: 0;
  box-sizing: border-box;
  overflow-x: auto;
  scrollbar-width: thin;
}

/* 批量条内容不压缩，超出时横向滚动，避免按钮被挤压 */
.search-toolbar__batch-replacement :deep(.search-toolbar-batch) {
  flex-wrap: nowrap;
  align-items: center;
}

.search-toolbar__batch-replacement :deep(.search-toolbar-batch) > * {
  flex: 0 0 auto;
}

.search-toolbar__batch-replacement :deep(.search-toolbar-batch em) {
  color: var(--color-text-secondary);
}

/* 视图切换是工具栏中的模式选择器，不按普通小按钮处理。 */
.search-toolbar__view-toggle {
  flex: 0 0 auto;
  min-height: var(--control-height-default);
  padding: 2px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.search-toolbar__view-toggle:deep(.el-segmented__item) {
  min-width: 56px;
  min-height: calc(var(--control-height-default) - 6px);
  padding: 0 9px;
  border-radius: calc(var(--radius-md) - 2px);
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  transition:
    color var(--transition-fast),
    background-color var(--transition-fast);
}

.search-toolbar__view-toggle:deep(.el-segmented__item:not(.is-selected):hover) {
  color: var(--color-text-primary);
  background: var(--color-surface-hover);
}

.search-toolbar__view-toggle:deep(.el-segmented__item-selected) {
  border-radius: calc(var(--radius-md) - 2px);
  background: var(--color-primary);
  box-shadow: none;
}

.search-toolbar__view-toggle:deep(.el-segmented__item.is-selected) {
  color: var(--color-text-on-primary);
}

.search-toolbar__view-toggle:deep(.el-segmented__item:focus-visible) {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.search-toolbar__view-option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  white-space: nowrap;
}

.search-toolbar__view-icon {
  flex: 0 0 auto;
  width: 14px;
  height: 14px;
  font-size: 14px;
}

@media (max-width: 767px) {
  .search-toolbar {
    padding: var(--space-control-gap) var(--space-control-gap);
  }

  .search-toolbar__row {
    align-items: stretch;
  }

  .search-toolbar__actions {
    margin-left: 0;
    width: 100%;
    justify-content: flex-end;
  }

  .search-toolbar__search,
  .search-toolbar__query,
  .search-toolbar__filter {
    flex: 1 1 100%;
    width: 100%;
    min-width: 0;
  }

  .search-toolbar__view-toggle {
    min-height: var(--control-height-sm);
  }
}
</style>
