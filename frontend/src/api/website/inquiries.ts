import { http } from '../http'
import type {
  WebsiteInquiryDetail,
  WebsiteInquiryPage,
  WebsiteInquiryQuery,
  WebsiteInquiryStats,
  WebsiteInquiryUpdateRequest,
  InquiryStatus
} from '../../types/website'

/** 分页查询询盘列表 */
export function fetchInquiries(params: WebsiteInquiryQuery = {}): Promise<WebsiteInquiryPage> {
  return http
    .get('/website/inquiries/admin/list', {
      params: {
        status: params.status && params.status !== 'all' ? params.status : undefined,
        keyword: params.keyword || undefined,
        page: params.page || 1,
        size: params.size || 20
      }
    })
    .then(res => res.data)
}

/** 询盘详情 */
export function fetchInquiryById(id: number): Promise<WebsiteInquiryDetail> {
  return http.get(`/website/inquiries/admin/${id}`).then(res => res.data)
}

/** 更新询盘（状态流转/跟进记录/负责人/下次联系时间/关闭原因） */
export function updateInquiry(id: number, data: WebsiteInquiryUpdateRequest): Promise<WebsiteInquiryDetail> {
  return http.put(`/website/inquiries/admin/${id}`, data).then(res => res.data)
}

/** 批量更新状态 */
export function batchUpdateInquiryStatus(ids: number[], status: InquiryStatus): Promise<{ updated: number }> {
  return http.put('/website/inquiries/admin/batch', { ids, status }).then(res => res.data)
}

/** 询盘统计 */
export function fetchInquiryStats(): Promise<WebsiteInquiryStats> {
  return http.get('/website/inquiries/admin/stats').then(res => res.data)
}
