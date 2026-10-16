import { http } from '../http'

/**
 * 制程 / 工序主数据客户端。
 *
 * ⚠️ 2026-10-06：随 ERP 前端下线，本文件原先承载的**生产执行链**客户端封装已整体删除
 * （建单、审批关口、报工、冲正、检验、完工入库、领料、执行看板、统计等，
 * 共 67 个导出）。后端执行链端点同期一并拆除；留底于 commit 23a0eda，需要恢复可从该提交取回。
 *
 * 现仅保留「制程与工序管理」页所需的主数据读写：工序库、制程模板、模板工序行、产品规则。
 * 注：任务单产品与配置、车间维护两个面板走 `http` 直调 `/manufacturing/master/*`，不经本文件。
 */

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
