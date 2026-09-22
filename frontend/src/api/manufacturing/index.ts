import { http } from '../http'

export interface ManufacturingOrder {
  id: number
  orderNo: string
  salesOrderRef?: string
  customerNameSnapshot: string
  salespersonNameSnapshot?: string
  productNameSnapshot: string
  productModelId?: number
  productMachineTierId?: number
  productModelNameSnapshot?: string
  machineTierSnapshot?: string
  specificationSnapshot?: string
  customizationMode?: string
  toolingRequirement?: string
  customRequirement?: string
  plannedQuantity: number
  completedQuantity: number
  unit: string
  dueDate?: string
  status: string
  taskType?: string
  processTemplateId?: number
  processTemplateNameSnapshot?: string
  workshopType?: string
  priority?:string
  plannedStartDate?:string
  plannedFinishDate?:string
  productionTeamName?:string
  responsibleEmployeeId?:number
  responsibleEmployeeName?:string
  inspectionStatus?: string
  remark?: string
  createdAt: string
  // === 氚云镜像来源（双轨制：源台账只读、业务工单可写） ===
  /** 氚云生产任务单号；为空表示是 ERP 自建单 */
  h3WorkOrderNo?: string
  externalSource?: string
  /** 氚云原状态原文（已生效 / 进行中 / 草稿 / 已取消） */
  sourceStatusRaw?: string
  /** 氚云生产进度原文，如 33.33% */
  sourceProgressRaw?: string
  /** 镜像待补原因；非空表示这条镜像数据缺东西（如没定到制程），需人工补 */
  sourceMirrorPending?: string
}
export interface FinishedGoodsLot {
  id: number
  lotNo: string
  salesOrderRef?: string
  customerNameSnapshot: string
  salespersonNameSnapshot?: string
  finishedGoodsName: string
  productModelNameSnapshot?: string
  machineTierSnapshot?: string
  specificationSnapshot?: string
  customizationMode?: string
  toolingRequirement?: string
  unit: string
  receivedQuantity: number
  availableQuantity: number
  warehouseName: string
  status: string
  createdAt: string
}
export interface Warehouse { id: number; warehouseCode: string; warehouseName: string }
export interface PageResult<T> { items: T[]; total: number; page: number; size: number }
/**
 * 工单产出清单行（P3 ①）。
 *
 * 建单时声明「这张单要做哪几个 SKU、各多少」，收货时按它逐项确认实际数量。
 * `isPrimary=1` 有且仅有一行（主产品）—— 工单状态机按它判定"做完没有"。
 */
export interface ManufacturingOutput {
  id: number
  manufacturingOrderId: number
  lineNo: number
  isPrimary: boolean
  salesProductId: number
  canonicalProductId: number
  productCodeSnapshot?: string
  productNameSnapshot: string
  specificationSnapshot?: string
  unit: string
  plannedQuantity: number
  receivedQuantity: number
  isMold: boolean
  remark?: string
}
/**
 * 产品选择器选项（P3 ①：产品身份只能从主数据选，不再手打名称）。
 * `custom=1` 表示自建 / 非标件（`MFG-`），界面上要能一眼看出来。
 */
export interface ProductOption {
  id: number
  productCode: string
  productName: string
  specification?: string
  unit?: string
  custom: number
}
/** 收货明细行（P3 ②）：按产出清单逐项填实际数量（outputId 指向清单行）。 */
export interface ReceiptItem { outputId: number; quantity: number; unitCost?: number; remark?: string }
export interface ManufacturingDetail {
  order: ManufacturingOrder
  outputs: ManufacturingOutput[]
  receipts: Record<string, unknown>[]
  lots: FinishedGoodsLot[]
  operations: ManufacturingOperation[]
  workReports: WorkReport[]
  inspections: InspectionRecord[]
  issues: ManufacturingIssue[]
  reworks: ManufacturingRework[]
}
export interface ProcessTemplate { id:number; templateCode:string; templateName:string; workshopType?:string; operationCount:number }
export interface ManufacturingOperation { id:number; sequenceNo:number; operationNameSnapshot:string; requiredQuantity:number; reportedQuantity:number; progressPercent:number; status:string; isFinal:boolean; assignedTeamName?:string; assignedEmployeeId?:number; assignedEmployeeName?:string }
export interface WorkReport { id:number; reportNo:string; manufacturingOrderOperationId:number; operationNameSnapshot:string; reportedQuantity:number; reporterNameSnapshot?:string; reportedAt:string; status:string; reversalReason?:string; remark?:string }
export interface InspectionRecord { id:number; inspectionNo:string; inspectionResult:string; qualifiedQuantity:number; unqualifiedQuantity:number; inspectorNameSnapshot?:string; inspectedAt:string; issueDescription?:string; remark?:string }
export interface ExecutionBoardItem {
  operationId:number; manufacturingOrderId:number; sequenceNo:number; operationNameSnapshot:string
  requiredQuantity:number; reportedQuantity:number; operationStatus:string; startedAt?:string
  orderNo:string; salesOrderRef?:string; customerNameSnapshot:string; productNameSnapshot:string
  specificationSnapshot?:string; customRequirement?:string; plannedQuantity:number; unit:string
  dueDate?:string; workshopType?:string; processTemplateNameSnapshot?:string; taskType:string
  salespersonNameSnapshot?:string; orderStatus:string
  priority:string; plannedStartDate?:string; plannedFinishDate?:string; productionTeamName?:string
  responsibleEmployeeId?:number; responsibleEmployeeName?:string; assignedTeamName?:string
  assignedEmployeeId?:number; assignedEmployeeName?:string; openIssueCount:number
}
export interface ExecutionBoardResult extends PageResult<ExecutionBoardItem> {
  summary:{ total:number; ready:number; inProgress:number; blocked:number; overdue:number; rework:number }
  workshops:string[]
}
export interface MyWorkReport extends WorkReport {
  orderNo:string; customerNameSnapshot:string; productNameSnapshot:string; unit:string
}
export interface ManufacturingIssue { id:number; issueNo:string; manufacturingOrderId:number; manufacturingOrderOperationId?:number; operationNameSnapshot?:string; issueType:string; severity:string; description:string; status:string; reporterNameSnapshot?:string; reportedAt:string; resolverNameSnapshot?:string; resolvedAt?:string; resolution?:string }
export interface ManufacturingRework { id:number; reworkNo:string; reworkRound:number; operationNameSnapshot:string; requirement:string; status:string; createdByName?:string; createdAt:string; completedAt?:string }
export interface ManufacturingAssignee { id:number; name:string; department?:string }
export interface MaterialRequirement { id:number; materialId?:number; materialName:string; specification?:string; plannedQuantity:number; issuedQuantity:number; returnedQuantity:number; pendingQuantity:number; unit:string; remark?:string }
export interface MaterialMovement { id:number; movementNo:string; requirementId:number; warehouseId:number; warehouseName:string; movementType:'ISSUE'|'RETURN'; quantity:number; quantityChange:number; materialName:string; specification?:string; unit:string; operatorName?:string; occurredAt:string; remark?:string }
export interface ManufacturingMaterials { requirements:MaterialRequirement[]; movements:MaterialMovement[] }
export const fetchManufacturingOrders = async (params: Record<string, unknown>) =>
  (await http.get<PageResult<ManufacturingOrder>>('/manufacturing/orders', { params })).data
export interface ScheduleSummary {
  total: number
  unscheduled: number
  scheduled: number
  inProduction: number
  overdue: number
  teams: Array<{ team: string; taskCount: number; overdueCount: number }>
}
export const fetchManufacturingScheduleSummary = async () =>
  (await http.get<ScheduleSummary>('/manufacturing/schedule-summary')).data
export const fetchManufacturingOrder = async (id: number) =>
  (await http.get<ManufacturingDetail>(`/manufacturing/orders/${id}`)).data
export const fetchManufacturingWarehouses = async () =>
  (await http.get<Warehouse[]>('/manufacturing/warehouses')).data
/** 建单弹窗用：启用中的车间（defaultProcessTemplateId 为空时需自选制程模板）。 */
export interface WorkshopOption { id:number; workshopName:string; defaultProcessTemplateId?:number|null }
export const fetchWorkshopOptions = async () =>
  (await http.get<WorkshopOption[]>('/manufacturing/workshop-options')).data
// ============ M0 幂等契约：写动作请求键 ============
// 后端对报工 / 冲正 / 收货 / 冲销等写动作要求 X-Request-Key（决策 #12「无键不直通」）：
//  - 同一次提交的重试必须复用同一个键 → 后端认定为「重放」，直接返回首次结果，不会重复落账；
//  - 新的一次提交必须换新键 → 否则会被当成上一次的重放而被静默丢弃。
// 因此页面在「打开弹窗 / 发起动作」时生成一次键，提交失败重试沿用，成功后丢弃。
export function newRequestKey(): string {
  const c = globalThis.crypto as Crypto | undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `rk-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
/** 组请求键头；未传键时兜底新生成一个，避免漏带键导致后端拒收。 */
function idemConfig(requestKey?: string) {
  return { headers: { 'X-Request-Key': requestKey || newRequestKey() } }
}
export const createManufacturingOrder = async (payload: Record<string, unknown>, requestKey?: string) =>
  (await http.post<number>('/manufacturing/orders', payload, idemConfig(requestKey))).data
export const completeManufacturingOrder = async (id: number, payload: Record<string, unknown>, requestKey?: string) =>
  (await http.post<number>(`/manufacturing/orders/${id}/completion-receipts`, payload, idemConfig(requestKey))).data
export const fetchProcessTemplates = async () =>
  (await http.get<ProcessTemplate[]>('/manufacturing/process-templates')).data
/**
 * 建单：按关键词搜产品主数据（P3 ①）。
 *
 * 不走「可售库存」—— 建单是**要生产**，库存本来就可能没有；候选必须是全部产品身份。
 */
export const fetchProductOptions = async (keyword: string, limit = 50) =>
  (await http.get<ProductOption[]>('/manufacturing/product-options', { params: { keyword, limit } })).data
/**
 * 建单：该车间可选的制程品类。
 *
 * 与后端制程解析读同一张配置表（`manufacturing_task_config_items`），
 * 所以「选得到」必然「解析得出制程」—— 不存在选了个解析不了的品类。
 */
export const fetchProcessCategories = async (workshopId: number) =>
  (await http.get<string[]>('/manufacturing/process-categories', { params: { workshopId } })).data
export const generateManufacturingOperations = async (id:number,templateId:number,requestKey?:string) =>
  { await http.post(`/manufacturing/orders/${id}/operations/generate`,{templateId},idemConfig(requestKey)) }
export const submitWorkReport = async (orderId:number,operationId:number,payload:Record<string,unknown>,requestKey?:string) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/operations/${operationId}/work-reports`,payload,idemConfig(requestKey))).data
export const reverseWorkReport = async (id:number,reason:string,requestKey?:string) =>
  { await http.post(`/manufacturing/work-reports/${id}/reverse`,{reason},idemConfig(requestKey)) }
export const submitManufacturingInspection = async (orderId:number,payload:Record<string,unknown>,requestKey?:string) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/inspections`,payload,idemConfig(requestKey))).data
export const fetchExecutionBoard = async (params:Record<string,unknown>) =>
  (await http.get<ExecutionBoardResult>('/manufacturing/execution-board',{ params })).data
export const fetchMyWorkReports = async (params:Record<string,unknown>) =>
  (await http.get<PageResult<MyWorkReport>>('/manufacturing/my-work-reports',{ params })).data
export interface PendingInspectionItem {
  id:number; orderNo:string; salesOrderRef?:string; customerNameSnapshot:string; productNameSnapshot:string
  specificationSnapshot?:string; customRequirement?:string; plannedQuantity:number; completedQuantity:number; unit:string
  dueDate?:string; workshopType?:string; processTemplateNameSnapshot?:string; taskType:string; salespersonNameSnapshot?:string
  status:string; productionCompletedAt?:string; inspectionStatus?:string
}
export interface PendingInspectionsResult extends PageResult<PendingInspectionItem> {
  reinspectionCount:number
}
export const fetchPendingInspections = async (params:Record<string,unknown>) =>
  (await http.get<PendingInspectionsResult>('/manufacturing/inspections/pending',{ params })).data
export interface AllWorkReport extends WorkReport {
  orderNo:string; customerNameSnapshot:string; productNameSnapshot:string; unit:string; workshopType?:string
  overproductionQuantity?:number; overproductionReason?:string; departmentNameSnapshot?:string; reversedReportId?:number
}
export interface AllWorkReportsResult extends PageResult<AllWorkReport> {
  workshops:string[]
}
export const fetchAllWorkReports = async (params:Record<string,unknown>) =>
  (await http.get<AllWorkReportsResult>('/manufacturing/work-reports',{ params })).data
export interface CompletionReceipt {
  id:number; receiptNo:string; manufacturingOrderId:number; warehouseId:number; warehouseName?:string
  completedAt:string; quantity:number; unit:string; finishedGoodsName:string; specificationSnapshot?:string
  operatorEmployeeId?:number; operatorName?:string; status:string; remark?:string
  reversedAt?:string; reversedByName?:string; reversalReason?:string; reversalMovementId?:number
  orderNo:string; salesOrderRef?:string; customerNameSnapshot:string; productNameSnapshot:string; orderUnit?:string
}
export interface CompletionReceiptsResult extends PageResult<CompletionReceipt> {
  warehouses:Array<{ id:number; warehouseCode:string; warehouseName:string }>
}
export const fetchCompletionReceipts = async (params:Record<string,unknown>) =>
  (await http.get<CompletionReceiptsResult>('/manufacturing/completion-receipts',{ params })).data
export const fetchCompletionReceiptDetail = async (id:number) =>
  (await http.get<{ receipt:CompletionReceipt; lots:FinishedGoodsLot[]; movements:Array<Record<string,unknown>> }>(`/manufacturing/completion-receipts/${id}`)).data
export const reverseCompletionReceipt = async (id:number,reason:string,requestKey?:string) =>
  { await http.post(`/manufacturing/completion-receipts/${id}/reverse`,{ reason },idemConfig(requestKey)) }
export const fetchManufacturingAssignees = async () =>
  (await http.get<ManufacturingAssignee[]>('/manufacturing/assignees')).data
export const fetchManufacturingMaterials=async(id:number)=>(await http.get<ManufacturingMaterials>(`/manufacturing/orders/${id}/materials`)).data
export const saveMaterialRequirement=async(orderId:number,id:number|undefined,payload:Record<string,unknown>)=>(await (id?http.put<number>(`/manufacturing/orders/${orderId}/materials/${id}`,payload):http.post<number>(`/manufacturing/orders/${orderId}/materials`,payload))).data
export const issueManufacturingMaterial=async(orderId:number,payload:Record<string,unknown>)=>(await http.post<number>(`/manufacturing/orders/${orderId}/material-issues`,payload)).data
export const returnManufacturingMaterial=async(orderId:number,payload:Record<string,unknown>)=>(await http.post<number>(`/manufacturing/orders/${orderId}/material-returns`,payload)).data
export const updateManufacturingSchedule = async (id:number,payload:Record<string,unknown>) =>
  { await http.put(`/manufacturing/orders/${id}/schedule`,payload) }
export const assignManufacturingOperation = async (orderId:number,operationId:number,payload:Record<string,unknown>) =>
  { await http.put(`/manufacturing/orders/${orderId}/operations/${operationId}/assignment`,payload) }
export const reportManufacturingIssue = async (orderId:number,payload:Record<string,unknown>) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/issues`,payload)).data
export const resolveManufacturingIssue = async (id:number,resolution:string) =>
  { await http.post(`/manufacturing/issues/${id}/resolve`,{ resolution }) }
export interface ManufacturingStatistics { summary:Record<string,number>; quality30d:Record<string,number>; delivery30d:Record<string,number>; workshops:Array<{workshop:string;taskCount:number;reworkCount:number;overdueCount:number}> }
export const fetchManufacturingStatistics = async () =>
  (await http.get<ManufacturingStatistics>('/manufacturing/statistics')).data
export interface OperationMaster { id:number; operationCode:string; operationName:string; operationCategory:string; defaultWorkshop?:string; standardHours?:number; reportingUnit?:string; requiresInspection:boolean; description?:string; active:boolean; sortOrder:number }
export interface ProcessTemplateMaster { id:number; templateCode:string; templateName:string; workshopType?:string; applicableProductKeyword?:string; description?:string; active:boolean; version:number; operationCount:number; defaultForWorkshop?:boolean }
export interface TemplateOperationMaster { id:number; operationId:number; sequenceNo:number; operationCode:string; operationName:string; operationCategory:string; requirementCoefficient:number; required:boolean; isFinal:boolean; allowParallel:boolean; remark?:string; operationActive:boolean }
export interface TemplateProductRule { id:number; templateId:number; productModelId:number; productName:string; modelCode?:string; customizationMode?:string; priority:number; active:boolean; remark?:string }
export const fetchOperationCatalog=async()=>(await http.get<OperationMaster[]>('/manufacturing/master/operations')).data
export const saveOperationMaster=async(id:number|undefined,payload:Record<string,unknown>)=>(await (id?http.put<number>(`/manufacturing/master/operations/${id}`,payload):http.post<number>('/manufacturing/master/operations',payload))).data
export const fetchTemplateCatalog=async()=>(await http.get<ProcessTemplateMaster[]>('/manufacturing/master/templates')).data
export const fetchTemplateMaster=async(id:number)=>(await http.get<{template:ProcessTemplateMaster;operations:TemplateOperationMaster[]}>(`/manufacturing/master/templates/${id}`)).data
export const saveTemplateMaster=async(id:number|undefined,payload:Record<string,unknown>)=>(await (id?http.put<number>(`/manufacturing/master/templates/${id}`,payload):http.post<number>('/manufacturing/master/templates',payload))).data
export const replaceTemplateOperations=async(id:number,rows:Record<string,unknown>[])=>{await http.put(`/manufacturing/master/templates/${id}/operations`,rows)}
export const fetchTemplateRules=async(id:number)=>(await http.get<TemplateProductRule[]>(`/manufacturing/master/templates/${id}/rules`)).data
export const saveTemplateRule=async(templateId:number,id:number|undefined,payload:Record<string,unknown>)=>(await (id?http.put<number>(`/manufacturing/master/templates/${templateId}/rules/${id}`,payload):http.post<number>(`/manufacturing/master/templates/${templateId}/rules`,payload))).data
export const deleteTemplateRule=async(templateId:number,id:number)=>{await http.delete(`/manufacturing/master/templates/${templateId}/rules/${id}`)}

// ========== 氚云生产任务单镜像（双轨制的生产侧接线） ==========
// 源轨道 production_orders（氚云台账，只读）→ 业务轨道 manufacturing_orders（可写）。
// 镜像只读源表、只写业务表，源台账内容不动；可反复调用，已镜像的自动跳过。
export interface H3MirrorProgress {
  /** 氚云源工单总数（只读台账） */
  sourceTotal:number
  /** 已镜像成业务工单的条数 */
  mirrored:number
  /** 尚未镜像 */
  remaining:number
  /** 已镜像但缺产品身份等、待人工补数据的条数 */
  pendingData:number
  /** 已镜像、无工序、带待补原因 → 补完制程配置后可供重试的条数 */
  retryable?:number
}

/** 「补完制程配置后重试展开工序」的结果。 */
export interface H3RetryResult {
  scanned:number
  /** 本次补全工序的条数 */
  fixed:number
  /** 仍缺配置、保持待补的条数 */
  stillPending:number
  failed:number
  stillSamples:string[]
  errors:string[]
  retryable:number
}
export interface H3MirrorResult {
  scanned:number; created:number; failed:number; pendingData:number
  /** 待补数据的样例（最多 10 条）：「工单号 → 原因」 */
  pendingSamples:string[]
  /** 失败明细（最多 20 条） */
  errors:string[]
  /** 本批跑完后还剩多少未镜像 */
  remaining:number
}
export const fetchH3MirrorProgress = async () =>
  (await http.get<H3MirrorProgress>('/manufacturing/mirror/h3-progress')).data
/** @param limit 0 = 本批不限量；建议页面分批点，便于观察每批结果 */
export const mirrorH3ProductionOrders = async (limit = 0) =>
  (await http.post<H3MirrorResult>('/manufacturing/mirror/h3-orders', null, { params: { limit } })).data

/**
 * 补完制程配置后，给「已镜像但工序没展开」的工单重试展开工序。
 *
 * 为什么需要单独一步：镜像只取「源表连不上业务表」的单，已镜像的不会被重扫 ——
 * 所以因缺配置落 pending 的单不会自己好起来，必须显式重试。
 * 只补工序（不重建工单），且只碰还没有工序行的单。
 */
export const retryH3PendingOperations = async (limit = 0) =>
  (await http.post<H3RetryResult>('/manufacturing/mirror/h3-retry-pending', null, { params: { limit } })).data
