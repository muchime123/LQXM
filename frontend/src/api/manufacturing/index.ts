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
export interface ManufacturingDetail {
  order: ManufacturingOrder
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
export const createManufacturingOrder = async (payload: Record<string, unknown>) =>
  (await http.post<number>('/manufacturing/orders', payload)).data
export const completeManufacturingOrder = async (id: number, payload: Record<string, unknown>) =>
  (await http.post<number>(`/manufacturing/orders/${id}/completion-receipts`, payload)).data
export const fetchProcessTemplates = async () =>
  (await http.get<ProcessTemplate[]>('/manufacturing/process-templates')).data
export const generateManufacturingOperations = async (id:number,templateId:number) =>
  { await http.post(`/manufacturing/orders/${id}/operations/generate`,{templateId}) }
export const submitWorkReport = async (orderId:number,operationId:number,payload:Record<string,unknown>) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/operations/${operationId}/work-reports`,payload)).data
export const reverseWorkReport = async (id:number,reason:string) =>
  { await http.post(`/manufacturing/work-reports/${id}/reverse`,{reason}) }
export const submitManufacturingInspection = async (orderId:number,payload:Record<string,unknown>) =>
  (await http.post<number>(`/manufacturing/orders/${orderId}/inspections`,payload)).data
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
export const reverseCompletionReceipt = async (id:number,reason:string) =>
  { await http.post(`/manufacturing/completion-receipts/${id}/reverse`,{ reason }) }
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
