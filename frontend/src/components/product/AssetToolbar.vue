<template>
  <div class="asset-filter-header">
    <nav class="asset-filter-tabs">
      <button
        v-for="item in visibleFilterOptions"
        :key="item.key"
        type="button"
        :class="{ active: activeFilter === item.key }"
        @click="$emit('filter-change', item.key)"
      >
        {{ item.label }}
        <span>{{ item.count }}</span>
      </button>

      <div class="tabs-spacer"></div>

      <template v-if="batchMode">
        <span class="batch-info-inline">{{ selectedCount }} / {{ visibleCount }} 已选</span>
        <button type="button" @click="$emit('toggle-select-all')">
          {{ isAllSelected ? '取消全选' : '全选' }}
        </button>
        <button type="button" :disabled="selectedCount === 0" @click="$emit('open-batch-tag-editor')">批量标签</button>
        <button
type="button"
class="primary"
:disabled="selectedCount === 0"
@click="$emit('batch-move-assets')">
          移动
        </button>
        <button type="button" :disabled="selectedCount === 0" @click="$emit('batch-website-visible', true)">
          官网显示
        </button>
        <button type="button" :disabled="selectedCount === 0" @click="$emit('batch-website-visible', false)">
          官网隐藏
        </button>
        <button
type="button"
class="danger"
:disabled="selectedCount === 0"
@click="$emit('batch-delete-assets')">
          删除
        </button>
        <button type="button" @click="$emit('toggle-batch-mode')">退出</button>
      </template>
      <template v-else>
        <button type="button" class="batch-select-btn" @click="$emit('toggle-batch-mode')">批量管理</button>
      </template>

      <span class="tabs-divider"></span>

      <div class="asset-upload-inline">
        <select
          :value="localUploadCategory"
          class="upload-cat-select"
          @change="$emit('change-upload-category', ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="item in categories" :key="item.key" :value="item.key">{{ item.label }}</option>
        </select>
        <label class="file-button">
          {{ uploading ? '上传中...' : uploadButtonLabel }}
          <input
type="file"
:accept="acceptTypes"
:disabled="uploading"
multiple
@change="$emit('upload', $event)" />
        </label>
      </div>

      <button
type="button"
class="tb-btn tb-export"
:disabled="exportZipState"
@click="$emit('export-zip')">
        <svg
width="16"
height="16"
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2">
          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
          <polyline points="7 10 12 15 17 10" />
          <line
x1="12"
y1="15"
x2="12"
y2="3" />
        </svg>
        {{ exportZipState ? '打包中...' : '打包下载全部素材' }}
      </button>
    </nav>

    <CategorySummaryTabs
      v-if="hasQuickFilters"
      variant="chip"
      aria-label="素材快捷筛选"
      :tabs="chipTabs"
      :model-value="activeChip"
      :clearable="!!(activeQuickFilter || activeTagFilter)"
      @update:model-value="handleChipToggle"
      @clear="$emit('clear-quick-filters')"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import CategorySummaryTabs from '../common/CategorySummaryTabs.vue'
import type { CategorySummaryTab } from '../common/CategorySummaryTabs.vue'

interface FilterOption {
  key: string
  label: string
  count: number
}

interface CategoryOption {
  key: string
  label: string
  count?: number
}

interface QuickFilterOption {
  key: string
  label: string
  count: number
}

const props = withDefaults(
  defineProps<{
    visibleFilterOptions: FilterOption[]
    activeFilter: string
    categories: CategoryOption[]
    activeQuickFilter: string | null
    activeTagFilter: string | null
    allTags: string[]
    batchMode: boolean
    selectedCount: number
    visibleCount: number
    isAllSelected: boolean
    uploading: boolean
    exportZipState: boolean
    localUploadCategory: string
    uploadButtonLabel: string
    acceptTypes: string
    hasQuickFilters: boolean
    visibleQuickFilterOptions: QuickFilterOption[]
  }>(),
  {}
)

const emit = defineEmits<{
  'filter-change': [key: string]
  'toggle-quick-filter': [key: string]
  'toggle-tag-filter': [tag: string]
  'clear-quick-filters': []
  'toggle-select-all': []
  'open-batch-tag-editor': []
  'batch-move-assets': []
  'batch-delete-assets': []
  'batch-website-visible': [visible: boolean]
  'toggle-batch-mode': []
  upload: [event: Event]
  'export-zip': []
  'change-upload-category': [key: string]
}>()

// 快捷筛选与标签合并为一组 chip 交给公共 CategorySummaryTabs 渲染，
// 标签组前插入分隔符，保持原有视觉分组。
const chipTabs = computed<CategorySummaryTab[]>(() => {
  const quick = props.visibleQuickFilterOptions.map(item => ({
    label: item.label,
    value: item.key,
    count: item.count
  }))
  const tags = props.allTags.map((tag, index) => ({
    label: tag,
    value: tag,
    separatorBefore: index === 0
  }))
  return [...quick, ...tags]
})

/** 当前高亮的 chip（快捷筛选与标签互斥展示，二者同时存在时优先快捷筛选） */
const activeChip = computed(() => props.activeQuickFilter || props.activeTagFilter || '')

function handleChipToggle(value: string) {
  if (props.allTags.includes(value)) {
    emit('toggle-tag-filter', value)
    return
  }
  emit('toggle-quick-filter', value)
}
</script>

<style scoped>
@import '../../styles/asset-toolbar.css';
</style>
