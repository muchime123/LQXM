<script setup>
/**
 * 通用分页组件。
 * props: page / totalPages / info（如「共 24 款产品」）
 * emits: change(page)
 */
const props = defineProps({
  page: { type: Number, default: 0 },
  totalPages: { type: Number, default: 0 },
  info: { type: String, default: '' },
})

const emit = defineEmits(['change'])

function goTo(p) {
  if (p < 0 || p >= props.totalPages || p === props.page) return
  emit('change', p)
}

const pageLabel = (p) => p + 1
</script>

<template>
  <div v-if="totalPages > 1" class="pagination">
    <button :disabled="page === 0" @click="goTo(page - 1)">上一页</button>
    <button
      v-for="p in totalPages"
      :key="p - 1"
      :class="{ active: page === p - 1 }"
      @click="goTo(p - 1)"
    >{{ pageLabel(p - 1) }}</button>
    <button :disabled="page >= totalPages - 1" @click="goTo(page + 1)">下一页</button>
    <span v-if="info" class="page-info">{{ info }}</span>
  </div>
</template>
