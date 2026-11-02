<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import { fetchWebsiteMediaBlob, uploadWebsiteMedia } from '@/api/website/media'
import { uploadFile } from '@/api/file/file'
import { notify } from '@/composables/ui/useToast'
import { previewWebsiteHtml, savedWebsiteHtml, websiteMediaUrls } from '@/utils/websiteRichTextMedia'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    height?: number
    mediaScope?: 'website' | 'company'
  }>(),
  {
    modelValue: '',
    placeholder: '请输入正文内容…',
    height: 480,
    mediaScope: 'website'
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const editorRef = shallowRef<IDomEditor>()
const valueHtml = ref('')
const uploading = ref(false)
const previewUrls = new Map<string, string>()
let hydrateVersion = 0
let disposed = false

async function editorHtml(html: string, version: number): Promise<string> {
  const urls = websiteMediaUrls(html)
  await Promise.all(urls.map(async url => {
    if (previewUrls.has(url)) return
    const id = Number(/\/media\/(\d+)\/content/.exec(url)?.[1])
    if (!id) return
    try {
      const blob = await fetchWebsiteMediaBlob(id)
      if (version !== hydrateVersion) return
      previewUrls.set(url, URL.createObjectURL(blob))
    } catch {
      // Preserve saved HTML if a referenced media file is missing.
    }
  }))
  return previewWebsiteHtml(html, previewUrls)
}

const toolbarConfig: Partial<IToolbarConfig> = {}
async function uploadImage(file: File): Promise<string> {
  if (props.mediaScope === 'company') {
    const result = await uploadFile('company', file)
    if (!result.fileName) throw new Error('公司图片上传未返回文件名')
    return `/api/files/company?encoded=${encodeURIComponent(result.fileName)}`
  }
  const result = await uploadWebsiteMedia(file)
  return `/api/website/media/${result.id}/content`
}
const editorConfig: Partial<IEditorConfig> = {
  placeholder: props.placeholder,
  MENU_CONF: {
    uploadImage: {
      async customUpload(file: File, insertFn: (url: string, alt: string, href: string) => void) {
        try {
          uploading.value = true
          const url = await uploadImage(file)
          if (disposed) return
          const preview = URL.createObjectURL(file)
          previewUrls.set(url, preview)
          insertFn(preview, file.name, '')
        } catch (err) {
          notify.errorWithDetail('图片上传失败', err)
        } finally {
          uploading.value = false
        }
      }
    }
  }
}

function handleCreated(editor: IDomEditor) {
  editorRef.value = editor
}

watch(valueHtml, (val) => {
  const saved = savedWebsiteHtml(val, previewUrls)
  if (saved !== props.modelValue) emit('update:modelValue', saved)
}, { flush: 'sync' })

watch(
  () => props.modelValue,
  async (val) => {
    const version = ++hydrateVersion
    const source = val || ''
    if (source === savedWebsiteHtml(valueHtml.value, previewUrls)) return
    const before = valueHtml.value
    const hydrated = await editorHtml(source, version)
    if (version === hydrateVersion && source === (props.modelValue || '')
      && valueHtml.value === before) valueHtml.value = hydrated
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  disposed = true
  hydrateVersion++
  editorRef.value?.destroy()
  editorRef.value = undefined
  for (const url of previewUrls.values()) URL.revokeObjectURL(url)
  previewUrls.clear()
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
