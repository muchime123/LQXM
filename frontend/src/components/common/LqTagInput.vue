<script setup lang="ts">
/**
 * R4 标签编辑器配方 · LqTagInput
 *
 * Chip 式标签输入器：显示已有标签为可删除的 chip，输入新标签按 Enter 添加。
 * 替代各业务弹窗中文本输入 + 逗号分隔的原始方式。
 *
 * v-model 支持 string（逗号分隔）和 string[] 两种格式。
 */
import { ref, computed } from 'vue'
import { tagList } from '../../utils/common/textUtils'

const props = withDefaults(
  defineProps<{
    /** v-model 值，支持逗号分隔字符串或数组 */
    modelValue?: string | string[]
    /** 占位符 */
    placeholder?: string
    /** 建议标签列表 */
    suggestions?: string[]
    /** 最大标签数 */
    max?: number
    /** 尺寸 */
    size?: 'sm' | 'md'
    /** 是否禁用 */
    disabled?: boolean
  }>(),
  {
    modelValue: '',
    placeholder: '输入标签后按 Enter 添加',
    suggestions: () => [],
    max: 20,
    size: 'md',
    disabled: false
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const inputValue = ref('')
const isInputFocused = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

/** 统一转换为 string[] 供内部使用。 */
const tags = computed<string[]>(() => {
  const val = props.modelValue
  if (Array.isArray(val)) return val
  return tagList(val)
})

/** 将 string[] 转换回与 modelValue 相同的格式并 emit。 */
function emitTags(list: string[]) {
  const val = props.modelValue
  if (Array.isArray(val)) {
    emit('update:modelValue', list.join(','))
  } else {
    emit('update:modelValue', list.join(', '))
  }
}

function addTag(raw: string) {
  const tag = raw.trim()
  if (!tag) return
  if (tags.value.includes(tag)) return
  if (tags.value.length >= props.max) return
  emitTags([...tags.value, tag])
  inputValue.value = ''
}

function removeTag(tag: string) {
  emitTags(tags.value.filter(t => t !== tag))
}

function toggleSuggestion(tag: string) {
  if (tags.value.includes(tag)) {
    removeTag(tag)
  } else {
    addTag(tag)
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    addTag(inputValue.value)
  } else if (e.key === 'Backspace' && inputValue.value === '' && tags.value.length > 0) {
    e.preventDefault()
    const last = tags.value[tags.value.length - 1]
    removeTag(last)
  } else if (e.key === ',' || e.key === ' ') {
    if (inputValue.value.trim()) {
      e.preventDefault()
      addTag(inputValue.value)
    }
  }
}

/** 过滤掉已选的建议标签 */
const availableSuggestions = computed(() => {
  if (!props.suggestions.length) return []
  return props.suggestions.filter(s => !tags.value.includes(s))
})
</script>

<template>
  <div class="lq-tag-input" :class="[`lq-tag-input--${size}`, { 'is-disabled': disabled }]">
    <div class="lq-tag-input__chips" @click="!disabled && inputRef?.focus()">
      <span v-for="tag in tags" :key="tag" class="lq-tag-input__chip">
        {{ tag }}
        <button
          v-if="!disabled"
          type="button"
          class="lq-tag-input__remove"
          :aria-label="`移除 ${tag}`"
          @click.stop="removeTag(tag)"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </span>
      <input
        :ref="(el) => { inputRef = el as HTMLInputElement | null }"
        v-model="inputValue"
        class="lq-tag-input__field"
        :placeholder="tags.length === 0 ? placeholder : ''"
        :disabled="disabled"
        @keydown="onKeydown"
        @focus="isInputFocused = true"
        @blur="isInputFocused = false"
      />
    </div>
    <div v-if="!disabled && availableSuggestions.length > 0" class="lq-tag-input__suggestions">
      <span class="lq-tag-input__sug-label">常用：</span>
      <button
        v-for="tag in availableSuggestions"
        :key="tag"
        type="button"
        class="lq-tag-input__sug-chip"
        @click="toggleSuggestion(tag)"
      >
        + {{ tag }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.lq-tag-input {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap, 8px);
}

.lq-tag-input.is-disabled .lq-tag-input__chips {
  background: var(--color-surface-hover);
  cursor: not-allowed;
  opacity: 0.7;
}

.lq-tag-input.is-disabled .lq-tag-input__field {
  cursor: not-allowed;
}

.lq-tag-input__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-height: 36px;
  padding: 4px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm, 4px);
  background: var(--color-surface);
  cursor: text;
  transition: border-color 0.2s;
}

.lq-tag-input__chips:focus-within {
  border-color: var(--color-primary);
}

.lq-tag-input--sm .lq-tag-input__chips {
  min-height: 30px;
  padding: 2px 6px;
}

.lq-tag-input__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-sm, 4px);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
}

.lq-tag-input__remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  padding: 0;
  opacity: 0.6;
  transition: opacity 0.2s;
}

.lq-tag-input__remove:hover {
  opacity: 1;
}

.lq-tag-input__field {
  flex: 1;
  min-width: 80px;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--color-text-primary);
  padding: 2px 0;
}

.lq-tag-input__field::placeholder {
  color: var(--color-text-placeholder);
}

.lq-tag-input__suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.lq-tag-input__sug-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.lq-tag-input__sug-chip {
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm, 4px);
  background: none;
  color: var(--color-text-secondary);
  font-size: 12px;
  padding: 2px 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.lq-tag-input__sug-chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
</style>
