<script setup>
/**
 * 通用分页组件。
 * props: page（0 基）/ totalPages / info / busy
 * emits: change(page)
 * 页码超过 7 页时收敛为「首页 + 省略 + 当前页附近 + 省略 + 末页」，
 * 手机端由 CSS 切换为「第 x / y 页」紧凑形态。
 */
import { computed } from 'vue'
import { buildPageWindow, PAGE_GAP } from '../../utils/pagination'

const props = defineProps({
  page: { type: Number, default: 0 },
  totalPages: { type: Number, default: 0 },
  info: { type: String, default: '' },
  busy: { type: Boolean, default: false },
})

const emit = defineEmits(['change'])

const pages = computed(() => buildPageWindow(props.page, props.totalPages))

function goTo(target) {
  if (props.busy) return
  if (!Number.isInteger(target) || target < 0 || target >= props.totalPages || target === props.page) return
  emit('change', target)
}

const pageLabel = (value) => value + 1
</script>

<template>
  <nav v-if="totalPages > 1" class="pagination" aria-label="产品分页" :aria-busy="busy">
    <button type="button" :disabled="busy || page === 0" @click="goTo(page - 1)">上一页</button>
    <span class="pagination__compact">第 {{ page + 1 }} / {{ totalPages }} 页</span>
    <div class="pagination__pages">
      <template v-for="(item, index) in pages" :key="`${item}-${index}`">
        <span v-if="item === PAGE_GAP" class="pagination__gap" aria-hidden="true">…</span>
        <button
          v-else
          type="button"
          :class="{ active: page === item }"
          :aria-current="page === item ? 'page' : undefined"
          :aria-label="`第 ${pageLabel(item)} 页`"
          :disabled="busy"
          @click="goTo(item)"
        >{{ pageLabel(item) }}</button>
      </template>
    </div>
    <button type="button" :disabled="busy || page >= totalPages - 1" @click="goTo(page + 1)">下一页</button>
    <span v-if="info" class="page-info">{{ info }}</span>
  </nav>
</template>
