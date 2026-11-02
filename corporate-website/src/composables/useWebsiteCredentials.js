import { ref } from 'vue'
import { fetchNativeCredentials } from '../api/website'
import { certifications as staticCredentials } from '../data/qualifications'

export const nativeCredentialsEnabled = import.meta.env.VITE_WEBSITE_CREDENTIALS_SOURCE === 'native'
const credentials = ref(nativeCredentialsEnabled ? [] : staticCredentials)
const loadError = ref(false)

export function useWebsiteCredentials() {
  async function load() {
    if (!nativeCredentialsEnabled) return
    try {
      const rows = await fetchNativeCredentials()
      credentials.value = rows.map((row) => ({ ...row, image: row.imageUrl || '' }))
      loadError.value = false
    } catch {
      credentials.value = []
      loadError.value = true
    }
  }
  return { credentials, loadError, load }
}
