export type CertificateFileField = 'specFile' | 'certFile'

export function certificateFileAssetId(certificateId: number, field: CertificateFileField): number {
  return certificateId * 10 + (field === 'certFile' ? 2 : 1)
}

export function certificateFileFieldLabel(field: CertificateFileField): string {
  return field === 'certFile' ? '证书图片' : '说明书文件'
}
