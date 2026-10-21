<template>
  <aside class="ws-panel">
    <div class="ws-panel-head">
      <h2>生产车间</h2>
      <span class="hint">共 {{ workshops.length }} 条产线</span>
    </div>
    <ul class="ws-list">
      <li
        v-for="w in workshops"
        :key="w.id"
        class="ws-item"
        :class="{ active: w.id === modelValue }"
        @click="$emit('update:modelValue', w.id)"
      >
        <div class="ws-title">
          <span class="ws-name">{{ w.workshopName }}</span>
          <el-tag v-if="!w.enabled" size="small" type="info" effect="plain">停用</el-tag>
        </div>
        <div class="ws-tpl" :class="{ missing: !w.defaultTemplateName }">
          {{ w.defaultTemplateName || '未设默认制程' }}
        </div>
        <div class="ws-counts">
          <span class="c-product">产品 {{ w.productCount }}</span>
          <span class="c-config">配置 {{ w.configCount }}</span>
        </div>
      </li>
    </ul>
  </aside>
</template>

<script setup lang="ts">
export interface WorkshopCard {
  id: number
  workshopName: string
  enabled: boolean
  defaultProcessTemplateId: number | null
  defaultTemplateName: string | null
  productCount: number
  configCount: number
}

defineProps<{ workshops: WorkshopCard[]; modelValue: number | null }>()
defineEmits<{ 'update:modelValue': [value: number] }>()
</script>

<style scoped>
.ws-panel { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-lg-xl, 16px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); position: sticky; top: 16px; }
.ws-panel-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 12px; }
.ws-panel-head h2 { margin: 0; font-size: 15px; color: var(--color-text-heading, #0f172a); }
.ws-panel-head .hint { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.ws-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.ws-item { padding: 12px; border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-xl, 10px); cursor: pointer; transition: border-color .15s, background .15s; }
.ws-item:hover { border-color: var(--color-primary-border, #a7f3d0); background: var(--color-surface-alt, #f8fafc); }
.ws-item.active { border-color: var(--color-primary, #0f766e); background: var(--color-primary-bg-light, #f0fdfa); box-shadow: inset 3px 0 0 var(--color-primary, #0f766e); }
.ws-title { display: flex; align-items: center; gap: 6px; }
.ws-name { font-weight: 600; font-size: 14px; color: var(--color-text-heading, #0f172a); }
.ws-tpl { margin-top: 4px; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.ws-tpl.missing { color: var(--color-danger, #dc2626); }
.ws-counts { display: flex; gap: 12px; margin-top: 8px; font-size: 12px; }
.c-product { color: var(--color-text-heading, #0f172a); }
.c-config { color: var(--color-text-secondary, #5f6f82); }
</style>
