<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Refresh, Monitor, Iphone, Notebook, Check, Delete, Plus, DocumentAdd, PictureFilled, WarningFilled, ArrowRight, InfoFilled } from '@element-plus/icons-vue'
import { fetchPageSections, updatePageSection } from '@/api/website/pageSections'
import { fetchBanners, fetchBannerById, createBanner, updateBanner, deleteBanner } from '@/api/website/banners'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import type {
  WebsiteBannerDetail,
  WebsiteBannerSummary,
  WebsitePageSection,
  WebsitePageSectionPayload
} from '@/types/website'
import { PAGE_SECTION_HINTS, PAGE_SECTION_PAGE_LABELS } from '@/types/website'
import type { PageSectionPageKey } from '@/types/website'
import { notify } from '@/composables/ui/useToast'
import WebsiteImageField from '@/components/website/WebsiteImageField.vue'

type Viewport = 'desktop' | 'tablet' | 'mobile'
type CmsSelection = { pageKey: string; sectionKey: string; source: string }
const EDITABLE_PAGES: Array<{ key: PageSectionPageKey; label: string; path: string; defaultSection: string }> = [
  { key: 'HOME', label: '首页', path: '/', defaultSection: 'hero' },
  { key: 'PRODUCTS', label: '产品中心', path: '/products', defaultSection: 'hero' },
  { key: 'SOLUTIONS', label: '解决方案', path: '/solutions', defaultSection: 'hero' },
  { key: 'ABOUT', label: '关于利强', path: '/about', defaultSection: 'intro' },
  { key: 'PACKAGING', label: '包装耗材', path: '/packaging', defaultSection: 'hero' },
  { key: 'MOLDS', label: '模具中心', path: '/molds', defaultSection: 'hero' },
]

/* ---------------- 结构化区块 Schema（showcase/service/cases 的内容 JSON 编辑） ---------------- */
type RowFieldType = 'text' | 'textarea' | 'image'
interface RowField {
  key: string
  label: string
  type: RowFieldType
  required?: boolean
  placeholder?: string
  hint?: string
}
interface StructuredSchema {
  title: string
  addLabel: string
  emptyHint: string
  fields: RowField[]
}
const STRUCTURED_SCHEMAS: Record<string, StructuredSchema> = {
  showcase: {
    title: '制造能力图廊',
    addLabel: '添加卡片',
    emptyHint: '留空时官网使用默认制造图廊；卡片按此顺序展示。',
    fields: [
      { key: 'title', label: '卡片标题', type: 'text', required: true },
      { key: 'description', label: '说明文字', type: 'textarea' },
      { key: 'image', label: '卡片图片', type: 'image' },
      { key: 'link', label: '跳转地址', type: 'text', placeholder: '如 /products、/molds、/about' },
    ],
  },
  intro: {
    title: '辅图',
    addLabel: '添加辅图',
    emptyHint: '留空时官网使用默认辅图；可添加 1 张辅图及说明，建议 4:3 比例。',
    fields: [
      { key: 'image', label: '辅图', type: 'image' },
      { key: 'caption', label: '图片说明', type: 'text', placeholder: '如：温州 · 制造现场' },
    ],
  },
  manufacturing: {
    title: '厂房图廊',
    addLabel: '添加图片',
    emptyHint: '留空时官网使用默认图廊；按此顺序展示，建议 4:3 比例。',
    fields: [
      { key: 'image', label: '图片', type: 'image' },
      { key: 'caption', label: '图片说明', type: 'text', placeholder: '如：生产与装配区域' },
    ],
  },
}
type StructuredRow = Record<string, string>

const router = useRouter()
const frameRef = ref<HTMLIFrameElement | null>(null)
const structurePanelRef = ref<HTMLElement | null>(null)
const sections = ref<WebsitePageSection[]>([])
const loading = ref(false)
const saving = ref(false)
const frameReady = ref(false)
const frameError = ref(false)
const frameKey = ref(0)
let frameHandshakeTimer: ReturnType<typeof setTimeout> | null = null
let frameHandshakeInterval: ReturnType<typeof setInterval> | null = null
const viewport = ref<Viewport>('desktop')
const selection = ref<CmsSelection>({ pageKey: 'HOME', sectionKey: 'hero', source: 'banner' })
const form = reactive<WebsitePageSectionPayload>({ pageKey: 'HOME', sectionKey: '', title: '', subtitle: '', content: '', imageUrl: '', sortOrder: 0, enabled: true })
const baseline = ref('')
const structuredRows = ref<StructuredRow[]>([])
/* 已推送到预览 iframe 的未保存草稿（含跨区块），结构面板据此显示标记；键为 `${pageKey}.${sectionKey}`。 */
const PUSHED_LS_KEY = 'liqiang-cms-pushed-drafts'
const PUSHED_BANNER_LS_KEY = 'liqiang-cms-pushed-banners'

function loadPushedFromLs(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(PUSHED_LS_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}
function savePushedToLs() {
  try {
    if (Object.keys(pushed).length === 0) localStorage.removeItem(PUSHED_LS_KEY)
    else localStorage.setItem(PUSHED_LS_KEY, JSON.stringify({ ...pushed }))
  } catch { /* ignore */ }
}
function loadPushedBannersFromLs(): number[] {
  try {
    const raw = localStorage.getItem(PUSHED_BANNER_LS_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(n => typeof n === 'number') : []
  } catch {
    return []
  }
}
function savePushedBannersToLs() {
  try {
    if (pushedBannerIds.size === 0) localStorage.removeItem(PUSHED_BANNER_LS_KEY)
    else localStorage.setItem(PUSHED_BANNER_LS_KEY, JSON.stringify(Array.from(pushedBannerIds)))
  } catch { /* ignore */ }
}

const pushed = reactive<Record<string, boolean>>(loadPushedFromLs())
const pushedBannerIds = new Set<number>(loadPushedBannersFromLs())
/* 写 pushed / pushedBannerIds 时同步持久化，避免编辑器刷新丢失未保存标记。 */
function markPushed(key: string, flag: boolean) {
  pushed[key] = flag
  if (!flag) delete pushed[key]
  savePushedToLs()
}
function markBannerPushed(id: number, flag: boolean) {
  if (flag) pushedBannerIds.add(id)
  else pushedBannerIds.delete(id)
  savePushedBannersToLs()
}
function clearAllPushedMarks() {
  for (const key of Object.keys(pushed)) delete pushed[key]
  pushedBannerIds.clear()
  localStorage.removeItem(PUSHED_LS_KEY)
  localStorage.removeItem(PUSHED_BANNER_LS_KEY)
}

/* ---------------- Hero Banner 状态 ---------------- */
const heroBannerItems = ref<WebsiteBannerSummary[]>([])
const heroBannerId = ref<number | null>(null)
const heroLoading = ref(false)
const bannerBaseline = ref('')
const bannerOriginalImageUrl = ref('')
const bannerForm = reactive({
  title: '', subtitle: '', description: '', imageUrl: '', badge: '',
  buttonText: '', buttonUrl: '', secondaryButtonText: '', secondaryButtonUrl: '',
  websiteEnabled: true, sortOrder: 0, imageAssetId: undefined as number | undefined,
  startDate: undefined as string | undefined, endDate: undefined as string | undefined,
})
const togglingId = ref<number | null>(null)

const websiteOrigin = computed(() => import.meta.env.VITE_CORPORATE_WEBSITE_URL || `${location.protocol}//${location.hostname}:5180`)
const activePage = computed(() => EDITABLE_PAGES.find(page => page.key === selection.value.pageKey) || EDITABLE_PAGES[0])
const frameUrl = computed(() => `${websiteOrigin.value}${activePage.value.path}?cmsEdit=1&cmsParentOrigin=${encodeURIComponent(location.origin)}`)
/* 每个页面实际渲染的区块（与 corporate-website 模板 data-cms-section 标注同步）。
 * V2 收口重构已删除 HOME 的 process/selector/cases/service/proof/selector-methods，
 * 以及 ABOUT 的 culture；此处过滤避免结构面板显示已下线的区块。 */
const PAGE_VALID_SECTIONS: Record<string, string[]> = {
  HOME: ['hero', 'strength', 'products', 'showcase', 'industries'],
  PRODUCTS: ['hero', 'catalog'],
  SOLUTIONS: ['hero', 'scenes', 'articles'],
  ABOUT: ['hero', 'intro', 'manufacturing', 'credentials', 'patents', 'contact'],
  PACKAGING: ['hero'],
  MOLDS: ['hero'],
}
const visibleSections = computed(() => {
  const validKeys = PAGE_VALID_SECTIONS[selection.value.pageKey]
  return sections.value
    .filter(item => item.pageKey === selection.value.pageKey)
    .filter(item => !validKeys || validKeys.includes(item.sectionKey))
    .sort((a, b) => a.sortOrder - b.sortOrder)
})
const selectedSection = computed(() => sections.value.find(item => item.pageKey === selection.value.pageKey && item.sectionKey === selection.value.sectionKey) || null)
const isHeroMode = computed(() => selection.value.pageKey === 'HOME' && selection.value.sectionKey === 'hero')
const currentSchema = computed(() => STRUCTURED_SCHEMAS[selection.value.sectionKey])
const isStructuredSection = computed(() => Boolean(currentSchema.value) && !isHeroMode.value)
const supportsSectionImage = computed(() => {
  if (selection.value.pageKey === 'ABOUT') return ['hero', 'intro', 'manufacturing'].includes(selection.value.sectionKey)
  if (selection.value.pageKey === 'PRODUCTS' || selection.value.pageKey === 'SOLUTIONS' || selection.value.pageKey === 'PACKAGING' || selection.value.pageKey === 'MOLDS') return selection.value.sectionKey === 'hero'
  return false
})
const sectionLabel = computed(() => {
  if (isHeroMode.value) return 'Hero 轮播'
  return PAGE_SECTION_HINTS[selection.value.sectionKey] || selection.value.sectionKey
})
const sectionTitle = computed(() => {
  if (isHeroMode.value) return 'Hero 轮播'
  return PAGE_SECTION_HINTS[selection.value.sectionKey] || form.title || '页面区块'
})

/* 结构化区块的实际生效内容（JSON 字符串）。普通区块原样保留 content。 */
const effectiveContent = computed(() => {
  if (!isStructuredSection.value) return form.content || ''
  try {
    return JSON.stringify(structuredRows.value)
  } catch {
    return form.content || ''
  }
})

const sourceInfo = computed<{ label: string; description: string; to?: string }>(() => {
  const key = selection.value.sectionKey
  /* Hero Banner 的增删改查全部在本面板完成，不再跳转 Banner 管理（Banner 管理仅维护 SIDE/ANNOUNCEMENT）。 */
  if (isHeroMode.value) return { label: '首页 Hero 轮播', description: '在此面板完成 Banner 的创建、编辑、删除、排序与官网显示切换。SIDE 与 ANNOUNCEMENT 位置的 Banner 请到「Banner 管理」维护。' }
  if (selection.value.pageKey === 'PRODUCTS') return { label: '页面配置 + 产品档案', description: '页面标题和说明在此修改；筛选条件与产品卡片来自产品档案。', to: '/products' }
  if (selection.value.pageKey === 'SOLUTIONS') return { label: '页面配置 + 解决方案内容', description: '页面标题和说明在此修改；场景卡片与文章来自解决方案管理。', to: '/website/solutions' }
  if (selection.value.pageKey === 'PACKAGING') return { label: '页面配置 + 包装耗材档案', description: '页面标题和说明在此修改；耗材系列卡片来自包装耗材档案。', to: '/packaging' }
  if (selection.value.pageKey === 'MOLDS') return { label: '页面配置 + 模具档案', description: '页面标题和说明在此修改；模具规格卡片来自模具档案。', to: '/molds' }
  if (key === 'products') return { label: '页面配置 + 产品档案', description: '标题在此修改；产品卡片来自已发布并设为首页精选的产品档案。', to: '/products' }
  if (key === 'industries') return { label: '页面配置 + 解决方案', description: '标题在此修改；场景卡片来自解决方案的行业场景。', to: '/website/solutions' }
  if (key === 'process') return { label: '页面配置 + 解决方案', description: '标题在此修改；合作流程步骤来自解决方案的「服务流程」。', to: '/website/solutions' }
  if (key === 'strength') return { label: '页面配置 + 公司信息', description: '标题在此修改；创立年份、产品数与专利数来自公司 / 产品档案。', to: '/company' }
  /* showcase/service/cases 等结构化区块的全部内容均在此面板直接编辑并实时预览，无需外链。 */
  if (key === 'showcase' || key === 'service' || key === 'cases' || key === 'proof' || key === 'selector-methods') {
    return { label: '页面配置（结构化内容）', description: '标题、描述与下方卡片列表都在此面板修改，保存后立即生效。' }
  }
  if (key === 'intro') return { label: '页面配置（辅图）', description: '标题、描述、主图与辅图都在此面板修改。主图为左侧大图，辅图为右侧小图（含说明文字）。' }
  if (key === 'manufacturing') return { label: '页面配置（图廊）', description: '标题、描述、主图与下方厂房图廊都在此面板修改。图廊图片按列表顺序展示。' }
  if (key === 'credentials') return { label: '页面配置', description: '区块标题、描述与显示状态在此修改；展示条目由官网本地数据维护，不涉及后台档案。' }
  if (key === 'patents') return { label: '页面配置', description: '区块标题、描述与显示状态在此修改；展示条目由官网本地数据维护，不涉及后台档案。' }
  if (selection.value.pageKey === 'ABOUT') return { label: '页面配置 + 公司信息', description: '区块展示文案与主图在此修改；企业事实、历程、文化及联系方式来自公司信息档案。', to: '/company' }
  return { label: '页面配置', description: '该区块的标题、描述和显示状态由固定页面区块控制。' }
})

/* ---------------- 表单序列化（脏检查） ---------------- */
function serializeForm() {
  return JSON.stringify({
    title: form.title || '', subtitle: form.subtitle || '',
    content: effectiveContent.value, imageUrl: form.imageUrl || '', enabled: form.enabled,
  })
}
function serializeBannerForm() {
  return JSON.stringify({ ...bannerForm })
}
const dirty = computed(() => {
  if (isHeroMode.value) return bannerBaseline.value !== serializeBannerForm()
  return baseline.value !== serializeForm()
})
/* 是否存在任意区块的未保存草稿（跨区块汇总，用于「放弃全部草稿」按钮）。 */
const hasPendingDrafts = computed(() => Object.values(pushed).some(Boolean))

/* Hero Banner 总开关（首页 Hero 区块显示状态） */
const heroBannersEnabled = computed({
  get: () => {
    const heroSection = sections.value.find(s => s.pageKey === 'HOME' && s.sectionKey === 'hero')
    return heroSection?.enabled ?? true
  },
  set: (val: boolean) => {
    const heroSection = sections.value.find(s => s.pageKey === 'HOME' && s.sectionKey === 'hero')
    if (heroSection) toggleSectionEnabled(heroSection, val)
  }
})

/* 获取区块元信息文本（用于结构面板副标签） */
function getSectionMeta(item: WebsitePageSection): string {
  if (!item.enabled) return '已隐藏'
  if (item.content) {
    try {
      const rows = JSON.parse(item.content)
      if (Array.isArray(rows) && rows.length) return `${rows.length} 项内容`
    } catch { /* ignore */ }
  }
  return item.imageUrl ? '含背景图' : '纯文字'
}

/* 获取结构化行的标题显示 */
function getStructuredRowTitle(row: StructuredRow, index: number): string {
  if (row.title) return row.title
  if (row.caption) return row.caption
  if (row.description) return row.description.slice(0, 20)
  return `第 ${index + 1} 项`
}

/* ---------------- 区块表单填充 ---------------- */
function parseContent(raw?: string | null): StructuredRow[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    const fields = currentSchema.value?.fields || []
    return parsed.map((row) => {
      const out: StructuredRow = {}
      for (const field of fields) out[field.key] = String(row?.[field.key] ?? '')
      return out
    })
  } catch {
    return []
  }
}
function fillForm(item: WebsitePageSection | null) {
  form.pageKey = item?.pageKey || selection.value.pageKey as PageSectionPageKey
  form.sectionKey = item?.sectionKey || selection.value.sectionKey
  form.title = item?.title || ''
  form.subtitle = item?.subtitle || ''
  form.content = item?.content || ''
  form.imageUrl = item?.imageUrl || ''
  form.sortOrder = item?.sortOrder || 0
  form.enabled = item?.enabled ?? true
  structuredRows.value = parseContent(item?.content)
  baseline.value = serializeForm()
}

async function loadSections(pageKey: PageSectionPageKey = selection.value.pageKey as PageSectionPageKey) {
  loading.value = true
  try {
    sections.value = await fetchPageSections(pageKey)
    /* 如果当前选中的区块不在有效区块列表中（如已被 V2 收口重构删除），自动回退到该页的默认区块。 */
    const validKeys = PAGE_VALID_SECTIONS[pageKey]
    if (validKeys && !validKeys.includes(selection.value.sectionKey)) {
      const defaultSection = EDITABLE_PAGES.find(p => p.key === pageKey)?.defaultSection || validKeys[0]
      selection.value = { pageKey, sectionKey: defaultSection, source: 'page-section' }
    }
    if (isHeroMode.value) return
    fillForm(selectedSection.value)
  } catch (error) {
    notify.errorWithDetail('加载首页区块失败', error)
  } finally {
    loading.value = false
  }
}

/* ---------------- Hero Banner 表单 ---------------- */
function fillBannerForm(detail: WebsiteBannerDetail) {
  heroBannerId.value = detail.id
  bannerForm.title = detail.title || ''
  bannerForm.subtitle = detail.subtitle || ''
  bannerForm.description = detail.description || ''
  bannerForm.imageUrl = detail.imageUrl || ''
  bannerOriginalImageUrl.value = detail.imageUrl || ''
  bannerForm.badge = detail.badge || ''
  bannerForm.buttonText = detail.buttonText || ''
  bannerForm.buttonUrl = detail.buttonUrl || ''
  bannerForm.secondaryButtonText = detail.secondaryButtonText || ''
  bannerForm.secondaryButtonUrl = detail.secondaryButtonUrl || ''
  bannerForm.websiteEnabled = detail.websiteEnabled
  bannerForm.sortOrder = detail.sortOrder
  bannerForm.imageAssetId = detail.imageAssetId || undefined
  bannerForm.startDate = detail.startDate || undefined
  bannerForm.endDate = detail.endDate || undefined
  bannerBaseline.value = serializeBannerForm()
  postToPreview({ type: 'LIQIANG_CMS_SHOW_BANNER', bannerId: detail.id })
}
async function loadBannerDetail(id: number) {
  /* 先用列表项已有的 imageUrl 立即填充预览，避免异步等待期间预览空白。 */
  const summary = heroBannerItems.value.find(b => b.id === id)
  if (summary?.imageUrl) bannerForm.imageUrl = summary.imageUrl
  try {
    const detail = await fetchBannerById(id)
    fillBannerForm(detail)
  } catch (error) {
    notify.errorWithDetail('加载 Banner 详情失败', error)
  }
}
async function loadHeroBanners() {
  heroLoading.value = true
  try {
    const page = await fetchBanners({ position: 'HERO', size: 50 })
    heroBannerItems.value = page.content || []
    const selected = heroBannerItems.value.find(b => b.id === heroBannerId.value)
    if (!selected) {
      const first = heroBannerItems.value.find(b => b.websiteEnabled) || heroBannerItems.value[0]
      heroBannerId.value = first?.id ?? null
    }
    if (heroBannerId.value != null) await loadBannerDetail(heroBannerId.value)
    else {
      heroBannerId.value = null
      bannerBaseline.value = ''
    }
  } catch (error) {
    notify.errorWithDetail('加载 Hero Banner 失败', error)
  } finally {
    heroLoading.value = false
  }
}

/* ---------------- 预览通信 ---------------- */
function postToPreview(message: Record<string, unknown>) {
  frameRef.value?.contentWindow?.postMessage(message, '*')
}
function reloadPreview() {
  frameReady.value = false
  frameError.value = false
  stopFrameHandshake()
  /* iframe 重挂载后官网侧 localStorage 草稿仍在，编辑器侧 pushed 标记保留，READY 时重推保持一致。 */
  frameKey.value += 1
}
function stopFrameHandshake() {
  if (frameHandshakeTimer) clearTimeout(frameHandshakeTimer)
  if (frameHandshakeInterval) clearInterval(frameHandshakeInterval)
  frameHandshakeTimer = null
  frameHandshakeInterval = null
}
function startFrameHandshake() {
  frameReady.value = false
  frameError.value = false
  stopFrameHandshake()
  const pingPreview = () => postToPreview({ type: 'LIQIANG_CMS_PING' })
  pingPreview()
  frameHandshakeInterval = setInterval(pingPreview, 500)
  frameHandshakeTimer = setTimeout(() => {
    stopFrameHandshake()
    if (!frameReady.value) frameError.value = true
  }, 4500)
}
function openWebsiteStandalone() { window.open(websiteOrigin.value, '_blank', 'noopener') }

async function selectSection(sectionKey: string, source = 'page-section', scroll = true) {
  if (sectionKey !== selection.value.sectionKey && dirty.value) {
    try {
      await ElMessageBox.confirm(
        `「${sectionLabel.value}」还有未保存修改，切换区块将放弃这些修改。`,
        '切换编辑区块',
        { confirmButtonText: '放弃并切换', cancelButtonText: '继续编辑', type: 'warning' },
      )
    } catch {
      postToPreview({ type: 'LIQIANG_CMS_FOCUS', sectionKey: selection.value.sectionKey, scroll: false })
      return
    }
  }
  selection.value = { pageKey: selection.value.pageKey, sectionKey, source }
  postToPreview({ type: 'LIQIANG_CMS_FOCUS', sectionKey, scroll })
  /* 选中区块后，结构面板自动滚动到对应行，确保用户知道当前编辑哪个区块。 */
  nextTick(() => {
    const panel = structurePanelRef.value
    if (!panel) return
    const active = panel.querySelector('.structure-row.active') as HTMLElement | null
    active?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  })
  if (isHeroMode.value) {
    loadHeroBanners()
    return
  }
  fillForm(selectedSection.value)
}

async function selectPage(pageKey: PageSectionPageKey) {
  if (pageKey === selection.value.pageKey) return
  if (hasPendingDrafts.value || dirty.value) {
    try {
      await ElMessageBox.confirm(
        '当前页面（包括本页其他未选中区块）和其他页面仍有未保存草稿，切换页面会刷新预览并丢失全部草稿。\n请先保存，或确认放弃这些草稿后继续。',
        '切换页面将丢失所有草稿',
        { confirmButtonText: '仍要切换', cancelButtonText: '先等等', type: 'warning' }
      )
    } catch {
      return
    }
    clearAllPushedMarks()
    postToPreview({ type: 'LIQIANG_CMS_CLEAR_DRAFT', pageKey: selection.value.pageKey, sectionKey: '' })
  }
  const page = EDITABLE_PAGES.find(item => item.key === pageKey) || EDITABLE_PAGES[0]
  selection.value = { pageKey: page.key, sectionKey: page.defaultSection, source: 'page-section' }
  sections.value = []
  fillForm(null)
  await loadSections(page.key)
  reloadPreview()
}

/* READY 后重推当前表单草稿：reloadPreview 会清空官网侧内存草稿，若表单仍 dirty 需重新推送一次保持预览一致。 */
function reapplyDraftOnReady() {
  if (isHeroMode.value) {
    if (heroBannerId.value == null) return
    postToPreview({
      type: 'LIQIANG_CMS_BANNER_DRAFT', bannerId: heroBannerId.value,
      data: {
        title: bannerForm.title, subtitle: bannerForm.subtitle, description: bannerForm.description,
        imageUrl: bannerForm.imageUrl, badge: bannerForm.badge,
        buttonText: bannerForm.buttonText, buttonUrl: bannerForm.buttonUrl,
        secondaryButtonText: bannerForm.secondaryButtonText, secondaryButtonUrl: bannerForm.secondaryButtonUrl,
      }
    })
    return
  }
  if (!form.sectionKey) return
  postToPreview({
    type: 'LIQIANG_CMS_DRAFT', pageKey: form.pageKey, sectionKey: form.sectionKey,
    data: { title: form.title, subtitle: form.subtitle, content: effectiveContent.value, imageUrl: form.imageUrl, enabled: form.enabled }
  })
}

function onPreviewMessage(event: MessageEvent) {
  if (event.source !== frameRef.value?.contentWindow) return
  if (event.data?.type === 'LIQIANG_CMS_READY' && event.data?.app === 'liqiang-corporate-website') {
    frameReady.value = true
    frameError.value = false
    stopFrameHandshake()
    postToPreview({ type: 'LIQIANG_CMS_FOCUS', sectionKey: selection.value.sectionKey, scroll: false })
    /* READY 后若表单仍 dirty，重推草稿避免预览与表单不一致（reloadPreview 后内存草稿已清空） */
    if (dirty.value) reapplyDraftOnReady()
  }
  if (event.data?.type === 'LIQIANG_CMS_SELECT') selectSection(event.data.sectionKey, event.data.source, false)
  if (event.data?.type === 'LIQIANG_CMS_NAV' && event.data?.pageKey) {
    const page = EDITABLE_PAGES.find(p => p.key === event.data.pageKey)
    if (page && page.key !== selection.value.pageKey) selectPage(page.key)
  }
}

function onBeforeWindowUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !hasPendingDrafts.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => {
  if (!dirty.value && !hasPendingDrafts.value) return true
  return window.confirm('当前有未保存的草稿（可能包含其他区块），确定离开可视化编辑器吗？')
})

/* ---------------- 实时草稿推送 ----------------
   仅在表单真正被用户修改（dirty）时才推送草稿，
   避免初始化加载后台数据时覆盖 iframe 已有的草稿（如 localStorage 中尚未保存的上传图片）。 */
watch(
  [() => form.title, () => form.subtitle, () => form.imageUrl, () => form.enabled, effectiveContent],
  () => {
    if (isHeroMode.value || !form.sectionKey) return
    const dirty = serializeForm() !== baseline.value
    markPushed(`${form.pageKey}.${form.sectionKey}`, dirty)
    if (!dirty) return
    postToPreview({
      type: 'LIQIANG_CMS_DRAFT', pageKey: form.pageKey, sectionKey: form.sectionKey,
      data: { title: form.title, subtitle: form.subtitle, content: effectiveContent.value, imageUrl: form.imageUrl, enabled: form.enabled }
    })
  }
)
watch(
  bannerForm,
  () => {
    if (!isHeroMode.value || heroBannerId.value == null) return
    const dirty = serializeBannerForm() !== bannerBaseline.value
    markPushed('HOME.hero', dirty)
    if (heroBannerId.value != null) markBannerPushed(heroBannerId.value, dirty)
    if (!dirty) return
    postToPreview({
      type: 'LIQIANG_CMS_BANNER_DRAFT', bannerId: heroBannerId.value,
      data: {
        title: bannerForm.title, subtitle: bannerForm.subtitle, description: bannerForm.description,
        imageUrl: bannerForm.imageUrl, badge: bannerForm.badge,
        buttonText: bannerForm.buttonText, buttonUrl: bannerForm.buttonUrl,
        secondaryButtonText: bannerForm.secondaryButtonText, secondaryButtonUrl: bannerForm.secondaryButtonUrl,
      }
    })
  },
  { deep: true }
)

/* ---------------- 结构化行操作 ---------------- */
function addStructuredRow() {
  const fields = currentSchema.value?.fields || []
  const row: StructuredRow = {}
  for (const field of fields) row[field.key] = ''
  structuredRows.value.push(row)
}
function removeStructuredRow(index: number) { structuredRows.value.splice(index, 1) }

/* ---------------- 保存 / 放弃 ---------------- */
async function save() {
  if (isHeroMode.value) { await saveBanner(); return }
  await saveSection()
}
async function saveBanner() {
  if (heroBannerId.value == null) {
    notify('请先选择一个 Hero Banner', 'warning')
    return
  }
  saving.value = true
  try {
    await updateBanner(heroBannerId.value, {
      position: 'HERO',
      ...bannerForm,
      imageAssetId: bannerForm.imageUrl === bannerOriginalImageUrl.value ? bannerForm.imageAssetId : undefined
    })
    notify('Banner 已保存并发布', 'success')
    if (heroBannerId.value != null) postToPreview({ type: 'LIQIANG_CMS_CLEAR_BANNER_DRAFT', bannerId: heroBannerId.value })
    markPushed('HOME.hero', false)
    if (heroBannerId.value != null) markBannerPushed(heroBannerId.value, false)
    await loadHeroBanners()
    reloadPreview()
  } catch (error) {
    notify.errorWithDetail('保存 Banner 失败', error)
  } finally {
    saving.value = false
  }
}

/* 创建新 Hero Banner：默认隐藏，避免创建即上线；用户编辑后再开启官网显示。 */
async function createHeroBanner() {
  if (dirty.value) {
    const ok = await askConfirm({
      title: '创建新 Banner',
      message: '当前 Hero Banner 有未保存修改，创建新 Banner 将放弃这些修改。',
      confirmText: '放弃并创建',
      cancelText: '继续编辑',
      danger: true
    })
    if (!ok) return
  }
  saving.value = true
  try {
    const nextSortOrder = heroBannerItems.value.length > 0
      ? Math.max(...heroBannerItems.value.map(b => b.sortOrder || 0)) + 1
      : 0
    const created = await createBanner({
      position: 'HERO',
      title: '新 Banner',
      subtitle: '',
      description: '',
      imageUrl: '',
      imageAssetId: undefined,
      buttonText: '探索产品',
      buttonUrl: '/products',
      secondaryButtonText: '获取包装方案',
      secondaryButtonUrl: '',
      badge: '',
      sortOrder: nextSortOrder,
      websiteEnabled: false,
      startDate: undefined,
      endDate: undefined,
    })
    notify('已创建新 Banner，请编辑后开启官网显示', 'success')
    await loadHeroBanners()
    heroBannerId.value = created.id
    await loadBannerDetail(created.id)
    reloadPreview()
  } catch (error) {
    notify.errorWithDetail('创建 Banner 失败', error)
  } finally {
    saving.value = false
  }
}

/* 删除当前 Hero Banner：软删除，可在 Banner 管理回收站恢复。 */
async function deleteHeroBanner() {
  if (heroBannerId.value == null) return
  const item = heroBannerItems.value.find(b => b.id === heroBannerId.value)
  const ok = await askConfirm({
    title: '删除 Banner',
    message: `确定删除「${item?.title || `Banner #${heroBannerId.value}`}」？此操作可在 Banner 管理的回收站恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  saving.value = true
  try {
    await deleteBanner(heroBannerId.value)
    notify('Banner 已删除', 'success')
    postToPreview({ type: 'LIQIANG_CMS_CLEAR_BANNER_DRAFT', bannerId: heroBannerId.value })
    markPushed('HOME.hero', false)
    markBannerPushed(heroBannerId.value, false)
    heroBannerId.value = null
    await loadHeroBanners()
    reloadPreview()
  } catch (error) {
    notify.errorWithDetail('删除 Banner 失败', error)
  } finally {
    saving.value = false
  }
}
async function saveSection() {
  const item = selectedSection.value
  if (!item) return
  if (isStructuredSection.value) {
    const missing = currentSchema.value?.fields
      .filter(f => f.required)
      .some(f => structuredRows.value.some(row => !row[f.key]?.trim()))
    if (missing) {
      notify('请填写所有必填项', 'warning')
      return
    }
  }
  saving.value = true
  try {
    await updatePageSection(item.id, { ...form, content: effectiveContent.value })
    notify('区块已保存并发布', 'success')
    postToPreview({ type: 'LIQIANG_CMS_CLEAR_DRAFT', pageKey: form.pageKey, sectionKey: form.sectionKey })
    markPushed(`${form.pageKey}.${form.sectionKey}`, false)
    await loadSections()
    reloadPreview()
  } catch (error) {
    notify.errorWithDetail('保存区块失败', error)
  } finally {
    saving.value = false
  }
}
function discardDraft() {
  if (isHeroMode.value) {
    if (heroBannerId.value != null) {
      postToPreview({ type: 'LIQIANG_CMS_CLEAR_BANNER_DRAFT', bannerId: heroBannerId.value })
      markPushed('HOME.hero', false)
      markBannerPushed(heroBannerId.value, false)
      loadBannerDetail(heroBannerId.value)
    }
    return
  }
  fillForm(selectedSection.value)
  postToPreview({ type: 'LIQIANG_CMS_CLEAR_DRAFT', pageKey: form.pageKey, sectionKey: form.sectionKey })
  markPushed(`${form.pageKey}.${form.sectionKey}`, false)
}

/* 放弃所有区块的未保存草稿（含 Hero Banner），恢复为已保存状态并重置预览。 */
function discardAllDrafts() {
  postToPreview({ type: 'LIQIANG_CMS_CLEAR_ALL_DRAFTS' })
  clearAllPushedMarks()
  if (isHeroMode.value) {
    if (heroBannerId.value != null) loadBannerDetail(heroBannerId.value)
  } else {
    fillForm(selectedSection.value)
  }
  reloadPreview()
}

/* ---------------- 结构面板：显示 / 隐藏 ---------------- */
async function toggleSectionEnabled(item: WebsitePageSection, newEnabled: boolean) {
  togglingId.value = item.id
  try {
    await updatePageSection(item.id, {
      pageKey: item.pageKey, sectionKey: item.sectionKey,
      title: item.title || undefined, subtitle: item.subtitle || undefined,
      content: item.content || undefined, imageUrl: item.imageUrl || undefined,
      sortOrder: item.sortOrder, enabled: newEnabled
    })
    notify(newEnabled ? '区块已显示' : '区块已隐藏', 'success')
    await loadSections()
    reloadPreview()
  } catch (error) {
    notify.errorWithDetail('更新区块显示失败', error)
  } finally {
    togglingId.value = null
  }
}

function onKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey)) return
  const key = event.key.toLowerCase()
  if (key === 's') {
    event.preventDefault()
    if (!saving.value && dirty.value) save()
  } else if (key === 'd') {
    event.preventDefault()
    if (dirty.value || (isHeroMode.value ? pushed['HOME.hero'] : pushed[`${form.pageKey}.${form.sectionKey}`])) discardDraft()
  }
}

onMounted(() => {
  window.addEventListener('message', onPreviewMessage)
  window.addEventListener('beforeunload', onBeforeWindowUnload)
  window.addEventListener('keydown', onKeydown)
  loadSections()
})
onBeforeUnmount(() => {
  window.removeEventListener('message', onPreviewMessage)
  window.removeEventListener('beforeunload', onBeforeWindowUnload)
  window.removeEventListener('keydown', onKeydown)
  stopFrameHandshake()
})
</script>

<template>
  <div class="visual-editor">
    <header class="visual-editor__toolbar">
      <div class="toolbar-brand">
        <div class="toolbar-brand__logo"><Monitor /></div>
        <div class="toolbar-brand__text">
          <h1>可视化官网编辑器</h1>
          <span class="toolbar-brand__subtitle">所见即所得 · 点击官网区块即可在右侧编辑</span>
        </div>
      </div>
      <div class="toolbar-nav">
        <div class="toolbar-nav__group">
          <span class="toolbar-nav__label">页面</span>
          <el-select
            :model-value="selection.pageKey"
            size="default"
            aria-label="选择官网页面"
            @change="selectPage"
          >
            <el-option
              v-for="page in EDITABLE_PAGES"
              :key="page.key"
              :label="page.label"
              :value="page.key"
            />
          </el-select>
        </div>
        <div class="toolbar-nav__divider"></div>
        <div class="toolbar-nav__group">
          <span class="toolbar-nav__label">设备</span>
          <el-radio-group v-model="viewport" size="default">
            <el-radio-button value="desktop"><el-icon><Monitor /></el-icon></el-radio-button>
            <el-radio-button value="tablet"><el-icon><Notebook /></el-icon></el-radio-button>
            <el-radio-button value="mobile"><el-icon><Iphone /></el-icon></el-radio-button>
          </el-radio-group>
        </div>
      </div>
      <div class="toolbar-actions">
        <el-tooltip content="刷新预览" placement="bottom">
          <el-button :icon="Refresh" circle @click="reloadPreview" />
        </el-tooltip>
        <template v-if="hasPendingDrafts">
          <el-button size="small" @click="discardAllDrafts">放弃全部草稿</el-button>
        </template>
        <template v-else-if="dirty">
          <el-button size="small" @click="discardDraft">放弃修改</el-button>
        </template>
        <el-tooltip content="保存 (Ctrl+S)" placement="bottom">
          <el-button
            type="primary"
            size="small"
            :icon="Check"
            :loading="saving"
            :disabled="!dirty"
            @click="save"
          >{{ isHeroMode ? '保存 Banner' : '保存区块' }}</el-button>
        </el-tooltip>
      </div>
    </header>

    <div class="visual-editor__workspace">
      <aside class="structure-panel" ref="structurePanelRef">
        <div class="structure-panel__header">
          <strong>{{ activePage.label }}</strong>
          <span>区块结构</span>
        </div>
        <p class="structure-panel__hint">点击区块名定位 · 开关控制官网显示</p>
        <div class="structure-panel__list">
          <div
            v-if="selection.pageKey === 'HOME'"
            type="button"
            class="structure-row structure-row--hero"
            :class="{ active: selection.sectionKey === 'hero' }"
            @click="selectSection('hero', 'banner')"
          >
            <div class="structure-row__icon">
              <el-icon><PictureFilled /></el-icon>
            </div>
            <div class="structure-row__content">
              <span class="structure-row__label">Hero 轮播</span>
              <span class="structure-row__meta">{{ heroBannerItems.length }} 个 Banner</span>
            </div>
            <div v-if="pushed['HOME.hero']" class="structure-row__draft" title="存在未保存草稿"></div>
            <div class="structure-row__switch-wrapper">
              <el-switch
                size="small"
                :model-value="heroBannersEnabled"
                @click.stop
              />
            </div>
          </div>
          <template v-for="item in visibleSections" :key="item.id">
            <div
              type="button"
              class="structure-row"
              :class="{ active: selection.sectionKey === item.sectionKey }"
              @click="selectSection(item.sectionKey)"
            >
              <div class="structure-row__index">{{ String(item.sortOrder).padStart(2, '0') }}</div>
              <div class="structure-row__content">
                <span class="structure-row__label">{{ PAGE_SECTION_HINTS[item.sectionKey] || item.title || item.sectionKey }}</span>
                <span class="structure-row__meta">{{ getSectionMeta(item) }}</span>
              </div>
              <div v-if="pushed[`${item.pageKey}.${item.sectionKey}`]" class="structure-row__draft" title="存在未保存草稿"></div>
              <div class="structure-row__switch-wrapper">
                <el-switch
                  size="small"
                  :model-value="item.enabled"
                  :loading="togglingId === item.id"
                  @click.stop
                  @change="(val: boolean) => toggleSectionEnabled(item, val)"
                />
              </div>
            </div>
          </template>
        </div>
      </aside>

      <section class="preview-stage" :class="`is-${viewport}`">
        <div class="preview-stage__status">
          <div class="status-indicator" :class="frameError ? 'error' : frameReady ? 'ready' : 'connecting'">
            <span></span>
          </div>
          <span class="status-text">
            {{ frameReady ? `已连接 · 正在编辑「${sectionLabel}」` : frameError ? '未连接到官网' : '正在连接官网…' }}
          </span>
        </div>
        <div class="preview-device">
          <div v-if="frameError" class="preview-connection-error">
            <el-icon class="preview-connection-error__icon"><WarningFilled /></el-icon>
            <strong>没有连接到企业官网</strong>
            <p>请先启动 corporate-website，预览地址固定为：</p>
            <code>{{ websiteOrigin }}</code>
            <div class="preview-connection-error__actions">
              <el-button type="primary" size="small" @click="reloadPreview">重新连接</el-button>
              <el-button size="small" @click="openWebsiteStandalone">单独打开官网</el-button>
            </div>
          </div>
          <div v-else-if="!frameReady" class="preview-stage__loading">
            <div class="loading-spinner"></div>
            <span>等待官网连接…</span>
          </div>
          <iframe
            v-show="frameReady"
            :key="frameKey"
            ref="frameRef"
            :src="frameUrl"
            title="利强官网可视化编辑预览"
            @load="startFrameHandshake"
          ></iframe>
        </div>
      </section>

      <aside class="editor-panel">
        <div class="editor-panel__header">
          <div class="editor-panel__breadcrumb">
            <span>{{ PAGE_SECTION_PAGE_LABELS[selection.pageKey as PageSectionPageKey] || activePage.label }}</span>
            <el-icon><ArrowRight /></el-icon>
            <span class="current">{{ sectionLabel }}</span>
          </div>
          <h2 class="editor-panel__title">{{ sectionTitle }}</h2>
          <div class="editor-panel__source" v-if="sourceInfo">
            <el-icon class="editor-panel__source-icon"><InfoFilled /></el-icon>
            <div class="editor-panel__source-content">
              <strong>{{ sourceInfo.label }}</strong>
              <span>{{ sourceInfo.description }}</span>
            </div>
            <el-button
              v-if="sourceInfo.to"
              link
              type="primary"
              size="small"
              @click="router.push(sourceInfo.to)"
            >前往管理 →</el-button>
          </div>
        </div>

        <!-- Hero Banner 编辑 -->
        <div v-if="isHeroMode" v-loading="heroLoading" class="editor-content">
          <el-form label-position="top" class="visual-form">
            <el-form-item label="选择 Banner">
              <div class="banner-selector">
                <el-select
                  v-model="heroBannerId"
                  placeholder="选择要编辑的 Banner"
                  @change="loadBannerDetail"
                >
                  <el-option
                    v-for="b in heroBannerItems"
                    :key="b.id"
                    :label="b.title || `Banner #${b.id}`"
                    :value="b.id"
                  >
                    <div class="banner-option">
                      <img v-if="b.imageUrl" :src="b.imageUrl" class="banner-option__thumb" alt="" />
                      <span class="banner-option__label">{{ b.title || `Banner #${b.id}` }}</span>
                    </div>
                  </el-option>
                </el-select>
                <el-button :icon="DocumentAdd" size="small" :loading="saving" @click="createHeroBanner">新建</el-button>
                <el-button v-if="heroBannerId != null" :icon="Delete" size="small" :loading="saving" type="danger" plain @click="deleteHeroBanner">删除</el-button>
              </div>
            </el-form-item>
            <template v-if="heroBannerId != null">
              <el-alert
                title="建议上传纯场景/设备图作为背景，文案由表单独立编辑，方便响应式排版。"
                type="info"
                :closable="false"
                show-icon
              />
              <div class="form-section">
                <div class="form-section__title">文案设置</div>
                <el-form-item label="主标题">
                  <el-input v-model="bannerForm.title" maxlength="200" show-word-limit placeholder="如：包装设备与模具" />
                  <span class="form-item-hint">支持 HTML 高亮标签，如 <code>{{ '<em>关键词</em>' }}</code></span>
                </el-form-item>
                <el-form-item label="副标题（Eyebrow）">
                  <el-input v-model="bannerForm.subtitle" maxlength="200" show-word-limit placeholder="顶部小字，如英文分类名" />
                </el-form-item>
                <el-form-item label="描述文案">
                  <el-input v-model="bannerForm.description" type="textarea" :rows="3" placeholder="1-2 句产品定位说明" />
                </el-form-item>
                <el-form-item label="标签">
                  <el-input v-model="bannerForm.badge" maxlength="100" placeholder="如 NEW / HOT，可留空" />
                </el-form-item>
              </div>
              <div class="form-section">
                <div class="form-section__title">背景与显示</div>
                <el-form-item label="Hero 背景图">
                  <WebsiteImageField v-model="bannerForm.imageUrl" aspect-ratio="12 / 5" :recommended-width="1920" :recommended-height="800" hint="建议 1920×800，纯场景图，不要内嵌文字" />
                </el-form-item>
                <el-form-item label="官网显示">
                  <el-switch v-model="bannerForm.websiteEnabled" />
                  <span class="field-note">隐藏后不会出现在公开 Banner 列表</span>
                </el-form-item>
                <el-form-item label="排序">
                  <el-input-number v-model="bannerForm.sortOrder" :min="0" />
                </el-form-item>
              </div>
              <div class="form-section">
                <div class="form-section__title">按钮设置</div>
                <div class="form-grid">
                  <el-form-item label="主按钮文字">
                    <el-input v-model="bannerForm.buttonText" />
                  </el-form-item>
                  <el-form-item label="主按钮链接">
                    <el-input v-model="bannerForm.buttonUrl" placeholder="/products" />
                  </el-form-item>
                  <el-form-item label="次按钮文字">
                    <el-input v-model="bannerForm.secondaryButtonText" />
                  </el-form-item>
                  <el-form-item label="次按钮链接">
                    <el-input v-model="bannerForm.secondaryButtonUrl" placeholder="留空则打开咨询弹窗" />
                  </el-form-item>
                </div>
              </div>
            </template>
            <el-empty v-else description="暂无 Hero Banner，点击「新建」开始添加" />
          </el-form>
          <div class="draft-state" :class="{ changed: dirty }">
            <div class="draft-state__dot"></div>
            <span>{{ dirty ? '存在未保存修改' : '当前与已保存数据一致' }}</span>
          </div>
        </div>

        <!-- 结构化内容区块 -->
        <template v-else-if="isStructuredSection && selectedSection">
          <div class="editor-content">
            <el-form label-position="top" class="visual-form">
              <div class="form-section">
                <div class="form-section__title">区块设置</div>
                <el-form-item label="区块标题">
                  <el-input v-model="form.title" maxlength="300" show-word-limit />
                </el-form-item>
                <el-form-item label="区块描述">
                  <el-input v-model="form.subtitle" type="textarea" :rows="3" maxlength="1000" show-word-limit />
                </el-form-item>
                <el-form-item v-if="supportsSectionImage" label="区块主图">
                  <WebsiteImageField v-model="form.imageUrl" aspect-ratio="16 / 9" :recommended-width="1600" :recommended-height="900" hint="建议 1600×900，修改后立即预览" />
                </el-form-item>
                <el-form-item label="官网显示">
                  <el-switch v-model="form.enabled" />
                  <span class="field-note">隐藏后访客看不到整个区块</span>
                </el-form-item>
              </div>
              <div class="form-section">
                <div class="form-section__title">{{ currentSchema!.title }}</div>
                <p class="form-section__hint">{{ currentSchema!.emptyHint }}</p>
                <div class="structured-editor">
                  <div v-for="(row, index) in structuredRows" :key="index" class="structured-row">
                    <div class="structured-row__head">
                      <span class="structured-row__index">{{ index + 1 }}</span>
                      <strong>{{ getStructuredRowTitle(row, index) }}</strong>
                      <el-button text type="danger" size="small" :icon="Delete" @click="removeStructuredRow(index)">移除</el-button>
                    </div>
                    <div class="structured-row__fields">
                      <template v-for="field in currentSchema!.fields" :key="field.key">
                        <el-form-item v-if="field.type === 'image'" :label="field.label">
                          <WebsiteImageField v-model="row[field.key]" aspect-ratio="4 / 3" :recommended-width="800" :recommended-height="600" :hint="field.hint || '建议 800×600，保持卡片图片统一比例'" />
                        </el-form-item>
                        <el-form-item v-else :label="field.label">
                          <el-input
                            v-if="field.type === 'text'"
                            v-model="row[field.key]"
                            :placeholder="field.placeholder || '请输入' + field.label"
                          />
                          <el-input
                            v-else-if="field.type === 'textarea'"
                            v-model="row[field.key]"
                            type="textarea"
                            :rows="2"
                            :placeholder="field.placeholder || '请输入' + field.label"
                          />
                        </el-form-item>
                      </template>
                    </div>
                  </div>
                  <el-button class="add-row-btn" :icon="Plus" @click="addStructuredRow">{{ currentSchema!.addLabel }}</el-button>
                </div>
              </div>
            </el-form>
            <div class="draft-state" :class="{ changed: dirty }">
              <div class="draft-state__dot"></div>
              <span>{{ dirty ? '存在未保存修改' : '当前与已保存数据一致' }}</span>
            </div>
          </div>
        </template>

        <!-- 普通区块 -->
        <template v-else-if="!isHeroMode && selectedSection">
          <div class="editor-content">
            <el-form label-position="top" class="visual-form">
              <div class="form-section">
                <div class="form-section__title">区块内容</div>
                <el-form-item label="区块标题">
                  <el-input v-model="form.title" maxlength="300" show-word-limit />
                </el-form-item>
                <el-form-item label="区块描述">
                  <el-input v-model="form.subtitle" type="textarea" :rows="4" maxlength="1000" show-word-limit />
                </el-form-item>
                <el-form-item v-if="supportsSectionImage" label="区块背景图">
                  <WebsiteImageField v-model="form.imageUrl" aspect-ratio="12 / 5" :recommended-width="1920" :recommended-height="800" hint="建议 1920×800，修改后立即预览" />
                </el-form-item>
                <el-form-item label="官网显示">
                  <el-switch v-model="form.enabled" />
                  <span class="field-note">隐藏后访客看不到整个区块</span>
                </el-form-item>
              </div>
            </el-form>
            <div class="draft-state" :class="{ changed: dirty }">
              <div class="draft-state__dot"></div>
              <span>{{ dirty ? '存在未保存修改' : '当前与已保存数据一致' }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!isHeroMode" description="该区块尚未建立页面配置" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* ============ 主容器 ============ */
.visual-editor {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  height: calc(100vh - var(--app-header-height, 58px) - 24px);
  min-height: 680px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
}

/* ============ 顶部工具栏 ============ */
.visual-editor__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xl-2xl);
  padding: var(--space-lg-xl) var(--space-xl-2xl);
  border-bottom: 1px solid var(--color-border-light);
  background: linear-gradient(180deg, var(--color-surface) 0%, var(--color-surface-alt) 100%);
}

.toolbar-brand {
  display: flex;
  align-items: center;
  gap: var(--space-md-lg);
  flex-shrink: 0;
}

.toolbar-brand__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-lg);
  background: linear-gradient(135deg, var(--color-primary) 0%, var(--color-primary-light) 100%);
  color: var(--color-text-on-primary);
  font-size: 18px;
  box-shadow: 0 4px 12px var(--color-primary-ring);
}

.toolbar-brand__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.toolbar-brand__text h1 {
  margin: 0;
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  line-height: var(--lh-tight);
}

.toolbar-brand__subtitle {
  font-size: var(--fs-3xs);
  color: var(--color-text-faint);
  line-height: var(--lh-normal);
}

.toolbar-nav {
  display: flex;
  align-items: center;
  gap: var(--space-lg-xl);
  flex-shrink: 0;
}

.toolbar-nav__group {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md);
}

.toolbar-nav__label {
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
  font-weight: var(--fw-medium);
}

.toolbar-nav__divider {
  width: 1px;
  height: 24px;
  background: var(--color-border-light);
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md);
  flex-shrink: 0;
}

/* ============ 工作区 ============ */
.visual-editor__workspace {
  display: grid;
  grid-template-columns: 240px minmax(500px, 1fr) 420px;
  min-height: 0;
}

/* ============ 结构面板 ============ */
.structure-panel {
  display: flex;
  flex-direction: column;
  overflow: auto;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
}

.structure-panel__header {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-lg-xl) var(--space-lg-xl) var(--space-sm);
}

.structure-panel__header strong {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
}

.structure-panel__header span {
  font-size: var(--fs-3xs);
  color: var(--color-text-faint);
}

.structure-panel__hint {
  margin: 0;
  padding: 0 var(--space-lg-xl) var(--space-md-lg);
  font-size: var(--fs-3xs);
  color: var(--color-text-muted);
  line-height: var(--lh-relaxed);
  border-bottom: 1px solid var(--color-border-lighter);
}

.structure-panel__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-md-lg);
}

.structure-row {
  display: grid;
  grid-template-columns: 28px 1fr auto auto;
  align-items: center;
  gap: var(--space-sm-md);
  width: 100%;
  padding: var(--space-sm-md) var(--space-md-lg);
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  background: transparent;
  color: var(--color-text-primary);
  text-align: left;
  cursor: pointer;
  transition: background-color var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}

.structure-row:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-lighter);
}

.structure-row.active {
  background: var(--color-primary-bg-light);
  border-color: var(--color-primary-border-light);
  color: var(--color-primary);
}

.structure-row__index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-semibold);
  color: var(--color-text-faint);
}

.structure-row.active .structure-row__index {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

.structure-row__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: 14px;
}

.structure-row__content {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.structure-row__label {
  overflow: hidden;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.structure-row__meta {
  overflow: hidden;
  font-size: var(--fs-3xs);
  color: var(--color-text-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.structure-row.active .structure-row__meta {
  color: var(--color-primary);
  opacity: 0.8;
}

.structure-row__draft {
  position: absolute;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-warning);
  box-shadow: 0 0 0 2px var(--color-surface);
  animation: pulse-dot 2s ease-in-out infinite;
}

.structure-row {
  position: relative;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.structure-row__switch-wrapper {
  display: flex;
  align-items: center;
}

.structure-row--hero {
  grid-template-columns: auto 1fr auto auto;
}

.structure-row--hero .structure-row__icon {
  background: var(--color-warning-bg);
  color: var(--color-warning);
}

/* ============ 预览区 ============ */
.preview-stage {
  position: relative;
  display: grid;
  place-items: start center;
  min-width: 0;
  overflow: auto;
  padding: var(--space-2xl) var(--space-lg-xl) var(--space-lg-xl);
  background: var(--color-page-bg);
}

.preview-stage__status {
  position: absolute;
  top: var(--space-md-lg);
  left: var(--space-lg-xl);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm-md);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  box-shadow: var(--shadow-xs);
}

.status-indicator {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
}

.status-indicator span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-warning);
}

.status-indicator.ready span {
  background: var(--color-success);
  box-shadow: 0 0 6px var(--color-success);
}

.status-indicator.error span {
  background: var(--color-danger);
  animation: blink 1s ease-in-out infinite;
}

.status-indicator.connecting span {
  background: var(--color-warning);
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.3); }
}

.status-text {
  font-size: var(--fs-3xs);
  color: var(--color-text-secondary);
  font-weight: var(--fw-medium);
}

.preview-device {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-md);
  transition: width var(--transition-slow), border-radius var(--transition-slow), border var(--transition-slow);
}

.preview-stage.is-tablet .preview-device {
  width: 820px;
  max-width: 100%;
  border-radius: var(--radius-xl);
  border: 6px solid var(--color-text-heading);
  box-shadow: var(--shadow-lg);
}

.preview-stage.is-mobile .preview-device {
  width: 390px;
  max-width: 100%;
  border-radius: 36px;
  border: 8px solid var(--color-text-heading);
  box-shadow: var(--shadow-lg);
}

.preview-device iframe {
  width: 100%;
  height: 100%;
  border: 0;
  background: var(--color-surface);
}

.preview-connection-error {
  position: absolute;
  z-index: 2;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--space-md-lg);
  padding: var(--space-3xl);
  background: var(--color-surface);
  color: var(--color-text-primary);
  text-align: center;
}

.preview-connection-error__icon {
  font-size: 48px;
  color: var(--color-warning);
}

.preview-connection-error strong {
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
}

.preview-connection-error p {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
}

.preview-connection-error code {
  padding: var(--space-sm-md) var(--space-md-lg);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  color: var(--color-primary);
  font-size: var(--fs-xxs);
  font-family: var(--font-mono);
}

.preview-connection-error__actions {
  display: flex;
  gap: var(--space-sm-md);
  margin-top: var(--space-md-lg);
}

.preview-stage__loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-md-lg);
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-border-light);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ============ 编辑面板 ============ */
.editor-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border-light);
}

.editor-panel__header {
  flex-shrink: 0;
  padding: var(--space-lg-xl);
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface);
}

.editor-panel__breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--fs-3xs);
  color: var(--color-text-faint);
}

.editor-panel__breadcrumb .current {
  color: var(--color-primary);
  font-weight: var(--fw-medium);
}

.editor-panel__title {
  margin: var(--space-xs) 0 var(--space-md-lg);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  line-height: var(--lh-snug);
}

.editor-panel__source {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm-md);
  padding: var(--space-sm-md) var(--space-md-lg);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
}

.editor-panel__source-icon {
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--color-primary);
  font-size: 16px;
}

.editor-panel__source-content {
  display: flex;
  flex-direction: column;
  gap: 1px;
  flex: 1;
  min-width: 0;
}

.editor-panel__source-content strong {
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--color-text-heading);
}

.editor-panel__source-content span {
  font-size: var(--fs-3xs);
  color: var(--color-text-muted);
  line-height: var(--lh-relaxed);
}

.editor-content {
  flex: 1;
  overflow: auto;
  padding: var(--space-lg-xl);
}

/* ============ 表单样式 ============ */
.visual-form :deep(.el-form-item) {
  margin-bottom: var(--space-md-lg);
}

.form-section {
  margin-bottom: var(--space-lg-xl);
  padding: var(--space-md-lg);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
}

.form-section__title {
  margin-bottom: var(--space-md-lg);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.form-section__title::before {
  content: '';
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--color-primary);
}

.form-section__hint {
  margin: 0 0 var(--space-md-lg);
  padding: var(--space-sm-md) var(--space-md-lg);
  border-radius: var(--radius-md);
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
  font-size: var(--fs-3xs);
  line-height: var(--lh-relaxed);
}

.form-item-hint {
  display: block;
  margin-top: var(--space-xs);
  font-size: var(--fs-3xs);
  color: var(--color-text-faint);
}

.form-item-hint code {
  padding: 1px var(--space-xs);
  border-radius: var(--radius-xs);
  background: var(--color-surface);
  font-size: var(--fs-3xs);
}

.field-note {
  display: block;
  margin-top: var(--space-xs);
  font-size: var(--fs-3xs);
  color: var(--color-text-faint);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 var(--space-md-lg);
}

/* ============ Banner 选择器 ============ */
.banner-selector {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md);
  width: 100%;
}

.banner-selector .el-select {
  flex: 1;
  min-width: 0;
}

.banner-option {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md);
}

.banner-option__thumb {
  width: 48px;
  height: 28px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.banner-option__label {
  overflow: hidden;
  font-size: var(--fs-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ============ 结构化编辑器 ============ */
.structured-editor {
  display: grid;
  width: 100%;
  gap: var(--space-md-lg);
}

.structured-row {
  display: grid;
  gap: var(--space-sm-md);
  padding: var(--space-md-lg);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.structured-row__head {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md);
}

.structured-row__head .structured-row__index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-semibold);
  color: var(--color-primary);
}

.structured-row__head strong {
  flex: 1;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--color-text-heading);
}

.structured-row__fields {
  display: grid;
  gap: var(--space-md-lg);
}

.add-row-btn {
  width: 100%;
  border-style: dashed;
}

/* ============ 草稿状态 ============ */
.draft-state {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: var(--space-lg-xl);
  padding: var(--space-sm-md) var(--space-md-lg);
  border-radius: var(--radius-lg);
  background: var(--color-success-bg);
  color: var(--color-success-text);
  font-size: var(--fs-3xs);
  font-weight: var(--fw-medium);
}

.draft-state__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

.draft-state.changed {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}

/* ============ 响应式 ============ */
@media (max-width: 1200px) {
  .visual-editor__workspace {
    grid-template-columns: 200px minmax(440px, 1fr) 360px;
  }
  .editor-panel__title {
    font-size: var(--fs-lg);
  }
}

@media (max-width: 900px) {
  .toolbar-brand__subtitle,
  .toolbar-nav__label {
    display: none;
  }
  .visual-editor__workspace {
    grid-template-columns: 1fr 320px;
  }
  .structure-panel {
    display: none;
  }
}
</style>
