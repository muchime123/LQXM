<template>
  <LqDialog
    :model-value="visible"
    :title="isEditing ? '编辑文章' : '新增文章'"
    size="lg"
    :close-on-click-modal="false"
    destroy-on-close
    :loading="saving"
    mode="form"
    confirm-text="保存"
    cancel-text="取消"
    @update:model-value="handleVisibleUpdate"
    @confirm="handleSave"
    @close="onClose"
  >
    <div class="form-grid">
      <div class="form-field">
        <label class="required">分类</label>
        <el-select v-model="form.categoryId" style="width: 100%">
          <el-option v-for="c in categories" :key="c.id" :value="c.id" :label="c.name" />
        </el-select>
      </div>
      <div class="form-field">
        <label class="required">标题</label>
        <el-input v-model="form.title" placeholder="文章标题" />
      </div>
      <div class="form-field">
        <label>作者</label>
        <el-input v-model="form.author" placeholder="叶经理" />
      </div>
      <div class="form-field">
        <label>来源</label>
        <el-input v-model="form.source" />
      </div>
      <div class="form-field full-field">
        <label>标签</label>
        <LqTagInput v-model="form.tags" placeholder="输入标签后按 Enter 添加" />
      </div>
      <div class="form-field">
        <label>状态</label>
        <el-select v-model="form.status" style="width: 100%">
          <el-option value="published" label="已发布" />
          <el-option value="draft" label="草稿" />
        </el-select>
      </div>
      <div class="form-field">
        <label>官网展示</label>
        <el-switch v-model="form.websiteVisible" />
        <span class="hint-label">仅「已发布」且勾选后才会在官网知识库栏目展示</span>
      </div>
    </div>
    <div class="content-field">
      <div class="content-head">
        <label class="required">正文内容</label>
        <span class="hint-label">富文本编辑，用 H2/H3 标题分节，官网知识库展示时自动生成侧边目录</span>
      </div>
      <RichTextEditor
        v-model="form.content"
        :height="460"
        placeholder="输入正文内容..."
      />
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { reactive, computed, watch } from 'vue'
import LqTagInput from '../common/LqTagInput.vue'
import LqDialog from '../common/LqDialog.vue'
import RichTextEditor from '../website/RichTextEditor.vue'
import { markdownToHtml } from '../../utils/common/markdown'
import { notify } from '../../composables/ui/useToast'

const props = defineProps<{
  visible: boolean
  article: any | null
  categories: any[]
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save', data: any): void
  (e: 'close'): void
}>()

const defaultForm = () => ({
  categoryId: null as number | null,
  title: '',
  author: '',
  source: '',
  tags: '',
  content: '',
  status: 'published',
  websiteVisible: false
})

const form = reactive(defaultForm())

const isEditing = computed(() => !!props.article?.id)

/** 旧数据为 Markdown：打开编辑时转 HTML 预填；已是 HTML 则原样使用。 */
function prepareContentForRichEditor(content?: string | null): string {
  const value = (content || '').trim()
  if (!value) return ''
  if (/<[a-z][\s\S]*>/i.test(value)) return value
  return markdownToHtml(value, { emptyText: '' })
}

function initForm() {
  Object.assign(form, defaultForm())
  if (props.article) {
    Object.assign(form, {
      categoryId: props.article.categoryId ?? null,
      title: props.article.title ?? '',
      author: props.article.author ?? '',
      source: props.article.source ?? '',
      tags: props.article.tags ?? '',
      content: prepareContentForRichEditor(props.article.content),
      status: props.article.status ?? 'published',
      websiteVisible: !!props.article.websiteVisible
    })
  }
}

watch(
  () => props.visible,
  val => {
    if (val) {
      initForm()
    }
  }
)

function handleVisibleUpdate(value: boolean) {
  emit('update:visible', value)
}

function onClose() {
  emit('update:visible', false)
  emit('close')
}

function handleSave() {
  if (!form.title || !form.content) {
    notify('请填写标题和内容', 'warning')
    return
  }
  emit('save', { ...form })
}
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 18px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.form-field.full-field {
  grid-column: 1 / -1;
}

.form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  display: inline-flex;
  align-items: baseline;
}

.form-field > label.required::before,
.content-head label.required::before {
  content: '*';
  color: var(--color-danger);
  margin-right: 3px;
  font-weight: 700;
}

.hint-label {
  font-size: 11px;
  color: var(--color-text-faint);
  font-weight: normal;
}

.content-field {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.content-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.content-head label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  display: inline-flex;
  align-items: baseline;
}
</style>
