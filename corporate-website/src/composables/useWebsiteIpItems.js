import { ref } from 'vue'
import { fetchNativeIpItems } from '../api/website'
import { patents as staticIpItems } from '../data/qualifications'

export const nativeIpEnabled = import.meta.env.VITE_WEBSITE_IP_SOURCE === 'native'
const items = ref(nativeIpEnabled ? [] : staticIpItems)
const loadError = ref(false)

export function useWebsiteIpItems() {
  async function load() {
    if (!nativeIpEnabled) return
    try {
      items.value = await fetchNativeIpItems()
      loadError.value = false
    } catch {
      items.value = []
      loadError.value = true
    }
  }
  return { items, loadError, load }
}
