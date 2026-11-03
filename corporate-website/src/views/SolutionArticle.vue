<script setup>
/**
 * 方案文章详情页。
 *
 * 状态边界（三者必须分开，不能都显示成「没有找到」）：
 *   loading   首次/切换文章加载中
 *   error     内容服务异常（可重试）
 *   missing   明确 404：文章未发布或链接已调整
 *   article   已拿到文章；正文为空时单独提示「正文待补充」
 *
 * 切换文章用请求序号保护，避免旧请求晚返回把新文章的正文/SEO 覆盖掉。
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import SiteCTA from '../components/common/SiteCTA.vue'
import ArticleViewer from '../components/article/ArticleViewer.vue'
import { fetchSolutionArticle, fetchSolutionArticles } from '../api/website'
import { setSeo, SITE_URL } from '../composables/useSeo'
import { useInquiryModal } from '../composables/useInquiryModal'
import { createLatestRequestGuard } from '../utils/productQuery'
import { sectionsToHtml } from '../utils/sectionsToHtml'

const route = useRoute()
const { openInquiry } = useInquiryModal()

const article = ref(null)
const allArticles = ref([])
const loading = ref(true)
const error = ref(null)
const missing = ref(false)

const requestGuard = createLatestRequestGuard()

function normalizeArticle(item) {
  if (!item) return null
  return {
    ...item,
    coverImage: item.coverImageUrl || item.coverImage || '',
    sections: item.contentSections || item.sections || [],
    contentHtml: typeof item.contentHtml === 'string' ? item.contentHtml : '',
    relatedSceneKeys: Array.isArray(item.relatedSceneKeys) ? item.relatedSceneKeys : [],
  }
}

/** 正文最终来源：与 ArticleViewer 同口径，空白富文本回退旧版结构化小节。 */
function resolveHtml(model) {
  if (!model) return ''
  const rich = (model.contentHtml || '').trim()
  if (rich) return rich
  return sectionsToHtml(model.sections)
}

const contentHtml = computed(() => resolveHtml(article.value))

/** 只有标签没有文字的正文视为空（例如运营只插入了一个空段落）。 */
const hasContent = computed(() => {
  const html = contentHtml.value
  if (!html.trim()) return false
  if (/<(img|table|iframe|video)\b/i.test(html)) return true
  return html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, '').length > 0
})

const readingTime = computed(() => {
  const text = contentHtml.value.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()
  if (!text) return ''
  return `约 ${Math.max(1, Math.round(text.length / 400))} 分钟读完`
})

/* 来源场景：从 /solutions?scene=xxx 进入时保留，用于「返回当前场景」与咨询上下文。 */
const sceneKey = computed(() => String(route.query.scene || ''))

const sceneReturn = computed(() => (sceneKey.value
  ? { path: '/solutions', query: { scene: sceneKey.value } }
  : null))

/** 相关阅读：共享场景标签优先，其次同分类，最后补足其他文章，最多 3 篇。 */
const related = computed(() => {
  const current = article.value
  if (!current) return []
  const others = allArticles.value.filter((item) => item.slug !== current.slug)
  const sceneKeys = new Set(current.relatedSceneKeys)
  const sceneMatched = others.filter((item) => item.relatedSceneKeys.some((key) => sceneKeys.has(key)))
  const picked = new Set(sceneMatched.map((item) => item.slug))
  const categoryMatched = others.filter((item) => !picked.has(item.slug) && item.category && item.category === current.category)
  categoryMatched.forEach((item) => picked.add(item.slug))
  const rest = others.filter((item) => !picked.has(item.slug))
  return [...sceneMatched, ...categoryMatched, ...rest].slice(0, 3)
})

function applySeo(model, slug) {
  const canonical = `${SITE_URL}/solutions/${slug}`
  if (!model) {
    /* 未发布 / 加载失败：不设 canonical，避免把不存在的文章标记为可索引 */
    setSeo(
      '包装方案参考',
      '围绕包装生产场景、常见问题与设备选型整理的实用方案参考。',
      { ogType: 'article' },
    )
    return
  }
  setSeo(
    model.seoTitle || model.title,
    model.seoDescription || model.summary,
    {
      ogType: 'article',
      ogImage: model.coverImage || undefined,
      canonical,
    },
  )
}

async function loadArticle() {
  const request = requestGuard.start()
  const slug = String(route.params.slug || '')
  loading.value = true
  error.value = null
  missing.value = false

  let detail = null
  let list = []
  let failure = null
  try {
    const [detailResult, listResult] = await Promise.all([
      fetchSolutionArticle(slug),
      /* 相关阅读依赖列表，但列表失败不应让正文不可读 */
      fetchSolutionArticles().catch(() => []),
    ])
    detail = detailResult
    list = Array.isArray(listResult) ? listResult : []
  } catch (err) {
    failure = err
  }

  if (!requestGuard.isLatest(request)) return
  loading.value = false

  if (failure) {
    error.value = failure
    article.value = null
    applySeo(null, slug)
    console.warn('Solution article unavailable:', failure)
    return
  }

  /* 接口返回了与 URL 不一致的文章时按未发布处理，避免正文/SEO 与地址栏错位 */
  if (!detail || (detail.slug && detail.slug !== slug)) {
    missing.value = true
    article.value = null
    allArticles.value = []
    applySeo(null, slug)
    return
  }

  article.value = normalizeArticle(detail)
  allArticles.value = list.map(normalizeArticle).filter(Boolean)
  applySeo(article.value, slug)
}

function openConsult() {
  openInquiry({
    type: 'consult',
    source: 'solution-article',
    articleSlug: String(route.params.slug || ''),
    articleTitle: article.value?.title || '',
    sceneKey: sceneKey.value,
    sceneLabel: article.value?.category || '',
  })
}

watch(() => route.params.slug, loadArticle, { immediate: true })
</script>

<template>
  <main class="solution-article-page">
    <div v-if="loading" class="solution-article-missing">
      <p>正在加载方案内容…</p>
    </div>

    <!-- 内容服务异常：可重试，与「文章不存在」区分 -->
    <div v-else-if="error" class="solution-article-missing">
      <h1>方案内容暂时无法加载</h1>
      <p>内容服务暂时没有响应。可以重新加载，或直接描述包装问题由工程师协助判断。</p>
      <div class="solution-article-missing__actions">
        <button type="button" class="button primary" @click="loadArticle">重新加载</button>
        <button type="button" class="button ghost" @click="openConsult">描述包装问题</button>
      </div>
    </div>

    <!-- 明确 404：文章未发布或链接已调整 -->
    <div v-else-if="missing" class="solution-article-missing">
      <h1>这篇方案还没有发布</h1>
      <p>该链接可能已经调整。您可以返回方案列表查看已发布内容，或直接描述包装问题。</p>
      <div class="solution-article-missing__actions">
        <RouterLink to="/solutions" class="button primary">返回解决方案</RouterLink>
        <button type="button" class="button ghost" @click="openConsult">描述包装问题</button>
      </div>
    </div>

    <template v-else-if="article">
      <nav class="solution-breadcrumb" aria-label="面包屑">
        <RouterLink to="/">首页</RouterLink><span>/</span>
        <RouterLink to="/solutions">解决方案</RouterLink><span>/</span>
        <strong>{{ article.category }}</strong>
      </nav>

      <!-- 从场景筛选进入时，额外提供恢复原筛选的入口；面包屑保持默认导航语义 -->
      <div v-if="sceneReturn" class="solution-article-scene-back">
        <RouterLink :to="sceneReturn">← 返回当前应用场景方案</RouterLink>
      </div>

      <header class="solution-article-head">
        <p class="solution-article-head__eyebrow">{{ article.category }} · SOLUTION GUIDE</p>
        <h1>{{ article.title }}</h1>
        <div class="solution-article-head__meta">
          <span v-if="article.updatedAt">更新于 {{ String(article.updatedAt).slice(0, 10) }}</span>
          <span v-if="readingTime">{{ readingTime }}</span>
        </div>
      </header>

      <div v-if="article.coverImage" class="solution-article-cover">
        <img :src="article.coverImage" :alt="article.title" decoding="async" />
      </div>

      <div v-if="article.intro" class="solution-article-lead">
        <span>问题概述</span>
        <p>{{ article.intro }}</p>
      </div>

      <ArticleViewer v-if="hasContent" :article="article" />

      <!-- 文章已发布但正文还没补：不要显示成「文章不存在」 -->
      <div v-else class="solution-article-body-empty">
        <h2>正文正在补充中</h2>
        <p>这篇方案的正文还在整理。您可以先查看其他方案，或直接描述包装问题由工程师协助判断。</p>
        <div class="solution-article-missing__actions">
          <RouterLink to="/solutions" class="button ghost">查看其他方案</RouterLink>
          <button type="button" class="button primary" @click="openConsult">描述包装问题</button>
        </div>
      </div>

      <div class="solution-article-disclaimer">
        本文用于前期判断与沟通参考。具体包装参数、保鲜结果和设备配置，需要结合实际产品、包材、环境及样品测试确认。
      </div>

      <section v-if="related.length" class="solution-related-reading">
        <header>
          <p class="eyebrow dark"><span></span> RELATED GUIDES</p>
          <h2>继续阅读</h2>
        </header>
        <div>
          <RouterLink
            v-for="item in related"
            :key="item.slug"
            :to="sceneReturn
              ? { path: `/solutions/${item.slug}`, query: sceneReturn.query }
              : `/solutions/${item.slug}`"
          >
            <img v-if="item.coverImage" :src="item.coverImage" :alt="item.title" loading="lazy" decoding="async" />
            <span>{{ item.category }}</span><strong>{{ item.title }}</strong><b>阅读全文 →</b>
          </RouterLink>
        </div>
      </section>

      <SiteCTA
        title="需要结合您的产品进一步判断？"
        description="提供产品、容器、膜材、当前问题和目标产能，我们将协助安排样品试封或设备选型沟通。"
        action-text="提交具体问题"
        inquiry-source="solution-article-bottom"
        :article-slug="String(route.params.slug || '')"
        :article-title="article.title"
        :scene-key="sceneKey"
      />
    </template>
  </main>
</template>
