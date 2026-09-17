<template>
  <LqDialog
    :model-value="visible"
    eyebrow="字段设置"
    title="控制字段可见性"
    subtitle="勾选的字段会在编辑页显示，取消勾选则会折叠隐藏"
    size="md"
    mode="detail"
    :show-footer="true"
    @update:model-value="v => $emit('update:visible', v)"
  >
    <section class="field-manager-body">
      <div class="fm-toolbar">
        <div class="fm-summary">
          已隐藏 <strong>{{ dormantCount }}</strong>
          <em>/ {{ totalCount }} 个字段</em>
        </div>
        <div class="fm-toolbar-actions">
          <button type="button" class="fm-select-all" @click="$emit('toggle-all', true)">全部显示</button>
          <button type="button" class="fm-select-all" @click="$emit('toggle-all', false)">全部隐藏</button>
          <button type="button" class="fm-reset" @click="$emit('reset-defaults')">恢复默认</button>
        </div>
      </div>

      <div class="field-groups">
        <section v-for="group in groups" :key="group.title" class="fm-group">
          <header class="fm-group-header">
            <h5>{{ group.title }}</h5>
            <span class="fm-group-count">{{ visibleCountIn(group) }}/{{ group.fields.length }} 显示</span>
          </header>
          <div class="fm-grid">
            <label
              v-for="field in group.fields"
              :key="field.key"
              class="fm-check"
              :class="{ 'is-checked': !dormantKeys.has(field.key), disabled: isDisabled(field) }"
            >
              <input
                type="checkbox"
                :checked="!dormantKeys.has(field.key)"
                :disabled="isDisabled(field)"
                @change="handleToggle(field.key)"
              />
              <span class="fm-check-label">{{ field.label }}</span>
              <span v-if="field.required" class="required-tag">必填</span>
            </label>
          </div>
        </section>
      </div>
    </section>

    <template #footer>
      <span class="footer-status">
        {{ dormantCount > 0 ? `${dormantCount} 个字段已隐藏` : '所有字段均已显示' }}
      </span>
      <button type="button" class="primary" @click="$emit('update:visible', false)">确认</button>
    </template>
  </LqDialog>
</template>

<script setup lang="ts">
import LqDialog from '../common/LqDialog.vue'

interface FieldItem {
  key: string
  label: string
  required?: boolean
}

interface FieldGroup {
  title: string
  fields: FieldItem[]
}

const props = withDefaults(
  defineProps<{
    visible: boolean
    groups: FieldGroup[]
    dormantKeys: Set<string>
    dormantCount: number
    totalCount: number
  }>(),
  {
    groups: () => [],
    dormantKeys: () => new Set<string>(),
    dormantCount: 0,
    totalCount: 0
  }
)

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'toggle-field': [key: string]
  'toggle-all': [show: boolean]
  'reset-defaults': []
}>()

function isDisabled(field: FieldItem): boolean {
  return !!field.required
}

function visibleCountIn(group: FieldGroup): number {
  return group.fields.filter(field => !props.dormantKeys.has(field.key)).length
}

function handleToggle(key: string) {
  emit('toggle-field', key)
}
</script>

<style scoped>
/* ===== 字段设置弹窗 ===== */

.field-manager-body {
  padding: 16px 22px 20px;
  background: var(--color-surface-alt);
}

/* 顶部工具栏：统计 + 批量操作 */
.fm-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap-wide);
  margin-bottom: var(--space-section-gap);
  padding: var(--space-control-gap-wide);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-xs);
}

.fm-summary {
  display: flex;
  align-items: baseline;
  gap: var(--space-control-gap-tight);
  margin-right: auto;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: var(--lh-none);
}

.fm-summary strong {
  color: var(--color-primary);
  font-size: var(--fs-2xl);
  line-height: var(--lh-none);
  font-weight: var(--fw-bold);
}

.fm-summary em {
  padding: 3px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-style: normal;
  font-weight: var(--fw-bold);
  white-space: nowrap;
}

.fm-toolbar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
}

.fm-select-all,
.fm-reset {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  white-space: nowrap;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.fm-select-all:hover,
.fm-reset:hover {
  border-color: var(--color-primary-border-light);
  background: var(--color-primary-bg);
  color: var(--color-success-text);
}

.fm-reset {
  background: var(--color-surface);
  font-weight: var(--fw-bold);
}

/* 分组卡片 */
.field-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
}

.fm-group {
  margin: 0;
  padding: var(--space-lg-xl) var(--space-xl);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-xs);
}

.fm-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  margin-bottom: var(--space-control-gap-wide);
  padding-bottom: var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-lighter);
}

.fm-group-header h5 {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  line-height: var(--lh-snug);
}

.fm-group-count {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-semibold);
  white-space: nowrap;
}

/* 字段勾选网格 */
.fm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--space-control-gap);
}

.fm-check {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  min-height: 36px;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
  color: var(--color-text-strong);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.fm-check:hover {
  border-color: var(--color-primary-border-light);
  background: var(--color-primary-bg);
}

.fm-check.is-checked {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg-light);
}

.fm-check.disabled {
  cursor: not-allowed;
  opacity: var(--opacity-disabled);
}

.fm-check input {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  accent-color: var(--color-primary);
}

.fm-check-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-heading-alt);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}

.required-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
  font-size: var(--fs-3xs);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-normal);
}

@media (max-width: 760px) {
  .fm-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .fm-summary {
    margin-right: 0;
  }

  .fm-toolbar-actions {
    justify-content: space-between;
  }

  .fm-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  }
}
</style>
