import { http } from '../http'

/** 物理文件上传结果 */
export interface PhysicalUploadResult {
  fileName?: string
  category?: string
  size?: number
  /** 兼容某些后端包裹在 data 中的情况 */
  data?: { fileName?: string }
}

/**
 * 上传物理文件到后端 /api/files/upload。
 * @param category 文件分类（如 product_images、documents）
 * @param file 要上传的文件
 * @param modelId 关联产品 ID（可选）
 * @param modelName 关联产品名称（可选）
 */
export async function uploadFile(
  category: string,
  file: File,
  modelId?: number | null,
  modelName?: string
): Promise<PhysicalUploadResult> {
  const fd = new FormData()
  fd.append('category', category)
  fd.append('file', file)
  if (modelId != null) fd.append('modelId', String(modelId))
  if (modelName) fd.append('modelName', modelName)

  const res = await http.post('/files/upload', fd, {
    /* 不手动设置 Content-Type：浏览器会自动加上 multipart/form-data 及 boundary，
     * 手动覆盖会导致后端无法解析 FormData。 */
    timeout: 600000
  })
  return res.data as PhysicalUploadResult
}
