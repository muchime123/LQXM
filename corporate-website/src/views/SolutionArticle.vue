<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import SiteCTA from '../components/common/SiteCTA.vue'
import ArticleViewer from '../components/article/ArticleViewer.vue'
import { fetchSolutionArticle, fetchSolutionArticles } from '../api/website'
import { setSeo } from '../composables/useSeo'

const route = useRoute()
const article = ref(null)
const allArticles = ref([])
const loading = ref(true)

function normalizeArticle(item) {
  return {
    ...item,
    coverImage: item.coverImageUrl || item.coverImage || '',
    sections: item.contentSections || item.sections || [],
    contentHtml: item.contentHtml || ''
  }
}

async function loadArticle() {
  loading.value = true
  const slug = route.params.slug
  try {
    const [detail, list] = await Promise.all([
      fetchSolutionArticle(slug),
      fetchSolutionArticles(),
    ])
    article.value = detail ? normalizeArticle(detail) : null
    if (list?.length) allArticles.value = list.map(normalizeArticle)
  } catch (err) {
    console.warn('Solution article CMS unavailable:', err)
  } finally {
    loading.value = false
  }

  if (article.value) {
    setSeo(
      article.value.seoTitle || article.value.title,
      article.value.seoDescription || article.value.summary,
    )
  }
}

const related = computed(() => article.value
  ? allArticles.value.filter((item) => item.slug !== article.value.slug && item.category === article.value.category).slice(0, 2)
  : [])

const readingTime = computed(() => {
  const html = article.value?.contentHtml || ''
  const text = html.replace(/<[^>]+>/g, '').trim()
  if (!text) return ''
  return `约 ${Math.max(1, Math.round(text.length / 400))} 分钟读完`
})

watch(() => route.params.slug, loadArticle, { immediate: true })
</script>

<template>
  <main class="solution-article-page">
    <div v-if="loading" class="solution-article-missing">
      <p>正在加载方案内容…</p>
    </div>
    <div v-else-if="!article" class="solution-article-missing">
      <h1>没有找到这篇方案内容</h1>
      <RouterLink to="/solutions" class="button primary">返回解决方案</RouterLink>
    </div>
    <template v-else>
      <nav class="solution-breadcrumb" aria-label="面包屑">
        <RouterLink to="/">首页</RouterLink><span>/</span>
        <RouterLink to="/solutions">解决方案</RouterLink><span>/</span>
        <strong>{{ article.category }}</strong>
      </nav>

      <header class="solution-article-head">
        <p class="solution-article-head__eyebrow">{{ article.category }} · SOLUTION GUIDE</p>
        <h1>{{ article.title }}</h1>
        <div class="solution-article-head__meta">
          <span v-if="article.updatedAt">更新于 {{ article.updatedAt.slice(0, 10) }}</span>
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

      <ArticleViewer :article="article" />

      <div class="solution-article-disclaimer">
        本文用于前期判断与沟通参考。具体包装参数、保鲜结果和设备配置，需要结合实际产品、包材、环境及样品测试确认。
      </div>

      <section v-if="related.length" class="solution-related-reading">
        <header>
          <p class="eyebrow dark"><span></span> RELATED GUIDES</p>
          <h2>继续阅读</h2>
        </header>
        <div>
          <RouterLink v-for="item in related" :key="item.slug" :to="`/solutions/${item.slug}`">
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
      />
    </template>
  </main>
</template>