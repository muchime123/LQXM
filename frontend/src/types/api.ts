export interface BackendApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: string
}

export interface ApiError {
  message: string
  timestamp: string
}

export interface PageResponse<T> {
  content: T[]
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ListResponse<T> {
  data: T[]
}

export interface ApiResponse<T = unknown> {
  data: T
  status: number
  statusText: string
}

export interface QueryParams {
  keyword?: string
  page?: number
  size?: number
  sort?: string
  dir?: 'asc' | 'desc'
}
