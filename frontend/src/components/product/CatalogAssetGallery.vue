<template>
  <div
    class="asset-manager"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- 拖拽上传遮罩 -->
    <div v-if="dragOver" class="asset-drop-overlay">
      <span>📥 释放文件以上传</span>
    </div>

    <!-- 操作区（吸顶） -->
    <AssetToolbar
      :visible-filter-options="visibleFilterOptions"
      :active-filter="activeFilter"
      :categories="categories"
      :active-quick-filter="activeQuickFilter"
      :active-tag-filter="activeTagFilter"
      :all-tags="allTags"
      :batch-mode="batchMode"
      :selected-count="selectedIds.size"
      :visible-count="visibleAssetCount"
      :is-all-selected="isAllSelected"
      :uploading="uploading"
      :export-zip-state="exportingZip"
      :local-upload-category="localUploadCategory"
      :upload-button-label="uploadButtonLabel"
      :accept-types="acceptTypes"
      :has-quick-filters="hasQuickFilters"
      :visible-quick-filter-options="visibleQuickFilterOptions"
      @filter-change="handleCategoryTabClick"
      @toggle-quick-filter="toggleQuickFilter"
      @toggle-tag-filter="toggleTagFilter"
      @clear-quick-filters="clearQuickFilters"
      @toggle-select-all="() => emit('toggle-select-all', visibleAssetItems)"
      @open-batch-tag-editor="openBatchTagEditor"
      @batch-move-assets="() => emit('batch-move-assets')"
      @batch-delete-assets="() => emit('batch-delete-assets')"
      @batch-website-visible="setWebsiteVisibleForSelected"
      @toggle-batch-mode="() => emit('toggle-batch-mode')"
      @upload="e => emit('upload', e)"
      @export-zip="() => emit('export-zip')"
      @change-upload-category="changeUploadCategory"
    />

    <!-- 素材分组展示 -->
    <AssetGrid
      ref="assetGridRef"
      :asset-groups="assetGroups"
      :assets="assets"
      :view-mode="viewMode"
      :batch-mode="batchMode"
      :selected-ids="selectedIds"
      :highlighted-id="highlightedId"
      :menu-open-id="menuOpenId"
      :drag-asset-id="dragAssetId"
      :drag-over-asset-id="dragOverAssetId"
      :duplicate-file-names="duplicateFileNames"
      :collapsed-tag-groups="collapsedTagGroups"
      :categories="categories"
      :model-id="modelId"
      @toggle-asset-select="id => emit('toggle-asset-select', id)"
      @preview="asset => emit('preview', asset)"
      @set-default="asset => emit('set-default', asset)"
      @open-tag-editor="openTagEditor"
      @toggle-menu="toggleMenu"
      @toggle-view-mode="toggleViewMode"
      @toggle-tag-group-collapse="toggleTagGroupCollapse"
      @select-group-assets="selectGroupAssets"
      @card-drag-start="handleCardDragStart"
      @card-drag-over="handleCardDragOver"
      @card-drag-leave="handleCardDragLeave"
      @card-drop="handleCardDrop"
      @card-drag-end="handleCardDragEnd"
      @set-asset-group-ref="(cat, el) => setAssetGroupRef(cat, el)"
      @gallery-ref="setGalleryGroupsRef"
    />

    <Teleport to="body">
      <div
        v-if="activeMenuAsset && !batchMode"
        class="asset-card-menu-panel asset-card-menu-panel--floating"
        :style="menuPanelStyle"
        @click.stop
      >
        <button type="button" @click="previewMenuAsset(activeMenuAsset)">预览素材</button>
        <button type="button" :disabled="activeMenuAsset.defaultAsset" @click="setDefaultMenuAsset(activeMenuAsset)">
          设为默认
        </button>
        <button type="button" @click="openTagEditor(activeMenuAsset)">编辑标签</button>
        <button type="button" @click="openFolder(activeMenuAsset)">打开所在文件夹</button>
        <div class="menu-sep"></div>
        <div class="menu-website-toggle" @click.stop>
          <label class="menu-toggle-row">
            <span>官网可见</span>
            <el-switch
              :model-value="activeMenuAsset.websiteVisible !== false"
              size="small"
              @change="(val: boolean | string | number) => activeMenuAsset && toggleWebsiteVisible(activeMenuAsset, Boolean(val))"
            />
          </label>
          <span class="menu-toggle-hint">关闭后该素材不出现在官网图库</span>
        </div>
        <div v-if="isImageAsset(activeMenuAsset.category)" class="menu-website-toggle" @click.stop>
          <label class="menu-toggle-row">
            <span>官网排序</span>
            <el-input-number
              :model-value="Number(activeMenuAsset.websiteSortOrder ?? 0)"
              :min="0"
              :max="9999"
              size="small"
              controls-position="right"
              style="width: 92px"
              @change="(val: number | undefined) => activeMenuAsset && saveWebsiteSortOrder(activeMenuAsset, val)"
            />
          </label>
          <span class="menu-toggle-hint">数字越小越靠前，仅用于官网图库排序</span>
        </div>
        <div class="menu-sep"></div>
        <template v-if="!isImageAsset(activeMenuAsset.category)">
          <button type="button" @click="pickCoverMenuAsset(activeMenuAsset)">
            {{ activeMenuAsset.coverFileName ? '更换自定义封面' : '上传自定义封面' }}
          </button>
          <button
            v-if="activeMenuAsset.coverFileName"
            type="button"
            class="danger"
            @click="clearCoverMenuAsset(activeMenuAsset)"
          >
            清除自定义封面
          </button>
        </template>
        <div class="menu-sep"></div>
        <button type="button" @click="emitMenuAction('move-asset', activeMenuAsset)">移动/调整分类</button>
        <button type="button" class="danger" @click="emitMenuAction('delete-asset', activeMenuAsset)">
          删除素材记录
        </button>
      </div>
    </Teleport>

    <!-- 隐藏的封面图片选择输入框 -->
    <input
      ref="coverFileInputRef"
      type="file"
      accept="image/*"
      class="hidden-file-input"
      @change="onCoverFilePicked"
    />

    <AssetTagEditor
      :visible="Boolean(tagEditorAsset || tagEditorMode === 'batch')"
      :mode="tagEditorMode"
      :asset="tagEditorAsset"
      :selected-count="selectedIds.size"
      :batch-tag-replace="batchTagReplace"
      :tag-suggestions="tagSuggestions"
      :model-id="modelId"
      @close="closeTagEditor"
      @submit-single="payload => emit('save-tags', payload)"
      @submit-batch="payload => emit('batch-save-tags', payload)"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { assetTags } from '../../utils/catalog/assetUtils'
import { productFileOwnerType } from '../../utils/catalog/fileAssetUtils'
import { fileAssetOpenFolderMessage, openFileAssetFolderByOptions } from '../../utils/ui/fileAssetUrls'

import AssetToolbar from './AssetToolbar.vue'
import AssetGrid from './AssetGrid.vue'
import AssetTagEditor from './AssetTagEditor.vue'

import { notify } from '@/composables'
import { filesFromClipboardEvent, isEditableElement } from '@/composables/upload/useFileUploadWorkflow'
import { getAssetAccept, ASSET_CATEGORY_KEYS, IMAGE_ASSET_CATEGORIES } from '../../constants/fields'
import { uploadFileAssetCover, clearFileAssetCover } from '@/api/file/fileAssets'
import { batchUpdateAssetWebsiteFields } from '@/api/catalog/assets'
import { askConfirm } from '@/composables/ui/useConfirmDialog'

type AssetId = number
type QuickFilterKey = 'noTag' | 'duplicate'
type TagEditorMode = 'single' | 'batch'
type ViewMode = 'grid' | 'grouped'

interface CatalogAssetView {
  id: AssetId
  modelId?: number | null
  ownerType?: string | null
  variantId?: number | null
  category: string
  fileName: string
  defaultAsset?: boolean
  tags?: string
  contentTags?: string
  specificationTags?: string
  effectiveTags?: string
  applicationTags?: string
  videoContentType?: string
  videoUsageStatus?: string
  machineTierId?: number | null
  machineTierLabel?: string
  machineTierIds?: number[]
  machineTierLabels?: string[]
  featureTags?: string
  demonstrationTags?: string
  videoUsageChannels?: string
  videoContentStatus?: string
  sourceFile?: string
  sourceSheet?: string
  sourceRowNumber?: number
  importBatchId?: number | string | null
  coverFileName?: string | null
  [key: string]: unknown
}

interface AssetCategoryOption {
  key: string
  label: string
  count?: number
}

interface AssetFilterOption {
  key: string
  label: string
  count: number
}

interface AssetSubGroup {
  tag: string | null
  items: CatalogAssetView[]
}

interface AssetGroup {
  category: string
  label: string
  items: CatalogAssetView[]
  subGroups: AssetSubGroup[]
}

interface ReorderItem {
  id: number
  sortOrder: number
}

const props = withDefaults(
  defineProps<{
    assets: CatalogAssetView[]
    categories: AssetCategoryOption[]
    activeFilter?: string
    uploadCategory?: string
    uploading?: boolean
    batchMode?: boolean
    selectedIds: Set<number>
    highlightedId?: number | null
    modelId?: number | null
    exportingZip?: boolean
    /** 任意弹窗打开时为 true，此时禁用粘贴上传避免与弹窗冲突 */
    modalSuspended?: boolean
  }>(),
  {
    activeFilter: 'all',
    uploadCategory: ASSET_CATEGORY_KEYS.mainImage,
    uploading: false,
    batchMode: false,
    highlightedId: null,
    modelId: null,
    exportingZip: false,
    modalSuspended: false
  }
)

const emit = defineEmits<{
  'filter-change': [key: string]
  'category-change': [key: string]
  upload: [event: Event]
  preview: [asset: CatalogAssetView]
  'set-default': [asset: CatalogAssetView]
  'move-asset': [asset: CatalogAssetView]
  'delete-asset': [asset: CatalogAssetView]
  'save-tags': [payload: {
    id: number
    contentTags: string
    machineTierAction: 'KEEP' | 'ASSIGN' | 'UNLINK'
    machineTierIds: number[]
    applicationTags: string
    videoContentType: string
    videoUsageChannels: string
    videoContentStatus: string
    featureTags: string
    demonstrationTags: string
  }]
  'batch-save-tags': [payload: {
    contentTags: string
    tagAction: 'APPEND' | 'REPLACE' | 'REMOVE'
    machineTierAction: 'KEEP' | 'ASSIGN' | 'UNLINK'
    machineTierIds: number[]
    applicationTags: string
    videoContentType: string
    videoUsageChannels: string
    videoContentStatus: string
    featureTags: string
    demonstrationTags: string
    replaceVideoMetadata: boolean
  }]
  'toggle-asset-select': [id: number]
  'toggle-select-all': [items: CatalogAssetView[]]
  'batch-move-assets': []
  'batch-delete-assets': []
  'toggle-batch-mode': []
  'drag-upload': [event: DragEvent]
  'paste-upload': [files: File[]]
  'clear-selection': []
  'reorder-assets': [items: ReorderItem[]]
  'export-zip': []
  'cover-changed': [assetId: number]
  'website-visibility-changed': [asset: CatalogAssetView]
}>()

const menuOpenId = ref<number | null>(null)
const menuPosition = ref({ top: 0, left: 0 })
const galleryGroupsRef = ref<HTMLElement | null>(null)
const groupSectionRefs = new Map<string, Element>()
const assetGridRef = ref<InstanceType<typeof AssetGrid> | null>(null)
const coverFileInputRef = ref<HTMLInputElement | null>(null)
const pickedCoverAsset = ref<CatalogAssetView | null>(null)
const dragOver = ref(false)
const dragCounter = ref(0)
const dragAssetId = ref<number | null>(null)
const dragOverAssetId = ref<number | null>(null)
const localUploadCategory = ref(props.uploadCategory)
const tagEditorAsset = ref<CatalogAssetView | null>(null)
const tagEditorMode = ref<TagEditorMode>('single')
const batchTagReplace = ref(false)
const tagSuggestions = [
  '圆模具',
  '方模具',
  '异形模具',
  '一出一',
  '一出二',
  '一出四',
  '铝箔',
  '片膜',
  '卷膜',
  '气调',
  '真空',
  '手动',
  '半自动',
  '全自动',
  '豆腐封口',
  '封口机构',
  '工作台'
]
const activeQuickFilter = ref<string | null>(null)
const activeTagFilter = ref<string | null>(null)
const viewMode = ref<ViewMode>('grid')
const collapsedTagGroups = ref(new Set<string>())
const activeMenuAsset = computed(() => props.assets.find(asset => asset.id === menuOpenId.value) || null)
const menuPanelStyle = computed(() => ({
  top: `${menuPosition.value.top}px`,
  left: `${menuPosition.value.left}px`
}))

const allTags = computed<string[]>(() => {
  const tagSet = new Set<string>()
  props.assets.forEach(a => {
    assetTags(a).forEach(t => tagSet.add(t))
  })
  return [...tagSet].sort().slice(0, 12)
})

const quickFilterOptions = computed<Array<{ key: QuickFilterKey; label: string; count: number }>>(() => {
  const noTag = props.assets.filter(a => !assetTags(a).length).length
  const dup =
    duplicateFileNames.value.size > 0 ? props.assets.filter(a => duplicateFileNames.value.has(a.fileName)).length : 0
  return [
    { key: 'noTag', label: '无标签', count: noTag },
    { key: 'duplicate', label: '同名重复', count: dup }
  ]
})

function isQuickFilterKey(key: string): key is QuickFilterKey {
  return key === 'noTag' || key === 'duplicate'
}

function toggleQuickFilter(key: string) {
  if (!isQuickFilterKey(key)) return
  activeQuickFilter.value = activeQuickFilter.value === key ? null : key
  activeTagFilter.value = null
  clearSelectionForScopeChange()
  resetAssetScrollAfterFilter()
}

function toggleTagFilter(tag: string) {
  activeTagFilter.value = activeTagFilter.value === tag ? null : tag
  activeQuickFilter.value = null
  clearSelectionForScopeChange()
  resetAssetScrollAfterFilter()
}

function clearQuickFilters() {
  activeQuickFilter.value = null
  activeTagFilter.value = null
  clearSelectionForScopeChange()
  resetAssetScrollAfterFilter()
}

function clearSelectionForScopeChange() {
  if (props.batchMode && props.selectedIds.size > 0) {
    emit('clear-selection')
  }
}

function resetAssetScrollAfterFilter() {
  nextTick(() => {
    requestAnimationFrame(() => scrollAssetListToTop())
  })
}

function setGalleryGroupsRef(el: HTMLElement | null) {
  galleryGroupsRef.value = el
}

function setAssetGroupRef(category: string, el: Element | null) {
  if (el) groupSectionRefs.set(category, el)
  else groupSectionRefs.delete(category)
}

function handleCategoryTabClick(key: string) {
  emit('filter-change', key)
  if (key !== 'all' && key !== localUploadCategory.value) {
    changeUploadCategory(key)
  }
  nextTick(() => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => scrollToAssetGroup(key))
    })
  })
}

function scrollAssetListToTop() {
  const container = galleryGroupsRef.value
  if (!container) return
  container.scrollTo({ top: 0, behavior: 'smooth' })
  const parent = findScrollParent(container)
  if (parent && parent !== container) {
    parent.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

function scrollToAssetGroup(key: string) {
  const container = galleryGroupsRef.value
  if (!container) return
  if (key === 'all') {
    container.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  const safeKey = typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(key) : String(key).replace(/[^\w-]/g, '\\$&')
  const target = container.querySelector(`[data-asset-section="${safeKey}"]`)
  if (!target) return
  const ctRect = container.getBoundingClientRect()
  const tRect = target.getBoundingClientRect()
  const relativeTop = tRect.top - ctRect.top + container.scrollTop
  container.scrollTo({
    top: Math.max(0, relativeTop - 8),
    behavior: 'smooth'
  })
}

function findScrollParent(element: HTMLElement) {
  let node = element.parentElement
  while (node && node !== document.body) {
    const style = window.getComputedStyle(node)
    const canScroll = /(auto|scroll|overlay)/.test(style.overflowY)
    if (canScroll && node.scrollHeight > node.clientHeight + 2) return node
    node = node.parentElement
  }
  return document.scrollingElement || document.documentElement
}

watch(
  () => props.uploadCategory,
  val => {
    localUploadCategory.value = val
  }
)
watch(
  () => props.batchMode,
  enabled => {
    if (enabled) closeMenu()
  }
)
watch(
  () => props.activeFilter,
  async () => {
    closeMenu()
    collapsedTagGroups.value = new Set()
    clearSelectionForScopeChange()
    if (props.activeFilter !== 'all' && props.activeFilter !== localUploadCategory.value) {
      changeUploadCategory(props.activeFilter)
    }
    await nextTick()
    requestAnimationFrame(() => requestAnimationFrame(() => scrollToAssetGroup(props.activeFilter)))
  }
)
watch(
  () => props.assets,
  async () => {
    if (menuOpenId.value && !activeMenuAsset.value) closeMenu()
    await nextTick()
    if (props.activeFilter !== 'all')
      requestAnimationFrame(() => requestAnimationFrame(() => scrollToAssetGroup(props.activeFilter)))
  }
)

const isAllSelected = computed(
  () => visibleAssetItems.value.length > 0 && visibleAssetItems.value.every(a => props.selectedIds.has(a.id))
)

const categoryLabel = (cat: string) => {
  return props.categories.find(c => c.key === cat)?.label || cat
}

const filterOptions = computed<AssetFilterOption[]>(() => {
  const options: AssetFilterOption[] = [{ key: 'all', label: '全部素材', count: props.assets.length }]
  props.categories.forEach(item => {
    options.push({
      key: item.key,
      label: item.label,
      count: props.assets.filter(a => a.category === item.key).length
    })
  })
  return options
})

const visibleFilterOptions = computed(() =>
  filterOptions.value.filter(item => item.key === 'all' || item.count > 0 || item.key === props.activeFilter)
)

const hasQuickFilters = computed(
  () => quickFilterOptions.value.some(item => item.count > 0) || allTags.value.length > 0
)

const visibleQuickFilterOptions = computed(() =>
  quickFilterOptions.value.filter(item => item.count > 0 || item.key === activeQuickFilter.value)
)

const uploadButtonLabel = computed(() => {
  const cat = props.categories.find(c => c.key === localUploadCategory.value)
  return '上传' + (cat?.label || '素材')
})

const acceptTypes = computed(() => getAssetAccept(localUploadCategory.value))

const assetGroups = computed<AssetGroup[]>(() => {
  let orderedKeys = props.categories.map(c => c.key)
  if (props.activeFilter !== 'all') {
    orderedKeys = [props.activeFilter, ...orderedKeys.filter(key => key !== props.activeFilter)]
  }
  return orderedKeys
    .map(cat => {
      const label = categoryLabel(cat)
      let items = props.assets.filter(a => a.category === cat)
      if (activeQuickFilter.value === 'noTag') {
        items = items.filter(a => !assetTags(a).length)
      } else if (activeQuickFilter.value === 'duplicate') {
        items = items.filter(a => duplicateFileNames.value.has(a.fileName))
      }
      const tagFilter = activeTagFilter.value
      if (tagFilter) {
        items = items.filter(a => {
          const tags = assetTags(a)
          return tags.includes(tagFilter)
        })
      }
      let subGroups: AssetSubGroup[]
      if (viewMode.value === 'grouped' && isImageAsset(cat) && items.length > 0) {
        const groupMap = new Map<string, CatalogAssetView[]>()
        const ungrouped: CatalogAssetView[] = []
        items.forEach(item => {
          const tags = assetTags(item)
          if (tags.length > 0) {
            const key = tags[0]
            if (!groupMap.has(key)) groupMap.set(key, [])
            groupMap.get(key)?.push(item)
          } else {
            ungrouped.push(item)
          }
        })
        subGroups = [
          ...[...groupMap.entries()].map(([tag, groupItems]) => ({ tag, items: groupItems })),
          ...(ungrouped.length ? [{ tag: null, items: ungrouped }] : [])
        ]
      } else {
        subGroups = [{ tag: null, items }]
      }
      return { category: cat, label, items, subGroups }
    })
    .filter(group => {
      if (group.items.length > 0) return true
      const hasSecondaryFilter = Boolean(activeQuickFilter.value || activeTagFilter.value)
      return !hasSecondaryFilter && group.category === props.activeFilter
    })
})

const visibleAssetItems = computed<CatalogAssetView[]>(() => assetGroups.value.flatMap(group => group.items))

const visibleAssetCount = computed(() => visibleAssetItems.value.length)

const duplicateFileNames = computed<Set<string>>(() => {
  const nameCount = new Map<string, number>()
  props.assets.forEach(a => {
    const key = a.fileName
    nameCount.set(key, (nameCount.get(key) || 0) + 1)
  })
  return new Set([...nameCount.entries()].filter(([, c]) => c > 1).map(([n]) => n))
})

function isImageAsset(category: string) {
  return IMAGE_ASSET_CATEGORIES.includes(category)
}

function toggleViewMode() {
  viewMode.value = viewMode.value === 'grid' ? 'grouped' : 'grid'
  collapsedTagGroups.value = new Set()
}

function toggleTagGroupCollapse(tagKey: string | null) {
  if (tagKey === null) return
  const next = new Set(collapsedTagGroups.value)
  if (next.has(tagKey)) next.delete(tagKey)
  else next.add(tagKey)
  collapsedTagGroups.value = next
}

function selectGroupAssets(items: CatalogAssetView[]) {
  items.forEach(item => {
    if (!props.selectedIds.has(item.id)) {
      emit('toggle-asset-select', item.id)
    }
  })
}

function openTagEditor(asset: CatalogAssetView) {
  menuOpenId.value = null
  tagEditorMode.value = 'single'
  tagEditorAsset.value = asset
}

function closeTagEditor() {
  tagEditorAsset.value = null
  tagEditorMode.value = 'single'
  batchTagReplace.value = false
}

function openBatchTagEditor() {
  tagEditorAsset.value = null
  batchTagReplace.value = false
  tagEditorMode.value = 'batch'
}

function toggleMenu(asset: CatalogAssetView, event: MouseEvent) {
  if (menuOpenId.value === asset.id) {
    menuOpenId.value = null
    return
  }
  const trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  const rect = trigger?.getBoundingClientRect()
  if (rect) {
    const menuWidth = 168
    const menuHeight = 300
    const gap = 8
    const left = Math.min(window.innerWidth - menuWidth - gap, Math.max(gap, rect.right - menuWidth))
    menuPosition.value = {
      top: Math.min(Math.max(gap, window.innerHeight - menuHeight - gap), Math.max(gap, rect.bottom + gap)),
      left
    }
  }
  menuOpenId.value = asset.id
}

function previewMenuAsset(asset: CatalogAssetView) {
  menuOpenId.value = null
  emit('preview', asset)
}

function setDefaultMenuAsset(asset: CatalogAssetView) {
  menuOpenId.value = null
  emit('set-default', asset)
}

function changeUploadCategory(key: string) {
  localUploadCategory.value = key
  emit('category-change', key)
}

function emitMenuAction(
  eventName: 'move-asset' | 'delete-asset',
  asset: CatalogAssetView
) {
  menuOpenId.value = null
  if (eventName === 'move-asset') emit('move-asset', asset)
  else emit('delete-asset', asset)
}

function onDragEnter(_e: DragEvent) {
  if (dragAssetId.value) return
  dragCounter.value++
  dragOver.value = true
}
function onDragOver(_e: DragEvent) {
  if (dragAssetId.value) return
  dragOver.value = true
}
function onDragLeave() {
  if (dragAssetId.value) return
  dragCounter.value--
  if (dragCounter.value <= 0) {
    dragOver.value = false
    dragCounter.value = 0
  }
}
function onDrop(e: DragEvent) {
  if (dragAssetId.value) return
  dragOver.value = false
  dragCounter.value = 0
  if (e.dataTransfer?.files?.length) {
    emit('drag-upload', e)
  }
}

async function openFolder(asset: CatalogAssetView) {
  menuOpenId.value = null
  try {
    const ownerType = productFileOwnerType(asset)
    const result = await openFileAssetFolderByOptions({
      ownerType,
      assetId: asset.id,
      category: asset.category,
      fileName: asset.fileName,
      modelId: props.modelId
    })
    const feedback = fileAssetOpenFolderMessage(result)
    notify(feedback.message, feedback.type)
  } catch {
    notify('操作失败，请检查后端是否运行', 'error')
  }
}

// ============ 自定义封面 ============
function pickCoverMenuAsset(asset: CatalogAssetView) {
  menuOpenId.value = null
  pickedCoverAsset.value = asset
  nextTick(() => {
    if (coverFileInputRef.value) {
      coverFileInputRef.value.value = ''
      coverFileInputRef.value.click()
    }
  })
}

async function onCoverFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  const asset = pickedCoverAsset.value
  pickedCoverAsset.value = null
  input.value = ''
  if (!file || !asset) return
  if (!file.type.startsWith('image/')) {
    notify('仅允许上传图片作为封面', 'warning')
    return
  }
  try {
    const ownerType = productFileOwnerType(asset)
    await uploadFileAssetCover(ownerType, asset.id, file)
    notify('封面上传成功', 'success')
    emit('cover-changed', asset.id)
  } catch (err) {
    notify.errorWithDetail('封面上传失败', err)
  }
}

async function clearCoverMenuAsset(asset: CatalogAssetView) {
  menuOpenId.value = null
  const ok = await askConfirm({
    title: '清除自定义封面',
    message: `确认清除该素材的自定义封面？清除后将按文件类型生成默认缩略图。`,
    danger: true
  })
  if (!ok) return
  try {
    const ownerType = productFileOwnerType(asset)
    await clearFileAssetCover(ownerType, asset.id)
    notify('封面已清除', 'success')
    emit('cover-changed', asset.id)
  } catch (err) {
    notify.errorWithDetail('清除封面失败', err)
  }
}

/**
 * 切换单张素材的官网可见性。直接调用批量端点（单条），成功后本地更新 + emit 通知父组件刷新。
 */
async function toggleWebsiteVisible(asset: CatalogAssetView, visible: boolean) {
  const prev = asset.websiteVisible !== false
  // 乐观更新：先改本地状态，失败回滚
  asset.websiteVisible = visible
  try {
    await batchUpdateAssetWebsiteFields([asset.id], { websiteVisible: String(visible) })
    notify(`${visible ? '已设为官网可见' : '已隐藏，不会出现在官网图库'}`, 'success')
    emit('website-visibility-changed', asset)
  } catch (err) {
    asset.websiteVisible = prev // 回滚
    notify.errorWithDetail('更新官网可见性失败', err)
  }
}

/** 保存单张图片的官网排序，改动即存。 */
async function saveWebsiteSortOrder(asset: CatalogAssetView, val: number | undefined) {
  const order = val == null || Number.isNaN(val) ? 0 : Math.max(0, Math.trunc(val))
  const prev = asset.websiteSortOrder ?? 0
  asset.websiteSortOrder = order
  try {
    await batchUpdateAssetWebsiteFields([asset.id], { websiteSortOrder: String(order) })
    notify(`已更新官网排序：${order}`, 'success')
  } catch (err) {
    asset.websiteSortOrder = prev
    notify.errorWithDetail('保存官网排序失败', err)
  }
}

/** 批量设置所选图片的官网可见性。 */
async function setWebsiteVisibleForSelected(visible: boolean) {
  if (props.selectedIds.size === 0) {
    notify('请先勾选要修改的素材', 'warning')
    return
  }
  const imageAssets = props.assets.filter(
    a => props.selectedIds.has(a.id) && IMAGE_ASSET_CATEGORIES.includes(a.category)
  )
  if (imageAssets.length === 0) {
    notify('所选素材中没有图片', 'warning')
    return
  }
  const actionText = visible ? '设为官网可见' : '从官网图库隐藏'
  const ok = await askConfirm({
    title: visible ? '批量设为官网可见' : '批量隐藏官网图片',
    message: `确认将所选 ${imageAssets.length} 张图片${actionText}？`,
    confirmText: '确认',
    cancelText: '取消'
  })
  if (!ok) return
  try {
    const ids = imageAssets.map(a => a.id)
    await batchUpdateAssetWebsiteFields(ids, { websiteVisible: String(visible) })
    imageAssets.forEach(a => {
      a.websiteVisible = visible
    })
    notify(`已${visible ? '显示' : '隐藏'} ${ids.length} 张图片`, 'success')
    emit('website-visibility-changed', imageAssets[0])
  } catch (err) {
    notify.errorWithDetail('批量更新官网可见性失败', err)
  }
}

function handleClickOutside(event: MouseEvent) {
  const target = event.target instanceof Element ? event.target : null
  if (target && !target.closest('.htb-more') && !target.closest('.asset-card-menu-panel')) {
    menuOpenId.value = null
  }
}

function closeMenu() {
  menuOpenId.value = null
}

function getFlatAssetsByCategory(category: string) {
  const group = assetGroups.value.find(g => g.category === category)
  if (!group) return []
  const flat: CatalogAssetView[] = []
  group.subGroups.forEach(sg => flat.push(...sg.items))
  return flat
}

function handleCardDragStart(asset: CatalogAssetView, event: DragEvent) {
  if (props.batchMode) return
  dragAssetId.value = asset.id
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(asset.id))
  }
  const card = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  if (card) {
    const clone = card.cloneNode(true) as HTMLElement
    clone.style.position = 'absolute'
    clone.style.top = '-9999px'
    clone.style.left = '-9999px'
    clone.style.width = card.offsetWidth + 'px'
    clone.style.opacity = '0.6'
    clone.style.pointerEvents = 'none'
    document.body.appendChild(clone)
    event.dataTransfer?.setDragImage(clone, clone.offsetWidth / 2, clone.offsetHeight / 2)
    requestAnimationFrame(() => document.body.removeChild(clone))
  }
}

function handleCardDragOver(asset: CatalogAssetView, _event: DragEvent) {
  if (props.batchMode) return
  if (dragAssetId.value && dragAssetId.value !== asset.id) {
    dragOverAssetId.value = asset.id
  }
}

function handleCardDragLeave(asset: CatalogAssetView) {
  if (dragOverAssetId.value === asset.id) {
    dragOverAssetId.value = null
  }
}

function handleCardDrop(targetAsset: CatalogAssetView, _event: DragEvent) {
  if (props.batchMode) return
  const srcId = dragAssetId.value
  const srcAsset = props.assets.find(a => a.id === srcId)
  if (!srcAsset || !srcId || srcId === targetAsset.id) {
    handleCardDragEnd()
    return
  }
  if (srcAsset.category !== targetAsset.category) {
    handleCardDragEnd()
    return
  }
  const flatList = getFlatAssetsByCategory(targetAsset.category)
  const srcIndex = flatList.findIndex(a => a.id === srcId)
  const targetIndex = flatList.findIndex(a => a.id === targetAsset.id)
  if (srcIndex === -1 || targetIndex === -1) {
    handleCardDragEnd()
    return
  }
  const reordered = [...flatList]
  const [moved] = reordered.splice(srcIndex, 1)
  reordered.splice(targetIndex, 0, moved)
  const items = reordered.map((asset, idx) => ({ id: asset.id, sortOrder: idx }))
  emit('reorder-assets', items)
  handleCardDragEnd()
}

function handleCardDragEnd() {
  dragAssetId.value = null
  dragOverAssetId.value = null
}

function handlePaste(e: ClipboardEvent) {
  if (props.modalSuspended) return
  if (isEditableElement()) return
  const files = filesFromClipboardEvent(e)
  if (!files.length) return

  e.preventDefault()
  emit('paste-upload', files)
}

onMounted(() => {
  window.addEventListener('click', handleClickOutside)
  window.addEventListener('resize', closeMenu)
  window.addEventListener('scroll', closeMenu, true)
  document.addEventListener('paste', handlePaste)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', handleClickOutside)
  window.removeEventListener('resize', closeMenu)
  window.removeEventListener('scroll', closeMenu, true)
  document.removeEventListener('paste', handlePaste)
})
</script>

<style scoped>
.asset-manager {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  position: relative;
  background: var(--color-surface);
}

/* 拖拽上传遮罩 */
.asset-drop-overlay {
  position: absolute;
  inset: 12px;
  z-index: var(--z-sticky);
  background: var(--color-primary-bg);
  border: 2px dashed var(--color-primary);
  border-radius: var(--radius-2xl);
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(2px);
}
.asset-drop-overlay span {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 22px;
  background: var(--color-surface);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-pill);
  box-shadow: 0 8px 24px var(--shadow-color-medium);
  font-size: var(--fs-base);
  color: var(--color-primary);
  font-weight: var(--fw-bold);
  animation: assetDropPulse 1.2s ease-in-out infinite;
}
@keyframes assetDropPulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.03);
  }
}

/* 菜单面板（右键菜单定位在 body） */
.asset-card-menu-panel {
  display: grid;
  gap: 2px;
  width: 160px;
  padding: var(--space-sm);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  box-shadow: 0 12px 28px var(--shadow-color-strong);
}
.asset-card-menu-panel--floating {
  position: fixed;
  /* 菜单通过 Teleport 渲染到 body，需高于弹窗(--z-modal:2000)与抽屉(--z-drawer:1000)，
     低于预览(--z-preview:2400, 菜单点预览时即关闭)与 toast(--z-toast:4000)。 */
  z-index: var(--z-popover);
}
.asset-card-menu-panel button {
  width: 100%;
  padding: 7px 8px;
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  text-align: left;
  font-size: var(--fs-xs);
  line-height: var(--lh-snug);
  color: var(--color-text-strong);
  cursor: pointer;
  transition: background var(--transition-fast);
}
.asset-card-menu-panel button:hover {
  background: var(--color-surface-hover);
}
.asset-card-menu-panel button.danger {
  color: var(--color-danger);
}
.asset-card-menu-panel button.danger:hover {
  background: var(--color-danger-bg-soft);
}
.menu-sep {
  height: 1px;
  background: var(--color-border);
  margin: 4px 4px;
}
.menu-website-toggle {
  padding: 6px 8px 4px;
}
.menu-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--color-text-strong);
  cursor: pointer;
}
.menu-toggle-hint {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--color-text-tertiary);
  line-height: 1.3;
}

.hidden-file-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}
</style>
