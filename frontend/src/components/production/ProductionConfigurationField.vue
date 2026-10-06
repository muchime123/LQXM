<template>
  <el-radio-group v-if="definition.control === 'radio'" v-model="value">
    <el-radio v-for="option in definition.choices" :key="option.value" :value="option.value">{{ option.label }}</el-radio>
  </el-radio-group>
  <el-select v-else-if="definition.control === 'select'" v-model="value" filterable allow-create default-first-option placeholder="请选择或输入" class="config-field">
    <el-option v-for="option in definition.choices" :key="option.value" :value="option.value" :label="option.label" />
  </el-select>
  <el-input v-else v-model="value" :type="definition.control === 'textarea' ? 'textarea' : 'text'" :rows="2" :maxlength="definition.maxlength" :placeholder="definition.placeholder" :show-word-limit="definition.control === 'textarea'" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { PRODUCTION_CONFIG_FIELDS, type ProductionConfigFieldKey } from '@/constants/productionConfiguration'
const props = defineProps<{ field: ProductionConfigFieldKey; modelValue?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()
interface FieldDefinition { control?: string; choices?: ReadonlyArray<{value: string; label: string}>; maxlength?: number; placeholder?: string }
const definition = computed<FieldDefinition>(() => PRODUCTION_CONFIG_FIELDS[props.field])
const value = computed({ get: () => props.modelValue || '', set: (v: string) => { emit('update:modelValue', v); emit('change', v) } })
</script>
<style scoped>
.config-field { width: 100%; }
</style>
