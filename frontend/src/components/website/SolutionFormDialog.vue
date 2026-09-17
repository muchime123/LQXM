<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { createSolution, updateSolution } from '@/api/website/solutions'
import type { WebsiteSolution, SolutionSection, WebsiteSolutionPayload, SolutionContentSection } from '@/types/website'
import { SOLUTION_SECTION_LABELS, VISIBLE_SOLUTION_SECTIONS } from '@/types/website'
import { notify } from '@/composables/ui/useToast'
import WebsiteImageField from '@/components/website/WebsiteImageField.vue'
import RichTextEditor from '@/components/website/RichTextEditor.vue'
import { sectionsToHtml } from '@/utils/richText'

const props = defineProps<{
  modelValue: WebsiteSolution | null
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'save'): void
}>()

const isEdit = computed(() => !!props.modelValue)

const form = ref<{
  section: SolutionSection
  itemKey: string
  slug: string
  title: string
  category: string
  summary: string
  coverImageUrl: string
  audience: string
  takeaway: string
  intro: string
  icon: string
  points: string[]
  contentSections: SolutionContentSection[]
  contentHtml: string
  relatedSceneKeys: string[]
  seoTitle: string
  seoDescription: string
  sortOrder: number
  websiteEnabled: boolean
  websiteFeatured: boolean
}>({
  section: 'SOLUTION',
  itemKey: '',
  slug: '',
  title: '',
  category: '',
  summary: '',
  coverImageUrl: '',
  audience: '',
  takeaway: '',
  intro: '',
  icon: '',
  points: [],
  contentSections: [],
  contentHtml: '',
  relatedSceneKeys: [],
  seoTitle: '',
  seoDescription: '',
  sortOrder: 0,
  websiteEnabled: true,
  websiteFeatured: false
})

watch(
  () => props.modelValue,
  (val) => {
    if (val) {
      form.value = {
        section: val.section,
        itemKey: val.itemKey || '',
        slug: val.slug || '',
        title: val.title,
        category: val.category || '',
        summary: val.summary || '',
        coverImageUrl: val.coverImageUrl || '',
        audience: val.audience || '',
        takeaway: val.takeaway || '',
        intro: val.intro || '',
        icon: val.icon || '',
        points: val.points ? [...val.points] : [],
        contentSections: (val.contentSections || []).map(section => ({
          title: section.title || '', body: section.body || '', points: [...(section.points || [])], imageUrl: section.imageUrl || ''
        })),
        contentHtml: val.contentHtml || sectionsToHtml(val.contentSections),
        relatedSceneKeys: [...(val.relatedSceneKeys || [])],
        seoTitle: val.seoTitle || '',
        seoDescription: val.seoDescription || '',
        sortOrder: val.sortOrder || 0,
        websiteEnabled: val.websiteEnabled,
        websiteFeatured: val.websiteFeatured || false
      }
    } else {
      const defaultSection = (window as any).__solution_default_section || 'INDUSTRY'
      form.value = {
        section: defaultSection,
        itemKey: '',
        slug: '',
        title: '',
        category: '',
        summary: '',
        coverImageUrl: '',
        audience: '',
        takeaway: '',
        intro: '',
        icon: '',
        points: [],
        contentSections: [],
        contentHtml: '',
        relatedSceneKeys: [],
        seoTitle: '',
        seoDescription: '',
        sortOrder: 0,
        websiteEnabled: true,
        websiteFeatured: false
      }
    }
  },
  { immediate: true }
)

async function handleSubmit() {
  if (!form.value.title.trim()) {
    notify.warning('请填写标题')
    return
  }
  if (form.value.section === 'ARTICLE' && !form.value.slug.trim()) {
    notify.warning('方案文章必须填写 URL 标识')
    return
  }
  if (form.value.section === 'INDUSTRY' && !form.value.itemKey.trim()) {
    notify.warning('行业场景必须填写场景 Key')
    return
  }
  const payload: WebsiteSolutionPayload = {
    section: form.value.section,
    itemKey: form.value.itemKey.trim() || undefined,
    slug: form.value.slug.trim() || undefined,
    title: form.value.title.trim(),
    category: form.value.category.trim() || undefined,
    summary: form.value.summary.trim() || undefined,
    coverImageUrl: form.value.coverImageUrl.trim() || undefined,
    audience: form.value.audience.trim() || undefined,
    takeaway: form.value.takeaway.trim() || undefined,
    intro: form.value.intro.trim() || undefined,
    icon: form.value.icon.trim() || undefined,
    points: form.value.points.length > 0 ? [...form.value.points] : undefined,
        contentSections: form.value.contentSections.map(section => ({
          title: section.title.trim(),
          body: section.body.trim(),
          points: section.points.map(point => point.trim()).filter(Boolean),
          imageUrl: section.imageUrl?.trim() || undefined
        })).filter(section => section.title || section.body),
        contentHtml: form.value.contentHtml.trim() || undefined,
        relatedSceneKeys: form.value.relatedSceneKeys,
    seoTitle: form.value.seoTitle.trim() || undefined,
    seoDescription: form.value.seoDescription.trim() || undefined,
    sortOrder: form.value.sortOrder,
    websiteEnabled: form.value.websiteEnabled,
    websiteFeatured: form.value.websiteFeatured
  }

  try {
    if (isEdit.value) {
      await updateSolution(props.modelValue!.id, payload)
      notify('更新成功', 'success')
    } else {
      await createSolution(payload)
      notify('创建成功', 'success')
    }
    emit('save')
    emit('update:modelValue', false)
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '保存失败'
    notify.error(message)
  }
}

function addPoint() {
  form.value.points.push('')
}

function removePoint(index: number) {
  form.value.points.splice(index, 1)
}

function movePointUp(index: number) {
  if (index > 0) {
    const arr = form.value.points
    ;[arr[index - 1], arr[index]] = [arr[index], arr[index - 1]]
  }
}

function movePointDown(index: number) {
  const arr = form.value.points
  if (index < arr.length - 1) {
    ;[arr[index + 1], arr[index]] = [arr[index], arr[index + 1]]
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="isEdit ? '编辑内容' : '新增内容'"
    width="min(960px, 95vw)"
    :close-on-click-modal="false"
    append-to-body
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form :model="form" label-width="96px" label-position="right" class="solution-edit-form">
      <el-divider content-position="left">基本信息</el-divider>
      <el-form-item label="分区">
        <el-select v-model="form.section" style="width: 100%">
          <el-option
            v-for="key in VISIBLE_SOLUTION_SECTIONS"
            :key="key"
            :label="SOLUTION_SECTION_LABELS[key]"
            :value="key"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" placeholder="输入标题" maxlength="200" show-word-limit />
      </el-form-item>
      <template v-if="form.section === 'ARTICLE'">
        <el-form-item label="URL 标识" required>
          <el-input v-model="form.slug" placeholder="如 tray-sealing-leak-diagnosis" maxlength="120" />
        </el-form-item>
        <el-form-item label="内容分类">
          <el-select v-model="form.category" placeholder="选择分类" style="width: 100%">
            <el-option label="生产场景" value="生产场景" />
            <el-option label="问题诊断" value="问题诊断" />
            <el-option label="选型参考" value="选型参考" />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item :label="form.section === 'INDUSTRY' ? '场景 Key' : 'Key'" :required="form.section === 'INDUSTRY'">
        <el-input
          v-model="form.itemKey"
          :placeholder="form.section === 'INDUSTRY' ? '必填，如 fresh-meat、prepared-food' : '可选，用于内容关联或锚点跳转'"
        />
        <div v-if="form.section === 'INDUSTRY'" class="field-help">用于首页跳转和方案文章关联；保存后不建议随意修改。</div>
      </el-form-item>
      <el-form-item v-if="form.section === 'SOLUTION'" label="图标">
        <el-select v-model="form.icon" placeholder="选择图标" clearable style="width: 100%">
          <el-option label="fresh" value="fresh" />
          <el-option label="sealing" value="sealing" />
          <el-option label="automation" value="automation" />
          <el-option label="consumables" value="consumables" />
        </el-select>
      </el-form-item>
      <el-form-item label="摘要">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="3"
          placeholder="输入摘要或描述"
          maxlength="1000"
          show-word-limit
        />
      </el-form-item>
      <el-form-item v-if="form.section !== 'PROCESS'" label="封面图片">
        <WebsiteImageField
          v-model="form.coverImageUrl"
          :aspect-ratio="form.section === 'INDUSTRY' ? '4 / 3' : '16 / 9'"
          :hint="form.section === 'INDUSTRY' ? '建议使用同一比例的生产场景图片，首页与解决方案页会共用该图片。' : '建议使用 16:9 文章封面图。'"
        />
      </el-form-item>

      <template v-if="form.section === 'ARTICLE'">
        <el-divider content-position="left">文章内容</el-divider>
        <el-form-item label="适合对象">
          <el-input v-model="form.audience" placeholder="这篇内容适合哪些客户或岗位" maxlength="500" />
        </el-form-item>
        <el-form-item label="读者收获">
          <el-input v-model="form.takeaway" placeholder="读完后可以获得什么" maxlength="500" />
        </el-form-item>
        <el-form-item label="文章导语">
          <el-input v-model="form.intro" type="textarea" :rows="4" placeholder="说明问题背景和核心判断" />
        </el-form-item>
        <el-form-item label="关联场景">
          <el-select v-model="form.relatedSceneKeys" multiple allow-create filterable default-first-option placeholder="输入场景 Key，如 fresh-meat" style="width: 100%" />
        </el-form-item>
        <el-form-item label="正文内容">
          <div class="rich-text-form-item">
            <RichTextEditor v-model="form.contentHtml" :height="560" placeholder="正文内容… 用 H2/H3 标题分节，官网会自动生成侧边目录" />
            <p class="field-help">H2/H3 标题会自动生成官网侧边目录；图片直接粘贴或从工具栏上传，自动进入官网素材库。</p>
          </div>
        </el-form-item>
        <el-divider content-position="left">搜索展示</el-divider>
        <el-form-item label="SEO 标题">
          <el-input v-model="form.seoTitle" maxlength="200" show-word-limit placeholder="留空则使用文章标题" />
        </el-form-item>
        <el-form-item label="SEO 描述">
          <el-input v-model="form.seoDescription" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="留空则使用文章摘要" />
        </el-form-item>
      </template>

      <el-divider content-position="left">发布设置</el-divider>
      <el-form-item v-if="form.section === 'SOLUTION'" label="要点">
        <div class="points-editor">
          <div
            v-for="(_, index) in form.points"
            :key="index"
            class="point-row"
          >
            <el-input
              v-model="form.points[index]"
              :placeholder="`要点 ${index + 1}`"
              class="point-input"
            />
            <el-button
              size="small"
              :disabled="index === 0"
              @click="movePointUp(index)"
            >↑</el-button>
            <el-button
              size="small"
              :disabled="index === form.points.length - 1"
              @click="movePointDown(index)"
            >↓</el-button>
            <el-button size="small" type="danger" @click="removePoint(index)">×</el-button>
          </div>
          <el-button size="small" @click="addPoint">+ 添加要点</el-button>
        </div>
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
      </el-form-item>
      <el-form-item label="官网可见">
        <el-switch v-model="form.websiteEnabled" />
        <span class="form-hint">关闭后不在官网展示</span>
      </el-form-item>
      <el-form-item v-if="form.section === 'ARTICLE'" label="重点展示">
        <el-switch v-model="form.websiteFeatured" />
        <span class="form-hint">用于官网优先展示和后续首页推荐</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.points-editor {
  width: 100%;
}

.point-row {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  align-items: center;
}

.point-input {
  flex: 1;
}

.form-hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-left: 8px;
}

.rich-text-form-item { width: 100%; }
.solution-edit-form { min-width: 0; }.field-help { margin-top: 5px; color: var(--color-text-tertiary); font-size: 11px; }
</style>
