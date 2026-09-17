<template>
  <el-input v-if="mode === 'text'" class="unit-field" :model-value="modelValue" :type="type" :size="size"
    :placeholder="placeholder" :disabled="disabled" :readonly="readonly" :clearable="clearable" :aria-label="ariaLabel"
    @update:model-value="emit('update:modelValue', $event)">
    <template v-if="unit" #append>
      <span class="unit-field__unit">{{ unit }}</span>
    </template>
  </el-input>
  <div v-else class="unit-field unit-field--number" :class="`unit-field--${size}`">
    <el-input-number :model-value="Number(modelValue ?? 0)" :size="size" :disabled="disabled" :aria-label="ariaLabel"
      v-bind="$attrs" @update:model-value="emit('update:modelValue', $event)" />
    <span v-if="unit" class="unit-field__unit unit-field__unit--number">{{ unit }}</span>
  </div>
</template>

<script setup lang="ts">
/**
 * UnitField 带单位输入
 * - mode='text'（默认）：包装 el-input，单位通过 #append slot 嵌入输入框内右侧
 * - mode='number'：包装 el-input-number，单位作为后置 span 紧贴输入框右侧
 *   适用于需要 min/max/step/precision 数值控制的场景（如尺寸、价格、数量）。
 *   number 模式下，min/max/step/precision/controls-position 等通过 $attrs 透传给 el-input-number。
 */
withDefaults(
  defineProps<{
    modelValue: string | number | null | undefined
    unit: string
    placeholder?: string
    type?: 'text' | 'number' | 'password' | 'textarea'
    size?: 'small' | 'default' | 'large'
    disabled?: boolean
    readonly?: boolean
    clearable?: boolean
    ariaLabel?: string
    mode?: 'text' | 'number'
  }>(),
  {
    placeholder: '',
    type: 'text',
    size: 'default',
    disabled: false,
    readonly: false,
    clearable: false,
    ariaLabel: undefined,
    mode: 'text'
  }
)

defineOptions({ inheritAttrs: false })

const emit = defineEmits<{
  'update:modelValue': [value: string | number]
}>()
</script>

<style scoped>
.unit-field__unit {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  white-space: nowrap;
}

/* ===== number 模式：el-input-number + 后置 unit span =====
 * el-input-number 无 #append slot，用 wrapper div + 后置 span 模拟。
 * unit 视觉上紧贴输入框右侧，与 el-input append 视觉接近。
 */

.unit-field--number {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
}

.unit-field--number .el-input-number {
  flex: 0 0 auto;
}

.unit-field__unit--number {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  white-space: nowrap;
  user-select: none;
}

/* size 变体：small 模式下 unit 字号略小，贴合 el-input-number--small 视觉 */
.unit-field--small .unit-field__unit--number {
  font-size: var(--fs-xs);
}
</style>
