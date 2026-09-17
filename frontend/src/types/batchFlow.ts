/**
 * R2 批量操作流程公共配方 · 类型定义
 * 覆盖：Catalog 批量删除/编辑、Packaging 规格批量删除/复制/移动/补齐/编辑、
 *       Media 批量发布任务/批量更新记录、MoveAsset 素材移动等场景。
 */

export type BatchResultLevel = 'success' | 'warning' | 'error' | 'info'

/**
 * 通用批量操作结果汇总对象。
 * 所有批量操作 API 最终都应返回此结构，供 toast / 进度面板 / LqResult 复用。
 */
export interface BatchResultSummary {
  /** 受影响条数（成功修改/删除/复制等） */
  affected?: number
  /** 总条数（选中数） */
  total?: number
  /** 跳过条数（已存在、无效等原因） */
  skipped?: number
  /** 失败条数 */
  failed?: number
  /** 失败明细错误信息 */
  errors?: string[]
  /** 业务侧自定义计数（如 models、variants、assets 等） */
  custom?: Record<string, number>
  /** 原始透传对象，供业务侧读取详情 */
  raw?: unknown
}

/**
 * 批量操作进度状态。
 */
export type BatchProgressStatus = 'running' | 'done' | 'error'

export interface BatchProgressState {
  visible: boolean
  title: string
  current: number
  total: number
  status: BatchProgressStatus
}
