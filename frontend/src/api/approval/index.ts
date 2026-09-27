import { http } from '../http'

/**
 * 通用审批（决策 #35）的**共享词表与只读入口**。
 *
 * <p>三条链（销售订单 / 生产任务单 / 采购订单）共用同一张审批流水表与同一套状态字面量，
 * 所以类型与文案只在**这一处**定义 —— 各单的 api 模块按需 re-export，
 * 不各自复制一份（同一业务概念两套词表 = 悄悄分叉，本项目已吃过这个亏）。
 *
 * <p>注意分工：**提交 / 通过 / 驳回是各单自己的端点**（URL 不同、权限不同），
 * 故留在各自的 api 模块里；这里只放共用的词表与「跨单据的待办清单」。
 */

/** 单据类型码，与后端 ErpApprovalService.DocType 的枚举名一一对应。 */
export type ApprovalDocType = 'SALES_ORDER' | 'MFG_ORDER' | 'PURCHASE_ORDER'

/** 审批态（单据表上冗余的当前态）。NONE = 从未提交过。 */
export type ApprovalStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'

/** 审批动作（流水里的一条）。 */
export type ApprovalAction = 'SUBMIT' | 'APPROVE' | 'REJECT'

/** 审批流水的一条（append-only，驳回几次都留痕）。 */
export interface ApprovalEntry {
  id: number
  docType: ApprovalDocType
  docId: number
  docNo: string
  action: ApprovalAction
  fromStatus: string
  toStatus: string
  actorEmployeeId: number
  actorName: string
  comment?: string | null
  createdAt: string
}

/** 跨三链的待审批项（审批人的待办）。 */
export interface PendingApproval {
  docType: ApprovalDocType
  docLabel: string
  docId: number
  docNo: string
  status: string
  submittedAt?: string | null
  submittedByEmployeeId?: number | null
  submittedByName?: string | null
}

export const APPROVAL_STATUS_TEXT: Record<ApprovalStatus, string> = {
  NONE: '未提交',
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export const APPROVAL_ACTION_TEXT: Record<ApprovalAction, string> = {
  SUBMIT: '提交审批',
  APPROVE: '审批通过',
  REJECT: '驳回',
}

export const APPROVAL_DOC_TYPE_TEXT: Record<ApprovalDocType, string> = {
  SALES_ORDER: '销售订单',
  MFG_ORDER: '生产任务单',
  PURCHASE_ORDER: '采购订单',
}

/** 待我审批的全类型清单（不做行级过滤——审批人通常不是建单人）。 */
export const fetchPendingApprovals = async () =>
  (await http.get<PendingApproval[]>('/erp-approvals/pending')).data
