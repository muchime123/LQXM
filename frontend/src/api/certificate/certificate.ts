import { http } from '../http'

export function fetchCertificates(params?: Record<string, any>) {
  return http.get('/certificates', { params }).then(res => res.data)
}

export function fetchCertificate(id: number) {
  return http.get(`/certificates/${id}`).then(res => res.data)
}

export function createCertificate(data: Record<string, any>) {
  return http.post('/certificates', data).then(res => res.data)
}

export function updateCertificate(id: number, data: Record<string, any>) {
  return http.put(`/certificates/${id}`, data).then(res => res.data)
}

export function deleteCertificate(id: number) {
  return http.delete(`/certificates/${id}`).then(res => res.data)
}

export function batchUpdateCertificates(ids: number[], fields: Record<string, unknown>) {
  return http.put('/certificates/batch', { ids, fields }).then(res => res.data as { affected: number })
}

export function fetchCertificateStats() {
  return http.get('/certificates/stats').then(res => res.data)
}

export function fetchCertificateFilters() {
  return http.get('/certificates/filters').then(res => res.data)
}

export function exportCertificates(params?: Record<string, any>) {
  return http.get('/certificates/export', { params, responseType: 'blob' }).then(res => res.data)
}

export function fetchFeeRecords(certificateId: number) {
  return http.get(`/certificates/${certificateId}/fee-records`).then(res => res.data)
}

export function addFeeRecord(certificateId: number, data: Record<string, any>) {
  return http.post(`/certificates/${certificateId}/fee-records`, data).then(res => res.data)
}

export function deleteFeeRecord(certificateId: number, recordId: number) {
  return http.delete(`/certificates/${certificateId}/fee-records/${recordId}`)
}

export function updateFeeRecord(certificateId: number, recordId: number, data: Record<string, any>) {
  return http.put(`/certificates/${certificateId}/fee-records/${recordId}`, data).then(res => res.data)
}
