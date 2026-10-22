<script setup lang="ts">
import {
  addPackagingAsset,
  reorderPackagingAssets,
  type PackagingAssetView,
  type PackagingModelDetail
} from '@/api/packaging'
import { FILE_ASSET_FAILURE_PREFIX } from '@/constants/fileAssetMessages'
import {
  PACKAGING_ASSET_CATEGORIES,
  PACKAGING_ASSET_CATEGORY_KEYS,
  assetCategoryLabel,
  getPackagingAssetAccept
} from '@/constants/packaging'
import type { FileOwnerType } from '@/types/fileAsset'
import { packagingFileOwnerTypeEntries } from '@/utils/catalog/fileAssetUtils'
import { isImageFile } from '@/utils/ui/fileUtils'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useFileAssetActions } from '../../composables/upload/useFileAssetActions'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { useFileUploadWorkflow } from '@/composables/upload/useFileUploadWorkflow'
import PackagingTagEditor from './PackagingTagEditor.vue'
import LqThumbnail from '../common/LqThumbnail.vue'
import CategorySummaryTabs from '../common/CategorySummaryTabs.vue'

interface Props {
  detail: PackagingModelDetail | null
  modelId: number
  fileOwnerTypeForPackagingAsset: (asset: Partial<PackagingAssetView> | null | undefined) => FileOwnerType | null
  packagingAssetThumbnailUrl: (asset: Partial<PackagingAssetView> | null | undefined) => string
  previewAsset: (asset: PackagingAssetView) => void
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'asset-changed': []
}>()

const fileAssetActions = useFileAssetActions({ notify, notifySuccess: false })
const activeFilter = ref<string>('all')
const localUploadCategory = ref<string>(PACKAGING_ASSET_CATEGORY_KEYS.mainImage)
const batchMode = ref(false)
const selectedIds = ref<Set<number>>(new Set())
const activeQuickFilter = ref<string | null>(null)
const menuOpenId = ref<number | null>(null)
const menuPosition = ref({ top: 0, left: 0 })
const draggingAssetId = ref<number | null>(null)
const dragOverAssetId = ref<number | null>(null)

const uploadedAssets = ref<PackagingAssetView[]>([])
const uploadTarget = ref<{ category: string; variantId?: number | null; makeDefault?: boolean } | null>(null)

const uploadButtonLabel = computed(() => {
  const cat = PACKAGING_ASSET_CATEGORIES.find(c => c.key === localUploadCategory.value)
  return '上传' + (cat?.label || '素材')
})

const acceptTypes = computed(() => getPackagingAssetAccept(localUploadCategory.value))

const { uploading, dragOver, dropZoneProps, pickFiles, handlePaste } = useFileUploadWorkflow({
  notify,
  category: computed(() => localUploadCategory.value),
  accept: acceptTypes,
  uploadOptions: () => ({ modelId: props.modelId }),
  registerUploaded: async ({ fileName }) => {
    const target = uploadTarget.value
    const category = target?.category || localUploadCategory.value
    const asset = await addPackagingAsset(props.modelId, category, fileName, '', target?.variantId ?? null)
    if (target?.makeDefault && asset?.id) {
      const ownerType = props.fileOwnerTypeForPackagingAsset(asset)
      if (ownerType) {
        await fileAssetActions.setDefault(ownerType, asset.id)
        asset.defaultAsset = true
      }
    }
    if (asset) uploadedAssets.value.push(asset)
    return asset
  },
  onBatchComplete: async () => {
    const target = uploadTarget.value
    let message = '素材已上传'
    if (target?.variantId && props.detail) {
      const variant = props.detail.variants.find(v => v.id === target.variantId)
      message = `图片已上传到「${variant?.variantName || variant?.specLabel || '规格'}」`
    } else if (target?.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && !target?.variantId) {
      message = '系列主图已上传'
    } else {
      message = `${assetCategoryLabel(target?.category || localUploadCategory.value)}已上传`
    }
    if (props.detail && uploadedAssets.value.length > 0) {
      uploadedAssets.value.forEach(asset => {
        const existingIndex = props.detail!.assets.findIndex(a => a.id === asset.id)
        if (existingIndex >= 0) {
          props.detail!.assets[existingIndex] = asset
        } else {
          props.detail!.assets.push(asset)
        }
        if (asset.defaultAsset && asset.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && !asset.variantId) {
          props.detail!.model.defaultImage = asset.fileName
        }
      })
      uploadedAssets.value = []
    }
    uploadTarget.value = null
    emit('asset-changed')
    notify(message, 'success')
  }
})

const allAssets = computed(() => props.detail?.assets || [])

const filterOptions = computed(() => {
  const options: Array<{ key: string; label: string; count: number }> = [
    { key: 'all', label: '全部素材', count: allAssets.value.length }
  ]
  PACKAGING_ASSET_CATEGORIES.forEach(cat => {
    const count = allAssets.value.filter(a => a.category === cat.key).length
    if (count > 0) options.push({ key: cat.key, label: cat.label, count })
  })
  return options
})

const visibleFilterOptions = computed(() =>
  filterOptions.value.filter(item => item.key === 'all' || item.count > 0 || item.key === activeFilter.value)
)

const quickFilterOptions = computed(() => {
  const noTag = allAssets.value.filter(a => !a.tags || a.tags.trim() === '').length
  return [{ key: 'noTag', label: '无标签', count: noTag }]
})

const visibleQuickFilterOptions = computed(() =>
  quickFilterOptions.value.filter(item => item.count > 0 || item.key === activeQuickFilter.value)
)

/** 交给公共 CategorySummaryTabs（chip 形态）渲染的候选项 */
const chipTabs = computed(() =>
  visibleQuickFilterOptions.value.map(item => ({ label: item.label, value: item.key, count: item.count }))
)

const filteredAssets = computed(() => {
  let items = allAssets.value
  if (activeFilter.value !== 'all') {
    items = items.filter(a => a.category === activeFilter.value)
  }
  if (activeQuickFilter.value === 'noTag') {
    items = items.filter(a => !a.tags || a.tags.trim() === '')
  }
  return items
})

const isAllSelected = computed(
  () => filteredAssets.value.length > 0 && filteredAssets.value.every(a => selectedIds.value.has(a.id))
)

const visibleAssetCount = computed(() => filteredAssets.value.length)

const hasQuickFilters = computed(() => visibleQuickFilterOptions.value.some(i => i.count > 0))

const assetGroups = computed(() => {
  let orderedKeys = PACKAGING_ASSET_CATEGORIES.map(c => c.key)
  if (activeFilter.value !== 'all') {
    orderedKeys = [activeFilter.value, ...orderedKeys.filter(k => k !== activeFilter.value)]
  }
  return orderedKeys
    .map(catKey => {
      const cat = PACKAGING_ASSET_CATEGORIES.find(c => c.key === catKey)
      if (!cat) return null
      let items = filteredAssets.value.filter(a => a.category === catKey)
      if (items.length === 0 && activeFilter.value !== catKey) return null
      return { category: catKey, label: cat.label, items }
    })
    .filter((g): g is { category: string; label: string; items: PackagingAssetView[] } => g !== null && g.items.length > 0)
})

function toggleFilter(key: string) {
  activeFilter.value = key
  selectedIds.value.clear()
  if (key !== 'all' && key !== localUploadCategory.value) {
    localUploadCategory.value = key
  }
}

function toggleQuickFilter(key: string) {
  activeQuickFilter.value = activeQuickFilter.value === key ? null : key
  selectedIds.value.clear()
}

function toggleBatchMode() {
  batchMode.value = !batchMode.value
  if (!batchMode.value) {
    selectedIds.value.clear()
    closeMenu()
  }
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedIds.value.clear()
  } else {
    filteredAssets.value.forEach(a => selectedIds.value.add(a.id))
  }
}

function toggleAssetSelect(id: number) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
  }
}

const tagEditor = reactive({
  visible: false,
  assetId: 0,
  tags: '',
  mode: 'single' as 'single' | 'batch'
})

function toggleMenu(asset: PackagingAssetView, event: MouseEvent) {
  if (menuOpenId.value === asset.id) {
    menuOpenId.value = null
    return
  }
  const trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  const rect = trigger?.getBoundingClientRect()
  if (rect) {
    const menuWidth = 168
    const menuHeight = 180
    const gap = 8
    const left = Math.min(window.innerWidth - menuWidth - gap, Math.max(gap, rect.right - menuWidth))
    menuPosition.value = {
      top: Math.min(Math.max(gap, window.innerHeight - menuHeight - gap), Math.max(gap, rect.bottom + gap)),
      left
    }
  }
  menuOpenId.value = asset.id
}

const activeMenuAsset = computed(() => allAssets.value.find(a => a.id === menuOpenId.value) || null)
const menuPanelStyle = computed(() => ({
  top: `${menuPosition.value.top}px`,
  left: `${menuPosition.value.left}px`
}))

function closeMenu() {
  menuOpenId.value = null
}

function handleClickOutside(event: MouseEvent) {
  const target = event.target instanceof Element ? event.target : null
  if (target && !target.closest('.htb-more') && !target.closest('.asset-card-menu-panel')) {
    menuOpenId.value = null
  }
}

async function makeDefault(id: number) {
  const asset = props.detail?.assets.find(item => item.id === id)
  const ownerType = props.fileOwnerTypeForPackagingAsset(asset)
  if (!ownerType) return
  await fileAssetActions.setDefault(ownerType, id)
  if (props.detail) {
    props.detail.assets.forEach(a => {
      if (a.category === asset?.category && a.variantId === asset?.variantId) {
        a.defaultAsset = a.id === id
      }
    })
    if (asset?.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && !asset?.variantId) {
      props.detail.model.defaultImage = asset.fileName
    }
  }
  emit('asset-changed')
  notify('已设为主图', 'success')
}

function openTagEditor(asset: PackagingAssetView) {
  menuOpenId.value = null
  tagEditor.assetId = asset.id
  tagEditor.tags = asset.tags || ''
  tagEditor.mode = 'single'
  tagEditor.visible = true
}

function openBatchTagEditor() {
  tagEditor.mode = 'batch'
  tagEditor.tags = ''
  tagEditor.visible = true
}

async function saveAssetTags() {
  try {
    let hasTagUpdates = false
    if (tagEditor.mode === 'batch') {
      const ids = Array.from(selectedIds.value)
      for (const id of ids) {
        const asset = props.detail?.assets.find(item => item.id === id)
        const ownerType = props.fileOwnerTypeForPackagingAsset(asset)
        if (ownerType) {
          await fileAssetActions.updateTags(ownerType, id, tagEditor.tags.trim())
          if (asset) asset.tags = tagEditor.tags.trim()
          hasTagUpdates = true
        }
      }
      notify(`已更新${ids.length}个素材的标签`, 'success')
    } else {
      const asset = props.detail?.assets.find(item => item.id === tagEditor.assetId)
      const ownerType = props.fileOwnerTypeForPackagingAsset(asset)
      if (!ownerType) return
      await fileAssetActions.updateTags(ownerType, tagEditor.assetId, tagEditor.tags.trim())
      if (asset) asset.tags = tagEditor.tags.trim()
      const category = asset?.category
      const tagsNotEmpty = tagEditor.tags.trim() !== ''
      const linkedByTag = tagsNotEmpty && category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage
      notify(
        linkedByTag
          ? '标签已更新，已自动关联对应规格型号（刷新列表查看缩略图）'
          : '标签已更新',
        'success'
      )
      hasTagUpdates = true
    }
    tagEditor.visible = false
    if (hasTagUpdates) emit('asset-changed')
  } catch (error) {
    notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.updateTags, error)
  }
}

async function removeAsset(id: number) {
  const ok = await askConfirm({ title: '删除素材', message: '确定删除该素材？', danger: true })
  if (!ok) return
  const asset = props.detail?.assets.find(item => item.id === id)
  const ownerType = props.fileOwnerTypeForPackagingAsset(asset)
  if (!ownerType) return
  await fileAssetActions.remove(ownerType, id)
  if (props.detail) {
    const index = props.detail.assets.findIndex(a => a.id === id)
    if (index >= 0) props.detail.assets.splice(index, 1)
    if (
      asset?.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage &&
      !asset?.variantId &&
      props.detail.model.defaultImage === asset.fileName
    ) {
      const remainingMainImage = props.detail.assets.find(
        a => a.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && !a.variantId
      )
      props.detail.model.defaultImage = remainingMainImage?.fileName || ''
    }
  }
  selectedIds.value.delete(id)
  emit('asset-changed')
  notify('素材已删除', 'success')
}

async function batchDeleteAssets() {
  const count = selectedIds.value.size
  if (count === 0) return
  const ok = await askConfirm({ title: '批量删除', message: `确定删除选中的${count}个素材？`, danger: true })
  if (!ok) return
  const ids = Array.from(selectedIds.value)
  for (const id of ids) {
    const asset = props.detail?.assets.find(item => item.id === id)
    const ownerType = props.fileOwnerTypeForPackagingAsset(asset)
    if (ownerType) {
      await fileAssetActions.remove(ownerType, id)
    }
  }
  if (props.detail) {
    ids.forEach(id => {
      const index = props.detail!.assets.findIndex(a => a.id === id)
      if (index >= 0) props.detail!.assets.splice(index, 1)
    })
  }
  selectedIds.value.clear()
  emit('asset-changed')
  notify(`已删除${count}个素材`, 'success')
}

function handleAssetDragStart(asset: PackagingAssetView, event: DragEvent) {
  if (batchMode.value) return
  draggingAssetId.value = asset.id
  if (event.dataTransfer) {
    event.dataTransfer.setData('text/plain', String(asset.id))
    event.dataTransfer.effectAllowed = 'move'
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
}

function handleAssetDragOver(asset: PackagingAssetView) {
  if (batchMode.value) return
  if (draggingAssetId.value && draggingAssetId.value !== asset.id) {
    dragOverAssetId.value = asset.id
  }
}

function handleAssetDragEnd() {
  draggingAssetId.value = null
  dragOverAssetId.value = null
}

async function handleAssetDrop(targetAsset: PackagingAssetView) {
  if (batchMode.value) return
  const sourceId = draggingAssetId.value
  dragOverAssetId.value = null
  draggingAssetId.value = null
  if (!sourceId || sourceId === targetAsset.id) return
  if (sourceAssetCategory.value !== targetAsset.category) return
  const sourceIndex = sourceCategoryAssets.value.findIndex(a => a.id === sourceId)
  const targetIndex = sourceCategoryAssets.value.findIndex(a => a.id === targetAsset.id)
  if (sourceIndex < 0 || targetIndex < 0) return
  const [source] = sourceCategoryAssets.value.splice(sourceIndex, 1)
  sourceCategoryAssets.value.splice(targetIndex, 0, source)
  const orderItems = sourceCategoryAssets.value.map((asset, index) => ({ id: asset.id, sortOrder: index + 1 }))
  const ownerTypes = packagingFileOwnerTypeEntries(sourceCategoryAssets.value)
  if (ownerTypes.length === 1) {
    await fileAssetActions.reorder(ownerTypes[0], orderItems)
  } else {
    await reorderPackagingAssets(orderItems)
  }
  if (props.detail) {
    orderItems.forEach(item => {
      const asset = props.detail!.assets.find(a => a.id === item.id)
      if (asset) asset.sortOrder = item.sortOrder
    })
  }
  emit('asset-changed')
}

const sourceAssetCategory = computed(() => {
  const source = allAssets.value.find(a => a.id === draggingAssetId.value)
  return source?.category || ''
})

const sourceCategoryAssets = computed(() => {
  const cat = sourceAssetCategory.value
  if (!cat) return []
  return allAssets.value.filter(a => a.category === cat).sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
})

function contextPasteHandler(event: ClipboardEvent) {
  uploadTarget.value = {
    category: localUploadCategory.value,
    makeDefault: localUploadCategory.value === PACKAGING_ASSET_CATEGORY_KEYS.mainImage
  }
  handlePaste(event, { category: localUploadCategory.value })
}

onMounted(() => {
  document.addEventListener('paste', contextPasteHandler)
  window.addEventListener('click', handleClickOutside)
})
onBeforeUnmount(() => {
  document.removeEventListener('paste', contextPasteHandler)
  window.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <section class="assets-body asset-manager" v-bind="dropZoneProps">
    <div v-if="dragOver" class="asset-drop-overlay">
      <span>📥 释放文件以上传</span>
    </div>

    <div class="asset-filter-header">
      <nav class="asset-filter-tabs">
        <button
          v-for="item in visibleFilterOptions"
          :key="item.key"
          type="button"
          :class="{ active: activeFilter === item.key }"
          @click="toggleFilter(item.key)"
        >
          {{ item.label }}
          <span>{{ item.count }}</span>
        </button>

        <div class="tabs-spacer"></div>

        <template v-if="batchMode">
          <span class="batch-info-inline">{{ selectedIds.size }} / {{ visibleAssetCount }} 已选</span>
          <button type="button" @click="toggleSelectAll()">{{ isAllSelected ? '取消全选' : '全选' }}</button>
          <button type="button" :disabled="selectedIds.size === 0" @click="openBatchTagEditor">批量标签</button>
          <button type="button" class="danger" :disabled="selectedIds.size === 0" @click="batchDeleteAssets">
            删除
          </button>
          <button type="button" @click="toggleBatchMode">退出</button>
        </template>
        <template v-else>
          <button type="button" class="batch-select-btn" @click="toggleBatchMode">批量管理</button>
        </template>

        <span class="tabs-divider"></span>

        <div class="asset-upload-inline">
          <select
            :value="localUploadCategory"
            class="upload-cat-select"
            @change="localUploadCategory = ($event.target as HTMLSelectElement).value"
          >
            <option v-for="cat in PACKAGING_ASSET_CATEGORIES" :key="cat.key" :value="cat.key">
              {{ cat.label }}
            </option>
          </select>
          <label class="file-button">
            {{ uploading ? '上传中...' : uploadButtonLabel }}
            <input
              type="file"
              :accept="acceptTypes"
              :disabled="uploading"
              multiple
              @change="pickFiles({ category: localUploadCategory })"
            />
          </label>
        </div>
      </nav>

      <CategorySummaryTabs
        v-if="hasQuickFilters"
        variant="chip"
        aria-label="包材素材快捷筛选"
        :tabs="chipTabs"
        :model-value="activeQuickFilter ?? ''"
        :clearable="!!activeQuickFilter"
        @update:model-value="toggleQuickFilter"
        @clear="activeQuickFilter = null"
      />
    </div>

    <div class="asset-gallery-groups">
      <section
        v-for="group in assetGroups"
        :key="group.category"
        class="asset-gallery-section"
        :data-asset-section="group.category"
      >
        <header>
          <h3>{{ group.label }}</h3>
          <span>{{ group.items.length }}个</span>
        </header>
        <div class="asset-preview-grid">
          <article
            v-for="asset in group.items"
            :key="asset.id"
            class="asset-preview-card"
            :class="{
              document: !isImageFile(asset.fileName),
              'menu-open': menuOpenId === asset.id,
              active: asset.defaultAsset,
              selected: selectedIds.has(asset.id),
              'drag-src': draggingAssetId === asset.id,
              'drag-over': dragOverAssetId === asset.id
            }"
            :draggable="!batchMode"
            @dragstart="handleAssetDragStart(asset, $event)"
            @dragover="handleAssetDragOver(asset)"
            @dragleave="dragOverAssetId = null"
            @drop="handleAssetDrop(asset)"
            @dragend="handleAssetDragEnd"
          >
            <label v-if="batchMode" class="asset-select-hitarea" @click="toggleAssetSelect(asset.id)"></label>
            <input
              v-if="batchMode"
              type="checkbox"
              class="asset-batch-check"
              :checked="selectedIds.has(asset.id)"
              @click.stop
              @change="toggleAssetSelect(asset.id)"
            />

            <span class="asset-category-badge">{{ group.label }}</span>
            <span class="asset-ext-corner">{{ asset.fileName.split('.').pop()?.toUpperCase() || 'FILE' }}</span>
            <span v-if="asset.defaultAsset" class="asset-default-badge">默认</span>

            <button type="button" class="asset-preview-face" @click="props.previewAsset(asset)">
              <LqThumbnail
                :src="packagingAssetThumbnailUrl(asset)"
                :file-name="asset.fileName"
                object-fit="cover"
              />
            </button>

            <div class="card-hover-toolbar" @click.stop>
              <button type="button" class="htb-btn" @click="props.previewAsset(asset)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <span>预览</span>
              </button>
              <button
                v-if="isImageFile(asset.fileName) && !asset.defaultAsset"
                type="button"
                class="htb-btn"
                @click="makeDefault(asset.id)"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
                </svg>
                <span>设主图</span>
              </button>
              <button type="button" class="htb-btn" @click="openTagEditor(asset)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
                  <line x1="7" y1="7" x2="7.01" y2="7" />
                </svg>
                <span>标签</span>
              </button>
              <button type="button" class="htb-btn htb-more" @click="toggleMenu(asset, $event)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <circle cx="12" cy="12" r="1" />
                  <circle cx="19" cy="12" r="1" />
                  <circle cx="5" cy="12" r="1" />
                </svg>
                <span>更多</span>
              </button>
            </div>

            <div class="asset-card-body">
              <strong :title="asset.fileName">{{ asset.fileName }}</strong>
              <small>
                <span>{{ asset.variantId ? `规格图 · ${asset.variantId}` : '系列图' }}</span>
                <span v-if="asset.tags && asset.tags.trim()">· {{ asset.tags }}</span>
              </small>
            </div>
          </article>
        </div>
      </section>

      <div v-if="assetGroups.length === 0" class="empty-section-hint">暂无素材，点击上方按钮或拖拽文件上传</div>
    </div>

    <Teleport to="body">
      <div
        v-if="activeMenuAsset && !batchMode"
        class="asset-card-menu-panel asset-card-menu-panel--floating"
        :style="menuPanelStyle"
        @click.stop
      >
        <button type="button" @click="props.previewAsset(activeMenuAsset)">预览素材</button>
        <button type="button" :disabled="activeMenuAsset.defaultAsset" @click="makeDefault(activeMenuAsset.id)">
          设为默认
        </button>
        <button type="button" @click="openTagEditor(activeMenuAsset)">编辑标签</button>
        <div class="menu-sep"></div>
        <button type="button" class="danger" @click="removeAsset(activeMenuAsset.id)">删除素材</button>
      </div>
    </Teleport>

    <PackagingTagEditor
      :visible="tagEditor.visible"
      :tags="tagEditor.tags"
      :mode="tagEditor.mode"
      @update:visible="val => { if (!val) tagEditor.visible = false }"
      @save="saveAssetTags"
      @update:tags="tagEditor.tags = $event"
    />
  </section>
</template>

<style scoped>
@import '../../styles/asset-toolbar.css';
@import '../../styles/asset-gallery.css';
</style>
