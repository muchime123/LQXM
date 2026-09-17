<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHero from '../components/common/PageHero.vue'
import SiteCTA from '../components/common/SiteCTA.vue'
import { fetchSolutions } from '../api/website'
import { usePageSections } from '../composables/usePageSections'
import { useCmsPreview } from '../composables/useCmsPreview'

const route = useRoute()
const router = useRouter()
const pageSections = usePageSections('SOLUTIONS')
const cmsPreview = useCmsPreview('SOLUTIONS')
const sectionEnabled = (key) => pageSections.isEnabled(key)
const pageContent = computed(() => {
  const s = (key) => pageSections.section(key)
  const d = (key) => cmsPreview.draft(key)
  return {
    hero: {
      title: d('hero').title || s('hero')?.title || '从应用场景出发，找到可借鉴的包装方案',
      description: d('hero').subtitle || s('hero')?.subtitle || '按产品与应用场景进入，查看问题诊断、工艺判断与设备选型参考。',
      image: d('hero').imageUrl || s('hero')?.imageUrl || '',
    },
    scenes: {
      title: d('scenes').title || s('scenes')?.title || '按应用场景查找',
      description: d('scenes').subtitle || s('scenes')?.subtitle || '先选择与您接近的产品和生产环境，再查看该场景下的工艺判断与实用内容。这里不是另一份设备目录。',
    },
    articles: {
      title: d('articles').title || s('articles')?.title || '实用方案内容',
    },
  }
})

/* 场景与方案内容全部来自后台「官网运营 · 解决方案」。
   后台清空内容时页面展示"整理中"空状态，不再内置兜底数据。 */
const productionScenes = ref([])
const articles = ref([])

function normalizeArticle(item) {
  return {
    ...item,
    coverImage: item.coverImageUrl || '/images/workshop.webp',
    sections: item.contentSections || [],
  }
}

async function loadCmsContent() {
  try {
    const groups = await fetchSolutions()
    const sceneItems = groups.find((group) => group.section === 'INDUSTRY')?.items || []
    const articleItems = groups.find((group) => group.section === 'ARTICLE')?.items || []
    articles.value = articleItems.map(normalizeArticle)
    productionScenes.value = sceneItems.map((scene) => ({
      id: scene.itemKey,
      name: scene.title,
      desc: scene.summary || '',
      image: scene.coverImageUrl || '/images/workshop.webp',
      articleSlugs: articles.value
        .filter((article) => article.relatedSceneKeys?.includes(scene.itemKey))
        .map((article) => article.slug),
    }))
    syncBrowseState()
  } catch (err) {
    console.warn('Solution CMS unavailable:', err)
  } finally {
    scrollToRequestedScene()
  }
}

/* 分类筛选标签由实际内容派生，避免出现没有内容可筛选的空分类 */
const solutionCategories = computed(() => [
  '全部内容',
  ...new Set(articles.value.map((article) => article.category).filter(Boolean)),
])
const hasContent = computed(() => productionScenes.value.length > 0 || articles.value.length > 0)

const activeCategory = ref('全部内容')
const activeSceneId = ref('')
const sceneAliases = { fresh: 'fresh-meat' }
const activeScene = computed(() => productionScenes.value.find((scene) => scene.id === activeSceneId.value))
const visibleArticles = computed(() => {
  if (activeScene.value) {
    return activeScene.value.articleSlugs
      .map((slug) => articles.value.find((article) => article.slug === slug))
      .filter(Boolean)
  }
  return activeCategory.value === '全部内容'
    ? articles.value
    : articles.value.filter((article) => article.category === activeCategory.value)
})

function syncBrowseState() {
  const requestedScene = sceneAliases[route.query.scene] || route.query.scene || ''
  activeSceneId.value = productionScenes.value.some((scene) => scene.id === requestedScene) ? requestedScene : ''
  const requestedCategory = route.query.category || '全部内容'
  activeCategory.value = solutionCategories.value.includes(requestedCategory) ? requestedCategory : '全部内容'
  if (activeSceneId.value) activeCategory.value = '全部内容'
}

/* 从首页场景卡跳转（/solutions?scene=xxx）时，数据就绪后定位到场景区，
   让"所选场景高亮 + 上下文条 + 下方筛选内容"一次性进入视野。 */
let didInitialSceneScroll = false
function scrollToRequestedScene() {
  const requestedScene = sceneAliases[route.query.scene] || route.query.scene || ''
  if (didInitialSceneScroll) return
    if (productionScenes.value.some((scene) => scene.id === requestedScene)) {
    didInitialSceneScroll = true
    requestAnimationFrame(() => {
      document.querySelector('#solution-scenes')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }
}

async function selectScene(sceneId) {
  activeSceneId.value = sceneId
  activeCategory.value = '全部内容'
  await router.replace({ query: { scene: sceneId } })
  document.querySelector('#solution-content')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function selectCategory(category) {
  activeSceneId.value = ''
  activeCategory.value = category
  router.replace({ query: category === '全部内容' ? {} : { category } })
}

watch(() => route.query, syncBrowseState, { immediate: true })
onMounted(() => Promise.all([loadCmsContent(), pageSections.load()]))
</script>

<template>
  <main class="solutions-page">
    <PageHero
      v-if="sectionEnabled('hero')"
      data-cms-page="SOLUTIONS"
      data-cms-section="hero"
      eyebrow="PACKAGING SOLUTIONS"
      :description="pageContent.hero.description"
      :image="pageContent.hero.image"
      :primary-action="{ text: '按应用场景查找', to: '#solution-library' }"
      :secondary-action="{ text: '描述您的问题', inquiry: { type: 'consult', source: 'solutions-hero' } }"
    >
      <template #title><span>{{ pageContent.hero.title }}</span></template>
    </PageHero>

    <section v-if="sectionEnabled('scenes') || sectionEnabled('articles')" id="solution-library" class="solution-library">
      <template v-if="hasContent">
        <header class="solution-library__header">
          <div v-if="sectionEnabled('scenes') && productionScenes.length" data-cms-page="SOLUTIONS" data-cms-section="scenes" data-cms-source="solution">
            <p class="eyebrow dark"><span></span> FIND BY APPLICATION</p>
            <h2>{{ pageContent.scenes.title }}</h2>
          </div>
          <p v-if="sectionEnabled('scenes') && productionScenes.length">{{ pageContent.scenes.description }}</p>
        </header>

        <div v-if="activeScene" class="solution-scene-context" role="status">
          <span class="solution-scene-context__tag">当前应用场景</span>
          <strong class="solution-scene-context__name">{{ activeScene.name }}</strong>
          <span class="solution-scene-context__count">已筛选 {{ visibleArticles.length }} 篇相关方案</span>
          <button type="button" class="solution-scene-context__clear" @click="selectCategory('全部内容')">返回全部场景</button>
        </div>

        <div v-if="sectionEnabled('scenes') && productionScenes.length" class="solution-scenes" aria-label="按应用场景筛选方案" data-cms-page="SOLUTIONS" data-cms-section="scenes" data-cms-source="solution">
          <button
            v-for="scene in productionScenes"
            :key="scene.id"
            type="button"
            class="solution-scene-card"
            :class="{ active: activeSceneId === scene.id }"
            :aria-pressed="activeSceneId === scene.id"
            @click="selectScene(scene.id)"
          >
            <img :src="scene.image" :alt="scene.name" loading="lazy" decoding="async" />
            <span v-if="activeSceneId === scene.id" class="solution-scene-card__badge">查看中</span>
            <span class="solution-scene-card__copy">
              <small>{{ scene.desc }}</small>
              <strong>{{ scene.name }}</strong>
              <b>查看 {{ scene.articleSlugs.length }} 篇相关内容</b>
            </span>
          </button>
        </div>

        <section v-if="sectionEnabled('articles') && articles.length" id="solution-content" class="solution-content-index" data-cms-page="SOLUTIONS" data-cms-section="articles" data-cms-source="solution">
          <header class="solution-content-index__header">
            <div>
              <p class="eyebrow dark"><span></span> KNOWLEDGE &amp; GUIDES</p>
              <div class="solution-content-index__title-row">
                <h2>{{ activeScene ? `${activeScene.name}解决方案` : pageContent.articles.title }}</h2>
                <span>共 {{ visibleArticles.length }} 篇</span>
              </div>
            </div>
            <div class="solution-content-index__status">
              <button v-if="activeScene" type="button" @click="selectCategory('全部内容')">查看全部内容</button>
            </div>
          </header>

          <div class="solution-filter" role="tablist" aria-label="方案内容分类">
            <button
              v-for="category in solutionCategories"
              :key="category"
              type="button"
              role="tab"
              :aria-selected="!activeScene && activeCategory === category"
              :class="{ active: !activeScene && activeCategory === category }"
              @click="selectCategory(category)"
            >{{ category }}</button>
          </div>

          <div class="solution-article-grid">
            <RouterLink
              v-for="(article, index) in visibleArticles"
              :key="article.slug"
              :to="`/solutions/${article.slug}`"
              class="solution-article-card"
            >
              <div class="solution-article-card__image">
                <img :src="article.coverImage" :alt="article.title" loading="lazy" decoding="async" />
              </div>
              <div class="solution-article-card__body">
                <div class="solution-article-card__meta">
                  <span>{{ article.category }}</span><b>{{ String(index + 1).padStart(2, '0') }}</b>
                </div>
                <h3>{{ article.title }}</h3>
                <p>{{ article.summary }}</p>
                <small>适合：{{ article.audience }}</small>
                <strong>阅读方案 <span>→</span></strong>
              </div>
            </RouterLink>
          </div>
          <p v-if="!visibleArticles.length" class="solution-empty-list">该筛选条件下的方案内容正在整理中。</p>
        </section>
      </template>

      <div v-else class="solution-empty">
        <p class="eyebrow dark"><span></span> KNOWLEDGE &amp; GUIDES</p>
        <h2>方案内容正在整理中</h2>
        <p>我们正在补充各应用场景的包装判断与选型参考。您可以先浏览产品中心，或直接描述您的包装问题，由工程师协助判断。</p>
        <div class="solution-empty__actions">
          <RouterLink to="/products" class="button primary">浏览产品中心</RouterLink>
          <RouterLink to="/contact" class="button ghost">描述我的包装问题</RouterLink>
        </div>
      </div>
    </section>

    <SiteCTA
      id="cta"
      title="没有找到与您完全相同的问题？"
      description="告诉我们产品、容器、当前问题和目标产能，工程师将结合样品与现场条件协助判断。"
      action-text="描述我的包装问题"
      inquiry-source="solutions-bottom"
    />
  </main>
</template>
