<template>
  <LqDialog
    :model-value="visible"
    eyebrow="批量编辑"
    :title="`修改 ${selectedCount} 个主产品`"
    subtitle="勾选要修改的字段并填入新值，未勾选的字段不会被修改"
    size="md"
    mode="form"
    :width="isSmallScreen ? '96vw' : undefined"
    @update:model-value="v => $emit('update:visible', v)"
    @close="$emit('close')"
  >
    <section class="batch-edit-body">
      <div class="spec-field-grid">
        <template v-for="group in fieldGroups" :key="group.title">
          <div class="batch-edit-group-title">{{ group.title }}</div>
          <template v-for="field in group.fields" :key="field.key">
            <label class="field-item batch-field-item">
              <span class="field-label">
                <input
                  type="checkbox"
                  :checked="checkedFields[field.key]"
                  @change="handleCheck(field.key, $event.target.checked)"
                />
                {{ field.label }}
              </span>
              <el-switch
                v-if="field.fieldType === 'toggle'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
                inline-prompt
                active-text="是"
                inactive-text="否"
              />
              <input
                v-else
                v-model="values[field.key]"
                type="text"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '输入新值' : '不修改'"
              />
            </label>
          </template>
        </template>
      </div>
    </section>

    <template #footer>
      <span class="footer-status">{{ checkedCountText }}</span>
      <button type="button" @click="$emit('close')">取消</button>
      <button
type="button"
class="primary"
:disabled="!hasCheckedFields"
@click="handleApply">应用修改</button>
    </template>
  </LqDialog>
</template>

<script setup>
import { reactive, computed, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  selectedCount: { type: Number, default: 0 },
  fieldGroups: { type: Array, default: () => [] },
  isSmallScreen: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'apply', 'update:visible'])

const checkedFields = reactive({})
const values = reactive({})

function initState() {
  const fields = props.fieldGroups.flatMap(g => g.fields)
  fields.forEach(f => {
    checkedFields[f.key] = false
    values[f.key] = f.fieldType === 'toggle' ? false : ''
  })
}

watch(
  () => props.visible,
  v => {
    if (v) initState()
  }
)

const hasCheckedFields = computed(() => Object.values(checkedFields).some(Boolean))

const checkedCountText = computed(() => {
  const count = Object.values(checkedFields).filter(Boolean).length
  if (count === 0) return '未勾选任何字段，不会修改任何内容'
  return `已勾选 ${count} 个字段，将覆盖到 ${props.selectedCount} 个产品`
})

function handleCheck(key, checked) {
  checkedFields[key] = checked
  if (!checked) {
    values[key] = ''
  }
}

function handleApply() {
  const payload = {}
  for (const [key, checked] of Object.entries(checkedFields)) {
    if (checked && values[key] !== '') {
      payload[key] = values[key]
    }
  }
  emit('apply', payload)
}
</script>

<style scoped>
.batch-edit-body {
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
}

.spec-field-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-form-item-gap);
}

.batch-edit-group-title {
  grid-column: 1 / -1;
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading-alt);
  padding: 8px 0;
  margin-top: var(--space-control-gap);
  border-top: 1px solid var(--color-border);
}

.batch-edit-group-title:first-child {
  margin-top: 0;
  border-top: none;
}

.batch-field-item .field-label {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
}

.batch-field-item .field-label input[type='checkbox'] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.batch-field-item input[type='text'] {
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--fs-sm);
  outline: none;
  transition: border-color var(--transition-base);
}

.batch-field-item input[type='text']:focus {
  border-color: var(--color-primary);
}

.batch-field-item input[type='text']:disabled {
  background: var(--color-surface-hover);
  color: var(--color-text-faint);
  cursor: not-allowed;
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.field-label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--color-text-strong);
}
</style>
