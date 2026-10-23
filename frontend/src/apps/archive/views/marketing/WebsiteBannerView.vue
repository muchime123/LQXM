<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchBanners,
  fetchBannerById,
  deleteBanner,
  toggleBannerEnabled,
  restoreBanner
} from '@/api/website/banners'
import { BANNER_POSITION_LABELS } from '@/types/website'
import type {
  WebsiteBannerSummary,
  WebsiteBannerDetail,
  BannerPosition,
  WebsiteBannerQuery
} from '@/types/website'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import BannerFormDialog from '@/components/website/BannerFormDialog.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import { useListPagination } from '@/composables/list/useListPagination'

const router = useRouter()
const loading = ref(false)
const banners = ref<WebsiteBannerSummary[]>([])
// 后端 /api/admin/website/banners 的 page 为 0 基（Spring Page），与 useListPagination 一致
const pagination = useListPagination({ size: 20 })

const searchKeyword = ref('')
const filterPosition = ref<BannerPosition | ''>('SIDE')
const filterEnabled = ref<boolean | ''>('')

const dialogVisible = ref(false)
const dialogSaving = ref(false)
const editing = ref<WebsiteBannerDetail | null>(null)

const positionTabs = computed(() =>
  (Object.keys(BANNER_POSITION_LABELS) as BannerPosition[]).map(key => ({
    key,
    label: BANNER_POSITION_LABELS[key]
  }))
)

async function loadData() {
  loading.value = true
  try {
    const params: WebsiteBannerQuery = {
      keyword: searchKeyword.value || undefined,
      position: filterPosition.value || undefined,
      websiteEnabled: filterEnabled.value === '' ? undefined : filterEnabled.value,
      ...pagination.getPageParams()
    }
    const result = await fetchBanners(params)
    banners.value = result.content
    pagination.updateFromResponse(result)
  } catch (err) {
    notify.errorWithDetail('加载失败', err)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.resetPage()
  loadData()
}

function handleReset() {
  searchKeyword.value = ''
  filterPosition.value = 'SIDE'
  filterEnabled.value = ''
  pagination.resetPage()
  loadData()
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

async function openEdit(item: WebsiteBannerSummary) {
  try {
    const detail = await fetchBannerById(item.id)
    editing.value = detail
  } catch (err) {
    notify.errorWithDetail('加载Banner详情失败', err)
    return
  }
  dialogVisible.value = true
}

function handleSave() {
  loadData()
}

async function handleDelete(item: WebsiteBannerSummary) {
  const ok = await askConfirm({
    title: '删除确认',
    message: `确定删除「${item.title}」？此操作可在回收站恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await deleteBanner(item.id)
    notify('已删除', 'success')
    loadData()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

async function handleRestore(item: WebsiteBannerSummary) {
  try {
    await restoreBanner(item.id)
    notify('已恢复', 'success')
    loadData()
  } catch (err) {
    notify.errorWithDetail('恢复失败', err)
  }
}

async function toggleEnabled(item: WebsiteBannerSummary) {
  try {
    await toggleBannerEnabled(item.id)
    notify(`${item.title} 已${item.websiteEnabled ? '取消发布' : '发布到官网'}`, 'success')
    loadData()
  } catch (err) {
    notify.errorWithDetail('操作失败', err)
  }
}

await loadData()
</script>

<template>
  <div class="banner-manage">
    <div class="page-header">
      <h2>官网内容管理 · Banner 管理</h2>
      <p class="page-desc">管理官网侧边栏（SIDE）与公告条（ANNOUNCEMENT）Banner。首页 HERO 轮播请在「可视化编辑器」中编辑。</p>
      <el-button class="hero-link" link type="primary" @click="router.push('/website/visual-editor')">前往可视化编辑器编辑 HERO →</el-button>
    </div>

    <div class="filter-bar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索标题..."
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select
        v-model="filterPosition"
        placeholder="位置"
        clearable
        style="width: 140px"
      >
        <el-option
          v-for="tab in positionTabs"
          :key="tab.key"
          :label="tab.label"
          :value="tab.key"
        />
      </el-select>

      <el-select
        v-model="filterEnabled"
        placeholder="状态"
        clearable
        style="width: 120px"
      >
        <el-option label="已发布" :value="true" />
        <el-option label="已隐藏" :value="false" />
      </el-select>

      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>

      <div class="filter-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新增Banner
        </el-button>
      </div>
    </div>

    <div class="banner-table">
      <el-table
        v-loading="loading"
        :data="banners"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="position" label="位置" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small">
              {{ BANNER_POSITION_LABELS[row.position as BannerPosition] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200">
          <template #default="{ row }">
            <div>
              <span class="title-text">{{ row.title }}</span>
              <el-tag v-if="row.deleted" size="small" type="danger" effect="plain">已删除</el-tag>
              <el-tag v-else-if="row.websiteEnabled === false" size="small" type="info" effect="plain">官网隐藏</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="subtitle" label="副标题" min-width="150" show-overflow-tooltip />
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column label="有效期" width="200" align="center">
          <template #default="{ row }">
            <span v-if="row.startDate || row.endDate">
              {{ row.startDate || '—' }} ~ {{ row.endDate || '永久' }}
            </span>
            <span v-else class="empty-val">永久有效</span>
          </template>
        </el-table-column>
        <el-table-column label="官网可见" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              v-if="!row.deleted"
              :model-value="row.websiteEnabled"
              @change="toggleEnabled(row)"
            />
            <span v-else class="deleted-tag">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="!row.deleted" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-else link type="success" size="small" @click="handleRestore(row)">恢复</el-button>
            <el-button v-if="!row.deleted" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无Banner数据" :image-size="80" />
        </template>
      </el-table>

      <div v-if="pagination.pageInfo.total > 0" class="pagination-wrapper">
        <LqPagination
          :pagination="pagination"
          :page-sizes="[20, 50, 100]"
          @change="loadData"
        />
      </div>
    </div>

    <BannerFormDialog
      v-if="dialogVisible"
      :banner="editing"
      :saving="dialogSaving"
      default-position="SIDE"
      @update:banner="dialogVisible = $event"
      @save="handleSave"
    />
  </div>
</template>

<style scoped>
.banner-manage {
  padding: 0;
}

.page-header {
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0 0 4px 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.page-desc {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.hero-link {
  margin-top: 8px;
  padding: 0;
  font-size: 12px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.filter-actions {
  margin-left: auto;
}

.banner-table {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  overflow: hidden;
}

.title-text {
  font-weight: 500;
  margin-right: 8px;
}

.empty-val {
  color: var(--color-text-tertiary);
}

.deleted-tag {
  color: var(--color-text-tertiary);
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px;
}
</style>
