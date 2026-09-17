<template>
  <svg
    class="file-type-icon"
    :style="sizeStyle"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <component
:is="part.tag"
v-for="(part, i) in iconParts"
:key="i"
v-bind="part.attrs" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { fileTypeIconKey, type FileTypeIconKey } from '../../utils/ui/fileIcon'

interface IconPart {
  tag: 'path' | 'polyline' | 'rect' | 'circle' | 'polygon'
  attrs: Record<string, string>
}

const ICONS: Record<FileTypeIconKey, IconPart[]> = {
  file: [
    { tag: 'path', attrs: { d: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z' } },
    { tag: 'polyline', attrs: { points: '14 2 14 8 20 8' } }
  ],
  fileText: [
    { tag: 'path', attrs: { d: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z' } },
    { tag: 'polyline', attrs: { points: '14 2 14 8 20 8' } },
    { tag: 'path', attrs: { d: 'M16 13H8' } },
    { tag: 'path', attrs: { d: 'M16 17H8' } },
    { tag: 'path', attrs: { d: 'M10 9H8' } }
  ],
  image: [
    { tag: 'rect', attrs: { x: '3', y: '3', width: '18', height: '18', rx: '2', ry: '2' } },
    { tag: 'circle', attrs: { cx: '8.5', cy: '8.5', r: '1.5' } },
    { tag: 'polyline', attrs: { points: '21 15 16 10 5 21' } }
  ],
  film: [
    { tag: 'polygon', attrs: { points: '23 7 16 12 23 17 23 7' } },
    { tag: 'rect', attrs: { x: '1', y: '5', width: '15', height: '14', rx: '2', ry: '2' } }
  ],
  music: [
    { tag: 'path', attrs: { d: 'M9 18V5l12-2v13' } },
    { tag: 'circle', attrs: { cx: '6', cy: '18', r: '3' } },
    { tag: 'circle', attrs: { cx: '18', cy: '16', r: '3' } }
  ],
  box: [
    { tag: 'path', attrs: { d: 'M21 8v13H3V8' } },
    { tag: 'path', attrs: { d: 'M1 3h22v5H1z' } },
    { tag: 'path', attrs: { d: 'M10 12h4' } }
  ],
  code: [
    { tag: 'polyline', attrs: { points: '16 18 22 12 16 6' } },
    { tag: 'polyline', attrs: { points: '8 6 2 12 8 18' } }
  ],
  table: [
    { tag: 'rect', attrs: { x: '3', y: '3', width: '18', height: '18', rx: '2', ry: '2' } },
    { tag: 'path', attrs: { d: 'M3 9h18' } },
    { tag: 'path', attrs: { d: 'M3 15h18' } },
    { tag: 'path', attrs: { d: 'M9 3v18' } }
  ],
  slides: [
    { tag: 'rect', attrs: { x: '3', y: '4', width: '18', height: '13', rx: '2', ry: '2' } },
    { tag: 'path', attrs: { d: 'M12 17v3' } },
    { tag: 'path', attrs: { d: 'M8 20h8' } }
  ],
  drawing: [
    { tag: 'path', attrs: { d: 'M12 19l7-7 3 3-7 7-3-3z' } },
    { tag: 'path', attrs: { d: 'M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z' } },
    { tag: 'path', attrs: { d: 'M2 2l7.586 7.586' } }
  ],
  award: [
    { tag: 'circle', attrs: { cx: '12', cy: '8', r: '6' } },
    { tag: 'path', attrs: { d: 'M15.477 12.89 17 22l-5-3-5 3 1.523-9.11' } }
  ],
  globe: [
    { tag: 'circle', attrs: { cx: '12', cy: '12', r: '10' } },
    { tag: 'path', attrs: { d: 'M2 12h20' } },
    { tag: 'path', attrs: { d: 'M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z' } }
  ]
}

const LEGACY_TOKENS: Record<string, FileTypeIconKey> = {
  FILE: 'file',
  DOC: 'fileText',
  PDF: 'fileText',
  IMAGE: 'image',
  VIDEO: 'film',
  AUDIO: 'music',
  ARCHIVE: 'box',
  CODE: 'code',
  TABLE: 'table',
  SLIDES: 'slides',
  DWG: 'drawing',
  CERT: 'award'
}

const props = withDefaults(
  defineProps<{
    ext?: string | null
    type?: string
    size?: string | number
  }>(),
  {
    ext: null,
    type: '',
    size: ''
  }
)

const iconParts = computed<IconPart[]>(() => {
  const key = resolveKey()
  return ICONS[key] || ICONS.file
})

const sizeStyle = computed(() => {
  if (props.size === '' || props.size == null) return {}
  const value = typeof props.size === 'number' ? `${props.size}px` : props.size
  return { width: value, height: value }
})

function resolveKey(): FileTypeIconKey {
  if (props.ext) return fileTypeIconKey(props.ext)
  const token = (props.type || '').trim().toUpperCase()
  return LEGACY_TOKENS[token] || 'file'
}
</script>

<style scoped>
.file-type-icon {
  display: inline-block;
  width: 1em;
  height: 1em;
  vertical-align: -0.125em;
  flex-shrink: 0;
}
</style>
