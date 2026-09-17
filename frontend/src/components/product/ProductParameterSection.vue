<template>
  <div class="editor-field-subsection product-parameter-subsection">
    <FormSection title="规格参数" description="维护机器规格、包装与气调等主要参数。">
      <div class="spec-field-grid archive-field-grid parameter-inline-grid">
        <article
          v-for="row in rows"
          :key="row.key"
          :class="[
            'parameter-field-item',
            { 'parameter-field-item-wide': row.wide, 'parameter-field-item-compact': row.compact }
          ]"
        >
          <template v-if="row.fields && row.fields.length > 1">
            <div class="parameter-inline-combo">
              <label
                v-for="field in row.fields"
                :key="field.key"
                :class="[
                  'parameter-field',
                  `parameter-field-${field.key}`,
                  { 'field-error': validationErrors.includes(field.key) }
                ]"
              >
                <span class="parameter-field-label">{{ field.zh }}</span>
                <select
                  v-if="(parameterOptions[field.key] || []).length"
                  v-model="form[field.key]"
                  class="choice-select"
                  @change="handleFieldInput(field.key)"
                >
                  <option value="">请选择{{ field.zh }}</option>
                  <option v-for="option in parameterOptions[field.key]" :key="option" :value="option">
                    {{ option }}
                  </option>
                </select>
                <input
                  v-else
                  v-model="form[field.key]"
                  :placeholder="'输入' + field.zh"
                  @input="handleFieldInput(field.key)"
                />
              </label>
            </div>
          </template>
          <template v-else>
            <label
              v-if="row.fields && row.fields.length === 1"
              :class="[
                'parameter-field',
                `parameter-field-${row.fields[0].key}`,
                { 'field-error': validationErrors.includes(row.fields[0].key) }
              ]"
            >
              <span class="parameter-field-label">{{ row.fields[0].zh }}</span>
              <select
                v-if="(parameterOptions[row.fields[0].key] || []).length"
                v-model="form[row.fields[0].key]"
                class="choice-select"
                @change="handleFieldInput(row.fields[0].key)"
              >
                <option value="">请选择{{ row.fields[0].zh }}</option>
                <option v-for="option in parameterOptions[row.fields[0].key]" :key="option" :value="option">
                  {{ option }}
                </option>
              </select>
              <input
                v-else
                v-model="form[row.fields[0].key]"
                :placeholder="'输入' + row.fields[0].zh"
                @input="handleFieldInput(row.fields[0].key)"
              />
            </label>
          </template>
        </article>
      </div>
    </FormSection>
  </div>
</template>

<script setup lang="ts">
import FormSection from '../common/FormSection.vue'

interface ParameterField {
  key: string
  zh: string
}

interface ParameterRow {
  key: string
  zh?: string
  wide?: boolean
  compact?: boolean
  fields: ParameterField[]
}

withDefaults(
  defineProps<{
    form: Record<string, any>
    rows: ParameterRow[]
    parameterOptions: Record<string, string[]>
    validationErrors?: string[]
  }>(),
  {
    validationErrors: () => []
  }
)

const emit = defineEmits<{
  'field-input': [key: string]
}>()

function handleFieldInput(key: string) {
  emit('field-input', key)
}
</script>

<style scoped>
.product-parameter-subsection {
  display: grid;
  gap: var(--space-card-gap);
}

.parameter-inline-grid {
  grid-template-columns: repeat(var(--field-cols, 4), minmax(0, 1fr));
  gap: 13px 16px;
}

.parameter-field-item {
  display: block;
}

.parameter-field-item-wide {
  grid-column: span 2;
}

.parameter-field-item-compact .parameter-inline-combo {
  gap: var(--space-control-gap);
}

.parameter-inline-combo {
  display: flex;
  align-items: flex-end;
  gap: var(--space-control-gap-wide);
}

.parameter-inline-combo .parameter-field {
  flex: 1;
  min-width: 0;
}

.parameter-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.parameter-field-label {
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: 650;
  letter-spacing: 0;
}

.parameter-field input,
.parameter-field textarea,
.choice-select {
  min-height: 40px;
  border-color: var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-normal);
}

.parameter-field input::placeholder,
.parameter-field textarea::placeholder {
  color: var(--color-text-faint);
  font-weight: var(--fw-medium);
}

.parameter-field.field-error input,
.parameter-field.field-error .choice-select {
  border-color: var(--color-danger);
}

.choice-select {
  width: 100%;
  min-height: 40px;
  padding: 9px 34px 9px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  outline: none;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    background 0.15s ease;
}

.choice-select:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
}

@media (max-width: 980px) {
  .parameter-inline-grid {
    grid-template-columns: repeat(min(var(--field-cols, 4), 2), minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .parameter-inline-grid {
    grid-template-columns: 1fr;
  }

  .parameter-inline-combo {
    flex-direction: column;
  }
}
</style>
