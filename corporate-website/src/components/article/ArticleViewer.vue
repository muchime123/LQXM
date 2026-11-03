<script setup>
/**
 * 通用文章阅读器（方案文章 / 知识库 / 新闻复用）。
 *
 * - 正文：优先 contentHtml，空白时回退旧版结构化小节，统一 DOMPurify 清洗
 * - 目录：桌面常显侧栏，手机端默认折叠为「本文目录（N 节）」可展开
 * - 锚点：标题 ID 统一去重，点击目录同步地址栏 #章节（replace，不新增历史）
 * - 表格：自动包裹横向滚动容器，避免宽参数表把手机页面撑出横向溢出
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { sectionsToHtml } from '../../utils/sectionsToHtml'

const props = defineProps({
  article: { type: Object, required: true }
})

const contentEl = ref(null)
const tocItems = ref([])
const activeId = ref('')
const tocOpen = ref(false)
const isNarrow = ref(false)

let observer = null
let mediaQuery = null

/* 正文 HTML：空白富文本视为「没有富文本」，回退结构化小节，避免白屏。 */
const html = computed(() => {
  const rich = typeof props.article?.contentHtml === 'string' ? props.article.contentHtml.trim() : ''
  const raw = rich || sectionsToHtml(props.article?.sections || props.article?.contentSections || [])
  return DOMPurify.sanitize(raw || '')
})

/** 宽表格统一包一层横向滚动容器（幂等，重复重建不会套娃）。 */
function wrapTables(root) {
  root.querySelectorAll('table').forEach((table) => {
    if (table.parentElement?.classList.contains('article-table-scroll')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'article-table-scroll'
    table.parentNode.insertBefore(wrapper, table)
    wrapper.appendChild(table)
  })
}

/** 标题 ID 去重：保留运营手写的唯一 id，冲突或缺失时回退 sec-N。 */
function assignHeadingIds(heads) {
  const used = new Set()
  return heads.map((head, index) => {
    const existing = String(head.id || '').trim()
    let id = existing && !used.has(existing) ? existing : `sec-${index + 1}`
    if (used.has(id)) {
      let suffix = 2
      while (used.has(`${id}-${suffix}`)) suffix += 1
      id = `${id}-${suffix}`
    }
    used.add(id)
    head.id = id
    return {
      id,
      level: head.tagName.toLowerCase(),
      text: (head.textContent || '').replace(/\s+/g, ' ').trim() || `第 ${index + 1} 节`,
    }
  })
}

async function rebuildToc() {
  if (observer) {
    observer.disconnect()
    observer = null
  }
  tocItems.value = []
  await nextTick()

  const root = contentEl.value
  if (!root) return

  wrapTables(root)

  const heads = Array.from(root.querySelectorAll('h2, h3'))
  if (!heads.length) return

  const items = assignHeadingIds(heads)
  tocItems.value = items

  observer = new IntersectionObserver(
    (entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting)
      if (visible.length) activeId.value = visible[visible.length - 1].target.id
    },
    { rootMargin: '-92px 0px -72% 0px', threshold: 0 }
  )
  items.forEach(({ id }) => {
    const el = findHeading(root, id)
    if (el) observer.observe(el)
  })
}

/* 用 id 精确比对而非拼接选择器，避免标题 id 含引号/中文时的转义问题 */
function findHeading(root, id) {
  if (!root) return null
  return Array.from(root.querySelectorAll('[id]')).find((el) => el.id === id) || null
}

function scrollToHeading(id) {
  const el = findHeading(contentEl.value, id)
  if (!el) return false
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  return true
}

function onTocClick(event, id) {
  event.preventDefault()
  activeId.value = id
  if (!scrollToHeading(id)) return
  /* 同步 #章节 便于复制章节链接；用 replaceState 避免污染前进后退 */
  if (typeof window !== 'undefined' && window.location.hash !== `#${id}`) {
    window.history.replaceState(null, '', `#${id}`)
  }
  if (isNarrow.value) tocOpen.value = false
}

/** 桌面端目录常显，点击标题行不应把它折叠起来。 */
function onSummaryClick(event) {
  if (!isNarrow.value) event.preventDefault()
}

function onTocToggle(event) {
  tocOpen.value = event.target.open
}

function onMediaChange(event) {
  isNarrow.value = event.matches
  tocOpen.value = !event.matches
}

watch(html, () => {
  if (props.article) rebuildToc()
})

onMounted(async () => {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    mediaQuery = window.matchMedia('(max-width: 900px)')
    isNarrow.value = mediaQuery.matches
    tocOpen.value = !mediaQuery.matches
    mediaQuery.addEventListener('change', onMediaChange)
  }

  await rebuildToc()

  /* 直接用 /solutions/xxx#章节 打开时定位到对应小节 */
  const hash = decodeURIComponent(window.location.hash.replace('#', ''))
  if (hash) scrollToHeading(hash)
})

onBeforeUnmount(() => {
  if (observer) observer.disconnect()
  if (mediaQuery) mediaQuery.removeEventListener('change', onMediaChange)
})
</script>

<template>
  <div class="article-viewer">
    <aside class="article-viewer__aside">
      <details class="article-viewer__toc" :open="tocOpen" @toggle="onTocToggle">
        <summary class="article-viewer__toc-summary" @click="onSummaryClick">
          <span class="article-viewer__toc-title">本文目录</span>
          <span v-if="tocItems.length" class="article-viewer__toc-count">（{{ tocItems.length }} 节）</span>
        </summary>
        <nav class="article-viewer__toc-list" aria-label="本文目录">
          <a
            v-for="item in tocItems"
            :key="item.id"
            :href="`#${item.id}`"
            :class="['article-viewer__toc-item', { 'is-active': activeId === item.id, 'is-h3': item.level === 'h3' }]"
            @click="onTocClick($event, item.id)"
          >{{ item.text }}</a>
          <p v-if="!tocItems.length" class="article-viewer__toc-empty">本文暂无小节</p>
        </nav>
      </details>
    </aside>
    <article ref="contentEl" class="article-rich" v-html="html"></article>
  </div>
</template>
