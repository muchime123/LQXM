<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  createWebsiteProductDraft,
  createWebsiteProductCategory,
  fetchWebsiteProductCategories,
  fetchWebsiteProductDrafts,
  fetchWebsiteProductCutoverReadiness,
  fetchWebsiteNativeProductPreview,
  fetchWebsiteProductPublicationReadiness,
  fetchWebsiteProductReview,
  publishWebsiteMedia,
  publishWebsiteProductCategory,
  publishWebsiteProduct,
  updateWebsiteProductDraft,
  updateWebsiteProductCategory,
  updateWebsiteProductSelection,
  updateWebsiteProductParameters,
  updateWebsiteProductDescription
} from '@/api/website/products'
import type { WebsiteNativeProductPreview, WebsiteProductCategory, WebsiteProductCutoverReadiness, WebsiteProductDraft, WebsiteProductDraftFields, WebsiteProductPublicationReadiness, WebsiteProductReview, WebsiteProductSelectionFields } from '@/api/website/products'
import { fetchWebsiteMediaBlob, fetchWebsiteMediaItem } from '@/api/website/media'
import type { WebsiteMediaItem } from '@/api/website/media'
import { notify } from '@/composables/ui/useToast'
import WebsiteMediaPicker from '@/components/website/WebsiteMediaPicker.vue'
import WebsiteProductVariantsDialog from '@/components/website/WebsiteProductVariantsDialog.vue'
import WebsiteProductGalleryDialog from '@/components/website/WebsiteProductGalleryDialog.vue'

const rows = ref<WebsiteProductDraft[]>([])
const loading = ref(false)
const saving = ref(false)
const page = ref(0)
const total = ref(0)
const editorVisible = ref(false)
const editingId = ref<number | null>(null)
const categories = ref<WebsiteProductCategory[]>([])
const categoryVisible = ref(false)
const categoryListVisible = ref(false)
const categoryEditingId = ref<number | null>(null)
const categorySaving = ref(false)
const categoryForm = reactive({ name: '', slug: '', sortOrder: 0 })
const variantsVisible = ref(false)
const variantProductId = ref<number | null>(null)
const variantProductName = ref('')
const galleryVisible = ref(false)
const galleryProductId = ref<number | null>(null)
const galleryProductName = ref('')
const reviewVisible = ref(false)
const reviewLoading = ref(false)
const review = ref<WebsiteProductReview | null>(null)
const nativePreview = ref<WebsiteNativeProductPreview | null>(null)
const readiness = ref<WebsiteProductPublicationReadiness | null>(null)
const reviewImageUrls = ref<Record<number, string>>({})
const reviewMedia = ref<WebsiteMediaItem[]>([])
const publishing = ref(false)
const selectionVisible = ref(false)
const selectionLoading = ref(false)
const selectionSaving = ref(false)
const selectionProductId = ref<number | null>(null)
const selectionForm = reactive<WebsiteProductSelectionFields>({ applicationTags: [], containerTags: [], processTags: [], capacityLevel: null })
const parametersVisible = ref(false)
const parametersLoading = ref(false)
const parametersSaving = ref(false)
const parametersProductId = ref<number | null>(null)
const parametersText = ref('{}')
const descriptionVisible = ref(false)
const descriptionLoading = ref(false)
const descriptionSaving = ref(false)
const descriptionProductId = ref<number | null>(null)
const descriptionText = ref('')
const descriptionTextEn = ref('')
const cutoverVisible = ref(false)
const cutoverLoading = ref(false)
const cutoverReadiness = ref<WebsiteProductCutoverReadiness | null>(null)
let reviewRequest = 0

async function openCutoverReadiness() {
  cutoverVisible.value = true
  cutoverLoading.value = true
  cutoverReadiness.value = null
  try {
    cutoverReadiness.value = await fetchWebsiteProductCutoverReadiness()
  } catch (error) {
    notify.errorWithDetail('加载官网产品切换检查失败', error)
  } finally {
    cutoverLoading.value = false
  }
}

function clearReviewImages() {
  Object.values(reviewImageUrls.value).forEach((url) => URL.revokeObjectURL(url))
  reviewImageUrls.value = {}
}

function closeReview() {
  reviewRequest++
  clearReviewImages()
  review.value = null
  nativePreview.value = null
  readiness.value = null
  reviewMedia.value = []
}

async function openReview(item: WebsiteProductDraft) {
  closeReview()
  reviewVisible.value = true
  reviewLoading.value = true
  const request = ++reviewRequest
  try {
    const [data, check, preview] = await Promise.all([
      fetchWebsiteProductReview(item.id), fetchWebsiteProductPublicationReadiness(item.id),
      fetchWebsiteNativeProductPreview(item.id)
    ])
    if (request !== reviewRequest) return
    review.value = data
    readiness.value = check
    nativePreview.value = preview
    const imageIds = [...new Set([data.heroMediaId, data.cardMediaId,
      ...data.gallery.map((row) => row.mediaId),
      ...preview.gallery.map((row) => row.mediaId)].filter((id): id is number => id !== null))]
    const [images, mediaItems] = await Promise.all([
      Promise.allSettled(imageIds.map((id) => fetchWebsiteMediaBlob(id))),
      Promise.allSettled(imageIds.map((id) => fetchWebsiteMediaItem(id)))
    ])
    if (request !== reviewRequest) return
    const urls: Record<number, string> = {}
    images.forEach((result, index) => {
      if (result.status === 'fulfilled') urls[imageIds[index]] = URL.createObjectURL(result.value)
    })
    reviewImageUrls.value = urls
    reviewMedia.value = mediaItems.flatMap((result) => result.status === 'fulfilled' ? [result.value] : [])
  } catch (error) {
    if (request === reviewRequest) notify.errorWithDetail('加载官网产品审阅内容失败', error)
  } finally {
    if (request === reviewRequest) reviewLoading.value = false
  }
}

async function refreshReview() {
  if (!reviewVisible.value || !review.value) return
  await openReview(review.value)
}

async function confirmPublication(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '确认官网发布', { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' })
    return true
  } catch {
    return false
  }
}

async function publishMedia(id: number) {
  if (!await confirmPublication(`确认发布官网媒体 #${id}？请先核对图片内容和版权。`)) return
  publishing.value = true
  try {
    await publishWebsiteMedia(id)
    await refreshReview()
    notify.success('官网媒体已发布')
  } catch (error) {
    notify.errorWithDetail('发布官网媒体失败', error)
  } finally {
    publishing.value = false
  }
}

async function publishCategory(item: WebsiteProductCategory) {
  if (!await confirmPublication(`确认发布官网分类「${item.name}」？请先核对名称和 URL 标识。`)) return
  publishing.value = true
  try {
    await publishWebsiteProductCategory(item.id)
    categories.value = await fetchWebsiteProductCategories()
    await refreshReview()
    notify.success('官网分类已发布')
  } catch (error) {
    notify.errorWithDetail('发布官网分类失败', error)
  } finally {
    publishing.value = false
  }
}

async function publishProduct() {
  if (!review.value || review.value.status !== 'DRAFT' || publishing.value) return
  const id = review.value.id
  try {
    const check = await fetchWebsiteProductPublicationReadiness(id)
    readiness.value = check
    if (!check.ready) {
      notify.error('发布前检查未通过，请先处理阻碍项')
      return
    }
    const warningText = check.warnings.length ? `\n待审阅：${check.warnings.join('；')}` : ''
    if (!await confirmPublication(`确认发布官网产品「${review.value.name}」？${warningText}\n公开接口切换到官网数据源后，该产品将对外展示。`)) return
    publishing.value = true
    await publishWebsiteProduct(id)
    await loadData()
    await refreshReview()
    notify.success('官网产品已发布；当前公开数据源仍由配置决定')
  } catch (error) {
    notify.errorWithDetail('发布官网产品失败', error)
  } finally {
    publishing.value = false
  }
}

function emptyFields(): WebsiteProductDraftFields {
  return {
    categoryId: null, name: '', nameEn: null, publicModel: null,
    heroMediaId: null, cardMediaId: null, slug: '', summary: null,
    summaryEn: null, seoTitle: null, seoDescription: null, sortOrder: 0, featured: false
  }
}
const form = reactive<WebsiteProductDraftFields>(emptyFields())

async function loadData() {
  loading.value = true
  try {
    const [result, categoryList] = await Promise.all([
      fetchWebsiteProductDrafts(page.value), fetchWebsiteProductCategories()
    ])
    rows.value = result.content
    total.value = result.totalElements
    categories.value = categoryList
  } catch (error) {
    notify.errorWithDetail('加载官网产品草稿失败', error)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyFields())
  editorVisible.value = true
}

function openEdit(item: WebsiteProductDraft) {
  editingId.value = item.id
  Object.assign(form, {
    categoryId: item.categoryId, name: item.name, nameEn: item.nameEn, publicModel: item.publicModel,
    heroMediaId: item.heroMediaId, cardMediaId: item.cardMediaId,
    slug: item.slug, summary: item.summary, summaryEn: item.summaryEn,
    seoTitle: item.seoTitle, seoDescription: item.seoDescription,
    sortOrder: item.sortOrder, featured: item.featured
  })
  editorVisible.value = true
}

function openVariants(item: WebsiteProductDraft) {
  variantProductId.value = item.id
  variantProductName.value = item.name
  variantsVisible.value = true
}

function openGallery(item: WebsiteProductDraft) {
  galleryProductId.value = item.id
  galleryProductName.value = item.name
  galleryVisible.value = true
}

async function openSelection(item: WebsiteProductDraft) {
  selectionProductId.value = item.id
  selectionVisible.value = true
  selectionLoading.value = true
  try {
    const data = await fetchWebsiteProductReview(item.id)
    if (selectionProductId.value !== item.id || !selectionVisible.value) return
    Object.assign(selectionForm, {
      applicationTags: [...data.applicationTags], containerTags: [...data.containerTags],
      processTags: [...data.processTags], capacityLevel: data.capacityLevel
    })
  } catch (error) {
    notify.errorWithDetail('加载官网产品筛选信息失败', error)
    selectionVisible.value = false
  } finally {
    selectionLoading.value = false
  }
}

async function saveSelection() {
  if (selectionProductId.value === null) return
  selectionSaving.value = true
  try {
    await updateWebsiteProductSelection(selectionProductId.value, {
      applicationTags: selectionForm.applicationTags,
      containerTags: selectionForm.containerTags,
      processTags: selectionForm.processTags,
      capacityLevel: selectionForm.capacityLevel
    })
    selectionVisible.value = false
    notify.success('官网产品筛选信息已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网产品筛选信息失败', error)
  } finally {
    selectionSaving.value = false
  }
}

async function openParameters(item: WebsiteProductDraft) {
  parametersProductId.value = item.id
  parametersVisible.value = true
  parametersLoading.value = true
  try {
    const preview = await fetchWebsiteNativeProductPreview(item.id)
    if (parametersProductId.value === item.id && parametersVisible.value) {
      parametersText.value = JSON.stringify(preview.technicalParameters, null, 2)
    }
  } catch (error) {
    notify.errorWithDetail('加载官网产品技术参数失败', error)
    parametersVisible.value = false
  } finally {
    parametersLoading.value = false
  }
}

async function saveParameters() {
  if (parametersProductId.value === null) return
  let parsed: unknown
  try {
    parsed = JSON.parse(parametersText.value)
  } catch {
    notify.error('技术参数必须是有效的 JSON 对象')
    return
  }
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
    notify.error('技术参数必须是 JSON 对象')
    return
  }
  parametersSaving.value = true
  try {
    await updateWebsiteProductParameters(parametersProductId.value, parsed as Record<string, unknown>)
    parametersVisible.value = false
    notify.success('官网产品技术参数已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网产品技术参数失败', error)
  } finally {
    parametersSaving.value = false
  }
}

function htmlToPlainText(html: string | null): string {
  if (!html) return ''
  const withBreaks = html.replace(/<br\s*\/?>/gi, '\n').replace(/<\/(?:p|div|h[1-6]|li)>/gi, '\n\n')
  return new DOMParser().parseFromString(withBreaks, 'text/html').body.textContent?.trim() || ''
}

async function openDescription(item: WebsiteProductDraft) {
  descriptionProductId.value = item.id
  descriptionVisible.value = true
  descriptionLoading.value = true
  try {
    const data = await fetchWebsiteProductReview(item.id)
    if (descriptionProductId.value !== item.id || !descriptionVisible.value) return
    const unsupportedMarkup = `${data.descriptionHtml || ''}${data.descriptionHtmlEn || ''}`
      .replace(/<\/?(?:p|br)\s*\/?>/gi, '')
    if (/<[^>]+>/.test(unsupportedMarkup)) {
      notify.error('正文包含图片、链接或其他排版，纯文本编辑会丢失这些内容，请先在审阅页核对')
      descriptionVisible.value = false
      return
    }
    descriptionText.value = htmlToPlainText(data.descriptionHtml)
    descriptionTextEn.value = htmlToPlainText(data.descriptionHtmlEn)
  } catch (error) {
    notify.errorWithDetail('加载官网产品详情正文失败', error)
    descriptionVisible.value = false
  } finally {
    descriptionLoading.value = false
  }
}

async function saveDescription() {
  if (descriptionProductId.value === null) return
  descriptionSaving.value = true
  try {
    await updateWebsiteProductDescription(descriptionProductId.value, descriptionText.value, descriptionTextEn.value)
    descriptionVisible.value = false
    notify.success('官网产品详情正文已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网产品详情正文失败', error)
  } finally {
    descriptionSaving.value = false
  }
}

function openCategoryCreate() {
  categoryEditingId.value = null
  Object.assign(categoryForm, { name: '', slug: '', sortOrder: 0 })
  categoryVisible.value = true
}

function openCategoryEdit(item: WebsiteProductCategory) {
  categoryEditingId.value = item.id
  Object.assign(categoryForm, { name: item.name, slug: item.slug, sortOrder: item.sortOrder })
  categoryVisible.value = true
}

async function saveCategory() {
  if (!categoryForm.name.trim() || !categoryForm.slug.trim()) {
    notify.error('请填写分类名称和 URL 标识')
    return
  }
  categorySaving.value = true
  try {
    const fields = {
      name: categoryForm.name.trim(), slug: categoryForm.slug.trim(), sortOrder: categoryForm.sortOrder
    }
    const created = categoryEditingId.value === null
      ? await createWebsiteProductCategory(fields)
      : await updateWebsiteProductCategory(categoryEditingId.value, fields)
    categories.value = await fetchWebsiteProductCategories()
    if (categoryEditingId.value === null && editorVisible.value) form.categoryId = created.id
    categoryVisible.value = false
    notify.success(categoryEditingId.value === null ? '官网分类草稿已创建' : '官网分类草稿已更新')
  } catch (error) {
    notify.errorWithDetail('保存官网分类失败', error)
  } finally {
    categorySaving.value = false
  }
}

function categoryName(id: number | null) {
  return categories.value.find((item) => item.id === id)?.name || '未分类'
}

async function save() {
  if (!form.name.trim() || !form.slug.trim()) {
    notify.error('请填写产品名称和 URL 标识')
    return
  }
  saving.value = true
  try {
    const fields = { ...form, name: form.name.trim(), slug: form.slug.trim() }
    if (editingId.value === null) await createWebsiteProductDraft(fields)
    else await updateWebsiteProductDraft(editingId.value, fields)
    editorVisible.value = false
    await loadData()
    notify.success('官网产品草稿已保存，当前不会出现在官网')
  } catch (error) {
    notify.errorWithDetail('保存官网产品草稿失败', error)
  } finally {
    saving.value = false
  }
}

function changePage(next: number) {
  page.value = next - 1
  loadData()
}

await loadData()
</script>

<template>
  <div class="website-products-page">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网产品草稿</h1>
        <p class="page-header__desc">独立维护官网产品内容。草稿暂不发布，现有官网产品展示保持不变。</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="categoryListVisible = true">分类草稿</el-button>
        <el-button @click="openCutoverReadiness">切换检查</el-button>
        <el-button type="primary" @click="openCreate">新增草稿</el-button>
        <el-button :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </header>

    <el-table v-loading="loading" :data="rows" empty-text="暂无官网产品草稿">
      <el-table-column prop="name" label="产品名称" min-width="220" />
      <el-table-column label="官网分类" min-width="140">
        <template #default="{ row }">{{ categoryName(row.categoryId) }}</template>
      </el-table-column>
      <el-table-column prop="publicModel" label="公开型号" min-width="160" />
      <el-table-column prop="slug" label="URL 标识" min-width="180" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="450">
        <template #default="{ row }">
          <el-button link type="primary" @click="openReview(row)">审阅</el-button>
          <el-button
            link
            type="primary"
            :disabled="row.status !== 'DRAFT'"
            @click="openEdit(row)"
          >编辑</el-button>
          <el-button
            link
            type="primary"
            :disabled="row.status !== 'DRAFT'"
            @click="openVariants(row)"
          >规格</el-button>
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openSelection(row)">筛选信息</el-button>
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openParameters(row)">技术参数</el-button>
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openDescription(row)">详情正文</el-button>
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openGallery(row)">图库</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="cutoverVisible" title="官网产品数据源切换检查" width="min(760px, 94vw)">
      <div v-loading="cutoverLoading" class="website-products-page__review">
        <template v-if="cutoverReadiness">
          <el-alert
            :title="cutoverReadiness.technicallyReady ? '技术检查通过，仍需内容与页面验收' : '当前不能切换到官网独立产品数据源'"
            :type="cutoverReadiness.technicallyReady ? 'success' : 'warning'"
            :closable="false"
          />
          <p>产品：已发布 {{ cutoverReadiness.publishedProducts }} · 草稿 {{ cutoverReadiness.draftProducts }} · 已发布精选 {{ cutoverReadiness.publishedFeaturedProducts }}</p>
          <p>分类：已发布 {{ cutoverReadiness.publishedCategories }} · 草稿 {{ cutoverReadiness.draftCategories }}</p>
          <p>官网媒体：已发布 {{ cutoverReadiness.publishedMedia }} · 草稿 {{ cutoverReadiness.draftMedia }}</p>
          <ul v-if="cutoverReadiness.blockers.length || cutoverReadiness.warnings.length">
            <li v-for="message in cutoverReadiness.blockers" :key="`blocker-${message}`">阻碍：{{ message }}</li>
            <li v-for="message in cutoverReadiness.warnings" :key="`warning-${message}`">提示：{{ message }}</li>
          </ul>
          <div v-for="issue in cutoverReadiness.productIssues" :key="issue.id">
            <strong>{{ issue.name }}（#{{ issue.id }}）</strong>
            <p v-for="message in issue.blockers" :key="message">阻碍：{{ message }}</p>
            <p v-for="message in issue.warnings" :key="message">提示：{{ message }}</p>
          </div>
          <p>此检查只读取数据，不会发布内容或切换公开接口。</p>
        </template>
      </div>
    </el-dialog>

    <el-dialog
      v-model="reviewVisible"
      title="官网产品草稿审阅"
      width="min(900px, 94vw)"
      @closed="closeReview"
    >
      <div v-loading="reviewLoading" class="website-products-page__review">
        <template v-if="review">
          <el-alert title="此内容仅供后台审阅，当前官网仍显示原有产品数据。" type="info" :closable="false" />
          <el-alert
            v-if="readiness"
            :title="readiness.ready ? '发布前检查通过' : '发布前检查未通过'"
            :type="readiness.ready ? 'success' : 'warning'"
            :closable="false"
          >
            <ul v-if="readiness.blockers.length || readiness.warnings.length">
              <li v-for="message in readiness.blockers" :key="`blocker-${message}`">需处理：{{ message }}</li>
              <li v-for="message in readiness.warnings" :key="`warning-${message}`">待审阅：{{ message }}</li>
            </ul>
          </el-alert>
          <h3>{{ review.name }} <small v-if="review.publicModel">{{ review.publicModel }}</small></h3>
          <p>分类：{{ review.categoryName || '未分类' }} · URL：{{ review.slug }} · 状态：{{ review.status }}</p>
          <p v-if="review.summary">{{ review.summary }}</p>
          <p>应用：{{ review.applicationTags.join('、') || '未填写' }} · 容器：{{ review.containerTags.join('、') || '未填写' }}</p>
          <p>工艺：{{ review.processTags.join('、') || '未填写' }} · 产能：{{ review.capacityLevel || '未填写' }}</p>
          <p>SEO 标题：{{ review.seoTitle || '未填写' }} · SEO 描述：{{ review.seoDescription || '未填写' }}</p>
          <h4>发布依赖</h4>
          <p>官网分类：{{ review.categoryName || '未分类' }}（{{ categories.find(item => item.id === review?.categoryId)?.status || '不存在' }}）</p>
          <div v-for="item in reviewMedia" :key="item.id" class="website-products-page__publication-row">
            <span>媒体 #{{ item.id }} · {{ item.displayName || item.originalFilename }} · {{ item.status }}</span>
            <el-button v-if="item.status === 'DRAFT'" link type="primary" :loading="publishing" @click="publishMedia(item.id)">审核后发布媒体</el-button>
          </div>
          <el-button
            v-if="review.status === 'DRAFT'"
            type="primary"
            :disabled="reviewLoading || publishing || !readiness?.ready"
            :loading="publishing"
            @click="publishProduct"
          >发布该产品</el-button>
          <h4>详情文字</h4>
          <pre class="website-products-page__description">{{ review.descriptionHtml || '未填写' }}</pre>
          <h4>图片（{{ review.gallery.length }}）</h4>
          <div class="website-products-page__gallery">
            <div v-for="asset in review.gallery" :key="asset.id" class="website-products-page__gallery-item">
              <img v-if="reviewImageUrls[asset.mediaId]" :src="reviewImageUrls[asset.mediaId]" :alt="asset.altText || review.name" />
              <span v-else>图片不可预览</span>
              <small>{{ asset.sortOrder + 1 }} · {{ asset.originalFilename }}</small>
            </div>
          </div>
          <template v-if="nativePreview">
            <el-divider>拟公开产品数据预览</el-divider>
            <div class="website-products-page__native-preview">
              <img
                v-if="nativePreview.product.heroMediaId && reviewImageUrls[nativePreview.product.heroMediaId]"
                :src="reviewImageUrls[nativePreview.product.heroMediaId]"
                :alt="nativePreview.product.name"
              />
              <div>
                <small>{{ nativePreview.product.category || '未分类' }} · {{ nativePreview.product.publicModel || '未填写型号' }}</small>
                <h3>{{ nativePreview.product.name }}</h3>
                <p>{{ nativePreview.product.summary || '未填写摘要' }}</p>
                <p>详情图库 {{ nativePreview.gallery.length }} 张 · 可选规格 {{ nativePreview.variants.length }} 条</p>
                <p>SEO：{{ nativePreview.product.seoTitle || '使用动态标题' }}</p>
              </div>
            </div>
            <pre class="website-products-page__description">{{ nativePreview.descriptionHtml || '未填写详情介绍' }}</pre>
            <pre v-if="Object.keys(nativePreview.technicalParameters).length" class="website-products-page__description">技术参数：{{ JSON.stringify(nativePreview.technicalParameters, null, 2) }}</pre>
          </template>
        </template>
      </div>
    </el-dialog>
    <el-pagination
      :current-page="page + 1"
      class="website-products-page__pagination"
      background
      layout="total, prev, pager, next"
      :total="total"
      :page-size="20"
      @current-change="changePage"
    />

    <el-dialog v-model="editorVisible" :title="editingId === null ? '新增官网产品草稿' : '编辑官网产品草稿'" width="min(680px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="官网分类">
          <div class="website-products-page__category-row">
            <el-select v-model="form.categoryId" clearable placeholder="请选择官网分类">
              <el-option
                v-for="item in categories"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
            <el-button @click="openCategoryCreate">新增分类</el-button>
          </div>
        </el-form-item>
        <el-form-item label="产品名称" required><el-input v-model="form.name" maxlength="300" show-word-limit /></el-form-item>
        <el-form-item label="公开型号"><el-input v-model="form.publicModel" maxlength="200" /></el-form-item>
        <el-form-item label="URL 标识" required>
          <el-input v-model="form.slug" maxlength="200" placeholder="小写字母、数字和连字符" />
        </el-form-item>
        <el-form-item label="公开摘要"><el-input v-model="form.summary" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="详情主图"><WebsiteMediaPicker v-model="form.heroMediaId" /></el-form-item>
        <el-form-item label="列表卡片图"><WebsiteMediaPicker v-model="form.cardMediaId" /></el-form-item>
        <el-form-item label="英文名称"><el-input v-model="form.nameEn" maxlength="300" /></el-form-item>
        <el-form-item label="英文摘要"><el-input v-model="form.summaryEn" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="SEO 标题"><el-input v-model="form.seoTitle" maxlength="300" /></el-form-item>
        <el-form-item label="SEO 描述"><el-input
          v-model="form.seoDescription"
          type="textarea"
          maxlength="500"
          show-word-limit
        /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="未来首页精选"><el-switch v-model="form.featured" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <WebsiteProductVariantsDialog
      v-model="variantsVisible"
      :product-id="variantProductId"
      :product-name="variantProductName"
    />
    <WebsiteProductGalleryDialog v-model="galleryVisible" :product-id="galleryProductId" :product-name="galleryProductName" />

    <el-dialog v-model="selectionVisible" title="官网产品筛选信息" width="min(620px, 92vw)">
      <el-form v-loading="selectionLoading" label-position="top">
        <el-form-item v-for="field in ([
          { key: 'applicationTags', label: '应用标签' },
          { key: 'containerTags', label: '容器标签' },
          { key: 'processTags', label: '工艺标签' }
        ] as const)" :key="field.key" :label="field.label">
          <el-select v-model="selectionForm[field.key]" multiple filterable allow-create default-first-option clearable
            placeholder="输入标签后按回车，可添加多个">
            <el-option v-for="tag in selectionForm[field.key]" :key="tag" :label="tag" :value="tag" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标产能"><el-input v-model="selectionForm.capacityLevel" maxlength="200" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="selectionVisible = false">取消</el-button>
        <el-button type="primary" :disabled="selectionLoading" :loading="selectionSaving" @click="saveSelection">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="parametersVisible" title="官网产品技术参数" width="min(680px, 92vw)">
      <div v-loading="parametersLoading">
        <p>填写 JSON 对象，例如 {"电压": "220V", "功率": "1.5kW"}。最多 50 项；值仅支持文字、数字或布尔值。</p>
        <el-input v-model="parametersText" type="textarea" :rows="14" spellcheck="false" />
      </div>
      <template #footer>
        <el-button @click="parametersVisible = false">取消</el-button>
        <el-button type="primary" :disabled="parametersLoading" :loading="parametersSaving" @click="saveParameters">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="descriptionVisible" title="官网产品详情正文" width="min(760px, 94vw)">
      <div v-loading="descriptionLoading">
        <el-alert title="纯文本模式：保存会将当前文字转为安全段落，原有标题和其他排版不会保留。含图片、视频或链接的正文暂不允许在此编辑。" type="warning" :closable="false" />
        <el-form label-position="top">
          <el-form-item label="中文详情"><el-input v-model="descriptionText" type="textarea" :rows="12" maxlength="30000" show-word-limit /></el-form-item>
          <el-form-item label="英文详情"><el-input v-model="descriptionTextEn" type="textarea" :rows="8" maxlength="30000" show-word-limit /></el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="descriptionVisible = false">取消</el-button>
        <el-button type="primary" :disabled="descriptionLoading" :loading="descriptionSaving" @click="saveDescription">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="categoryListVisible" title="官网产品分类" width="min(720px, 92vw)">
      <el-table :data="categories" empty-text="暂无官网分类">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="slug" label="URL 标识" />
        <el-table-column prop="sortOrder" label="排序" width="80" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openCategoryEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="primary" :loading="publishing" @click="publishCategory(row)">审核后发布</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="openCategoryCreate">新增分类</el-button></template>
    </el-dialog>

    <el-dialog v-model="categoryVisible" :title="categoryEditingId === null ? '新增官网分类草稿' : '编辑官网分类草稿'" width="min(480px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="分类名称" required><el-input v-model="categoryForm.name" maxlength="200" /></el-form-item>
        <el-form-item label="URL 标识" required><el-input v-model="categoryForm.slug" maxlength="200" placeholder="小写字母、数字和连字符" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="categoryForm.sortOrder" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryVisible = false">取消</el-button>
        <el-button type="primary" :loading="categorySaving" @click="saveCategory">保存分类草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-products-page__pagination { margin-top: 20px; justify-content: flex-end; }
.website-products-page__category-row { display: flex; gap: 8px; width: 100%; }
.website-products-page__category-row .el-select { flex: 1; }
.website-products-page__review { min-height: 120px; }
.website-products-page__description { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
.website-products-page__gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
.website-products-page__gallery-item { display: grid; gap: 5px; min-width: 0; }
.website-products-page__gallery-item img { width: 100%; aspect-ratio: 4 / 3; object-fit: contain; background: #f5f7fa; }
.website-products-page__gallery-item small { overflow-wrap: anywhere; }
.website-products-page__publication-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.website-products-page__native-preview { display: grid; grid-template-columns: minmax(150px, 220px) 1fr; gap: 18px; padding: 14px; background: #f5f7fa; border-radius: 10px; }
.website-products-page__native-preview img { width: 100%; aspect-ratio: 4 / 3; object-fit: contain; }
</style>
