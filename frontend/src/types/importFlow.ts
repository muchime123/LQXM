/**
 * R1 导入流程公共配方 · 类型定义
 * 覆盖：Software 批量导入（3 步：扫描→选择→结果）、Pricing Excel 导入、
 *       Catalog Excel 导入、Media 更新记录导入等场景的通用数据结构。
 */

export type ImportResultLevel = 'success' | 'warning' | 'error' | 'info'

/**
 * 通用导入结果汇总对象。
 * 所有业务导入 API 最终都应返回此结构，供 LqResult / toast / 重试面板复用。
 */
export interface ImportResultSummary {
  /** 新增条数 */
  created?: number
  /** 更新条数 */
  updated?: number
  /** 跳过条数（已存在、无效等原因） */
  skipped?: number
  /** 失败明细错误信息 */
  errors?: string[]
  /** 业务侧自定义计数（如 assets、models、reused 等） */
  custom?: Record<string, number>
  /** 原始透传对象，供业务侧读取详情 */
  raw?: unknown
}

/**
 * 导入步骤定义。配合 LqStepper 使用，通过当前状态自动推导 activeStep。
 * state 枚举：
 *   - idle：未进入流程，仅展示配置/模板下载
 *   - upload：文件已上传 / 路径已输入，待解析
 *   - preview：已解析出预览列表，待用户勾选
 *   - executing：导入执行中
 *   - result：导入完成，展示汇总与错误明细
 */
export type ImportState = 'idle' | 'upload' | 'preview' | 'executing' | 'result'

export interface ImportStepDef {
  title: string
  description?: string
  /** 此步骤对应的最低导入状态；状态 >= state 时步骤置为 done/active */
  state: ImportState
}
