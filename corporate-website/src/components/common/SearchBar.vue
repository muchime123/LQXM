<script setup>
/**
 * 搜索输入框：SVG 放大镜图标 + 防抖由父级处理。
 * clearable 时提供独立清除入口，只清空关键词，不影响其他筛选条件。
 */
defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '搜索' },
  clearable: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'input', 'clear'])

function onInput(event) {
  emit('update:modelValue', event.target.value)
  emit('input')
}

function onClear() {
  emit('update:modelValue', '')
  emit('clear')
}
</script>

<template>
  <div class="searchbar">
    <input
      :value="modelValue"
      type="search"
      :placeholder="placeholder"
      :aria-label="placeholder"
      @input="onInput"
    />
    <button
      v-if="clearable && modelValue"
      type="button"
      class="searchbar__clear"
      aria-label="清除搜索关键词"
      @click="onClear"
    >
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
        <line x1="18" y1="6" x2="6" y2="18" />
        <line x1="6" y1="6" x2="18" y2="18" />
      </svg>
    </button>
  </div>
</template>
