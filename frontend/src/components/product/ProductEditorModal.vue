<template>
  <LqDialog
    :model-value="visible"
    :eyebrow="mode === 'create' ? '新建主产品' : '编辑主产品'"
    :title="form.name || '主产品信息'"
    subtitle="维护主产品基础资料与图片说明"
    size="xl"
    :width="isSmallScreen ? '96vw' : undefined"
    mode="form"
    :dirty="dirty"
    :scrollable="false"
    :confirm-text="mode === 'create' ? '创建主产品' : '保存主产品'"
    @update:model-value="handleDialogVisibleChange"
    @confirm="handleSubmit"
  >
    <form class="product-editor-content" @submit.prevent="handleSubmit">
      <aside class="editor-section-nav" aria-label="主产品编辑导航">
        <!-- eslint-disable vue/no-mutating-props -->
        <SidebarAssetPanel
          :form="form"
          :model-id="modelId"
          :image-uploading="imageUploading"
          :manual-uploading="manualUploading"
          :main-image-assets="mainImageAssets"
          :mode="mode"
          @upload-image="emit('upload-image', $event)"
          @upload-manual="emit('upload-manual', $event)"
          @choose-image="emit('choose-image', $event)"
          @preview-asset="emit('preview-asset', $event)"
          @clear-image="form.defaultImage = ''"
          @update:dirty="emit('update:dirty', $event)"
        />
        <!-- eslint-enable vue/no-mutating-props -->

        <SidebarWebsitePanel :form="form" @field-change="handleFieldInput" />

        <button type="button" class="sidebar-field-settings" @click="showFieldManager = true">
          <span>字段设置</span>
          <em>{{ dormantCount }} 项隐藏</em>
        </button>
      </aside>

      <div class="editor-form-sections compact-editor-sections">
        <!-- eslint-disable vue/no-mutating-props -->
        <section class="product-editor-form-card">
          <ProductIdentitySection
            :form="form"
            :sections="[...primaryEditorFieldSections, ...secondaryEditorFieldSections]"
            :validation-errors="validationErrors"
            :field-choices="fieldChoices"
            :field-choice-options="fieldChoiceOptions"
            :grid-columns="gridColumns"
            @field-input="handleFieldInput"
          />
          <ProductSelectionSection :form="form" @field-input="handleFieldInput" />
          <ProductParameterSection
            :form="form"
            :rows="visibleParameterRows"
            :parameter-options="parameterOptions"
            :validation-errors="validationErrors"
            @field-input="handleFieldInput"
          />
        </section>
        <!-- eslint-enable vue/no-mutating-props -->
      </div>
    </form>
  </LqDialog>

  <FieldManagerDialog
    :visible="showFieldManager"
    :groups="fieldManagerGroups as any"
    :dormant-keys="dormantFieldKeys"
    :dormant-count="dormantCount"
    :total-count="allFieldCount"
    @update:visible="showFieldManager = $event"
    @toggle-field="toggleDormant"
    @toggle-all="handleToggleAll"
    @reset-defaults="resetDormantDefaults"
  />
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { MODEL_FIELD_GROUPS } from '../../constants/fields'
import { PRODUCT_EDITOR_MANAGED_FIELD_KEYS, findProductModelField } from '../../constants/fields/product-editor'

import LqDialog from '../common/LqDialog.vue'
import SidebarAssetPanel from './SidebarAssetPanel.vue'
import SidebarWebsitePanel from './SidebarWebsitePanel.vue'
import ProductIdentitySection from './ProductIdentitySection.vue'
import ProductSelectionSection from './ProductSelectionSection.vue'
import ProductParameterSection from './ProductParameterSection.vue'
import FieldManagerDialog from './FieldManagerDialog.vue'

type EditorMode = 'create' | 'edit'

interface ProductEditorForm {
  name?: string
  defaultImage?: string
  [key: string]: any
}

interface MainImageAsset {
  id?: number | null
  fileName: string
}

interface ProductField {
  key: string
  label: string
  required?: boolean
  multiline?: boolean
  fieldType?: 'text' | 'select' | 'buttonGroup' | 'toggle'
}

interface FieldManagerGroup {
  title: string
  fields: ProductField[]
}

interface EditorFieldSection {
  id: string
  title: string
  description: string
  columns: number
  keys: string[]
  fields: ProductField[]
}

interface PreviewPayload {
  category: string
  fileName: string
  modelId?: number | null
  ownerType?: string
  assetId?: number
}

type UploadLikeEvent = Event | { target: { files: File[] } }

const props = withDefaults(
  defineProps<{
    visible?: boolean
    mode?: EditorMode
    form: ProductEditorForm
    dirty?: boolean
    imageUploading?: boolean
    manualUploading?: boolean
    validationErrors?: string[]
    mainImageAssets?: MainImageAsset[]
    modelId?: number | null
  }>(),
  {
    visible: false,
    mode: 'edit',
    dirty: false,
    imageUploading: false,
    manualUploading: false,
    validationErrors: () => [],
    mainImageAssets: () => [],
    modelId: null
  }
)

const emit = defineEmits<{
  close: []
  submit: []
  'upload-image': [event: UploadLikeEvent]
  'upload-manual': [event: UploadLikeEvent]
  'choose-image': [fileName: string]
  'preview-asset': [payload: PreviewPayload]
  'update:dirty': [dirty: boolean]
  'field-change': [key: string]
}>()

const isSmallScreen = ref(false)
let mediaQuery: MediaQueryList | null = null

function handleMediaQueryChange(e: MediaQueryListEvent) {
  isSmallScreen.value = e.matches
}

onMounted(() => {
  mediaQuery = window.matchMedia('(max-width: 760px)')
  isSmallScreen.value = mediaQuery.matches
  mediaQuery.addEventListener('change', handleMediaQueryChange)
})

onUnmounted(() => {
  if (mediaQuery) {
    mediaQuery.removeEventListener('change', handleMediaQueryChange)
  }
})

// ===== 字段可视化管理（localStorage）=====
const DORMANT_STORAGE_KEY = 'product_editor_dormant_fields'

// 可被字段管理器管理的可选字段（扩展字段）
const ALL_MANAGED_FIELD_KEYS = PRODUCT_EDITOR_MANAGED_FIELD_KEYS

// 默认可见的核心字段（身份 + 说明 + 常用参数），其余非核心字段默认隐藏，保持编辑页清爽
const DEFAULT_VISIBLE_KEYS = [
  // 主产品身份
  'modelCode',
  'name',
  'category',
  'status',
  'sortOrder',
  // 产品说明
  'applicableScope',
  'descriptionCn',
  'descriptionEn',
  'remark'
]

const DEFAULT_DORMANT_KEYS = ALL_MANAGED_FIELD_KEYS.filter(key => !DEFAULT_VISIBLE_KEYS.includes(key))

function normalizeManagedKeys(keys: unknown): string[] {
  if (!Array.isArray(keys)) return []
  return [...new Set(keys.map(String))].filter(key => ALL_MANAGED_FIELD_KEYS.includes(key))
}

function loadDormantKeys() {
  try {
    const raw = localStorage.getItem(DORMANT_STORAGE_KEY)
    if (raw) {
      const keys = JSON.parse(raw)
      return normalizeManagedKeys(keys)
    }
  } catch {
    /* ignore */
  }
  return [...DEFAULT_DORMANT_KEYS]
}

function saveDormantKeys(keys: string[]) {
  try {
    localStorage.setItem(DORMANT_STORAGE_KEY, JSON.stringify(normalizeManagedKeys(keys)))
  } catch {
    /* ignore */
  }
}

const dormantFieldKeys = ref(new Set(loadDormantKeys()))
const showFieldManager = ref(false)

// ===== 每行列数（localStorage）=====
const GRID_COLS_KEY = 'product_editor_grid_cols'

function loadGridColumns() {
  try {
    const raw = localStorage.getItem(GRID_COLS_KEY)
    const v = parseInt(raw || '', 10)
    if (v >= 2 && v <= 4) return v
  } catch {
    /* ignore */
  }
  return 3
}

function saveGridColumns(n: number) {
  try {
    localStorage.setItem(GRID_COLS_KEY, String(n))
  } catch {
    /* ignore */
  }
}

const gridColumns = ref(loadGridColumns())

const PRIMARY_FIELD_SECTIONS = [
  {
    id: 'identity',
    title: '主产品身份',
    description: '只维护产品识别、归档和筛选所需的核心字段。',
    columns: 4,
    keys: [
      'modelCode',
      'name',
      'category',
      'status',
      'sortOrder'
    ]
  },
  {
    id: 'description',
    title: '产品说明',
    description: '用于说明书、店铺详情和后续资料整理，可以留空后补。',
    columns: 2,
    keys: ['applicableScope', 'descriptionCn', 'descriptionEn', 'remark']
  }
]

const parameterRows: Array<{ key: string; zh: string; fields: Array<{ key: string; shortLabel?: string }> }> = []

const visibleParameterRows = computed(
  () =>
    parameterRows
      .filter(row => row.fields.some(field => !dormantFieldKeys.value.has(field.key)))
      .map(row => ({
        ...row,
        fields: row.fields.map(field => ({ ...field, zh: (field as any).zh || (field as any).shortLabel }))
      })) as any
)

const FIELD_CHOICES: Record<string, string[]> = {
  status: ['ACTIVE', 'DISCONTINUED', 'PREVIEW']
}

function fieldChoices(key: string) {
  return FIELD_CHOICES[key] || []
}

function fieldChoiceOptions(key: string) {
  const choices = fieldChoices(key)
  const current = props.form[key]
  const currentValue = typeof current === 'string' ? current : ''
  if (currentValue && !choices.includes(currentValue)) {
    return [...choices, currentValue]
  }
  return choices
}

const parameterOptions: Record<string, string[]> = {}

const dormantCount = computed(() => ALL_MANAGED_FIELD_KEYS.filter(key => dormantFieldKeys.value.has(key)).length)

const allFieldCount = computed(() => ALL_MANAGED_FIELD_KEYS.length)

const fieldManagerGroups = computed<FieldManagerGroup[]>(() => {
  const groups: FieldManagerGroup[] = []
  const seen = new Set<string>()
  MODEL_FIELD_GROUPS.forEach(group => {
    const fields = group.fields
      .map(f => f.key)
      .filter(key => ALL_MANAGED_FIELD_KEYS.includes(key) && !seen.has(key))
      .map(key => {
        seen.add(key)
        return findProductModelField(key) as ProductField | null
      })
      .filter((field): field is ProductField => Boolean(field))
    if (fields.length > 0) {
      groups.push({ title: group.title, fields })
    }
  })
  return groups
})

const editorFieldSections = computed<EditorFieldSection[]>(() =>
  PRIMARY_FIELD_SECTIONS.map(section => ({
    ...section,
    fields: section.keys
      .filter(key => !dormantFieldKeys.value.has(key))
      .map(key => findProductModelField(key) as ProductField | null)
      .filter((field): field is ProductField => Boolean(field))
  })).filter(section => section.fields.length > 0)
)

const primaryEditorFieldSections = computed(() =>
  editorFieldSections.value.filter(section => section.id === 'identity')
)
const secondaryEditorFieldSections = computed(() =>
  editorFieldSections.value.filter(section => section.id !== 'identity')
)

function optionListId(key: string) {
  return parameterOptions[key] ? `parameter-options-${key}` : undefined
}

void optionListId

function handleClose() {
  emit('close')
}

function handleDialogVisibleChange(value: boolean) {
  if (!value) handleClose()
}

function handleSubmit() {
  emit('submit')
}

function handleFieldInput(key: string) {
  markDirty()
  emit('field-change', key)
}

function markDirty() {
  emit('update:dirty', true)
}

// ===== 字段可视化管理方法 =====
function toggleDormant(key: string) {
  if (key === 'name') return // name 必填，不可隐藏
  if (!ALL_MANAGED_FIELD_KEYS.includes(key)) return
  const set = new Set(dormantFieldKeys.value)
  if (set.has(key)) {
    set.delete(key)
  } else {
    set.add(key)
  }
  dormantFieldKeys.value = set
  saveDormantKeys([...set])
}

function toggleAllFields() {
  if (dormantCount.value === 0) {
    // Hide all optional fields, keeping name and parameter fields visible.
    const all = new Set<string>()
    ALL_MANAGED_FIELD_KEYS.forEach(key => all.add(key))
    dormantFieldKeys.value = all
    saveDormantKeys([...all])
  } else {
    // Show all fields that are still managed by the product editor.
    dormantFieldKeys.value = new Set()
    saveDormantKeys([])
  }
}

void toggleAllFields

function handleToggleAll(show: boolean) {
  if (show) {
    dormantFieldKeys.value = new Set()
    saveDormantKeys([])
  } else {
    const all = new Set<string>()
    ALL_MANAGED_FIELD_KEYS.forEach(key => all.add(key))
    dormantFieldKeys.value = all
    saveDormantKeys([...all])
  }
}

function resetDormantDefaults() {
  dormantFieldKeys.value = new Set(DEFAULT_DORMANT_KEYS)
  saveDormantKeys([...DEFAULT_DORMANT_KEYS])
  gridColumns.value = 3
  saveGridColumns(3)
}
</script>

<style>
.product-editor-dialog {
  z-index: var(--z-modal);
}

.editor-section-card {
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.product-identity-section {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.editor-section-card::before {
  display: none;
}

.section-title-with-note {
  display: block;
  margin-bottom: var(--space-xl);
  padding-bottom: var(--space-lg);
  border-bottom: 1px solid var(--color-border);
}

.section-title-with-note h5 {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-md);
  font-weight: var(--fw-black);
  line-height: 1.35;
}

.section-title-with-note p {
  max-width: none;
  margin: 4px 0 0;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: var(--lh-relaxed);
  text-align: left;
}

.archive-field-grid,
.compact-field-grid {
  grid-template-columns: repeat(var(--field-cols, 3), minmax(0, 1fr));
}

.product-identity-section .archive-field-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 13px 16px;
}

.product-identity-section .field-applicableScope,
.product-identity-section .field-remark {
  grid-column: span 2;
}

.product-identity-section .field-label,
.product-parameter-subsection .field-label {
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: 650;
  letter-spacing: 0;
}

.product-identity-section .field-label.required::before {
  color: var(--color-danger);
}

.product-identity-section .field-item input,
.product-identity-section .field-item textarea,
.product-identity-section .choice-select,
.product-parameter-subsection input {
  min-height: 40px;
  border-color: var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-normal);
}

.product-identity-section .field-item input::placeholder,
.product-identity-section .field-item textarea::placeholder,
.product-parameter-subsection input::placeholder {
  color: var(--color-text-faint);
  font-weight: var(--fw-medium);
}

.product-editor-content {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  gap: 0;
  padding: 0;
  overflow: hidden;
  background: var(--color-surface);
}

.editor-section-nav {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: var(--space-md);
  border: 0;
  border-right: 1px solid var(--color-border);
  border-radius: 0;
  background: var(--color-surface-alt);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 16px 12px;
}

.editor-form-sections {
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--color-surface);
}

.compact-editor-sections {
  padding: 20px 24px 24px;
}

.editor-empty-hint {
  display: grid;
  place-items: center;
  min-height: 92px;
  border: 1px dashed var(--color-border-input-hover);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.editor-field-subsection {
  display: grid;
  gap: var(--space-card-gap);
}

.editor-field-subsection + .editor-field-subsection {
  margin-top: 28px;
}

.editor-field-subsection header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-lg);
  padding-bottom: 4px;
}

.product-identity-section .editor-field-subsection header {
  display: none;
}

.editor-field-subsection header strong {
  color: var(--color-text-heading);
  font-size: var(--fs-base);
  font-weight: var(--fw-extrabold);
}

.editor-field-subsection header span {
  max-width: 420px;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: 1.45;
  text-align: left;
}

/* ===== C25 FormSection 推广：ProductIdentitySection 内部 =====
 * 内容直接落在弹窗内容区，内部 .form-section 仅保留 header + body 结构。
 * 间距沿用 section-gap 语义别名。
 */

.product-identity-section .form-section {
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}

.product-identity-section .form-section + .form-section {
  margin-top: var(--space-section-gap);
}

.product-identity-section .form-section__header {
  padding: 0 0 var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-lighter);
  background: transparent;
  gap: var(--space-control-gap-wide);
}

.product-identity-section .form-section__body {
  padding: var(--space-control-gap-wide) 0 0;
}

.product-identity-section .form-section__error {
  padding: var(--space-sm) 0;
  border-bottom: none;
  margin: var(--space-control-gap) 0 0;
  border-radius: var(--radius-md);
}

/* 规格参数与主产品身份/说明共用同一卡片视觉，
   内部 FormSection 同样去壳，仅保留 header + body 结构，保证整张表单连续。 */
.product-parameter-subsection {
  margin-top: var(--space-xl-2xl);
}

.product-parameter-subsection .form-section {
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}

.product-parameter-subsection .form-section__header {
  padding: 0 0 var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-lighter);
  background: transparent;
  gap: var(--space-control-gap-wide);
}

.product-parameter-subsection .form-section__body {
  padding: var(--space-control-gap-wide) 0 0;
}

.product-parameter-subsection header {
  display: block;
}

.parameter-inline-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 13px 16px;
}

.parameter-field-item {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.parameter-field-wide {
  grid-column: span 2;
}

.parameter-field-item > input,
.parameter-inline-combo input {
  width: 100%;
  min-height: 40px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  text-align: left;
  outline: none;
  padding: 0 10px;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    background 0.15s ease;
}

.parameter-field-item > input:focus,
.parameter-inline-combo input:focus {
  border-color: var(--color-primary);
  background: var(--color-surface);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
}

.parameter-inline-combo {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-control-gap);
}

.parameter-inline-combo label {
  display: grid;
  min-width: 0;
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

.field-error-input,
.parameter-inline-combo label.field-error input {
  border-color: var(--color-danger);
  background: var(--color-danger-bg);
}

@media (max-width: 980px) {
  .archive-field-grid,
  .compact-field-grid {
    grid-template-columns: repeat(min(var(--field-cols, 3), 2), minmax(0, 1fr));
  }

  .product-identity-section .archive-field-grid,
  .product-parameter-subsection .parameter-inline-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .archive-field-grid,
  .compact-field-grid {
    grid-template-columns: 1fr;
  }

  .parameter-inline-grid,
  .parameter-inline-combo {
    grid-template-columns: 1fr;
  }
}

.sidebar-field-settings {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  cursor: pointer;
  transition: all 0.15s ease;
}

.sidebar-field-settings:hover {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.sidebar-field-settings em {
  min-width: 48px;
  padding: 2px 6px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  font-style: normal;
  font-weight: var(--fw-semibold);
  text-align: center;
}

.sidebar-field-settings:hover em {
  background: var(--color-primary-border-light);
  color: var(--color-primary);
}
</style>
