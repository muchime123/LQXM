/**
 * 生产跟单**列表列的显示 / 隐藏**（本机勾选 + 全站默认 + 代码兜底三级）。
 *
 * 【为什么单独一个模块】这一段原本夹在页面里，与导出、详情、进度表的状态混在一起，
 * 但它自己有一套独立的生效链与存档格式，且两端（前端勾选矩阵 / 后端默认列配置）
 * 各写了一份列 key 清单 —— 漂移不会报错，只会变成线上才现的静默故障，
 * 所以列定义搬到这里后，契约测试（trackingListColumns.test.ts）直接读本文件比对后端常量。
 *
 * 【生效链】本机勾选（localStorage）> 全站默认（管理员配置，服务端）> 代码兜底常量。
 *   员工自己勾过就以自己为准；没勾过就跟随管理员定的全站默认。见 loadColumnDefaults()。
 * ⚠️ key 只用于存取；改 key 会让老存档失配（该列回到默认），可接受。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchTrackingColumnDefaults, saveTrackingColumnDefaults } from '@/api/productionTracking'

/**
 * ============================ 列表列显示/隐藏 ============================
 * LQ 需求（2026-10-12）：列表字段由使用者自行勾选显示或隐藏 —— 列多时横向要一直拖。
 * 【存「隐藏」不存「显示」】存档只记被隐藏的列：以后新加的列不在隐藏名单里 ⇒ 默认可见，
 *   新列自动出现在列设置中；导出弹窗则尊重用户上次主动选择的字段。
 * 【默认状态＝你现在看到的样子】此前被 LQ 注释掉的 6 列（车间 / 跟单状态 / 跟单待办 / 要求完成 /
 *   销售去向 / 跟单员）全部还原成真正的列、并进默认隐藏名单 —— 界面与改造前一致，要看得自己去勾。
 * 【不可隐藏】编号 / 产品 / 操作：行身份与操作入口，隐藏后列表失去意义，故不进可选项。
 */
export const LIST_COLUMN_DEFS: Array<{ key: string, label: string }> = [
  { key: 'salesperson', label: '业务员' },
  { key: 'workshop', label: '车间' },
  { key: 'sourceStatus', label: '任务单状态' },
  { key: 'trackingStatus', label: '跟单状态' },
  { key: 'trackingAction', label: '跟单待办' },
  { key: 'sourceCreatedAt', label: '任务单提交时间' },
  { key: 'applicationDate', label: '申请时间' },
  { key: 'scheduleTime', label: '排产时间' },
  { key: 'dueDate', label: '要求完成' },
  { key: 'requestedLeadDays', label: '工期' },
  { key: 'expectedFinish', label: '预计完成' },
  { key: 'countdown', label: '生产倒计时' },
  { key: 'completion', label: '完工 / 入库' },
  { key: 'deliveryResult', label: '入库记录交期' },
  { key: 'stages', label: '跟单节点' },
  { key: 'timeSignals', label: '时间核实' },
  { key: 'salesTrace', label: '销售去向' },
  { key: 'assignee', label: '跟单员' }
]
const LIST_COLUMNS_HIDDEN_KEY = 'lq.trackingListHiddenColumns'
/**
 * **代码兜底**的默认隐藏名单（＝改造前被注释掉的 6 列）。
 * ⚠️ 它不是唯一来源：全站默认由管理员配置、存在服务端（production_tracking_column_defaults），
 * 生效链是 **本机勾选 > 全站默认 > 本常量**。后端也认同一份兜底值（fallbackHidden）。
 */
export const DEFAULT_HIDDEN_LIST_COLUMNS = ['workshop', 'trackingStatus', 'trackingAction', 'dueDate', 'salesTrace', 'assignee']
export const VALID_LIST_COLUMN_KEYS = LIST_COLUMN_DEFS.map(column => column.key)

function restoreHiddenListColumns(): string[] {
  try {
    const raw = localStorage.getItem(LIST_COLUMNS_HIDDEN_KEY)
    if (!raw) return [...DEFAULT_HIDDEN_LIST_COLUMNS]
    const parsed = JSON.parse(raw) as unknown
    const saved = Array.isArray(parsed) ? parsed.filter((key): key is string => typeof key === 'string') : []
    return saved.filter(key => VALID_LIST_COLUMN_KEYS.includes(key))
  } catch { return [...DEFAULT_HIDDEN_LIST_COLUMNS] }
}

export function useTrackingListColumns(options: {
  /** 是否具备「存为全站默认」权限（独立权限 manufacturing.listcolumns.manage）。由页面注入，本模块不碰登录态。 */
  canManage: () => boolean
}) {
  const hiddenListColumns = ref<string[]>(restoreHiddenListColumns())
  const columnSettingVisible = ref(false)
  /** 勾选组绑定：get＝当前可见列；set＝反推隐藏名单并落盘。 */
  const visibleListColumns = computed<string[]>({
    get: () => VALID_LIST_COLUMN_KEYS.filter(key => !hiddenListColumns.value.includes(key)),
    set: (keys) => {
      hiddenListColumns.value = VALID_LIST_COLUMN_KEYS.filter(key => !keys.includes(key))
      try { localStorage.setItem(LIST_COLUMNS_HIDDEN_KEY, JSON.stringify(hiddenListColumns.value)) } catch { /* 隐私模式等：仅本次会话生效 */ }
    }
  })
  function isListColumnVisible(key: string) { return !hiddenListColumns.value.includes(key) }
  function resetListColumns() {
    visibleListColumns.value = [...VALID_LIST_COLUMN_KEYS]
    ElMessage.success('已恢复显示全部列')
  }

  /* ---------- 全站默认列（管理员配置、对全员生效；2026-10-12） ---------- */
  const canManageColumnDefaults = computed(() => options.canManage())
  const savingColumnDefaults = ref(false)
  /** 服务端登记的全站默认隐藏列；null＝还没读到（读失败或无权限）⇒ 一律用代码兜底。 */
  const serverDefaultHidden = ref<string[] | null>(null)
  function columnLabel(key: string) { return LIST_COLUMN_DEFS.find(column => column.key === key)?.label || key }
  /** 本机是否存过自己的勾选。存过＝个人优先，全站默认不再套用。 */
  function hasLocalColumnOverride() {
    try { return localStorage.getItem(LIST_COLUMNS_HIDDEN_KEY) !== null } catch { return false }
  }
  /** 当前生效的「默认隐藏」＝服务端登记优先，读不到则代码兜底。 */
  function effectiveDefaultHidden(): string[] {
    return serverDefaultHidden.value ?? [...DEFAULT_HIDDEN_LIST_COLUMNS]
  }
  /**
   * 拉取全站默认列。**只在「本机没自己勾过」时套用**——个人勾选优先于全站默认。
   * 读失败（无权限 / 后端未升级）时静默退回代码兜底，不弹错、不阻断列表。
   */
  async function loadColumnDefaults() {
    try {
      const res = await fetchTrackingColumnDefaults()
      const hidden = (res.columns || []).filter(column => !column.visible)
        .map(column => column.columnKey).filter(key => VALID_LIST_COLUMN_KEYS.includes(key))
      serverDefaultHidden.value = hidden
      if (!hasLocalColumnOverride()) hiddenListColumns.value = [...hidden]
    } catch { serverDefaultHidden.value = null }
  }
  /** 恢复默认＝丢掉本机勾选，回到全站默认（管理员没配过就回代码兜底）。 */
  function restoreColumnDefault() {
    try { localStorage.removeItem(LIST_COLUMNS_HIDDEN_KEY) } catch { /* 隐私模式等：仅本次会话生效 */ }
    hiddenListColumns.value = effectiveDefaultHidden()
    ElMessage.success('已恢复默认显示列')
  }
  const globalDefaultSummary = computed(() => {
    const hidden = effectiveDefaultHidden()
    if (!hidden.length) return '全部列都显示'
    return `${hidden.length} 列默认隐藏：${hidden.map(columnLabel).join('、')}`
  })
  /**
   * 把当前勾选存为全站默认（仅管理员）。全量提交 15 列——后端按「未提交＝回落代码兜底」处理，
   * 所以不会出现「存了全显示、下次打开又变回隐藏」的歧义。
   */
  async function saveGlobalColumnDefaults() {
    const total = VALID_LIST_COLUMN_KEYS.length
    const hidden = hiddenListColumns.value.length
    try {
      await ElMessageBox.confirm(
        `将把当前勾选存为全站默认：显示 ${total - hidden} 列、隐藏 ${hidden} 列。`
        + '没自己勾过显示列的员工，下次打开就按这套显示；自己勾过的员工不受影响。',
        '存为全站默认', { type: 'warning' })
    } catch { return }
    savingColumnDefaults.value = true
    try {
      await saveTrackingColumnDefaults(
        VALID_LIST_COLUMN_KEYS.map(key => ({ columnKey: key, visible: !hiddenListColumns.value.includes(key) })))
      serverDefaultHidden.value = [...hiddenListColumns.value]
      // 本机同时改为「跟随全站默认」：否则刚设完默认的管理员自己还挂着一份旧勾选，看着像没生效。
      try { localStorage.removeItem(LIST_COLUMNS_HIDDEN_KEY) } catch { /* 同上 */ }
      ElMessage.success(`已存为全站默认（${hidden} 列默认隐藏）`)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存全站默认失败')
    } finally { savingColumnDefaults.value = false }
  }

  return {
    LIST_COLUMN_DEFS, VALID_LIST_COLUMN_KEYS, hiddenListColumns, columnSettingVisible, visibleListColumns,
    isListColumnVisible, resetListColumns, canManageColumnDefaults, savingColumnDefaults, serverDefaultHidden,
    columnLabel, globalDefaultSummary, loadColumnDefaults, restoreColumnDefault, saveGlobalColumnDefaults
  }
}
