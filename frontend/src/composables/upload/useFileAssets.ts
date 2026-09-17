import { computed, getCurrentScope, onScopeDispose, ref, unref, type Ref } from 'vue'
import {
  fetchFileAsset,
  fetchFileAssets,
  fileAssetApi,
  type FileAssetDeleteOptions,
  type FileAssetReorderPayload,
  type FileAssetTagsPayload
} from '../../api/file'
import type { FileCapability, FileHandle, FileOwnerType } from '../../types/fileAsset'
import {
  fileAssetDownloadUrl,
  fileAssetOpenFolderMessage,
  fileAssetPreviewUrl,
  fileAssetTitle,
  isPreviewableFileAsset
} from '../../utils/ui/fileAssetUrls'
import { notify as defaultNotify, type NotifyFn } from '@/composables/ui/useToast'
import { FILE_ASSET_MESSAGES, FILE_ASSET_FAILURE_PREFIX, fileAssetDeleteSuccess } from '@/constants/fileAssetMessages'
import { fileCapabilityUnsupportedMessages, useFileAssetCapabilities } from './useFileAssetCapabilities'
import { reportFileAssetDiagnostic } from '@/utils/fileAssetDiagnostics'
import { normalizeFileAssetIds, runFileAssetBatch } from './fileAssetBatch'

interface UseFileAssetsOptions {
  ownerType: FileOwnerType
  ownerId?: number | null | Ref<number | null | undefined>
  notify?: NotifyFn
  onChanged?: () => Promise<void> | void
}

function isCanceledFileAssetRequest(error: unknown) {
  if (!error || typeof error !== 'object') return false
  const candidate = error as { name?: string; code?: string }
  return candidate.name === 'CanceledError' || candidate.code === 'ERR_CANCELED'
}

export function useFileAssets(options: UseFileAssetsOptions) {
  const notify = options.notify || defaultNotify
  const items = ref<FileHandle[]>([])
  const capabilities = ref<FileCapability[]>([])
  const loading = ref(false)
  const actionLoading = ref(false)
  const loaded = ref(false)
  const capability = useFileAssetCapabilities({ notify })
  let activeListRequest: AbortController | null = null
  let activeListOwnerId: number | null = null

  function cancelActiveListRequest(reason: 'owner_changed' | 'scope_disposed') {
    if (!activeListRequest || activeListRequest.signal.aborted) return
    reportFileAssetDiagnostic('request_canceled', {
      severity: 'info',
      operation: 'load',
      ownerType: options.ownerType,
      ownerId: activeListOwnerId,
      message: reason
    })
    activeListRequest.abort()
    activeListRequest = null
    activeListOwnerId = null
  }

  if (getCurrentScope()) {
    onScopeDispose(() => cancelActiveListRequest('scope_disposed'))
  }

  const ownerId = computed(() => unref(options.ownerId) ?? null)
  const capabilitiesLoaded = computed(() => capability.isLoaded(options.ownerType))
  const files = computed(() => fileAssetApi(options.ownerType))

  function hasCapability(fileCapability: FileCapability) {
    return capability.hasCapability(options.ownerType, fileCapability)
  }

  async function ensureCapability(fileCapability: FileCapability) {
    const supported = await capability.ensureCapability(options.ownerType, fileCapability)
    capabilities.value = capability.capabilities(options.ownerType)
    return supported
  }

  function findLoadedItem(assetId: number) {
    return items.value.find(item => item.assetId === assetId) || null
  }

  async function ensureFileCapability(assetId: number, fileCapability: FileCapability) {
    if (!(await ensureCapability(fileCapability))) return false
    const item = findLoadedItem(assetId)
    if (!item) {
      notify(FILE_ASSET_MESSAGES.assetNotLoaded, 'warning')
      return false
    }
    if (!item.capabilities.includes(fileCapability)) {
      notify(fileCapabilityUnsupportedMessages[fileCapability], 'warning')
      return false
    }
    return true
  }

  async function ensureFilesCapability(assetIds: number[], fileCapability: FileCapability) {
    if (!(await ensureCapability(fileCapability))) return false
    const unsupported = assetIds.find(assetId => {
      const item = findLoadedItem(assetId)
      return !item || !item.capabilities.includes(fileCapability)
    })
    if (unsupported) {
      notify(fileCapabilityUnsupportedMessages[fileCapability], 'warning')
      return false
    }
    return true
  }

  async function refreshChanged() {
    await options.onChanged?.()
    if (loaded.value) {
      await load()
    }
  }

  async function load() {
    const currentOwnerId = ownerId.value
    cancelActiveListRequest('owner_changed')

    const request = new AbortController()
    activeListRequest = request
    activeListOwnerId = currentOwnerId
    loading.value = true
    try {
      const nextItems = await fetchFileAssets(options.ownerType, currentOwnerId, request.signal)
      if (activeListRequest !== request || ownerId.value !== currentOwnerId) return items.value
      items.value = nextItems
      loaded.value = true
      return items.value
    } catch (err) {
      if (request.signal.aborted || isCanceledFileAssetRequest(err)) return items.value
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.loadList, err)
      throw err
    } finally {
      if (activeListRequest === request) {
        activeListRequest = null
        activeListOwnerId = null
        loading.value = false
      }
    }
  }

  async function loadCapabilities() {
    try {
      capabilities.value = [...(await capability.ensureLoaded(options.ownerType))]
      return capabilities.value
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.loadCapabilities, err)
      throw err
    }
  }

  async function get(assetId: number) {
    return fetchFileAsset(options.ownerType, assetId)
  }

  async function updateTags(assetId: number, tags: FileAssetTagsPayload, replace = true) {
    if (!(await ensureFileCapability(assetId, 'UPDATE_TAGS'))) return null
    actionLoading.value = true
    try {
      const result = await files.value.updateTags(assetId, tags, replace)
      notify(FILE_ASSET_MESSAGES.tagsUpdated, 'success')
      await refreshChanged()
      return result
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.updateTags, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function batchUpdateTags(assetIds: number[], tags: FileAssetTagsPayload, replace = true) {
    const ids = normalizeFileAssetIds(assetIds)
    if (!ids.length) return []
    if (!(await ensureFilesCapability(ids, 'UPDATE_TAGS'))) return []
    actionLoading.value = true
    try {
      const results = await runFileAssetBatch(ids, assetId => files.value.updateTags(assetId, tags, replace))
      notify(FILE_ASSET_MESSAGES.tagsUpdated, 'success')
      await refreshChanged()
      return results
    } catch (err) {
      await refreshChanged().catch(() => undefined)
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.updateTags, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function setDefault(assetId: number) {
    if (!(await ensureFileCapability(assetId, 'SET_DEFAULT'))) return null
    actionLoading.value = true
    try {
      const result = await files.value.setDefault(assetId)
      notify(FILE_ASSET_MESSAGES.defaultUpdated, 'success')
      await refreshChanged()
      return result
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.setDefault, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function reorder(orderItems: FileAssetReorderPayload) {
    if (!(await ensureCapability('REORDER'))) return
    if (!orderItems.every(item => findLoadedItem(item.id)?.capabilities.includes('REORDER') ?? false)) {
      notify(fileCapabilityUnsupportedMessages.REORDER, 'warning')
      return
    }
    actionLoading.value = true
    try {
      await files.value.reorder(orderItems)
      notify(FILE_ASSET_MESSAGES.reorderSaved, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.reorder, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function moveCategory(assetId: number, category: string) {
    if (!(await ensureFileCapability(assetId, 'MOVE_CATEGORY'))) return
    if (!category?.trim()) {
      notify(FILE_ASSET_MESSAGES.selectCategory, 'warning')
      return
    }
    actionLoading.value = true
    try {
      await files.value.moveCategory(assetId, category)
      notify(FILE_ASSET_MESSAGES.categoryUpdated, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.moveCategory, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function batchMoveCategory(assetIds: number[], category: string) {
    const ids = normalizeFileAssetIds(assetIds)
    if (!ids.length) return
    if (!category?.trim()) {
      notify(FILE_ASSET_MESSAGES.selectCategory, 'warning')
      return
    }
    if (!(await ensureFilesCapability(ids, 'MOVE_CATEGORY'))) return
    actionLoading.value = true
    try {
      await runFileAssetBatch(ids, assetId => files.value.moveCategory(assetId, category))
      notify(FILE_ASSET_MESSAGES.categoryUpdated, 'success')
      await refreshChanged()
    } catch (err) {
      await refreshChanged().catch(() => undefined)
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.moveCategory, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function moveOwner(assetId: number, targetOwnerType: FileOwnerType, targetOwnerId: number) {
    if (!(await ensureFileCapability(assetId, 'MOVE_OWNER'))) return
    if (!targetOwnerType || !targetOwnerId) {
      notify(FILE_ASSET_MESSAGES.selectOwner, 'warning')
      return
    }
    actionLoading.value = true
    try {
      await files.value.moveOwner(assetId, targetOwnerType, targetOwnerId)
      notify(FILE_ASSET_MESSAGES.ownerUpdated, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.moveOwner, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function remove(assetId: number, deleteOptions: FileAssetDeleteOptions = {}) {
    const requiredCapability: FileCapability = deleteOptions.permanent ? 'PERMANENT_DELETE' : 'DELETE'
    if (!(await ensureFileCapability(assetId, requiredCapability))) return
    actionLoading.value = true
    try {
      if (deleteOptions.permanent) {
        await files.value.permanentDelete(assetId, deleteOptions.deletePhysical ?? false)
      } else {
        await files.value.delete(assetId)
      }
      notify(fileAssetDeleteSuccess(Boolean(deleteOptions.permanent)), 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.delete, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function batchRemove(assetIds: number[], deleteOptions: FileAssetDeleteOptions = {}) {
    const ids = normalizeFileAssetIds(assetIds)
    if (!ids.length) return
    const requiredCapability: FileCapability = deleteOptions.permanent ? 'PERMANENT_DELETE' : 'DELETE'
    if (!(await ensureFilesCapability(ids, requiredCapability))) return
    actionLoading.value = true
    try {
      await runFileAssetBatch(ids, async assetId => {
        if (deleteOptions.permanent) {
          await files.value.permanentDelete(assetId, deleteOptions.deletePhysical ?? false)
        } else {
          await files.value.delete(assetId)
        }
      })
      notify(fileAssetDeleteSuccess(Boolean(deleteOptions.permanent), true), 'success')
      await refreshChanged()
    } catch (err) {
      await refreshChanged().catch(() => undefined)
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.batchDelete, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function restore(assetId: number) {
    if (!(await ensureFileCapability(assetId, 'RESTORE'))) return
    actionLoading.value = true
    try {
      await files.value.restore(assetId)
      notify(FILE_ASSET_MESSAGES.restored, 'success')
      await refreshChanged()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.restore, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function localPath(assetId: number) {
    if (!(await ensureFileCapability(assetId, 'OPEN_FOLDER'))) return null
    actionLoading.value = true
    try {
      return await files.value.localPath(assetId)
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.localPath, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  async function openFolder(assetId: number) {
    if (!(await ensureFileCapability(assetId, 'OPEN_FOLDER'))) return null
    actionLoading.value = true
    try {
      const result = await files.value.openFolder(assetId)
      const feedback = fileAssetOpenFolderMessage(result)
      notify(feedback.message, feedback.type)
      return result
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.openFolder, err)
      throw err
    } finally {
      actionLoading.value = false
    }
  }

  return {
    items,
    capabilities,
    loading,
    actionLoading,
    loaded,
    capabilitiesLoaded,
    hasCapability,
    load,
    loadCapabilities,
    get,
    previewUrl: fileAssetPreviewUrl,
    downloadUrl: fileAssetDownloadUrl,
    fileTitle: fileAssetTitle,
    isPreviewable: isPreviewableFileAsset,
    updateTags,
    batchUpdateTags,
    setDefault,
    reorder,
    moveCategory,
    batchMoveCategory,
    moveOwner,
    remove,
    batchRemove,
    restore,
    localPath,
    openFolder
  }
}
