<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import { createAsset } from '@/api/company'
import { useFileUpload } from '@/composables/upload/useFileUpload'
import { notify } from '@/composables/ui/useToast'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    height?: number
  }>(),
  {
    modelValue: '',
    placeholder: '请输入正文内容…',
    height: 480
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const editorRef = shallowRef<IDomEditor>()
const valueHtml = ref(props.modelValue || '')
const { uploading, uploadSelectedFile } = useFileUpload({ notify })

const toolbarConfig: Partial<IToolbarConfig> = {}
const editorConfig: Partial<IEditorConfig> = {
  placeholder: props.placeholder,
  MENU_CONF: {
    uploadImage: {
      async customUpload(file: File, insertFn: (url: string, alt: string, href: string) => void) {
        try {
          const result = await uploadSelectedFile('company', file)
          if (!result?.fileName) return
          try {
            await createAsset({
              category: 'website_media',
              fileName: result.fileName,
              tags: '官网正文',
              defaultAsset: false,
              sortOrder: 0
            })
          } catch (err) {
            notify.errorWithDetail('图片已上传，但登记到素材库失败', err)
          }
          insertFn(`/api/files/company?name=${encodeURIComponent(result.fileName)}`, '', '')
        } catch (err) {
          notify.errorWithDetail('图片上传失败', err)
        }
      }
    }
  }
}

function handleCreated(editor: IDomEditor) {
  editorRef.value = editor
}

watch(valueHtml, (val) => {
  if (val !== props.modelValue) emit('update:modelValue', val)
})

watch(
  () => props.modelValue,
  (val) => {
    if (val !== valueHtml.value) valueHtml.value = val || ''
  }
)

onBeforeUnmount(() => {
  editorRef.value?.destroy()
  editorRef.value = undefined
})
</script>

<template>
  <div class="rich-text-editor">
    <Toolbar
      :editor="editorRef"
      :default-config="toolbarConfig"
      mode="default"
      class="rich-text-editor__toolbar"
    />
    <div class="rich-text-editor__body" :style="{ height: `${height}px` }">
      <Editor
        v-model="valueHtml"
        :default-config="editorConfig"
        mode="default"
        class="rich-text-editor__canvas"
        @on-created="handleCreated"
      />
    </div>
    <p v-if="uploading" class="rich-text-editor__tip">图片上传中，请稍候…</p>
  </div>
</template>

<style scoped>
.rich-text-editor {
  position: relative;
  width: 100%;
}

.rich-text-editor__toolbar {
  border: 1px solid var(--color-border);
  border-bottom: none;
  border-radius: var(--radius-md) var(--radius-md) 0 0;
  background: var(--color-surface);
  z-index: 2;
}

.rich-text-editor__body {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-top: none;
  border-radius: 0 0 var(--radius-md) var(--radius-md);
  background: var(--color-surface);
}

.rich-text-editor__body :deep(.w-e-text-container) {
  height: 100%;
}

.rich-text-editor__tip {
  margin: var(--space-sm) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
}
</style>
