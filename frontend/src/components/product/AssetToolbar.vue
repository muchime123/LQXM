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

    <div v-if="hasQuickFilters" class="asset-toolbar-chips">
      <button
        v-for="qf in visibleQuickFilterOptions"
        :key="qf.key"
        type="button"
        :class="{ active: activeQuickFilter === qf.key }"
        class="tb-chip"
        @click="$emit('toggle-quick-filter', qf.key)"
      >
        {{ qf.label }}
        <span v-if="qf.count > 0">{{ qf.count }}</span>
      </button>
      <template v-if="allTags.length">
        <span class="tb-chip-sep"></span>
        <button
          v-for="tag in allTags"
          :key="tag"
          type="button"
          :class="{ active: activeTagFilter === tag }"
          class="tb-chip"
          @click="$emit('toggle-tag-filter', tag)"
        >
          {{ tag }}
        </button>
      </template>
      <button
        v-if="activeQuickFilter || activeTagFilter"
        type="button"
        class="tb-chip tb-chip-clear"
        @click="$emit('clear-quick-filters')"
      >
        清除
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
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

withDefaults(
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

defineEmits<{
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
</script>

<style scoped>
@import '../../styles/asset-toolbar.css';
</style>
