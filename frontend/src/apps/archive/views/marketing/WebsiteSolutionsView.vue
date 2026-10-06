<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, Refresh, View } from '@element-plus/icons-vue'
import { fetchSolutionsGrouped, deleteSolution, batchUpdateSolutionVisible } from '@/api/website/solutions'
import { SOLUTION_SECTION_LABELS, VISIBLE_SOLUTION_SECTIONS } from '@/types/website'
import type { WebsiteSolution, SolutionSection, SolutionSectionGroup } from '@/types/website'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import SolutionFormDialog from '@/components/website/SolutionFormDialog.vue'
import WebsitePublishStatus from '@/components/website/WebsitePublishStatus.vue'
import { openCorporateWebsite } from '@/utils/websiteUrl'

const loading = ref(false)
const route = useRoute()
const sections = ref<SolutionSectionGroup[]>([])
const dialogVisible = ref(false)
const dialogSaving = ref(false)
const editing = ref<WebsiteSolution | null>(null)
const activeSections = ref<SolutionSection[]>(['ARTICLE', 'INDUSTRY'])

const sectionTabs = computed(() =>
  VISIBLE_SOLUTION_SECTIONS.map(key => ({
    key,
    label: SOLUTION_SECTION_LABELS[key]
  }))
)

const sectionMeta: Record<SolutionSection, { hint: string; icon: string }> = {
  SOLUTION: {
    hint: '官网 Solutions 页的四大能力方向',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>'
  },
  INDUSTRY: {
    hint: '按行业拆分的应用场景与解决方案',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><rect x="4" y="2" width="16" height="20" rx="2"/><path d="M9 22v-4h6v4"/><path d="M8 6h.01M12 6h.01M16 6h.01M8 10h.01M12 10h.01M16 10h.01M8 14h.01M12 14h.01M16 14h.01"/></svg>'
  },
  PROCESS: {
    hint: '从咨询到交付的服务流程',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M8 6h13M8 12h13M8 18h13"/><path d="M3 6h.01M3 12h.01M3 18h.01"/></svg>'
  },
  ARTICLE: {
    hint: '支持独立详情页发布的技术文章',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="M8 13h8M8 17h8M8 9h2"/></svg>'
  }
}

const stats = computed(() => {
  const all = sections.value.flatMap(g => g.items)
  return {
    total: all.length,
    published: all.filter(i => i.websiteEnabled).length,
    hidden: all.filter(i => !i.websiteEnabled).length,
    articles: getSectionItems('ARTICLE').length
  }
})

function getSectionItems(section: SolutionSection): WebsiteSolution[] {
  const group = sections.value.find(s => s.section === section)
  return group?.items || []
}
function solutionEligibility(item: WebsiteSolution) {
  if (!item.title?.trim()) return { eligible: false, reason: '缺少标题' }
  if (item.section === 'ARTICLE' && !item.slug?.trim()) return { eligible: false, reason: '缺少文章 URL 标识' }
  if (item.section === 'INDUSTRY' && !item.itemKey?.trim()) return { eligible: false, reason: '缺少场景 Key' }
  if ((item.section === 'ARTICLE' || item.section === 'INDUSTRY') && !item.coverImageUrl) return { eligible: false, reason: '缺少封面图片' }
  return { eligible: true, reason: item.websiteEnabled ? '官网当前可见' : '条件满足，可发布' }
}

async function loadData() {
  loading.value = true
  try {
    sections.value = await fetchSolutionsGrouped()
  } catch (err) {
    notify.errorWithDetail('加载失败', err)
  } finally {
    loading.value = false
  }
}

function openCreate(section: SolutionSection) {
  editing.value = null
  dialogVisible.value = true
  ;(window as unknown as Record<string, unknown>).__solution_default_section = section
}

function openEdit(item: WebsiteSolution) {
  editing.value = item
  dialogVisible.value = true
}

function handleSave() {
  loadData()
}

async function handleDelete(item: WebsiteSolution) {
  const ok = await askConfirm({
    title: '删除确认',
    message: `确定删除「${item.title}」？此操作不可恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await deleteSolution(item.id)
    notify('已删除', 'success')
    loadData()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

async function toggleVisible(item: WebsiteSolution, enabled: boolean) {
  const eligibility = solutionEligibility(item)
  if (enabled && !eligibility.eligible) {
    item.websiteEnabled = false
    notify.warning(eligibility.reason)
    return
  }
  const confirmed = await askConfirm({
    title: enabled ? '确认发布解决方案' : '确认取消官网展示',
    message: enabled
      ? `「${item.title}」将出现在官网解决方案内容中，请确认标题、封面和正文已经适合公开。`
      : `「${item.title}」将从官网隐藏，但后台文章和图片不会删除。`,
    confirmText: enabled ? '确认发布' : '取消展示',
    cancelText: '返回检查',
    danger: !enabled
  })
  if (!confirmed) {
    item.websiteEnabled = !enabled
    return
  }
  try {
    await batchUpdateSolutionVisible([item.id], enabled)
    notify(`${item.title} 已${enabled ? '发布到官网' : '取消发布'}`, 'success')
    loadData()
  } catch (err) {
    item.websiteEnabled = !enabled
    notify.errorWithDetail('操作失败', err)
  }
}

function openWebsite() {
  openCorporateWebsite('/solutions')
}

await loadData()
const requestedId = Number(route.query.id)
const requested = Number.isFinite(requestedId)
  ? sections.value.flatMap(group => group.items).find(item => item.id === requestedId)
  : undefined
if (requested) openEdit(requested)
</script>

<template>
  <div class="solutions-manage">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网内容管理</p>
        <h1 class="page-header__title">方案内容</h1>
        <p class="page-header__desc">
          管理官网应用场景与方案文章。方案文章支持独立详情页发布。
        </p>
      </div>
      <div class="page-header__actions">
        <el-button link :icon="View" @click="openWebsite">预览官网</el-button>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>
    </header>

    <div class="overview-strip">
      <MetricGrid :columns="4" density="compact">
        <MetricCard title="全部内容" :value="stats.total" tone="primary" hint="场景+文章" />
        <MetricCard title="已发布到官网" :value="stats.published" tone="success" hint="官网可见" />
        <MetricCard title="官网隐藏" :value="stats.hidden" tone="warning" hint="未在官网展示" />
        <MetricCard title="方案文章" :value="stats.articles" tone="default" hint="含独立详情页" />
      </MetricGrid>
    </div>

    <div class="sections-accordion">
      <el-collapse v-model="activeSections" multiple>
        <el-collapse-item v-for="tab in sectionTabs" :key="tab.key" :name="tab.key">
          <template #title>
            <div class="section-head">
              <!-- eslint-disable-next-line vue/no-v-html -->
              <span class="section-head__icon" v-html="sectionMeta[tab.key].icon"></span>
              <span class="section-head__label">{{ tab.label }}</span>
              <span class="section-head__hint">{{ sectionMeta[tab.key].hint }}</span>
              <span class="section-head__count">{{ getSectionItems(tab.key).length }}</span>
            </div>
          </template>

          <div class="section-body">
            <div class="section-toolbar">
              <span class="section-count">
                共 <strong>{{ getSectionItems(tab.key).length }}</strong> 条内容
              </span>
              <el-button type="primary" size="small" :icon="Plus" @click="openCreate(tab.key)">
                新增{{ tab.label }}
              </el-button>
            </div>

            <div class="section-table-card">
              <el-table v-loading="loading" :data="getSectionItems(tab.key)" stripe size="default" style="width: 100%">
                <el-table-column prop="sortOrder" label="排序" width="80" align="center">
                  <template #default="{ row }">
                    <span class="sort-order">{{ row.sortOrder }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="title" label="标题" min-width="220">
                  <template #default="{ row }">
                    <div class="title-cell">
                      <div class="title-cell__line">
                        <span class="title-text">{{ row.title }}</span>
                        <el-tag v-if="row.websiteFeatured" size="small" type="warning" effect="light">重点</el-tag>
                        <el-tag v-if="row.websiteEnabled === false" size="small" type="info" effect="plain"
                          >官网隐藏</el-tag
                        >
                      </div>
                      <code v-if="row.itemKey" class="title-cell__key">{{ row.itemKey }}</code>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="summary" label="摘要" min-width="240" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span v-if="row.summary" class="summary-text">{{ row.summary }}</span>
                    <span v-else class="empty-val">未填写</span>
                  </template>
                </el-table-column>
                <el-table-column v-if="tab.key === 'ARTICLE'" prop="category" label="分类" width="100" />
                <el-table-column prop="icon" label="图标" width="96" align="center">
                  <template #default="{ row }">
                    <span v-if="row.icon" class="icon-badge">{{ row.icon }}</span>
                    <span v-else class="empty-val">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="发布状态" width="150" align="center">
                  <template #default="{ row }">
                    <div class="status-cell">
                      <el-switch
                        v-model="row.websiteEnabled"
                        size="small"
                        @change="toggleVisible(row, Boolean($event))"
                      />
                      <WebsitePublishStatus :enabled="row.websiteEnabled" :eligible="solutionEligibility(row).eligible" :reason="solutionEligibility(row).reason" compact />
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="130" fixed="right" align="center">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                    <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
                  </template>
                </el-table-column>
                <template #empty>
                  <el-empty :image-size="72" description="当前分区暂无内容">
                    <el-button type="primary" size="small" @click="openCreate(tab.key)">新增{{ tab.label }}</el-button>
                  </el-empty>
                </template>
              </el-table>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <SolutionFormDialog
      v-if="dialogVisible"
      :model-value="editing"
      :saving="dialogSaving"
      @update:model-value="dialogVisible = $event"
      @save="handleSave"
    />
  </div>
</template>

<style scoped>
.solutions-manage {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
}

/* ==================== Header ==================== */
.page-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-section-gap-wide);
  padding: var(--space-xl-2xl) var(--space-xl);
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--color-surface) 0%, var(--color-surface-alt) 100%);
  box-shadow: var(--shadow-sm);
}

.page-header::before {
  position: absolute;
  inset: var(--space-section-gap) auto var(--space-section-gap) 0;
  width: 3px;
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
  background: var(--color-primary);
  content: '';
}

.page-header__content {
  position: relative;
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.page-header__eyebrow {
  margin: 0;
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  letter-spacing: var(--tracking-wider);
}

.page-header__title {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  letter-spacing: -0.02em;
}

.page-header__desc {
  max-width: 68ch;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-base);
  line-height: var(--lh-loose);
}

.page-header__actions {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-sm);
  flex: 0 0 auto;
}

/* ==================== Summary strip ==================== */
.overview-strip {
  min-width: 0;
}

/* ==================== Accordion ==================== */
.sections-accordion {
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
}

.sections-accordion :deep(.el-collapse) {
  border: none;
}

.sections-accordion :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 56px;
  padding: 0 var(--space-xl);
  background: var(--color-surface-alt);
  border-bottom: 1px solid var(--color-border-lighter);
  transition: background var(--transition-fast);
}

.sections-accordion :deep(.el-collapse-item__header:hover) {
  background: var(--color-surface-hover);
}

.sections-accordion :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

.sections-accordion :deep(.el-collapse-item:last-child .el-collapse-item__header) {
  border-bottom: 1px solid var(--color-border-lighter);
}

.section-head {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  width: 100%;
  min-width: 0;
  padding: var(--space-control-gap) 0;
}

.section-head__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.section-head__label {
  flex: 0 0 auto;
  color: var(--color-text-heading);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

.section-head__hint {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-head__count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 26px;
  height: 22px;
  margin-left: auto;
  padding: 0 var(--space-sm-md);
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  font-variant-numeric: tabular-nums;
}

/* ==================== Section body ==================== */
.section-body {
  padding: var(--space-lg-xl) var(--space-xl) var(--space-xl);
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md-lg);
}

.section-count {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
}

.section-count strong {
  color: var(--color-text-heading);
  font-weight: var(--fw-semibold);
}

.section-table-card {
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

/* ==================== Table ==================== */
.section-table-card :deep(.el-table) {
  --el-table-header-bg-color: var(--color-surface-alt);
  --el-table-row-hover-bg-color: var(--color-surface-hover);
  --el-table-border-color: var(--color-border-lighter);
  --el-table-header-text-color: var(--color-text-strong);
}

.section-table-card :deep(th.el-table__cell) {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--color-text-strong);
}

.section-table-card :deep(td.el-table__cell) {
  padding: var(--space-control-gap) 0;
}

.sort-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  font-variant-numeric: tabular-nums;
}

.title-cell {
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.title-cell__line {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  min-width: 0;
}

.title-text {
  overflow: hidden;
  color: var(--color-text-heading);
  font-weight: var(--fw-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title-cell__key {
  justify-self: start;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-family: var(--font-mono, monospace);
  font-size: var(--fs-xs);
}

.summary-text {
  display: block;
  overflow: hidden;
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-val {
  color: var(--color-text-faint);
}

.icon-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 30px;
  height: 26px;
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
}

.status-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-control-gap-tight);
}

.status-cell__text {
  font-size: var(--fs-xs);
}

.status-cell__text.is-on {
  color: var(--color-success-text);
  font-weight: var(--fw-medium);
}

.status-cell__text.is-off {
  color: var(--color-text-faint);
}

/* ==================== Responsive ==================== */
@media (max-width: 767px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
    gap: var(--space-control-gap-wide);
    padding: var(--space-control-gap-wide);
  }

  .page-header::before {
    inset: 0 var(--space-control-gap-wide) auto;
    width: auto;
    height: 3px;
    border-radius: 0 0 var(--radius-pill) var(--radius-pill);
  }

  .page-header__actions {
    justify-content: flex-start;
  }

  .section-head__hint {
    display: none;
  }

  .section-body {
    padding: var(--space-md-lg) var(--space-md-lg) var(--space-lg-xl);
  }
}
</style>
