<template>
  <div
    class="brand-assets-root"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- 拖拽上传遮罩 -->
    <div v-if="dragOver" class="brand-drop-overlay">
      <span>释放文件以上传</span>
    </div>

    <!-- 操作区：分类导航 + 视图/批量/上传 -->
    <div class="brand-toolbar">
      <nav class="brand-cat-tabs">
        <button
          v-for="cat in brandCategories"
          :key="cat.key"
          :class="{ active: activeCategory === cat.key }"
          @click="activeCategory = cat.key"
        >
          <span class="cat-icon">{{ cat.icon }}</span>
          {{ cat.label }}
          <span class="cat-count">{{ catCounts[cat.key] || 0 }}</span>
        </button>
        <div class="tabs-spacer"></div>
        <template v-if="batchMode">
          <span class="batch-info">{{ selectedIds.size }} / {{ currentAssets.length }} 已选</span>
          <button type="button" @click="toggleSelectAll">{{ isAllSelected ? '取消全选' : '全选' }}</button>
          <button type="button" :disabled="selectedIds.size === 0" @click="openBatchTagEditor">批量标签</button>
          <button
type="button"
class="primary"
:disabled="selectedIds.size === 0"
@click="openMoveModal(null)">
            移动
          </button>
          <button
type="button"
class="danger"
:disabled="selectedIds.size === 0"
@click="batchDelete">批量删除</button>
          <button type="button" @click="exitBatchMode">退出</button>
        </template>
        <template v-else>
          <button type="button" class="batch-btn" @click="enterBatchMode">批量管理</button>
          <button
type="button"
class="trash-btn"
:class="{ active: showTrash }"
@click="showTrash = !showTrash">
            回收站 {{ trashCount > 0 ? `(${trashCount})` : '' }}
          </button>
        </template>
        <span class="tabs-divider"></span>
        <label class="upload-btn">
          {{ uploading ? '上传中...' : '+ 上传文件' }}
          <input
            type="file"
            :accept="currentCatInfo?.accept || '*/*'"
            :multiple="true"
            :disabled="uploading"
            @change="handleFilesUpload"
          />
        </label>
      </nav>

      <!-- 搜索框 + 快速筛选 chips + 排序 -->
      <div class="brand-toolbar-secondary">
        <div class="search-box">
          <svg
class="search-icon"
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2">
            <circle cx="11" cy="11" r="8"></circle>
            <path d="m21 21-4.35-4.35"></path>
          </svg>
          <input
            v-model="searchQuery"
            type="text"
            placeholder="搜索文件名称..."
            class="search-input"
            @keyup.enter="handleSearch"
          />
          <button v-if="searchQuery" class="search-clear" @click="searchQuery = ''">×</button>
        </div>
        <div v-if="noTagCount > 0" class="brand-filter-chips">
          <button
            type="button"
            class="filter-chip"
            :class="{ active: activeQuickFilter === 'noTag' }"
            @click="toggleQuickFilter('noTag')"
          >
            无标签 {{ noTagCount }}
          </button>
          <button
            v-if="activeQuickFilter"
            type="button"
            class="filter-chip filter-chip-clear"
            @click="clearQuickFilters"
          >
            清除
          </button>
        </div>
        <div class="sort-control">
          <label class="sort-label">排序</label>
          <select v-model="sortMode" class="sort-select">
            <option value="custom">自定义顺序</option>
            <option value="nameAsc">名称 A→Z</option>
            <option value="nameDesc">名称 Z→A</option>
            <option value="dateNewest">最新上传</option>
            <option value="dateOldest">最早上传</option>
          </select>
        </div>
      </div>
    </div>

    <!-- 回收站视图 -->
    <template v-if="showTrash">
      <LoadingState v-if="trashLoading" text="加载回收站..." />
      <EmptyState v-else-if="trashAssets.length === 0" text="回收站为空" />
      <div v-else class="brand-grid">
        <div v-for="item in trashAssets" :key="item.id" class="brand-card deleted">
          <div class="brand-card-face" @click="openPreview(item)">
            <LqThumbnail
              :src="companyAssetPreviewUrl(item)"
              :file-name="item.fileName"
              :renderable="isImageFile(item.fileName)"
              :fallback-label="item.fileName"
              object-fit="contain"
            />
          </div>
          <div class="brand-card-body">
            <strong>{{ item.fileName }}</strong>
            <small>{{ categoryLabel(item.category) }}</small>
            <div class="trash-actions">
              <button @click="restoreItem(item)">恢复</button>
              <button class="danger" @click="permanentDeleteItem(item)">永久删除</button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 正常视图 -->
    <template v-else>
      <LoadingState v-if="assetsLoading" />

      <!-- 无资产时 -->
      <EmptyState
        v-else-if="displayAssets.length === 0"
        :text="`暂无「${currentCatInfo?.label || activeCategory}」分类的${
          activeQuickFilter === 'noTag' ? '无标签' : ''
        }资产`"
        :hint="currentCatInfo?.isImage !== false ? '拖拽图片到此处或点击上方按钮上传' : '点击上方按钮上传文件'"
      />

      <!-- 资产网格 -->
      <div v-else class="brand-grid">
        <article
          v-for="asset in displayAssets"
          :key="asset.id"
          class="brand-card"
          :class="{
            selected: batchMode && selectedIds.has(asset.id),
            'menu-open': menuOpenId === asset.id,
            'drag-over': dragOverId === asset.id
          }"
          :draggable="!batchMode"
          @dragstart.stop="handleDragStart(asset, $event)"
          @dragover.prevent.stop="handleDragOver(asset, $event)"
          @dragleave="handleDragLeave(asset)"
          @drop.prevent.stop="handleAssetDrop(asset, $event)"
          @dragend="handleDragEnd"
        >
          <!-- 批量复选框 -->
          <div v-if="batchMode" class="batch-check" @click.stop="toggleSelect(asset.id)">
            <input type="checkbox" :checked="selectedIds.has(asset.id)" />
          </div>

          <!-- 默认标签 -->
          <span v-if="asset.defaultAsset" class="default-badge">默认</span>
          <!-- 分类标签 -->
          <span class="cat-badge">{{ categoryLabel(asset.category) }}</span>

          <!-- 素材预览 -->
          <div class="brand-card-face" @click="openPreview(asset)">
            <LqThumbnail
              :src="companyAssetThumbnailUrl(asset)"
              :file-name="asset.fileName"
              object-fit="contain"
            />

            <!-- Hover 工具栏（深色纵向布局） -->
            <div v-if="!batchMode" class="hover-toolbar">
              <button
type="button"
class="htb-btn"
title="预览"
@click.stop="openPreview(asset)">
                <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <span>预览</span>
              </button>
              <button
                type="button"
                class="htb-btn"
                :class="{ active: asset.defaultAsset }"
                :title="asset.defaultAsset ? '已设为默认' : '设为默认'"
                @click.stop="setAsDefault(asset)"
              >
                <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                  <polygon
                    points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"
                  />
                </svg>
                <span>{{ asset.defaultAsset ? '已默认' : '默认' }}</span>
              </button>
              <button
type="button"
class="htb-btn"
title="标签"
@click.stop="openTagEditor(asset)">
                <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                  <path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z" />
                  <line
x1="7"
y1="7"
x2="7.01"
y2="7" />
                </svg>
                <span>标签</span>
              </button>
              <button
type="button"
class="htb-btn htb-more"
title="更多"
@click.stop="toggleMenu(asset, $event)">
                <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                  <circle cx="12" cy="5" r="1" />
                  <circle cx="12" cy="12" r="1" />
                  <circle cx="12" cy="19" r="1" />
                </svg>
                <span>更多</span>
              </button>
            </div>
          </div>

          <!-- 卡片底部信息 -->
          <div class="brand-card-body">
            <strong :title="asset.fileName">{{ asset.fileName }}</strong>
            <div v-if="tagList(asset.tags).length" class="tag-chips">
              <span v-for="tag in tagList(asset.tags)" :key="tag">{{ tag }}</span>
            </div>
            <small v-if="asset.description">{{ asset.description }}</small>
            <div v-if="asset.createdAt || asset.fileSize" class="asset-meta">
              <span v-if="asset.fileSize">{{ formatFileSize(asset.fileSize) }}</span>
              <span v-if="asset.createdAt" class="asset-date">{{ formatDate(asset.createdAt) }}</span>
            </div>
          </div>
        </article>
      </div>
    </template>

    <!-- ===== "更多" 浮动菜单 ===== -->
    <Teleport to="body">
      <div
v-if="activeMenuAsset && !batchMode"
class="more-menu-panel"
:style="menuPanelStyle"
@click.stop>
        <button type="button" @click="menuAction('preview')">预览素材</button>
        <button type="button" :disabled="activeMenuAsset.defaultAsset" @click="menuAction('setDefault')">
          设为默认
        </button>
        <button type="button" @click="menuAction('tag')">编辑标签</button>
        <div class="menu-sep"></div>
        <button type="button" @click="menuAction('move')">移动到其他分类</button>
        <button type="button" class="danger" @click="menuAction('delete')">删除素材</button>
      </div>
    </Teleport>

    <!-- ===== 移动到分类弹窗 ===== -->
    <MoveAssetDialog
      v-model="moveModalOpen"
      :target-ids="moveTargetIds"
      :category-options="moveCategoryOptions"
      :current-category-key="currentCategoryForMove"
      :target-category="moveTargetCategory"
      :saving="moveSaving"
      @select-category="moveTargetCategory = $event"
      @confirm="submitMove"
      @close="onMoveClose"
    />

    <!-- 标签编辑弹窗 -->
    <TagEditorDialog
      v-model="tagEditorOpen"
      :mode="tagEditMode"
      :target-asset="tagEditAsset"
      :selected-count="selectedIds?.size || 0"
      :current-tags="tagEditAsset?.tags || ''"
      :saving="tagSaving"
      @save="handleTagSave"
      @close="onTagEditorClose"
    />

  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
// ========== 组件导入 ==========
import LoadingState from '../common/LoadingState.vue'
import EmptyState from '../common/EmptyState.vue'
import LqThumbnail from '../common/LqThumbnail.vue'
import MoveAssetDialog from './MoveAssetDialog.vue'
import TagEditorDialog from './TagEditorDialog.vue'
// ========== API 导入 ==========
import { getAssets, getTrashedAssets, createAsset } from '../../api/company'
// ========== Composables 导入 ==========
import { notify } from '../../composables/ui/useToast'
import { useDragSort } from '../../composables/list/useDragSort'
import { useFileAssets } from '../../composables/upload/useFileAssets'
import { useFileUploadWorkflow } from '../../composables/upload/useFileUploadWorkflow'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
// ========== Utils 导入 ==========
import { isImageFile } from '../../utils/ui/fileUtils'
import {
  findFileAssetHandle,
  fileAssetPreviewUrlByOptions,
  fileAssetThumbnailUrlByOptions
} from '../../utils/ui/fileAssetUrls'
import { FILE_OWNER_TYPE_KEYS } from '../../types/fileAsset'
import { tagList } from '../../utils/common/textUtils'
import { formatFileSize } from '../../utils/common/formatUtils'
import { fmtLocaleDate } from '../../utils/common/dateUtils'
// ========== Constants 导入 ==========
import { BRAND_ASSET_CATEGORIES } from '../../constants/fields/brand-assets'
// ========== Types 导入 ==========
import type { CompanyAsset, CompanyInfo } from '../../types/company'

const props = defineProps<{
  companyInfo: CompanyInfo
  initialCategory?: string
}>()

const emit = defineEmits(['preview-asset', 'imported', 'changed'])

// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======
// 品牌资产 Tab - BrandAssets Logic
// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======

const brandCategories = BRAND_ASSET_CATEGORIES

function categoryLabel(key: string) {
  const cat = brandCategories.find(c => c.key === key)
  return cat?.label || key
}

const assetsLoading = ref(false)
const allAssets = ref<CompanyAsset[]>([])

async function loadAssets() {
  assetsLoading.value = true
  try {
    allAssets.value = await getAssets()
    await refreshCompanyFileHandles()
  } catch {
    notify('加载品牌资产失败', 'error')
    allAssets.value = []
  } finally {
    assetsLoading.value = false
  }
}

const catCounts = computed(() => {
  const counts: Record<string, number> = {}
  brandCategories.forEach(c => {
    counts[c.key] = 0
  })
  allAssets.value.forEach(a => {
    if (counts[a.category] !== undefined) counts[a.category]++
  })
  return counts
})

const activeCategory = ref('brand_logo')
watch(() => props.initialCategory, category => {
  if (category && brandCategories.some(item => item.key === category)) activeCategory.value = category
}, { immediate: true })
const sortMode = ref('custom')
const searchQuery = ref('')

const currentCatInfo = computed(() => brandCategories.find(c => c.key === activeCategory.value))

const companyFileAssets = useFileAssets({
  ownerType: FILE_OWNER_TYPE_KEYS.company,
  notify
})

function companyAssetThumbnailUrl(asset: CompanyAsset | null | undefined) {
  if (!asset) return ''
  const handle = companyFileHandle(asset.fileName, asset.category)
  return fileAssetThumbnailUrlByOptions({
    ownerType: FILE_OWNER_TYPE_KEYS.company,
    assetId: handle?.assetId || asset.id,
    category: 'company',
    fileName: asset.fileName,
    modelId: null
  })
}

async function refreshCompanyFileHandles() {
  try {
    await companyFileAssets.load()
  } catch {
    // Legacy file URLs remain available if unified handles cannot be loaded.
  }
}

function companyFileHandle(fileName: string | null | undefined, category?: string | null) {
  if (!fileName) return null
  return (
    companyFileAssets.items.value.find(
      handle => handle.fileName === fileName && (!category || handle.category === category)
    ) || findFileAssetHandle(companyFileAssets.items.value, fileName)
  )
}

function companyAssetPreviewUrl(asset: CompanyAsset | null | undefined) {
  const handle = companyFileHandle(asset?.fileName, asset?.category)
  return (
    companyFileAssets.previewUrl(handle) ||
    fileAssetPreviewUrlByOptions({
      ownerType: FILE_OWNER_TYPE_KEYS.company,
      assetId: asset?.id,
      category: 'company',
      fileName: asset?.fileName,
      modelId: null
    })
  )
}

const currentAssets = computed(() => {
  let list = allAssets.value.filter(a => a.category === activeCategory.value)
  switch (sortMode.value) {
    case 'nameAsc':
      list = [...list].sort((a, b) => (a.fileName || '').localeCompare(b.fileName || '', 'zh-CN'))
      break
    case 'nameDesc':
      list = [...list].sort((a, b) => (b.fileName || '').localeCompare(a.fileName || '', 'zh-CN'))
      break
    case 'dateNewest':
      list = [...list].sort((a, b) => +new Date(b.createdAt || 0) - +new Date(a.createdAt || 0))
      break
    case 'dateOldest':
      list = [...list].sort((a, b) => +new Date(a.createdAt || 0) - +new Date(b.createdAt || 0))
      break
    default:
      list = [...list].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  }
  return list
})

const activeQuickFilter = ref<string | null>(null)

const noTagCount = computed(() => currentAssets.value.filter(a => !tagList(a.tags).length).length)

function toggleQuickFilter(key: string) {
  activeQuickFilter.value = activeQuickFilter.value === key ? null : key
  exitBatchMode()
}

function clearQuickFilters() {
  activeQuickFilter.value = null
  exitBatchMode()
}

const displayAssets = computed(() => {
  let list = currentAssets.value
  if (activeQuickFilter.value === 'noTag') {
    list = list.filter(a => !tagList(a.tags).length)
  }
  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase().trim()
    list = list.filter(
      a =>
        (a.fileName || '').toLowerCase().includes(query) ||
        tagList(a.tags).some(tag => tag.toLowerCase().includes(query))
    )
  }
  return list
})

function handleSearch() {
  exitBatchMode()
}

// ========== 文件上传 ==========
let uploadedAssetCount = 0

const uploadWorkflow = useFileUploadWorkflow({
  notify,
  category: 'company',
  accept: computed(() => currentCatInfo.value?.accept || '*/*'),
  multiple: true,
  disabled: computed(() => Boolean(draggedId.value)),
  registerUploaded: async ({ fileName }) => {
    try {
      const asset = await createAsset({
        category: activeCategory.value,
        fileName,
        tags: '',
        defaultAsset: false,
        sortOrder: currentAssets.value.length + uploadedAssetCount
      })
      uploadedAssetCount++
      return asset
    } catch (err) {
      notify.errorWithDetail(`"${fileName}" 保存失败`, err)
      return null
    }
  },
  onBatchComplete: async () => {
    if (uploadedAssetCount > 0) {
      notify(`成功上传 ${uploadedAssetCount} 个文件`, 'success')
      await loadAssets()
      emit('changed')
      emit('imported')
    }
    uploadedAssetCount = 0
  },
  onRejected: (rejected: File[]) => {
    const catInfo = currentCatInfo.value
    if (rejected.length && catInfo?.label) {
      notify(`${rejected.length} 个文件类型不允许上传到「${catInfo.label}」分类`, 'warning')
    }
  }
})

const { uploading, dragOver } = uploadWorkflow

function handleFilesUpload(event: Event) {
  return uploadWorkflow.handleInputChange(event)
}

function onDragEnter(event: DragEvent) {
  if (draggedId.value) return
  uploadWorkflow.onDragEnter(event)
}

function onDragOver(event: DragEvent) {
  if (draggedId.value) return
  uploadWorkflow.onDragOver(event)
}

function onDragLeave(event: DragEvent) {
  if (draggedId.value) return
  uploadWorkflow.onDragLeave(event)
}

function onDrop(event: DragEvent) {
  if (draggedId.value) return
  return uploadWorkflow.onDrop(event)
}

// ========== 批量模式 ==========
const batchMode = ref(false)
const selectedIds = ref<Set<number>>(new Set())

function enterBatchMode() {
  batchMode.value = true
  selectedIds.value = new Set()
}
function exitBatchMode() {
  batchMode.value = false
  selectedIds.value = new Set()
}

function toggleSelect(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  if (isAllSelected.value) selectedIds.value = new Set()
  else selectedIds.value = new Set(displayAssets.value.map(a => a.id))
}

const isAllSelected = computed(
  () => displayAssets.value.length > 0 && displayAssets.value.every(a => selectedIds.value.has(a.id))
)

// ========== 排序拖拽 ==========
const {
  draggedId,
  dragOverId,
  handleDragStart,
  handleDragOver,
  handleDragLeave,
  handleDrop: handleAssetDrop,
  handleDragEnd
} = useDragSort({
  items: currentAssets,
  canDrag: computed(() => !batchMode.value && sortMode.value === 'custom'),
  onReorder: async ({ items }) => {
    try {
      await companyFileAssets.reorder(items)
      await loadAssets()
    } catch {
      notify('排序保存失败', 'error')
    }
  }
})

// ========== 设为默认 ==========
async function setAsDefault(asset: CompanyAsset) {
  try {
    await companyFileAssets.setDefault(asset.id)
    notify('已设为默认素材', 'success')
    loadAssets()
    emit('changed')
  } catch {
    notify('操作失败', 'error')
  }
}

// ========== "更多" 浮动菜单 ==========
const menuOpenId = ref<number | null>(null)
const menuPosition = ref({ top: 0, left: 0 })

const activeMenuAsset = computed(() => allAssets.value.find(a => a.id === menuOpenId.value) || null)

const menuPanelStyle = computed(() => ({
  top: `${menuPosition.value.top}px`,
  left: `${menuPosition.value.left}px`
}))

function toggleMenu(asset: CompanyAsset, event: MouseEvent) {
  if (menuOpenId.value === asset.id) {
    menuOpenId.value = null
    return
  }
  const rect = (event?.currentTarget as HTMLElement | null)?.getBoundingClientRect?.()
  if (rect) {
    const menuW = 170,
      menuH = 230,
      gap = 6
    menuPosition.value = {
      top: Math.min(window.innerHeight - menuH - gap, Math.max(gap, rect.bottom + gap)),
      left: Math.min(window.innerWidth - menuW - gap, Math.max(gap, rect.right - menuW))
    }
  }
  menuOpenId.value = asset.id
}

function closeMenu() {
  menuOpenId.value = null
}

function menuAction(action: 'preview' | 'setDefault' | 'tag' | 'move' | 'delete') {
  const asset = activeMenuAsset.value
  if (!asset) return
  closeMenu()
  switch (action) {
    case 'preview':
      openPreview(asset)
      break
    case 'setDefault':
      setAsDefault(asset)
      break
    case 'tag':
      openTagEditor(asset)
      break
    case 'move':
      openMoveModal(asset)
      break
    case 'delete':
      deleteItem(asset)
      break
  }
}

function handleClickOutside(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  if (target && !target.closest('.htb-more') && !target.closest('.more-menu-panel')) {
    closeMenu()
  }
}

// ========== 移动分类（BrandAssetMoveModal 内联） ==========
const moveModalOpen = ref(false)
const moveTargetIds = ref<number[] | null>(null)
const moveTargetCategory = ref<string | null>(null)
const moveSaving = ref(false)

const currentCategoryForMove = computed(() => {
  if (!moveTargetIds.value || moveTargetIds.value.length === 0) return null
  if (moveTargetIds.value.length === 1) {
    const asset = allAssets.value.find(a => a.id === moveTargetIds.value![0])
    return asset?.category || null
  }
  return null
})

const moveCategoryOptions = computed(() =>
  brandCategories.filter(c => c.key !== currentCategoryForMove.value || (moveTargetIds.value?.length ?? 0) > 1)
)

function openMoveModal(asset?: CompanyAsset | null) {
  if (asset) {
    moveTargetIds.value = [asset.id]
  } else if (batchMode.value && selectedIds.value.size > 0) {
    moveTargetIds.value = [...selectedIds.value]
  } else {
    return
  }
  moveModalOpen.value = true
}

async function submitMove() {
  if (!moveTargetCategory.value || !moveTargetIds.value?.length) return
  moveSaving.value = true
  try {
    if (moveTargetIds.value.length === 1) {
      await companyFileAssets.moveCategory(moveTargetIds.value[0], moveTargetCategory.value)
    } else {
      await companyFileAssets.batchMoveCategory(moveTargetIds.value, moveTargetCategory.value)
    }
    notify(`已移动 ${moveTargetIds.value.length} 个资产`, 'success')
    moveModalOpen.value = false
    onMoveCompleted()
    moveTargetCategory.value = null
  } catch (err) {
    notify.errorWithDetail('移动失败', err)
  } finally {
    moveSaving.value = false
  }
}

function onMoveCompleted() {
  exitBatchMode()
  loadAssets()
  emit('changed')
}

function onMoveClose() {
  moveTargetIds.value = null
  moveTargetCategory.value = null
}

// ========== 标签编辑（BrandAssetTagEditor 内联） ==========
const tagEditorOpen = ref(false)
const tagEditMode = ref<'single' | 'batch'>('single')
const tagEditAsset = ref<CompanyAsset | null>(null)
const tagSaving = ref(false)

function openTagEditor(asset: CompanyAsset) {
  tagEditMode.value = 'single'
  tagEditAsset.value = asset
  tagEditorOpen.value = true
}

function openBatchTagEditor() {
  tagEditMode.value = 'batch'
  tagEditAsset.value = null
  tagEditorOpen.value = true
}

async function handleTagSave(tags: string) {
  tagSaving.value = true
  try {
    if (tagEditMode.value === 'batch') {
      await companyFileAssets.batchUpdateTags([...selectedIds.value], tags, true)
      notify(`已为 ${selectedIds.value.size} 个素材设置标签`, 'success')
    } else if (tagEditAsset.value) {
      await companyFileAssets.updateTags(tagEditAsset.value.id, tags)
      notify('标签已更新', 'success')
    }
    tagEditorOpen.value = false
    onTagSaved()
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    tagSaving.value = false
  }
}

function onTagSaved() {
  exitBatchMode()
  loadAssets()
}

function onTagEditorClose() {
  tagEditAsset.value = null
}

// ========== 删除（软删除到回收站）==========
async function deleteItem(asset: CompanyAsset) {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确定要将「${asset.fileName}」移入回收站吗？30 天内可恢复。`,
    danger: true
  })
  if (!ok) return

  try {
    await companyFileAssets.remove(asset.id)
    notify('资产已移入回收站', 'success')
    loadAssets()
    emit('changed')
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

async function batchDelete() {
  const ids = [...selectedIds.value]
  if (!ids.length) return

  const ok = await askConfirm({
    title: `确认删除 ${ids.length} 个资产？`,
    message: `选中的 ${ids.length} 个资产将被移入回收站，30 天内可恢复。`,
    danger: true
  })
  if (!ok) return

  try {
    await companyFileAssets.batchRemove(ids)
    notify(`已删除 ${ids.length} 个资产`, 'success')
    exitBatchMode()
    loadAssets()
    emit('changed')
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

// ========== 回收站 ==========
const showTrash = ref(false)
const trashAssets = ref<CompanyAsset[]>([])
const trashCount = ref(0)
const trashLoading = ref(false)

async function loadTrash() {
  trashLoading.value = true
  try {
    trashAssets.value = await getTrashedAssets()
    trashCount.value = trashAssets.value.length
  } catch {
    trashAssets.value = []
    trashCount.value = 0
  } finally {
    trashLoading.value = false
  }
}

async function restoreItem(item: CompanyAsset) {
  try {
    await companyFileAssets.restore(item.id)
    notify('资产已恢复', 'success')
    loadTrash()
    loadAssets()
    emit('changed')
  } catch {
    notify('恢复失败', 'error')
  }
}

async function permanentDeleteItem(item: CompanyAsset) {
  const ok = await askConfirm({
    title: '永久删除',
    message: `确定要永久删除「${item.fileName}」吗？此操作不可撤销。`,
    danger: true
  })
  if (!ok) return

  try {
    await companyFileAssets.remove(item.id, { permanent: true, deletePhysical: true })
    notify('已永久删除', 'success')
    loadTrash()
    emit('changed')
  } catch {
    notify('删除失败', 'error')
  }
}

watch(showTrash, val => {
  if (val) loadTrash()
})

watch(activeCategory, () => {
  exitBatchMode()
  showTrash.value = false
  activeQuickFilter.value = null
})

// ========== 预览 ==========
function openPreview(asset: CompanyAsset) {
  emit('preview-asset', {
    fileName: asset.fileName,
    ownerType: FILE_OWNER_TYPE_KEYS.company,
    assetId: asset.id,
    id: asset.id,
    category: 'company'
  })
}

function handleEscKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    closeMenu()
  }
}

function formatDate(dateStr?: string | null): string {
  return fmtLocaleDate(dateStr)
}

onMounted(async () => {
  await loadAssets()
  loadTrash()
  uploadWorkflow.activatePaste()
  window.addEventListener('click', handleClickOutside)
  window.addEventListener('resize', closeMenu)
  window.addEventListener('scroll', closeMenu, true)
  document.addEventListener('keydown', handleEscKey)
})

onBeforeUnmount(() => {
  uploadWorkflow.deactivatePaste()
  window.removeEventListener('click', handleClickOutside)
  window.removeEventListener('resize', closeMenu)
  window.removeEventListener('scroll', closeMenu, true)
  document.removeEventListener('keydown', handleEscKey)
})
</script>

<style scoped>
/* ====== ====== ====== ====== ====== ====== ====== ====== ====== ====== */
/* 品牌资产 Tab 样式 */
/* ====== ====== ====== ====== ====== ====== ====== ====== ====== ====== */

.brand-assets-root {
  position: relative;
  padding: 8px 0;
  min-height: 300px;
}

/* ========== 拖拽遮罩 ========== */
.brand-drop-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(3, 105, 161, 0.08);
  border: 3px dashed var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: var(--color-primary);
  font-weight: 600;
}

/* ========== 工具栏 ========== */
.brand-toolbar {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--color-page-bg);
  padding-bottom: 12px;
  margin-bottom: 4px;
}

.brand-cat-tabs {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  flex-wrap: wrap;
  padding: 8px 12px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
}

.brand-cat-tabs > button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.brand-cat-tabs > button:hover {
  background: var(--color-surface-hover);
  color: var(--color-text-primary);
}

.brand-cat-tabs > button.active {
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-color: var(--color-primary-border);
  font-weight: 600;
}

.cat-icon {
  font-size: 16px;
  line-height: 1;
}

.cat-count {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-faint);
  font-weight: 500;
}

button.active .cat-count {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

.tabs-spacer {
  flex: 1;
}

.tabs-divider {
  width: 1px;
  height: 24px;
  background: var(--color-border-light);
  margin: 0 6px;
}

.batch-info {
  font-size: 13px;
  color: var(--color-primary);
  font-weight: 600;
}

.brand-cat-tabs > button.primary {
  background: var(--color-primary) !important;
  color: var(--color-text-on-primary) !important;
  border-color: var(--color-primary) !important;
}
.brand-cat-tabs > button.danger {
  color: var(--color-danger);
}
.brand-cat-tabs > button.danger:hover {
  background: var(--color-danger-bg);
}
.brand-cat-tabs > button.danger:disabled,
.brand-cat-tabs > button.primary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.batch-btn,
.trash-btn {
  padding: 6px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.batch-btn:hover,
.trash-btn:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.trash-btn.active {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
  border-color: var(--color-warning-border);
}

.upload-btn {
  display: inline-flex;
  align-items: center;
  padding: 7px 16px;
  border: 1px dashed var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.upload-btn:hover {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}
.upload-btn input {
  display: none;
}

/* ========== 工具栏次行（筛选+排序） ========== */
.brand-toolbar-secondary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  gap: var(--space-control-gap-wide);
}

.search-box {
  display: flex;
  align-items: center;
  flex: 1;
  max-width: 280px;
  position: relative;
}

.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--color-text-faint);
}

.search-input {
  width: 100%;
  padding: 6px 32px 6px 32px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 13px;
  transition: all var(--transition-fast);
}

.search-input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px var(--color-primary-ring);
}

.search-input::placeholder {
  color: var(--color-text-faint);
}

.search-clear {
  position: absolute;
  right: 8px;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: var(--radius-pill);
  background: var(--color-border-light);
  color: var(--color-text-secondary);
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}

.search-clear:hover {
  background: var(--color-border);
  color: var(--color-text-primary);
}

.sort-control {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.sort-label {
  font-size: 12px;
  color: var(--color-text-faint);
  white-space: nowrap;
}

.sort-select {
  padding: 4px 28px 4px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 12px;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%2394a3b8' fill='none' stroke-width='1.5' stroke-linecap='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 8px center;
  transition: border-color var(--transition-fast);
}

.sort-select:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px var(--color-primary-ring);
}
.brand-filter-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  flex-wrap: wrap;
}

.filter-chip {
  padding: 3px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.filter-chip:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.filter-chip.active {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border-color: var(--color-primary);
}

.filter-chip-clear {
  color: var(--color-danger-accent);
  border-color: var(--color-danger-border);
}

.filter-chip-clear:hover {
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
}

/* ========== 资产网格 ========== */
.brand-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
}

/* ========== 资产卡片 ========== */
.brand-card {
  position: relative;
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--color-surface);
  transition: all var(--transition-fast);
}

.brand-card:hover {
  border-color: var(--color-primary);
  box-shadow: 0 2px 12px var(--color-primary-ring);
}

.brand-card.selected {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px var(--color-primary-ring);
  background: var(--color-primary-bg);
}

.brand-card.menu-open {
  border-color: var(--color-primary-border);
  box-shadow: 0 8px 24px var(--shadow-color-medium);
}

.brand-card.drag-over {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
  transform: scale(1.02);
}

.brand-card.deleted {
  opacity: 0.6;
}

.brand-card.deleted:hover {
  opacity: 0.85;
}

/* 批量复选框 */
.batch-check {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 5;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.brand-card.selected .batch-check {
  background: var(--color-primary);
  border-color: var(--color-primary);
}

.batch-check input {
  appearance: none;
  width: 12px;
  height: 12px;
  border: none;
  margin: 0;
  background: transparent;
}

.brand-card.selected .batch-check input::after {
  content: '';
  display: block;
  width: 6px;
  height: 3px;
  border-left: 2px solid var(--color-text-on-primary);
  border-bottom: 2px solid var(--color-text-on-primary);
  transform: rotate(-45deg);
  margin: 3px 0 0 2px;
}

/* 默认标签 */
.default-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 5;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: 11px;
  font-weight: 600;
}

.cat-badge {
  position: absolute;
  bottom: 60px;
  left: 8px;
  z-index: 5;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
  background: rgba(0, 0, 0, 0.55);
  color: var(--color-text-on-primary);
  font-size: 11px;
  font-weight: 500;
}

/* 预览面 */
.brand-card-face {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  overflow: hidden;
  background: var(--color-surface-alt);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  /* img 内边距透传给 LqThumbnail（原 .brand-card-face img { padding: 4px }） */
  --lq-thumb-pad: 4px;
}

/* Hover 工具栏 — 深色纵向布局 */
.hover-toolbar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: stretch;
  justify-content: stretch;
  background: var(--color-overlay-dark);
  backdrop-filter: blur(4px);
  opacity: 0;
  transform: translateY(100%);
  transition: all var(--transition-fast);
}

.brand-card:hover .hover-toolbar {
  opacity: 1;
  transform: translateY(0);
}

.hover-toolbar .htb-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 7px 2px 6px;
  border: none;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  background: transparent;
  color: rgba(255, 255, 255, 0.88);
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.12s,
    color 0.12s;
  line-height: 1;
}

.hover-toolbar .htb-btn:last-child {
  border-right: none;
}
.hover-toolbar .htb-btn:hover {
  background: rgba(255, 255, 255, 0.14);
  color: var(--color-text-on-primary);
}
.hover-toolbar .htb-btn.active {
  color: var(--color-primary-light);
}
.hover-toolbar .htb-btn.active:hover {
  background: rgba(94, 234, 212, 0.15);
}
.hover-toolbar .htb-btn svg {
  pointer-events: none;
}

/* ===== "更多" 浮动菜单 ===== */
.more-menu-panel {
  position: fixed;
  z-index: 10040;
  display: grid;
  gap: 2px;
  width: 170px;
  padding: 6px;
  background: var(--color-text-on-primary);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  box-shadow: 0 12px 28px var(--shadow-color-strong);
}

.more-menu-panel button {
  width: 100%;
  padding: 7px 10px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  text-align: left;
  font-size: 12px;
  line-height: 1.3;
  color: var(--color-text-strong);
  cursor: pointer;
  transition: background 0.1s;
}

.more-menu-panel button:hover {
  background: var(--color-surface-hover);
}
.more-menu-panel button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.more-menu-panel button.danger {
  color: var(--color-danger);
}
.more-menu-panel button.danger:hover {
  background: var(--color-danger-bg-soft);
}

.menu-sep {
  height: 1px;
  background: var(--color-border-light);
  margin: 4px 4px;
}

/* 卡片底部 */
.brand-card-body {
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.brand-card-body strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-heading);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-card-body small {
  font-size: 11px;
  color: var(--color-text-faint);
}

.tag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight);
}

.tag-chips span {
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 500;
}

.asset-meta {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  margin-top: 2px;
}

.asset-meta span {
  font-size: 11px;
  color: var(--color-text-faint);
}

.asset-date {
  padding-left: 8px;
  border-left: 1px solid var(--color-border-light);
}

/* 回收站操作 */
.trash-actions {
  display: flex;
  gap: var(--space-control-gap);
  margin-top: 4px;
}

.trash-actions button {
  padding: 4px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.trash-actions button:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}
.trash-actions button.danger:hover {
  color: var(--color-danger);
  border-color: var(--color-danger);
}

@media (max-width: 800px) {
  .brand-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
  }
  .brand-cat-tabs {
    flex-direction: column;
    align-items: stretch;
  }
  .tabs-spacer,
  .tabs-divider {
    display: none;
  }
}
</style>
