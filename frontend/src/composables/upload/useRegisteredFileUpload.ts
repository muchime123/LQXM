import { useFileUpload, type UploadOptions, type UploadResult } from './useFileUpload'
import type { NotifyFn } from '../ui/useToast'

interface RegisteredFileUploadOptions {
  notify?: NotifyFn
}

interface UploadAndRegisterOptions<TAsset> extends UploadOptions {
  register: (uploaded: UploadResult & { fileName: string }) => Promise<TAsset>
}

export interface RegisteredFileUploadResult<TAsset> {
  upload: UploadResult & { fileName: string }
  asset: TAsset
}

export function useRegisteredFileUpload(options: RegisteredFileUploadOptions = {}) {
  const fileUpload = useFileUpload(options)

  async function uploadAndRegister<TAsset>(
    category: string,
    file: File | null | undefined,
    options: UploadAndRegisterOptions<TAsset>
  ): Promise<RegisteredFileUploadResult<TAsset> | null> {
    const { register, ...uploadOptions } = options
    const upload = await fileUpload.uploadSelectedFile(category, file, uploadOptions)
    if (!upload?.fileName) return null
    const typedUpload = upload as UploadResult & { fileName: string }
    const asset = await register(typedUpload)
    return { upload: typedUpload, asset }
  }

  return {
    uploading: fileUpload.uploading,
    uploadSelectedFile: fileUpload.uploadSelectedFile,
    uploadFromInputEvent: fileUpload.uploadFromInputEvent,
    pickAndUpload: fileUpload.pickAndUpload,
    uploadAndRegister
  }
}
