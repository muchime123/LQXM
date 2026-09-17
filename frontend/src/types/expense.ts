// P6.1 费用中心类型定义
import type {
  ExpenseStats, ClaimRow, ClaimDetail, ClaimItemRow,
  EXPENSE_CATEGORY_OPTIONS, APPROVAL_STATUS_OPTIONS
} from '../api/expense/expenses'
import type {
  DingtalkStatus, SyncRunRow, SyncRunDetail, SyncTriggered,
  SYNC_STATUS_OPTIONS, TRIGGER_OPTIONS
} from '../api/dingtalk'

export type {
  ExpenseStats, ClaimRow, ClaimDetail, ClaimItemRow,
  DingtalkStatus, SyncRunRow, SyncRunDetail, SyncTriggered
}

export { EXPENSE_CATEGORY_OPTIONS, APPROVAL_STATUS_OPTIONS, SYNC_STATUS_OPTIONS, TRIGGER_OPTIONS }
