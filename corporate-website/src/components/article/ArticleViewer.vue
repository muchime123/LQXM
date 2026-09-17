<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { sectionsToHtml } from '../../utils/sectionsToHtml'

const props = defineProps({
  article: { type: Object, required: true }
})

const contentEl = ref(null)
const tocItems = ref([])
const activeId = ref('')
let observer = null

/* 正文 HTML：优先富文本，旧"结构化小节"数据转 HTML 兼容渲染 */
const html = computed(() => {
  const raw = props.article?.contentHtml || sectionsToHtml(props.article?.sections || props.article?.contentSections || [])
  return DOMPurify.sanitize(raw || '')
})

/* 从渲染后的 h2/h3 自动生成侧边目录，并做滚动高亮 */
function rebuildToc() {
  tocItems.value = []
  if (observer) {
    observer.disconnect()
    observer = null
  }
  nextTick(() => {
    const root = contentEl.value
    if (!root) return
    const heads = Array.from(root.querySelectorAll('h2, h3'))
    if (!heads.length) return
    const items = heads.map((head, index) => {
      const id = head.id || `sec-${index + 1}`
      head.id = id
      return {
        id,
        level: head.tagName.toLowerCase(),
        text: (head.textContent || '').replace(/\s+/g, ' ').trim()
      }
    })
    tocItems.value = items
    observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((entry) => entry.isIntersecting)
        if (visible.length) activeId.value = visible[visible.length - 1].target.id
      },
      { rootMargin: '-92px 0px -72% 0px', threshold: 0 }
    )
    items.forEach(({ id }) => {
      const el = document.getElementById(id)
      if (el) observer.observe(el)
    })
  })
}

function onTocClick(event, id) {
  event.preventDefault()
  activeId.value = id
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

watch(html, () => {
  if (props.article) rebuildToc()
})
onMounted(rebuildToc)
onBeforeUnmount(() => {
  if (observer) observer.disconnect()
})
</script>

<template>
  <div class="article-viewer">
    <aside class="article-viewer__aside">
      <nav class="article-viewer__toc" aria-label="本文目录">
        <span class="article-viewer__toc-title">本文目录</span>
        <a
          v-for="item in tocItems"
          :key="item.id"
          :href="`#${item.id}`"
          :class="['article-viewer__toc-item', { 'is-active': activeId === item.id, 'is-h3': item.level === 'h3' }]"
          @click="onTocClick($event, item.id)"
        >{{ item.text }}</a>
        <p v-if="!tocItems.length" class="article-viewer__toc-empty">本文暂无小节</p>
      </nav>
    </aside>
    <article ref="contentEl" class="article-rich" v-html="html"></article>
  </div>
</template>