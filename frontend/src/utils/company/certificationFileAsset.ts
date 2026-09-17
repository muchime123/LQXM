// 与后端 CompanyFileAssetAdapter.CERT_FILE_BASE 保持一致：负整数合成 ID，
// 避免与 CompanyAsset 真实正数 ID 冲突。
const CERT_FILE_BASE = -3_000_000_000_000
const FILES_PER_CERT = 100

export type CertificationFileField = 'fileName'

export function certificationFileAssetId(
  certificationId: number,
  _field: CertificationFileField,
  index = 0
): number {
  return CERT_FILE_BASE - certificationId * FILES_PER_CERT - index
}

export function certificationFileFieldLabel(field: CertificationFileField): string {
  return field === 'fileName' ? '附件文件' : field
}

/** 把 fileName 字段解析为文件名数组（兼容旧版单文件字符串和 JSON 数组格式）。 */
export function parseCertificationFileNames(fileName: string | null | undefined): string[] {
  if (!fileName) return []
  const trimmed = fileName.trim()
  if (trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed)
      return Array.isArray(parsed) ? parsed : [fileName]
    } catch {
      return [fileName]
    }
  }
  return [fileName]
}

/** 把文件名数组序列化为 fileName 字段值（单文件保持纯字符串，多文件用 JSON 数组）。 */
export function stringifyCertificationFileNames(names: string[]): string {
  if (!names.length) return ''
  if (names.length === 1) return names[0]
  return JSON.stringify(names)
}
