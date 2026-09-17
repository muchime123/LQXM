<script setup>
/**
 * 搜索输入框：SVG 放大镜图标 + 防抖由父级处理。
 * v-model="keyword" 或 :value + @input
 */
defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '搜索' },
})

const emit = defineEmits(['update:modelValue', 'input'])

function onInput(event) {
  emit('update:modelValue', event.target.value)
  emit('input')
}
</script>

<template>
  <div class="searchbar">
    <input
      :value="modelValue"
      type="text"
      :placeholder="placeholder"
      :aria-label="placeholder"
      @input="onInput"
    />
  </div>
</template>
