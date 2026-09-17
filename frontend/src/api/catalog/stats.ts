import { http } from '../http'

export function fetchCatalogStats() {
  return http.get('/catalog/stats').then(res => res.data)
}
