/**
 * File URL and preview helpers.
 */

export type FilePreviewType =
  'image' | 'pdf' | 'text' | 'word' | 'legacy-word' | 'excel' | 'ppt' | 'audio' | 'video' | 'archive' | 'unsupported'

export const FILE_TYPE_GROUPS: Record<string, string[]> = {
  image: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico', 'tiff', 'tif', 'avif'],
  pdf: ['pdf'],
  text: ['txt', 'md', 'csv', 'log', 'json', 'xml', 'html', 'htm', 'yaml', 'yml', 'ini', 'sql'],
  word: ['docx', 'wps', 'rtf'],
  legacyWord: ['doc'],
  excel: ['xls', 'xlsx', 'xlsm', 'et'],
  ppt: ['ppt', 'pptx', 'dps'],
  audio: ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a', 'wma'],
  video: ['mp4', 'webm', 'avi', 'mov', 'mkv', 'wmv', 'flv', 'm4v'],
  archive: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2']
}

// legacy-word 与 rtf 也支持预览（通过后端 LibreOffice 转 PDF），
// 与后端 OfficePreviewService.OFFICE_EXTENSIONS 保持一致。
const PREVIEWABLE_GROUPS = new Set(['image', 'pdf', 'text', 'word', 'legacy-word', 'excel', 'ppt', 'audio', 'video'])

/**
 * 走后端 LibreOffice → PDF 链路预览的 Office 类型。
 * 前端不再用 mammoth/xlsx 自行解析，统一由后端转 PDF 后 iframe 渲染。
 */
export const OFFICE_PREVIEW_TYPES: readonly FilePreviewType[] = [
  'word',
  'legacy-word',
  'excel',
  'ppt'
]

export function isOfficePreviewType(type: string | null | undefined): boolean {
  return (OFFICE_PREVIEW_TYPES as readonly string[]).includes(String(type || ''))
}

const EXTENSION_TO_GROUP: Record<string, string> = Object.entries(FILE_TYPE_GROUPS).reduce(
  (acc, [group, exts]) => {
    for (const ext of exts) acc[ext] = group
    return acc
  },
  {} as Record<string, string>
)

export function fileExtension(fileName: string | null | undefined): string {
  const name = String(fileName || '')
    .trim()
    .toLowerCase()
  const cleanName = name.split(/[?#]/)[0]
  const dot = cleanName.lastIndexOf('.')
  return dot >= 0 ? cleanName.substring(dot + 1) : ''
}

export function encodeFileName(fileName: string): string {
  return btoa(unescape(encodeURIComponent(fileName)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

export function fileUrl(category: string, fileName: string, modelId?: number | null): string {
  if (!fileName) return ''
  return modelId != null
    ? `/api/files/${modelId}/${category}?encoded=${encodeFileName(fileName)}`
    : `/api/files/${category}?encoded=${encodeFileName(fileName)}`
}

export function filePreviewType(fileNameOrType: string | null | undefined): FilePreviewType {
  const raw = String(fileNameOrType || '')
    .trim()
    .toLowerCase()
  const ext = raw.includes('.') ? fileExtension(raw) : raw.replace(/^\./, '')
  const group = EXTENSION_TO_GROUP[ext]
  if (group === 'legacyWord') return 'legacy-word'
  return (group as FilePreviewType) || 'unsupported'
}

export function isImageFile(fileName: string | null | undefined): boolean {
  return filePreviewType(fileName) === 'image'
}

export function isPdfFile(fileName: string | null | undefined): boolean {
  return filePreviewType(fileName) === 'pdf'
}

export function isPreviewableFile(fileNameOrType: string | null | undefined): boolean {
  return PREVIEWABLE_GROUPS.has(filePreviewType(fileNameOrType))
}

export function fileIcon(fileName: string | null | undefined): string {
  switch (filePreviewType(fileName)) {
    case 'image':
      return 'IMG'
    case 'pdf':
      return 'PDF'
    case 'text':
      return 'TXT'
    case 'word':
    case 'legacy-word':
      return 'DOC'
    case 'excel':
      return 'XLS'
    case 'ppt':
      return 'PPT'
    case 'audio':
      return 'AUD'
    case 'video':
      return 'VID'
    case 'archive':
      return 'ZIP'
    default:
      return 'FILE'
  }
}
