import { http } from '../http'
import type { ApprovalStatus, ApprovalEntry } from '../approval'

export interface ManufacturingOrder {
  approvalBasis?: string
  salesOrderItemId?: number | null
  externalSource?: string
  externalId?: string
  h3WorkOrderNo?: string
  productConfigJson?: string | Record<string, unknown>
  id: number
  orderNo: string
  salesOrderRef?: string
  customerNameSnapshot: string
  salespersonNameSnapshot?: string
  productNameSnapshot: string
  processCategorySnapshot?: string | null
  skuKeySnapshot?: string | null
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
  /**
   * 审批态（决策 #35）。与 `status`（业务流转态）**是两个独立字段**：
   * 驳回后 `status` 回到 `DRAFT`，只有它记得「被驳回过」，所以列表要单独出一列。
   */
  approvalStatus?: ApprovalStatus
  approvalSubmittedAt?: string | null
  approvalSubmittedByEmployeeId?: number | null
  approvalDecidedAt?: string | null
  approvalDecidedByEmployeeId?: number | null
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
export interface InventoryProductOption { id:number; productCode:string; productName:string; specification?:string; unit:string; custom:number }
export const fetchManufacturingProductOptions = async (keyword:string) =>
  (await http.get<InventoryProductOption[]>('/manufacturing/product-options',{ params:{ keyword,limit:100 } })).data
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
  salesProductId?: number | null
  canonicalProductId?: number | null
  productCodeSnapshot?: string
  productNameSnapshot: string
  skuKey?: string | null
  identityStatus?: 'RESOLVED' | 'PENDING_PRODUCT' | 'PENDING_REVIEW' | 'NON_STANDARD'
  specificationSnapshot?: string
  unit: string
  plannedQuantity: number
  receivedQuantity: number
  isMold: boolean
  remark?: string
}
// 09-22：`ProductOption` / `fetchProductOptions` 已删除 —— 建单抽屉取消「可售产品（SKU）」选择后
//        前端再无消费方，保持「下单链路无 SKU 痕迹」。后端 `GET /api/manufacturing/product-options`
//        与建单入参的可选 `salesProductId` 都保留，待 SKU 主数据重新设计后可直接复用。
/** 收货明细行（P3 ②）：按产出清单逐项填实际数量（outputId 指向清单行）。 */
export interface ReceiptItem { outputId: number; quantity: number; unitCost?: number; remark?: string }
export interface ManufacturingDetail {
  salesDemand?: { orderId: number; orderNo: string; itemId: number } | null
  order: ManufacturingOrder
  outputs: ManufacturingOutput[]
  receipts: Record<string, unknown>[]
  lots: FinishedGoodsLot[]
  operations: ManufacturingOperation[]
  workReports: WorkReport[]
  inspections: InspectionRecord[]
  issues: ManufacturingIssue[]
  reworks: ManufacturingRework[]
  /** 审批流水（含历次驳回），append-only。 */
  approvals: ApprovalEntry[]
}
export interface ProcessTemplate { id:number; templateCode:string; templateName:string; workshopType?:string; operationCount:number }
export interface ManufacturingOperation { id:number; sequenceNo:number; operationNameSnapshot:string; requiredQuantity:number; reportedQuantity:number; progressPercent:number; status:string; isFinal:boolean; required?:boolean | number; startedAt?:string; completedAt?:string; executionRound?:number; assignedTeamName?:string; assignedEmployeeId?:number; assignedEmployeeName?:string }
export interface WorkReport { id:number; reportNo:string; manufacturingOrderOperationId:number; operationNameSnapshot:string; reportedQuantity:number; reporterNameSnapshot?:string; reportedAt:string; status:string; reversalReason?:string; remark?:string }
export interface InspectionRecord { id:number; inspectionNo:string; inspectionResult:string; qualifiedQuantity:number; unqualifiedQuantity:number; inspectorNameSnapshot?:string; inspectedAt:string; issueDescription?:string; remark?:string }
export interface ExecutionBoardItem {
  requiredOperationCount?: number; completedOperationCount?: number
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
// ============ 审批关口（决策 #35）============
// 建单落 `DRAFT`（草稿），建单人提交、老板审批通过后才流转到车间：
//   有工序 → `RELEASED`（可排产报工）；0 工序 → `PENDING_INSPECTION`（直接待完工检验）。
// 驳回退回 `DRAFT`，原因必填。提交（manage）与审批（approve）是**两个独立权限**。
/** 提交审批（草稿 → 待审批）。权限 `manufacturing.manage`。 */
export const submitManufacturingOrderApproval = async (id: number, requestKey?: string) =>
  (await http.post(`/manufacturing/orders/${id}/submit`, {}, idemConfig(requestKey))).data
/** 审批通过（待审批 → 按单据自身落 RELEASED / PENDING_INSPECTION）。权限 `manufacturing.approve`。 */
export const approveManufacturingOrder = async (id: number, comment?: string, requestKey?: string) =>
  (await http.post(`/manufacturing/orders/${id}/approve`, { comment }, idemConfig(requestKey))).data
/** 驳回（待审批 → 草稿），原因必填。权限 `manufacturing.approve`。 */
export const rejectManufacturingOrder = async (id: number, comment: string, requestKey?: string) =>
  (await http.post(`/manufacturing/orders/${id}/reject`, { comment }, idemConfig(requestKey))).data
/** 某张生产任务单的完整审批历史（含驳回）。 */
export const fetchManufacturingOrderApprovals = async (id: number) =>
  (await http.get<ApprovalEntry[]>(`/manufacturing/orders/${id}/approvals`)).data
export const fetchProcessTemplates = async () =>
  (await http.get<ProcessTemplate[]>('/manufacturing/process-templates')).data
/**
 * 建单：该车间可选的制程品类。
 *
 * 与后端制程解析读同一张配置表（`manufacturing_task_config_items`），
 * 所以「选得到」必然「解析得出制程」—— 不存在选了个解析不了的品类。
 */
export const fetchProcessCategories = async (workshopId: number) =>
  (await http.get<string[]>('/manufacturing/process-categories', { params: { workshopId } })).data
/**
 * 生产任务单下单的联动选项：该产线可选的产品名称与可选配置。
 *
 * 与制程解析读同一张「任务单产品与配置」表 —— 选得到的必然解析得出制程。
 */
export interface TaskConfigOptions { productNames: string[]; productTemplates: Record<string, number | null>; optionalConfigs: string[] }
export const fetchTaskConfigOptions = async (workshopId: number) =>
  (await http.get<TaskConfigOptions>('/manufacturing/task-config-options', { params: { workshopId } })).data
export const generateManufacturingOperations = async (id:number,templateId:number,requestKey?:string) =>
  { await http.post(`/manufacturing/orders/${id}/operations/generate`,{templateId},idemConfig(requestKey)) }
export const submitWorkReport = async (orderId:number,operationId:number,payload:Record<string,unknown>,requestKey?:string) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/operations/${operationId}/work-reports`,payload,idemConfig(requestKey))).data
export const reverseWorkReport = async (id:number,reason:string,requestKey?:string) =>
  { await http.post(`/manufacturing/work-reports/${id}/reverse`,{reason},idemConfig(requestKey)) }
export const submitManufacturingInspection = async (orderId:number,payload:Record<string,unknown>,requestKey?:string) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/inspections`,payload,idemConfig(requestKey))).data
export const submitQualityCompletion = async (orderId:number,payload:Record<string,unknown>,requestKey?:string) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/quality-completion`,payload,idemConfig(requestKey))).data
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
  stockPosted?: boolean | number
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
/**
 * 作废生产任务单 —— 接错单的回收通道。
 *
 * 可作废判据按**事实**判而不是按状态判（「待完工检验」意味着工序已全部完成、已有报工）：
 * 有报工 / 有完工入库单 / 有未退回领料 / 有未取消的关联出库单，任一成立即拒绝，
 * 并告诉用户是哪一条、下一步该做什么。作废后该销售明细回到「待下达生产需求」，可重新下达。
 * 权限复用 `manufacturing.manage`。
 */
export const voidManufacturingOrder = async (id:number,reason:string,requestKey?:string) =>
  { await http.post(`/manufacturing/orders/${id}/void`,{ reason },idemConfig(requestKey)) }
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
