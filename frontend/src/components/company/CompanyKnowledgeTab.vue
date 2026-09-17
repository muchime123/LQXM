<template>
  <div class="kb-section">
    <div class="kb-layout">
      <!-- 左侧分类树 -->
      <aside class="kb-sidebar">
        <div class="kb-search">
          <input
v-model.trim="kbSearchQuery"
type="search"
placeholder="搜索知识库..."
@keyup.enter="doKbSearch" />
        </div>
        <div class="kb-sidebar-header">
          <span class="kb-sidebar-title">知识分类</span>
          <span class="kb-sidebar-count">{{ kbCategories.length }}</span>
        </div>
        <div class="kb-categories">
          <div
            v-for="cat in kbCategories"
            :key="cat.id"
            class="kb-cat-item"
            :class="{ active: selectedKbCategory === cat.id }"
            @click="onKbCategoryClick(cat.id)"
          >
            <span class="cat-name">{{ cat.name }}</span>
            <div class="cat-actions">
              <span class="cat-count">{{ cat.articleCount || 0 }}</span>
              <button class="cat-edit-btn" title="编辑分类" @click.stop="openCatEdit(cat)">✏</button>
              <button class="cat-del-btn" title="删除分类" @click.stop="confirmDeleteCat(cat)">✕</button>
            </div>
          </div>
          <button class="kb-add-cat" @click="openCatAdd">+ 新增分类</button>
        </div>
      </aside>

      <!-- 右侧内容区 -->
      <main class="kb-content">
        <LoadingState v-if="kbLoading" />
        <div v-else-if="selectedArticle" class="article-detail">
          <div class="detail-toolbar">
            <button class="back-btn" @click="selectedArticle = null">← 返回列表</button>
            <div class="detail-actions">
              <button class="action-btn" title="编辑" @click="openEditArticle(selectedArticle)">✏</button>
              <button class="action-btn danger" title="删除" @click="confirmDeleteArticle(selectedArticle)">🗑</button>
            </div>
          </div>
          <h2 class="article-title">{{ selectedArticle.title }}</h2>
          <div class="article-meta">
            <span v-if="selectedArticle.websiteVisible" class="article-website">官网展示</span>
            <span v-if="selectedArticle.author">作者：{{ selectedArticle.author }}</span>
            <span v-if="selectedArticle.source">来源：{{ selectedArticle.source }}</span>
            <span v-if="selectedArticle.tags" class="article-tags">
              <span v-for="t in tagList(selectedArticle.tags)" :key="t" class="tag">{{ t }}</span>
            </span>
          </div>
          <div class="article-body" v-html="renderContent(selectedArticle.content)"></div>
        </div>
        <div v-else-if="kbArticles.length > 0" class="article-list">
          <div class="list-header">
            <span>{{ kbCategories.find(c => c.id === selectedKbCategory)?.name || '全部文章' }}</span>
            <div class="kb-status-filter">
              <button
                v-for="opt in [
                  { value: 'all', label: '全部' },
                  { value: 'published', label: '已发布' },
                  { value: 'draft', label: '草稿' }
                ]"
                :key="opt.value"
                :class="{ active: kbStatusFilter === opt.value }"
                @click="kbStatusFilter = opt.value; loadKbArticles()"
              >
                {{ opt.label }}
              </button>
            </div>
            <button class="btn-add-sm" @click="openNewArticle">
              <svg
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2"
width="14"
height="14">
                <line
x1="12"
y1="5"
x2="12"
y2="19" />
                <line
x1="5"
y1="12"
x2="19"
y2="12" />
              </svg>
              新建文章
            </button>
          </div>
          <div v-for="article in kbArticles" :key="article.id" class="article-card">
            <div class="article-card-main" @click="openArticle(article)">
              <div class="article-card-header">
                <span class="article-status" :class="article.status">{{
                  article.status === 'draft' ? '草稿' : '已发布'
                }}</span>
                <span v-if="article.websiteVisible" class="article-website" title="该文章将在官网知识库栏目展示">官网展示</span>
                <div class="article-card-title">{{ article.title }}</div>
              </div>
              <div class="article-card-excerpt">{{ getExcerpt(article.content) }}</div>
              <div class="article-card-meta">
                <span v-if="article.author">{{ article.author }}</span>
                <span v-if="article.viewCount">{{ article.viewCount }} 次阅读</span>
              </div>
            </div>
            <div class="card-actions-mini">
              <button class="mini-btn" title="编辑" @click.stop="openEditArticle(article)">✏</button>
              <button class="mini-btn danger" title="删除" @click.stop="confirmDeleteArticle(article)">🗑</button>
            </div>
          </div>
        </div>
        <EmptyState v-else :text="`暂无${selectedKbCategory ? '该分类' : ''}文章`" hint="点击「新建文章」开始编写">
          <button class="btn-add-sm" style="margin-top: 12px" @click="openNewArticle">
            <svg
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2"
width="14"
height="14">
              <line
x1="12"
y1="5"
x2="12"
y2="19" />
              <line
x1="5"
y1="12"
x2="19"
y2="12" />
            </svg>
            新建文章
          </button>
        </EmptyState>
      </main>
    </div>

    <!-- 文章编辑弹窗 -->
    <ArticleEditorModal
      v-model:visible="showArticleForm"
      :article="currentArticle"
      :categories="kbCategories"
      :saving="articleSaving"
      @save="handleArticleSave"
    />

    <!-- 分类编辑弹窗 -->
    <CategoryFormDialog
      v-model="showCatForm"
      :is-edit="!!editingCatId"
      :form-data="catFormData"
      :saving="catSaving"
      @save="handleCatSave"
      @close="cancelCatForm"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import LoadingState from '../common/LoadingState.vue'
import EmptyState from '../common/EmptyState.vue'
import ArticleEditorModal from './ArticleEditorModal.vue'
import CategoryFormDialog from './CategoryFormDialog.vue'
import {
  getKbCategories,
  getKbArticles,
  createKbArticle,
  updateKbArticle,
  deleteKbArticle,
  incrementKbArticleView,
  createKbCategory,
  updateKbCategory,
  deleteKbCategory
} from '../../api/company'
import { notify } from '../../composables/ui/useToast'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { tagList } from '../../utils/common/textUtils'
import { markdownToHtml } from '../../utils/common/markdown'
import type { KbCategory, KbArticle } from '../../types/company'

const kbCategories = ref<KbCategory[]>([])
const kbArticles = ref<KbArticle[]>([])
const selectedKbCategory = ref<number | null>(null)
const selectedArticle = ref<KbArticle | null>(null)
const kbLoading = ref(false)
const kbSearchQuery = ref('')
const kbStatusFilter = ref('all')

async function loadKbCategories() {
  try {
    kbCategories.value = await getKbCategories()
    if (kbCategories.value.length > 0 && !selectedKbCategory.value) {
      selectedKbCategory.value = kbCategories.value[0].id
    }
  } catch {
    notify('加载分类失败', 'error')
  }
}

async function loadKbArticles() {
  kbLoading.value = true
  try {
    const params: { status: string; categoryId?: number; keyword?: string } = { status: kbStatusFilter.value }
    if (selectedKbCategory.value) params.categoryId = selectedKbCategory.value
    if (kbSearchQuery.value) params.keyword = kbSearchQuery.value
    kbArticles.value = await getKbArticles(params)
  } catch {
    notify('加载文章失败', 'error')
  } finally {
    kbLoading.value = false
  }
}

async function openArticle(article: KbArticle) {
  selectedArticle.value = article
  article.viewCount = (article.viewCount || 0) + 1
  try {
    await incrementKbArticleView(article.id)
  } catch {}
}

function doKbSearch() {
  loadKbArticles()
}
function getExcerpt(content: string | null | undefined) {
  if (!content) return ''
  const text = content.replace(/<[^>]+>/g, '').replace(/[#*`_\[\]\(\)]/g, '').replace(/\n/g, ' ')
  return text.slice(0, 120) + (text.length > 120 ? '...' : '')
}
/** 内容为 HTML 直接渲染；旧 Markdown 数据转 HTML 后渲染。 */
function renderContent(content: string | null | undefined) {
  const value = (content || '').trim()
  if (!value) return markdownToHtml('', { emptyText: '暂无内容' })
  if (/<[a-z][\s\S]*>/i.test(value)) return value
  return markdownToHtml(value, { emptyText: '暂无内容' })
}

// ===== 文章编辑 =====
const showArticleForm = ref(false)
const articleSaving = ref(false)
const currentArticle = ref<Partial<KbArticle> | null>(null)

function openNewArticle() {
  currentArticle.value = { categoryId: selectedKbCategory.value }
  showArticleForm.value = true
}

function openEditArticle(article: KbArticle) {
  currentArticle.value = article
  showArticleForm.value = true
}

async function handleArticleSave(data: Partial<KbArticle>) {
  articleSaving.value = true
  try {
    if (currentArticle.value?.id) {
      await updateKbArticle(currentArticle.value.id, data)
      notify('文章已更新', 'success')
    } else {
      await createKbArticle(data)
      notify('文章已创建', 'success')
    }
    showArticleForm.value = false
    loadKbArticles()
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    articleSaving.value = false
  }
}

// ===== 文章删除 =====
async function confirmDeleteArticle(article: KbArticle) {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确定要删除文章「${article.title}」吗？此操作不可撤销。`,
    danger: true
  })
  if (!ok) return

  try {
    await deleteKbArticle(article.id)
    notify('文章已删除', 'success')
    loadKbArticles()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

// ===== 分类管理 =====
const showCatForm = ref(false)
const editingCatId = ref<number | null>(null)
const catSaving = ref(false)
const catFormData = ref({ name: '', nameEn: '', description: '' })

function openCatAdd() {
  editingCatId.value = null
  catFormData.value = { name: '', nameEn: '', description: '' }
  showCatForm.value = true
}
function openCatEdit(cat: KbCategory) {
  editingCatId.value = cat.id
  catFormData.value = { name: cat.name || '', nameEn: cat.nameEn || '', description: cat.description || '' }
  showCatForm.value = true
}
function onKbCategoryClick(catId: number) {
  selectedKbCategory.value = catId
  kbSearchQuery.value = ''
}
function cancelCatForm() {
  showCatForm.value = false
}

async function handleCatSave(data: { name: string; nameEn: string; description: string }) {
  if (!data.name) {
    notify('请填写分类名称', 'warning')
    return
  }
  catSaving.value = true
  try {
    if (editingCatId.value) {
      await updateKbCategory(editingCatId.value, data)
      notify('分类已更新', 'success')
    } else {
      await createKbCategory(data)
      notify('分类已添加', 'success')
    }
    showCatForm.value = false
    loadKbCategories()
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    catSaving.value = false
  }
}

async function confirmDeleteCat(cat: KbCategory) {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确定要删除分类「${cat.name}」吗？该分类下的文章不会被删除。`,
    danger: true
  })
  if (!ok) return

  try {
    await deleteKbCategory(cat.id)
    notify('分类已删除', 'success')
    if (selectedKbCategory.value === cat.id) {
      selectedKbCategory.value = null
      kbArticles.value = []
    }
    loadKbCategories()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

watch(selectedKbCategory, loadKbArticles)

onMounted(() => {
  loadKbCategories().then(() => loadKbArticles())
})
</script>

<style scoped>
.kb-section {
  padding: 8px 0;
}

.kb-layout {
  display: grid;
  grid-template-columns: 268px 1fr;
  gap: 20px;
  align-items: start;
  min-height: 520px;
}

@media (max-width: 900px) {
  .kb-layout {
    grid-template-columns: 1fr;
  }

  .kb-sidebar {
    position: static;
    top: auto;
  }
}

/* ===== 侧边栏 ===== */
.kb-sidebar {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  padding: 16px;
  height: fit-content;
  position: sticky;
  top: 20px;
  box-shadow: var(--shadow-sm);
}

.kb-search {
  position: relative;
  margin-bottom: 14px;
}

.kb-search::before,
.kb-search::after {
  content: '';
  position: absolute;
  pointer-events: none;
  opacity: 0.65;
}

.kb-search::before {
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 13px;
  height: 13px;
  border: 1.5px solid var(--color-text-faint);
  border-radius: 50%;
}

.kb-search::after {
  left: 23px;
  top: calc(50% + 4px);
  width: 6px;
  height: 1.5px;
  background: var(--color-text-faint);
  transform: rotate(45deg);
  transform-origin: left center;
}

.kb-search input {
  width: 100%;
  padding: 8px 12px 8px 33px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-lg);
  font-size: 13px;
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  transition: all var(--transition-fast);
}

.kb-search input::placeholder {
  color: var(--color-text-placeholder);
}

.kb-search input:focus {
  outline: none;
  border-color: var(--color-primary);
  background: var(--color-surface);
  box-shadow: var(--shadow-focus);
}

.kb-sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 8px;
  margin-bottom: 4px;
}

.kb-sidebar-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--color-text-faint);
}

.kb-sidebar-count {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
  color: var(--color-text-faint);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.kb-categories {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kb-cat-item {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  padding: 9px 10px;
  border-radius: var(--radius-lg);
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-secondary);
  transition: all var(--transition-fast);
}

.kb-cat-item::before {
  content: '';
  position: absolute;
  left: -4px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  opacity: 0;
  transition: opacity var(--transition-fast);
}

.kb-cat-item:hover {
  background: var(--color-surface-hover);
  color: var(--color-text-primary);
}

.kb-cat-item.active {
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-weight: 600;
}

.kb-cat-item.active::before {
  opacity: 1;
}

.kb-cat-item.active:hover {
  background: var(--color-surface-selected-hover);
}

.cat-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat-actions {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  flex-shrink: 0;
}

.cat-count {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
  color: var(--color-text-faint);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.kb-cat-item:hover .cat-count {
  background: var(--color-surface);
  color: var(--color-text-muted);
}

.kb-cat-item.active .cat-count {
  background: var(--color-surface);
  color: var(--color-primary);
}

.cat-edit-btn,
.cat-del-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  cursor: pointer;
  color: var(--color-text-faint);
  font-size: 12px;
  line-height: 1;
  opacity: 0;
  transition: all var(--transition-fast);
}

.kb-cat-item:hover .cat-edit-btn,
.kb-cat-item:hover .cat-del-btn,
.kb-cat-item.active .cat-edit-btn,
.kb-cat-item.active .cat-del-btn {
  opacity: 1;
}

.cat-edit-btn:hover {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.cat-del-btn:hover {
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
}

.kb-add-cat {
  margin-top: 10px;
  padding: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
  font-size: 13px;
  color: var(--color-text-faint);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.kb-add-cat:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-bg);
}

/* ===== 内容区 ===== */
.kb-content {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  padding: 24px;
  min-height: 520px;
  box-shadow: var(--shadow-sm);
}

/* 文章列表 */
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  margin-bottom: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border-lighter);
}

.list-header > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text-heading);
}

.list-header > span:first-child::before {
  content: '';
  width: 4px;
  height: 16px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
}

.kb-status-filter {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  margin-left: auto;
}

.kb-status-filter button {
  padding: 4px 14px;
  font-size: 12px;
  border: none;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.kb-status-filter button:hover {
  color: var(--color-text-primary);
}

.kb-status-filter button.active {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-weight: 600;
}

.article-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.article-card {
  padding: 16px 18px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-card-gap);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.article-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.article-card-main {
  flex: 1;
  min-width: 0;
}

.article-card-header {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  margin-bottom: 6px;
}

.article-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  flex-shrink: 0;
}

.article-status::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.article-status.published {
  background: var(--color-success-bg-soft);
  color: var(--color-success-text);
}

.article-status.draft {
  background: var(--color-warning-bg-soft);
  color: var(--color-warning-text);
}

.article-website {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  flex-shrink: 0;
}

.article-website::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
}

.article-card-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--color-text-heading);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.article-card-excerpt {
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.article-card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11px;
  color: var(--color-text-faint);
}

.article-card-meta span + span {
  margin-left: 8px;
}

.article-card-meta span + span::before {
  content: '·';
  margin-right: 8px;
  color: var(--color-border);
}

.card-actions-mini {
  display: flex;
  gap: var(--space-control-gap-tight);
  flex-shrink: 0;
  opacity: 0;
  transition: opacity var(--transition-fast);
}

.article-card:hover .card-actions-mini,
.article-card:focus-within .card-actions-mini {
  opacity: 1;
}

.mini-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  color: var(--color-text-dim);
  font-size: 13px;
  line-height: 1;
  transition: all var(--transition-fast);
}

.mini-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.mini-btn.danger:hover {
  color: var(--color-danger);
  border-color: var(--color-danger);
  background: var(--color-danger-bg-tint);
}

.btn-add-sm {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: 7px 14px;
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), box-shadow var(--transition-fast);
}

.btn-add-sm:hover {
  background: var(--color-primary-hover);
  box-shadow: 0 4px 12px var(--color-primary-shadow);
}

/* 操作按钮（与财务账户 Tab 同款） */
.action-btn {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  color: var(--color-text-dim);
  font-size: 14px;
  line-height: 1;
  transition: all var(--transition-fast);
}

.action-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.action-btn.danger:hover {
  color: var(--color-danger);
  border-color: var(--color-danger);
  background: var(--color-danger-bg-tint);
}

/* ===== 文章详情 ===== */
.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  margin-bottom: 20px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.back-btn:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
  background: var(--color-primary-bg);
}

.detail-actions {
  display: flex;
  gap: var(--space-control-gap-tight);
}

.article-title {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.4;
  color: var(--color-text-heading);
  margin: 0 0 12px;
  word-break: break-word;
}

.article-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  padding-bottom: 16px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--color-border-lighter);
  font-size: 12px;
  color: var(--color-text-faint);
}

.article-meta > span + span {
  position: relative;
  padding-left: 16px;
}

.article-meta > span + span::before {
  content: '·';
  position: absolute;
  left: 4px;
  color: var(--color-border);
}

.article-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}

.article-tags .tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 500;
}

.article-body {
  font-size: 14px;
  line-height: 1.8;
  color: var(--color-text-secondary);
  max-width: 780px;
  word-break: break-word;
}

.article-body h1,
.article-body h2,
.article-body h3,
.article-body h4,
.article-body h5,
.article-body h6 {
  color: var(--color-text-heading);
  font-weight: 700;
  line-height: 1.4;
  margin: 1.6em 0 0.6em;
}

.article-body h1 {
  font-size: 22px;
}

.article-body h2 {
  font-size: 19px;
}

.article-body h3 {
  font-size: 16px;
}

.article-body h4,
.article-body h5,
.article-body h6 {
  font-size: 14px;
}

.article-body h1:first-child,
.article-body h2:first-child,
.article-body h3:first-child,
.article-body h4:first-child {
  margin-top: 0;
}

.article-body p {
  margin: 0 0 1em;
}

.article-body strong {
  color: var(--color-text-heading);
  font-weight: 600;
}

.article-body ul {
  margin: 0 0 1em;
  padding-left: 1.4em;
}

.article-body li {
  margin-bottom: 0.35em;
}

.article-body table {
  width: 100%;
  margin: 1em 0;
  border-collapse: collapse;
  font-size: 13px;
}

.article-body th,
.article-body td {
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  text-align: left;
}

.article-body th {
  background: var(--color-surface-alt);
  color: var(--color-text-heading);
  font-weight: 600;
}

.article-body .empty-line {
  color: var(--color-text-faint);
}
</style>
