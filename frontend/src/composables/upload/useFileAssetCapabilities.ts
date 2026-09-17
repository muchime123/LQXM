import { computed, ref } from 'vue'
import { fetchAllFileAssetCapabilities, fetchFileAssetCapabilities } from '../../api/file'
import type { FileCapability, FileOwnerType } from '../../types/fileAsset'
import { isFileCapability, isFileOwnerType } from '../../types/fileAsset'
import { notify as defaultNotify, type NotifyFn } from '@/composables/ui/useToast'
import { FILE_ASSET_FAILURE_PREFIX, fileCapabilityUnsupportedMessages } from '@/constants/fileAssetMessages'

export { fileCapabilityUnsupportedMessages } from '@/constants/fileAssetMessages'

interface UseFileAssetCapabilitiesOptions {
  notify?: NotifyFn
}

const capabilityCache = new Map<FileOwnerType, Set<FileCapability>>()
const pendingLoads = new Map<FileOwnerType, Promise<Set<FileCapability>>>()
let pendingAllLoad: Promise<void> | null = null
let allCapabilitiesUnavailable = false
let cacheEpoch = 0
const cacheVersion = ref(0)

function cacheCapabilities(ownerType: FileOwnerType, capabilities: readonly string[] | null | undefined) {
  const supported = (capabilities || []).filter(isFileCapability)
  capabilityCache.set(ownerType, new Set(supported))
}

async function fetchAndCacheCapabilities(ownerType: FileOwnerType) {
  const requestEpoch = cacheEpoch
  const loaded = new Set((await fetchFileAssetCapabilities(ownerType)).filter(isFileCapability))
  if (requestEpoch === cacheEpoch) {
    capabilityCache.set(ownerType, loaded)
    cacheVersion.value += 1
  }
  return loaded
}

async function fetchAndCacheAllCapabilities() {
  const requestEpoch = cacheEpoch
  const capabilityMap = await fetchAllFileAssetCapabilities()
  if (requestEpoch !== cacheEpoch) return
  Object.entries(capabilityMap).forEach(([ownerType, capabilities]) => {
    if (isFileOwnerType(ownerType)) {
      cacheCapabilities(ownerType, capabilities)
    }
  })
  cacheVersion.value += 1
}

export function useFileAssetCapabilities(options: UseFileAssetCapabilitiesOptions = {}) {
  const notify = options.notify || defaultNotify
  const loadedOwnerTypes = computed(() => {
    void cacheVersion.value
    return new Set(capabilityCache.keys())
  })

  async function loadCapabilities(ownerType: FileOwnerType) {
    const existing = pendingLoads.get(ownerType)
    if (existing) return [...(await existing)]

    const request = fetchAndCacheCapabilities(ownerType).finally(() => {
      if (pendingLoads.get(ownerType) === request) pendingLoads.delete(ownerType)
    })
    pendingLoads.set(ownerType, request)
    return [...(await request)]
  }

  async function loadAllCapabilities() {
    if (!pendingAllLoad) {
      const request = fetchAndCacheAllCapabilities().finally(() => {
        if (pendingAllLoad === request) pendingAllLoad = null
      })
      pendingAllLoad = request
    }
    await pendingAllLoad
  }

  async function ensureLoaded(ownerType: FileOwnerType) {
    const cached = capabilityCache.get(ownerType)
    if (cached) return cached
    if (!allCapabilitiesUnavailable) {
      try {
        await loadAllCapabilities()
        const loaded = capabilityCache.get(ownerType)
        if (loaded) return loaded
      } catch {
        allCapabilitiesUnavailable = true
        // Fall back to the per-owner endpoint to keep older backends usable.
      }
    }
    try {
      await loadCapabilities(ownerType)
      return capabilityCache.get(ownerType) || new Set<FileCapability>()
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.loadCapabilities, err)
      throw err
    }
  }

  function hasCapability(ownerType: FileOwnerType, capability: FileCapability) {
    void cacheVersion.value
    return capabilityCache.get(ownerType)?.has(capability) || false
  }

  async function ensureCapability(ownerType: FileOwnerType, capability: FileCapability) {
    const loaded = await ensureLoaded(ownerType)
    if (!loaded.has(capability)) {
      notify(fileCapabilityUnsupportedMessages[capability], 'warning')
      return false
    }
    return true
  }

  function capabilities(ownerType: FileOwnerType) {
    void cacheVersion.value
    return [...(capabilityCache.get(ownerType) || [])]
  }

  function isLoaded(ownerType: FileOwnerType) {
    void cacheVersion.value
    return capabilityCache.has(ownerType)
  }

  function clearCapabilities(ownerType?: FileOwnerType) {
    cacheEpoch += 1
    if (ownerType) {
      capabilityCache.delete(ownerType)
      pendingLoads.delete(ownerType)
      cacheVersion.value += 1
      return
    }
    capabilityCache.clear()
    pendingLoads.clear()
    pendingAllLoad = null
    allCapabilitiesUnavailable = false
    cacheVersion.value += 1
  }

  return {
    loadedOwnerTypes,
    capabilities,
    ensureLoaded,
    ensureCapability,
    hasCapability,
    isLoaded,
    loadCapabilities,
    loadAllCapabilities,
    clearCapabilities
  }
}
