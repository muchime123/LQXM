<script setup lang="ts">
/**
 * C13 LqDatePicker — el-date-picker 封装
 *
 * 统一 value-format="YYYY-MM-DD" 和 width: 100%，
 * 消除 15 处业务代码中的重复 props 三件套。
 */
import { ElDatePicker } from 'element-plus'

const props = withDefaults(
  defineProps<{
    modelValue?: string | string[] | Date | Date[] | null
    /** 日期选择类型 */
    type?: 'date' | 'daterange' | 'datetime' | 'month' | 'year'
    /** 占位符 */
    placeholder?: string
    /** 范围选择时的开始占位符 */
    startPlaceholder?: string
    /** 范围选择时的结束占位符 */
    endPlaceholder?: string
    /** 是否禁用 */
    disabled?: boolean
    /** 是否清空 */
    clearable?: boolean
    /** 自定义 value-format，默认 YYYY-MM-DD */
    valueFormat?: string
  }>(),
  {
    type: 'date',
    placeholder: '选择日期',
    startPlaceholder: '开始日期',
    endPlaceholder: '结束日期',
    disabled: false,
    clearable: true,
    valueFormat: 'YYYY-MM-DD'
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string | string[] | Date | Date[] | null]
  change: [value: string | string[] | Date | Date[] | null]
}>()

function onInput(val: string | string[] | Date | Date[] | null) {
  emit('update:modelValue', val)
}

function onChange(val: string | string[] | Date | Date[] | null) {
  emit('change', val)
}
</script>

<template>
  <ElDatePicker
    :model-value="props.modelValue"
    :type="props.type"
    :placeholder="props.placeholder"
    :start-placeholder="props.startPlaceholder"
    :end-placeholder="props.endPlaceholder"
    :disabled="props.disabled"
    :clearable="props.clearable"
    :value-format="props.valueFormat"
    style="width: 100%"
    @update:model-value="onInput"
    @change="onChange"
  />
</template>

<style scoped>
/* 统一宽度，避免业务页内联 style */
</style>
