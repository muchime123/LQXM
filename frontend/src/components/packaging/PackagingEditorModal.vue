<template>
  <LqDialog
    :model-value="visible"
    :title="modelId ? '编辑包材' : '新建包材'"
    size="lg"
    :width="isSmallScreen ? '96vw' : undefined"
    mode="form"
    :close-on-click-modal="false"
    :dirty="dirty"
    :loading="saving"
    :scrollable="false"
    class="packaging-editor-dialog"
    confirm-text="保存包材"
    @close="emit('close')"
    @cancel="emit('close')"
    @confirm="save"
  >
    <div class="packaging-editor">
      <div class="editor-body">
        <aside class="editor-nav">
          <button
            v-for="section in sections"
            :key="section.id"
            type="button"
            :class="{ active: activeSection === section.id }"
            @click="scrollTo(section.id)"
          >
            <span>{{ section.title }}</span>
            <em>{{ section.done ? '已填' : '待补' }}</em>
          </button>
        </aside>

        <div ref="bodyRef" class="editor-sections">
          <section id="packaging-basic" class="form-section">
            <h3>基础信息</h3>
            <div class="field-grid">
              <label class="field field-full">
                <span>名称 <b>*</b></span>
                <input
v-model.trim="form.name"
placeholder="例：500ml PP盒"
required
@input="dirty = true" />
              </label>
              <label class="field">
                <span>英文名称</span>
                <input v-model.trim="form.nameEn" placeholder="500ml clear container" @input="dirty = true" />
              </label>
              <label class="field">
                <span>编码</span>
                <input v-model.trim="form.modelCode" placeholder="例：LQ-PKG-500" @input="dirty = true" />
              </label>
            </div>
          </section>

          <section id="packaging-classify" class="form-section">
            <h3>分类与规格</h3>
            <div class="field-grid">
              <label class="field">
                <span>包材类型</span>
                <select v-model="form.packagingKind" @change="dirty = true">
                  <option value="">请选择</option>
                  <option v-for="item in PACKAGING_KINDS" :key="item.key" :value="item.key">
                    {{ item.label }}
                  </option>
                </select>
              </label>
              <label v-if="isFilm" class="field">
                <span>膜形态</span>
                <select v-model="form.filmForm" @change="dirty = true">
                  <option value="">请选择</option>
                  <option v-for="item in FILM_FORMS" :key="item.key" :value="item.key">{{ item.label }}</option>
                </select>
              </label>
              <label v-if="!isFilm" class="field">
                <span>形状</span>
                <select v-model="form.shapeType" @change="dirty = true">
                  <option value="">请选择</option>
                  <option v-for="item in SHAPE_TYPES" :key="item.key" :value="item.key">{{ item.label }}</option>
                </select>
              </label>
              <label class="field">
                <span>{{ materialFieldLabel }}</span>
                <select v-model="form.material" @change="dirty = true">
                  <option value="">请选择</option>
                  <option v-for="m in materialOptions" :key="m.key" :value="m.key">{{ m.label }}</option>
                </select>
              </label>
              <label class="field">
                <span>{{ isFilm ? '工艺/风格' : '颜色/风格' }}</span>
                <input
                  v-model.trim="form.colorStyle"
                  :placeholder="isFilm ? '透明光标 / 彩膜 / 防雾 / 磨砂' : '透明 / 黑色 / 白色 / 彩印'"
                  @input="dirty = true"
                />
              </label>
              <label v-if="!isFilm" class="field">
                <span>容量</span>
                <input v-model.trim="form.capacityMl" placeholder="500ml / 750ml / 1000ml" @input="dirty = true" />
              </label>
            </div>
          </section>

          <section id="packaging-commerce" class="form-section">
            <h3>采购与销售</h3>
            <div class="field-grid">
              <label class="field">
                <span>{{ isFilm ? '起订重量' : '起订量' }}</span>
                <input
                  v-model.trim="form.moq"
                  :placeholder="isFilm ? '例：50kg / 100kg' : '例：1000 个'"
                  @input="dirty = true"
                />
              </label>
              <label class="field">
                <span>{{ isFilm ? '参考报价' : '参考单价' }}</span>
                <input
                  v-model.trim="form.unitPrice"
                  :placeholder="isFilm ? '例：17 元/kg' : '例：0.15 元/个'"
                  @input="dirty = true"
                />
              </label>
              <label class="field">
                <span>交期</span>
                <input v-model.trim="form.leadTime" placeholder="例：7-10 天" @input="dirty = true" />
              </label>
              <label class="field">
                <span>状态</span>
                <select v-model="form.status" @change="dirty = true">
                  <option value="">请选择</option>
                  <option v-for="item in PACKAGING_STATUSES" :key="item" :value="item">{{ item }}</option>
                </select>
              </label>
              <label class="check-field">
                <input v-model="form.customizable" type="checkbox" @change="dirty = true" />
                <span>支持定制</span>
              </label>
            </div>
          </section>

          <section id="packaging-marketing" class="form-section">
            <h3>应用与描述</h3>
            <div class="field-grid">
              <label class="field field-full">
                <span>{{ isFilm ? '适用盒型/设备' : '适用产品/设备' }}</span>
                <input
                  v-model.trim="form.applicable"
                  :placeholder="isFilm ? '例：适用PP/PET盒型，全自动封口机' : '例：自热火锅、航空餐盒、外卖打包'"
                  @input="dirty = true"
                />
              </label>
              <label class="field field-full">
                <span>标签</span>
                <input v-model.trim="form.tags" placeholder="耐高温, 微波炉, 防雾" @input="dirty = true" />
              </label>
              <label class="field field-full">
                <span>{{ isFilm ? '工艺/报价说明' : '产品亮点' }}</span>
                <textarea
                  v-model.trim="form.advantages"
                  rows="4"
                  :placeholder="
                    isFilm
                      ? '记录膜结构、厚度范围、计价口径、特殊材质和报价规则'
                      : '产品卖点、适用场景、竞争优势和客户反馈'
                  "
                  @input="dirty = true"
                ></textarea>
              </label>
              <label class="field field-full">
                <span>备注</span>
                <textarea
                  v-model.trim="form.remark"
                  rows="3"
                  placeholder="内部备注、供应商信息、特殊要求等"
                  @input="dirty = true"
                ></textarea>
              </label>
            </div>
          </section>
          <section id="packaging-website" class="form-section">
            <h3>官网发布</h3>
            <div class="field-grid">
              <label class="check-field">
                <input v-model="form.websiteEnabled" type="checkbox" @change="dirty = true" />
                <span>发布到企业官网</span>
              </label>
              <label class="field">
                <span>官网排序</span>
                <input v-model.number="form.websiteSortOrder" type="number" min="0" @input="dirty = true" />
              </label>
            </div>
            <p class="form-help">仅“在售”且开启发布的包材会出现在官网，数字越小越靠前。</p>
          </section>
        </div>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { createPackagingModel, fetchPackagingModel, updatePackagingModel } from '@/api/packaging'
import LqDialog from '../common/LqDialog.vue'
import {
  FILM_FORMS,
  FILM_MATERIALS,
  BOX_MATERIALS,
  PACKAGING_KINDS,
  PACKAGING_STATUSES,
  SHAPE_TYPES,
  normalizeKindKey,
  normalizeShapeKey
} from '@/constants/packaging'
import { notify } from '@/composables/ui/useToast'

const props = defineProps<{
  visible: boolean
  modelId: number | null
}>()

const emit = defineEmits<{
  close: []
  saved: [id?: number]
}>()

const saving = ref(false)
const dirty = ref(false)
const activeSection = ref('basic')
const bodyRef = ref<HTMLElement | null>(null)

const form = reactive({
  name: '',
  nameEn: '',
  modelCode: '',
  packagingKind: '',
  filmForm: '',
  shapeType: '',
  material: '',
  colorStyle: '',
  capacityMl: '',
  applicable: '',
  advantages: '',
  moq: '',
  unitPrice: '',
  leadTime: '',
  customizable: false,
  status: '在售',
  tags: '',
  remark: '',
  sortOrder: 0
  ,
  websiteEnabled: false,
  websiteSortOrder: 0
})

const isSmallScreen = computed(() => typeof window !== 'undefined' && window.innerWidth <= 760)

const sections = computed(() => [
  { id: 'basic', title: '基础信息', done: Boolean(form.name) },
  { id: 'classify', title: '分类规格', done: Boolean(form.packagingKind || form.material || form.capacityMl) },
  { id: 'commerce', title: '采购销售', done: Boolean(form.moq || form.unitPrice || form.status) },
  { id: 'marketing', title: '应用描述', done: Boolean(form.applicable || form.advantages || form.tags) },
  { id: 'website', title: '官网发布', done: Boolean(form.websiteEnabled) }
])

const isFilm = computed(() => normalizeKindKey(form.packagingKind) === 'film')
const materialFieldLabel = computed(() => {
  if (!isFilm.value) return '材质'
  if (form.filmForm === 'roll') return '膜结构/材质'
  if (form.filmForm === 'sheet') return '片膜材质'
  return '膜材质'
})

/** 已知选项 + 历史遗留值兜底（如 PP/PET、通用膜），避免下拉显示空白 */
const materialOptions = computed(() => {
  const base: ReadonlyArray<{ key: string; label: string }> = isFilm.value ? FILM_MATERIALS : BOX_MATERIALS
  const current = form.material
  if (current && !base.some(item => item.key === current)) {
    return [...base, { key: current, label: `${current}（历史值）` }]
  }
  return base
})

function scrollTo(id: string) {
  activeSection.value = id
  document.getElementById(`packaging-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function cleanPayload() {
  return Object.fromEntries(
    Object.entries(form).map(([key, value]) => [
      key,
      key === 'packagingKind'
        ? normalizeKindKey(String(value || '').trim())
        : key === 'shapeType'
          ? normalizeShapeKey(String(value || '').trim())
          : typeof value === 'string'
            ? value.trim()
            : value
    ])
  )
}

async function save() {
  if (!form.name.trim()) {
    notify.warning('请输入包材名称')
    return
  }
  saving.value = true
  try {
    const payload = cleanPayload()
    const saved = props.modelId
      ? await updatePackagingModel(props.modelId, payload)
      : await createPackagingModel(payload)
    dirty.value = false
    emit('saved', saved?.id ?? props.modelId ?? undefined)
  } catch (error) {
    notify.errorWithDetail('保存失败', error)
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (props.modelId) {
    const detail = await fetchPackagingModel(props.modelId)
    Object.assign(form, detail.model)
    form.packagingKind = normalizeKindKey(form.packagingKind)
    form.shapeType = normalizeShapeKey(form.shapeType)
    dirty.value = false
  }
  await nextTick()
  bodyRef.value?.addEventListener('scroll', () => {
    let current = sections.value[0]
    for (const section of sections.value) {
      const el = document.getElementById(`packaging-${section.id}`)
      if (el && bodyRef.value && el.offsetTop - bodyRef.value.offsetTop <= bodyRef.value.scrollTop + 24) {
        current = section
      }
    }
    if (current) activeSection.value = current.id
  })
})
</script>

<style scoped>
.packaging-editor {
  display: flex;
  flex-direction: column;
  height: min(760px, 88vh);
}

.editor-body {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
}

.editor-nav {
  display: grid;
  align-content: start;
  gap: var(--space-sm);
  padding: var(--space-lg);
  border-right: 1px solid var(--color-border);
  background: var(--color-surface-alt);
}

.editor-nav button {
  display: flex;
  justify-content: space-between;
  gap: var(--space-control-gap);
  border: 0;
  border-radius: var(--radius-md);
  padding: var(--space-md);
  background: transparent;
  color: var(--color-text-muted);
  text-align: left;
  cursor: pointer;
}

.editor-nav button.active {
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
  font-weight: var(--fw-bold);
}

.editor-nav em {
  font-size: var(--fs-xs);
  font-style: normal;
}

.editor-sections {
  display: grid;
  gap: var(--space-xl);
  overflow-y: auto;
  padding: var(--space-xl-2xl);
}

.form-section {
  display: grid;
  gap: var(--space-lg);
}

.form-section h3 {
  margin: 0;
  padding-bottom: var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-lighter, var(--color-border));
  color: var(--color-text-heading);
  font-size: var(--fs-md);
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}

.field,
.check-field {
  display: grid;
  gap: var(--space-sm);
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.field-full {
  grid-column: 1 / -1;
}

.field b {
  color: var(--color-danger);
}

.field input,
.field select,
.field textarea {
  width: 100%;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  padding: 9px 11px;
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  font-weight: var(--fw-normal);
  box-sizing: border-box;
}

.field textarea {
  resize: vertical;
}

.field input:focus,
.field select:focus,
.field textarea:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus);
}

.check-field {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  align-self: end;
  min-height: 38px;
}

.check-field input {
  width: 16px;
  height: 16px;
  accent-color: var(--color-primary);
}

@media (max-width: 760px) {
  .packaging-editor {
    height: 90vh;
  }

  .editor-body {
    grid-template-columns: 1fr;
  }

  .editor-nav {
    display: none;
  }

  .field-grid {
    grid-template-columns: 1fr;
  }
}
</style>
