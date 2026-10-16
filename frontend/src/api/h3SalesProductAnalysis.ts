import { http } from '@/api/http'

export interface SalesProductAnalysisRow {
  /** 实体编号：氚云按产品名称关联，故名称优先解析；名称重名/查不到时退回原编号。 */
  productCode: string
  /** 氚云出库明细里实际录入的编号，逗号分隔（实测每个组合最多 4 个）。与 productCode 不同说明编号录错了。 */
  rawCodes: string | null
  /** 1 = 本行由「录入编号与名称不符」的明细行聚合而来。 */
  codeRewritten: number
  category: string
  productGroup: 'BOX' | 'FILM' | 'MACHINE' | 'UNASSIGNED'
  orderCount: number
  lineCount: number
  nameCount: number
  unitCount: number
  categoryCount: number
  firstShip: string | null
  lastShip: string | null
  salesAmount: number
  sampleNames: string | null
  sampleUnits: string | null
  inCatalog: number | boolean
  catalogName: string | null
  catalogUnit: string | null
  nonZeroStockRows: number
  stockWarehouseCount: number
}

export interface SalesProductAnalysis {
  batches: Array<{ id: number; total_count: number; completed_at: string }>
  categories: Array<{ category: string; productGroup: SalesProductAnalysisRow['productGroup']; lineCount: number; orderCount: number; salesAmount: number }>
  groups: Array<{ productGroup: SalesProductAnalysisRow['productGroup']; lineCount: number; salesAmount: number;
    categoryCount: number; productCount: number; reviewCount: number }>
  summary: { combinations: number; productCount: number; lineCount: number; salesAmount: number }
  scope: { orders: number; lineCount: number; firstShip: string | null; lastShip: string | null;
    positiveProductLines: number; returnLines: number; nonProductLines: number }
  items: SalesProductAnalysisRow[]
  total: number
  page: number
  size: number
}

export const fetchH3SalesProductAnalysis = async (params: { keyword?: string; view?: string; category?: string; productGroup?: string; onlyInStock?: boolean; sort?: string; direction?: string; dateFrom?: string; dateTo?: string; page?: number; size?: number }) =>
  (await http.get<SalesProductAnalysis>('/h3-sales-product-analysis', { params })).data
