/**
 * 状态标签工具
 * 统一管理文档、证书等状态的样式和显示文本
 */

export const DOC_STATUS = {
  PUBLISHED: 'published',
  DRAFT: 'draft',
  ARCHIVED: 'archived'
}

export const DOC_STATUS_LABELS: Record<string, string> = {
  published: '已发布',
  draft: '草稿',
  archived: '已归档'
}

export const CERT_ASSET_STATUS = {
  VALID: '有效',
  EXPIRED: '已失效',
  PENDING: '未生效',
  TRANSFERRED: '已转让'
}

export function certStatusTagClass(status: string | null | undefined): string {
  if (status === CERT_ASSET_STATUS.VALID) return 'tag-ok'
  if (status === CERT_ASSET_STATUS.EXPIRED) return 'tag-expired'
  if (status === CERT_ASSET_STATUS.PENDING) return 'tag-pending'
  if (status === CERT_ASSET_STATUS.TRANSFERRED) return 'tag-trans'
  return ''
}

export function certFeeTagClass(status: string | null | undefined): string {
  if (!status) return ''
  if (status.includes('已支付') || status.includes('已公告') || status.includes('已登记')) return 'tag-ok'
  if (status.includes('未支付') || status.includes('待查询') || status.includes('待核实')) return 'tag-warn'
  if (status.includes('已失效')) return 'tag-expired'
  return ''
}
