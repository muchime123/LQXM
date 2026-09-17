/**
 * 文件类型图标映射
 * 根据文件扩展名返回对应的图标 key，由 FileTypeIcon 组件渲染为 SVG
 */

export type FileTypeIconKey =
  | 'file'
  | 'fileText'
  | 'image'
  | 'film'
  | 'music'
  | 'box'
  | 'code'
  | 'table'
  | 'slides'
  | 'drawing'
  | 'award'
  | 'globe'

const FILE_ICON_KEYS: Record<string, FileTypeIconKey> = {
  pdf: 'fileText',
  doc: 'fileText',
  docx: 'fileText',
  md: 'fileText',
  txt: 'fileText',
  rtf: 'fileText',
  log: 'fileText',
  json: 'fileText',
  xml: 'fileText',
  psd: 'fileText',
  xls: 'table',
  xlsx: 'table',
  csv: 'table',
  ppt: 'slides',
  pptx: 'slides',
  js: 'code',
  mjs: 'code',
  html: 'globe',
  htm: 'globe',
  css: 'code',
  png: 'image',
  jpg: 'image',
  jpeg: 'image',
  gif: 'image',
  bmp: 'image',
  webp: 'image',
  svg: 'image',
  ico: 'image',
  tiff: 'image',
  tif: 'image',
  ai: 'drawing',
  dwg: 'drawing',
  dxf: 'drawing',
  cdr: 'drawing',
  mp3: 'music',
  wav: 'music',
  ogg: 'music',
  flac: 'music',
  aac: 'music',
  m4a: 'music',
  wma: 'music',
  mp4: 'film',
  webm: 'film',
  mov: 'film',
  avi: 'film',
  mkv: 'film',
  wmv: 'film',
  flv: 'film',
  zip: 'box',
  rar: 'box',
  '7z': 'box',
  tar: 'box',
  gz: 'box',
  exe: 'code',
  bat: 'code',
  sh: 'code'
}

/**
 * 根据文件扩展名获取图标 key
 * @param ext - 文件扩展名
 */
export function fileTypeIconKey(ext: string | null | undefined): FileTypeIconKey {
  return FILE_ICON_KEYS[(ext || '').toLowerCase()] || 'file'
}
