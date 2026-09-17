import {
  fileAssetApi,
  type FileAssetDeleteOptions,
  type FileAssetReorderPayload,
  type FileAssetTagsPayload
} from '../../api/file'
import type { FileCapability, FileOwnerType } from '../../types/fileAsset'
import { fileAssetOpenFolderMessage } from '../../utils/ui/fileAssetUrls'
import { notify as defaultNotify, type NotifyFn } from '@/composables/ui/useToast'
import { FILE_ASSET_MESSAGES, FILE_ASSET_FAILURE_PREFIX, fileAssetDeleteSuccess } from '@/constants/fileAssetMessages'
import { useFileAssetCapabilities } from './useFileAssetCapabilities'
import { normalizeFileAssetIds, runFileAssetBatch } from './fileAssetBatch'

interface FileAssetActionsOptions {
  notify?: NotifyFn
  onChanged?: () => Promise<void> | void
  notifySuccess?: boolean
}

export function useFileAssetActions(options: FileAssetActionsOptions = {}) {
  const notify = options.notify || defaultNotify
  const notifySuccess = options.notifySuccess !== false
  const capability = useFileAssetCapabilities({ notify })

  async function refreshChanged() {
    await options.onChanged?.()
  }

  async function updateTags(ownerType: FileOwnerType, assetId: number, tags: FileAssetTagsPayload, replace = true) {
    if (!(await capability.ensureCapability(ownerType, 'UPDATE_TAGS'))) return null
    try {
      const result = await fileAssetApi(ownerType).updateTags(assetId, tags, replace)
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.tagsUpdated, 'success')
      await refreshChanged()
      return result
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.updateTags, err)
      throw err
    }
  }

  async function batchUpdateTags(
    ownerType: FileOwnerType,
    assetIds: number[],
    tags: FileAssetTagsPayload,
    replace = true
  ) {
    const ids = normalizeFileAssetIds(assetIds)
    if (!ids.length) return []
    if (!(await capability.ensureCapability(ownerType, 'UPDATE_TAGS'))) return []
    try {
      const results = await runFileAssetBatch(ids, assetId =>
        fileAssetApi(ownerType).updateTags(assetId, tags, replace)
      )
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.tagsBatchUpdated, 'success')
      await refreshChanged()
      return results
    } catch (err) {
      await refreshChanged().catch(() => undefined)
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.batchUpdateTags, err)
      throw err
    }
  }

  async function setDefault(ownerType: FileOwnerType, assetId: number) {
    if (!(await capability.ensureCapability(ownerType, 'SET_DEFAULT'))) return null
    try {
      const result = await fileAssetApi(ownerType).setDefault(assetId)
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.defaultUpdated, 'success')
      await refreshChanged()
      return result
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.setDefault, err)
      throw err
    }
  }

  async function remove(ownerType: FileOwnerType, assetId: number, deleteOptions: FileAssetDeleteOptions = {}) {
    const requiredCapability: FileCapability = deleteOptions.permanent ? 'PERMANENT_DELETE' : 'DELETE'
    if (!(await capability.ensureCapability(ownerType, requiredCapability))) return
    try {
      if (deleteOptions.permanent) {
        await fileAssetApi(ownerType).permanentDelete(assetId, deleteOptions.deletePhysical ?? false)
      } else {
        await fileAssetApi(ownerType).delete(assetId)
      }
      if (notifySuccess) notify(fileAssetDeleteSuccess(Boolean(deleteOptions.permanent)), 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.delete, err)
      throw err
    }
  }

  async function batchRemove(ownerType: FileOwnerType, assetIds: number[], deleteOptions: FileAssetDeleteOptions = {}) {
    const ids = normalizeFileAssetIds(assetIds)
    if (!ids.length) return
    const requiredCapability: FileCapability = deleteOptions.permanent ? 'PERMANENT_DELETE' : 'DELETE'
    if (!(await capability.ensureCapability(ownerType, requiredCapability))) return
    try {
      await runFileAssetBatch(ids, async assetId => {
        if (deleteOptions.permanent) {
          await fileAssetApi(ownerType).permanentDelete(assetId, deleteOptions.deletePhysical ?? false)
        } else {
          await fileAssetApi(ownerType).delete(assetId)
        }
      })
      if (notifySuccess) notify(fileAssetDeleteSuccess(Boolean(deleteOptions.permanent), true), 'success')
      await refreshChanged()
    } catch (err) {
      await refreshChanged().catch(() => undefined)
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.batchDelete, err)
      throw err
    }
  }

  async function restore(ownerType: FileOwnerType, assetId: number) {
    if (!(await capability.ensureCapability(ownerType, 'RESTORE'))) return
    try {
      await fileAssetApi(ownerType).restore(assetId)
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.restored, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.restore, err)
      throw err
    }
  }

  async function batchRestore(ownerType: FileOwnerType, assetIds: number[]) {
    const ids = normalizeFileAssetIds(assetIds)
    if (!ids.length) return
    if (!(await capability.ensureCapability(ownerType, 'RESTORE'))) return
    try {
      await runFileAssetBatch(ids, assetId => fileAssetApi(ownerType).restore(assetId))
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.batchRestored, 'success')
      await refreshChanged()
    } catch (err) {
      await refreshChanged().catch(() => undefined)
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.batchRestore, err)
      throw err
    }
  }

  async function moveCategory(ownerType: FileOwnerType, assetId: number, category: string) {
    if (!(await capability.ensureCapability(ownerType, 'MOVE_CATEGORY'))) return
    if (!category?.trim()) {
      notify(FILE_ASSET_MESSAGES.selectCategory, 'warning')
      return
    }
    try {
      await fileAssetApi(ownerType).moveCategory(assetId, category)
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.categoryUpdated, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.moveCategory, err)
      throw err
    }
  }

  async function moveOwner(
    ownerType: FileOwnerType,
    assetId: number,
    targetOwnerType: FileOwnerType,
    targetOwnerId: number
  ) {
    if (!(await capability.ensureCapability(ownerType, 'MOVE_OWNER'))) return
    if (!targetOwnerType || !targetOwnerId) {
      notify(FILE_ASSET_MESSAGES.selectOwner, 'warning')
      return
    }
    try {
      await fileAssetApi(ownerType).moveOwner(assetId, targetOwnerType, targetOwnerId)
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.ownerUpdated, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.moveOwner, err)
      throw err
    }
  }

  async function localPath(ownerType: FileOwnerType, assetId: number) {
    if (!(await capability.ensureCapability(ownerType, 'OPEN_FOLDER'))) return null
    try {
      return await fileAssetApi(ownerType).localPath(assetId)
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.localPath, err)
      throw err
    }
  }

  async function openFolder(ownerType: FileOwnerType, assetId: number) {
    if (!(await capability.ensureCapability(ownerType, 'OPEN_FOLDER'))) return null
    try {
      const result = await fileAssetApi(ownerType).openFolder(assetId)
      const feedback = fileAssetOpenFolderMessage(result)
      if (feedback.type === 'warning' || notifySuccess) {
        notify(feedback.message, feedback.type)
      }
      return result
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.openFolder, err)
      throw err
    }
  }

  async function reorder(ownerType: FileOwnerType, items: FileAssetReorderPayload) {
    if (!(await capability.ensureCapability(ownerType, 'REORDER'))) return
    try {
      await fileAssetApi(ownerType).reorder(items)
      if (notifySuccess) notify(FILE_ASSET_MESSAGES.reorderSaved, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.reorder, err)
      throw err
    }
  }

  return {
    capabilities: capability.capabilities,
    ensureCapability: capability.ensureCapability,
    hasCapability: capability.hasCapability,
    updateTags,
    batchUpdateTags,
    setDefault,
    remove,
    batchRemove,
    restore,
    batchRestore,
    moveCategory,
    moveOwner,
    localPath,
    openFolder,
    reorder
  }
}
