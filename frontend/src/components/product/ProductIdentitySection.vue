<template>
  <section class="editor-section-card product-identity-section">
    <FormSection
      v-for="section in sections"
      :key="section.id"
      class="product-identity-section__item"
      :title="section.title"
      :description="section.description"
      :required-count="sectionRequiredCount(section)"
      :error="sectionError(section)"
    >
      <div class="spec-field-grid archive-field-grid" :style="{ '--field-cols': section.columns || gridColumns }">
        <label
          v-for="field in section.fields"
          :key="field.key"
          :class="[
            'field-item',
            `field-${field.key}`,
            { 'field-item-wide': field.multiline, 'field-error': validationErrors.includes(field.key) }
          ]"
        >
          <span :class="['field-label', { required: field.required }]">{{ field.label }}</span>
          <el-switch
            v-if="field.fieldType === 'toggle'"
            v-model="form[field.key]"
            :disabled="field.key === 'websiteFeatured' && !form.websiteEnabled"
            inline-prompt
            active-text="是"
            inactive-text="否"
            @change="handleFieldInput(field.key)"
          />
          <textarea
            v-else-if="field.multiline"
            v-model="form[field.key]"
            :placeholder="'输入' + field.label"
            rows="4"
            @input="handleFieldInput(field.key)"
          ></textarea>
          <select
            v-else-if="fieldChoices(field.key).length"
            v-model="form[field.key]"
            class="choice-select"
            @change="handleFieldInput(field.key)"
          >
            <option value="">请选择{{ field.label }}</option>
            <option v-for="option in fieldChoiceOptions(field.key)" :key="option" :value="option">
              {{ option }}
            </option>
          </select>
          <input
            v-else
            v-model="form[field.key]"
            :placeholder="'输入' + field.label"
            @input="handleFieldInput(field.key)"
          />
        </label>
      </div>
    </FormSection>
  </section>
</template>

<script setup lang="ts">
import FormSection from '../common/FormSection.vue'

interface ProductField {
  key: string
  label: string
  required?: boolean
  multiline?: boolean
  fieldType?: 'text' | 'select' | 'buttonGroup' | 'toggle'
}

interface EditorFieldSection {
  id: string
  title: string
  description: string
  columns: number
  fields: ProductField[]
}

const props = withDefaults(
  defineProps<{
    form: Record<string, any>
    sections: EditorFieldSection[]
    validationErrors?: string[]
    fieldChoices: (key: string) => string[]
    fieldChoiceOptions: (key: string) => string[]
    gridColumns?: number
  }>(),
  {
    validationErrors: () => [],
    gridColumns: 3
  }
)

const emit = defineEmits<{
  'field-input': [key: string]
}>()

function sectionRequiredCount(section: EditorFieldSection): number {
  return section.fields.filter((f) => f.required).length
}

function sectionError(section: EditorFieldSection): string {
  const badKeys = section.fields.filter((f) => props.validationErrors.includes(f.key))
  if (badKeys.length === 0) return ''
  return `${badKeys.map((f) => f.label).join('、')} 校验未通过`
}

function handleFieldInput(key: string) {
  emit('field-input', key)
}
</script>
