/**
 * 生产跟单的**导出**（网页 / Excel 两条输出）。
 *
 * 【为什么单独一个模块】导出的列定义、勾选记忆、取数分组、落盘原本写在页面里，
 * 与详情/进度表/筛选的状态混在同一个作用域；而导出又有它自己的一条铁律：
 * 「导出的行必须与列表所见同源」——筛选入参、每个单元格的文案、日期口径都不能另写一份。
 * 搬出来后，这条铁律的边界就变成了本模块的入参（`filters`），模块内不再有任何页面状态。
 *
 * 【边界】本模块不持有列表数据、不碰筛选状态：
 * - `filters()`：调用方注入的筛选入参（页面传 `trackingFilters()`，与列表同一个函数），
 *   保证「导出与列表对不上」这类静默漂移在类型与调用点上都只有一个来源；
 * - `selectedIds()`：打开弹窗时列表里选中的行；
 * - `visibility`：数据范围文案（全部 / 仅本人），与列表返回的可见范围同一份。
 *
 * 两条输出（html / xlsx）共用 `collectExportGroups`：取数、按车间分组、节点列展开只做一次，
 * 最后一步「谁把二维数组写成文件」才分叉。
 */
import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchTrackingExport } from '@/api/productionTracking'
import type { TrackingOrder, TrackingStageProgress } from '@/api/productionTracking'
import { currentCompletionDate, deliveryOutcomeText, deliveryOutcomeTone, formatMinute, productSize, productionCountdown, reviewOtherReasonsText, reviewReasonText, shortDate, shortOrderNo, shortWorkshop, stageNotesText, trackingStatusText } from '@/utils/tracking/rules'
import { effectiveLeadDaysCellText } from '@/utils/trackingDateGuard'
import { effectiveAction, trackingActionLabel as actionLabel } from '@/utils/trackingAction'
import { buildTrackingWorkbook, trackingExportFileName, trackingExportGuideSheet } from '@/utils/export/trackingWorkbook'
import { buildTrackingHtmlBlob, trackingHtmlFileName, type HtmlCell, type Tone } from '@/utils/export/trackingHtml'
import { groupBySource, type TrackingColumnSource } from '@/utils/export/trackingColumnSources'
import { htmlColumnWidth } from '@/utils/export/trackingHtmlWidths'
import { downloadBlob } from '@/utils/ui/downloadBlob'

export interface ExportColumn {
  key: string
  label: string
  /**
   * 字段来源三分法（LQ 10-15）：`h3`＝氚云同步只读、`manual`＝跟单登记人工、`derived`＝系统计算只读。
   * 导出弹窗按它分组、网页版导出件按它给表头着色；每一列都必须声明，漏了会被契约测试抓。
   */
  source: TrackingColumnSource
  value: (row: TrackingOrder) => string | number
  /**
   * 网页导出的**展示替代值**（10-14 LQ：网页版尽量与列表所见一致，好塞进一张纸）。
   * 只影响 HTML；xlsx 与导回按完整任务号/日期匹配，必须继续走 value()。
   * 可以是字符串（`\n` 落成换行），也可以是结构化格子：主值 + 右侧徽标 + 下一行副信息。
   */
  htmlValue?: (row: TrackingOrder, selected: ReadonlySet<string>) => HtmlCell
  /**
   * 网页版**表头文案**（可选）。xlsx 的表头是导回解析的锚（按「任务号」「车间预计完成」等
   * 字面认列，见后端 ProductionTrackingRoundTripService），所以并入信息只能改这一份。
   */
  htmlLabel?: string
  /**
   * **Excel 专属列**（10-15 LQ 定稿：网页版与 Excel 的字段选择本就该不同）。
   * 网页版把这些信息当作宿主列的**组成部分**整体呈现，所以在网页格式下选择器不显示它们；
   * 切到 Excel 才出现（Excel 里是独立列，也是导回解析的锚）。
   */
  excelOnly?: boolean
  /**
   * 网页版把本列**并入**宿主列（值写在宿主列的副行/徽标里），不再单独占一列 —— 省一列＝省纸。
   * 仅当宿主列也在本次导出范围内才收起；只勾了本列时照常单独成列，信息不丢。
   * xlsx 不受影响（Excel 里仍是独立列，供线下改表和导回）。
   */
  htmlMergedInto?: string
  /** 网页导出用的语义色；Excel 不支持样式，忽略即可。空串＝不上色。 */
  tone?: (row: TrackingOrder) => Tone | ''
}
/**
 * 网页版「产品」这个整体字段的 key。数量/规格/模具排布的 `htmlMergedInto` 都指向它 ——
 * 它们只是产品格里的组成部分，网页版永不单独成列（选择器也不列出）。
 */
const PRODUCT_COMPOSITE_KEY = 'product'
/** 网页版并入产品格的三个 Excel 专属列。只勾了它们也要把产品补上，否则弹窗「已选 N 列」与实际导出对不上。 */
const PRODUCT_MERGED_KEYS = ['quantity', 'specification', 'moldLayout']
export const EXPORT_COLUMNS: ExportColumn[] = [
  // 任务号：网页版与列表一致只留末 4 位（shortOrderNo），业务员名挂在下一行（省一列）；
  // xlsx 保留全号供导回匹配，业务员仍是独立列（label 必须是「任务号」——导回按它认列）。
  {
    key: 'orderNo',
    label: '任务号',
    source: 'h3',
    htmlLabel: '任务号 / 业务员',
    value: row => row.orderNo || '',
    htmlValue: (row, selected) => ({ text: shortOrderNo(row.orderNo), sub: selected.has('salesperson') ? row.salespersonNameSnapshot || '' : '' })
  },
  // 产品：网页版是**一个整体字段**（10-15 LQ 定稿）——名称 + 数量徽标 +「规格尺寸段 / 模具排布」副行，
  // 永远在同一格里，不因勾选而拆开（数量/规格/模具在网页格式下也不出现在选择器里）。
  // 表头就写「产品」两个字：格里的内容虽含四部分，但把四部分都列进表头又长又挤（10-15 LQ 明确要求固定单名）。
  // xlsx 相反：名称、数量、规格、模具排布各占一列，供线下改表与导回（它们标了 excelOnly）。
  {
    key: 'product',
    label: '产品',
    source: 'h3',
    value: row => row.productNameSnapshot || '',
    htmlValue: row => {
      const sub = [productSize(row), row.moldLayout?.trim()].filter(Boolean).join(' / ')
      return {
        text: row.productNameSnapshot || '',
        sub,
        badge: `${row.plannedQuantity ?? ''}${row.unit || ''}`
      }
    }
  },
  // 规格/模具排布/数量：网页版是产品格的组成部分（并入），仅 Excel 里单独成列。
  { key: 'specification', label: '规格', source: 'h3', value: row => row.specificationSnapshot || '', htmlMergedInto: 'product', excelOnly: true },
  { key: 'moldLayout', label: '模具排布（一出几）', source: 'h3', value: row => row.moldLayout || '', htmlMergedInto: 'product', excelOnly: true },
  // 车间：网页版按列表省掉「车间」后缀。
  { key: 'workshop', label: '车间', source: 'h3', value: row => row.workshopType || '', htmlValue: row => shortWorkshop(row.workshopType) },
  { key: 'sourceStatus', label: '生产任务单状态', source: 'h3', value: row => row.sourceStatus || '' },
  // 跟单状态/待办：系统按节点推进与停止登记自动判定，不是谁手填的 → 系统计算。
  { key: 'trackingStatus', label: '跟单状态', source: 'derived', value: row => trackingStatusText(row.trackingStatus) },
  { key: 'trackingAction', label: '跟单待办', source: 'derived', value: row => actionLabel(effectiveAction(row)) },
  // 停止跟单三项：只随「停止跟单」这个人工动作产生 → 跟单登记。
  { key: 'stoppedReason', label: '停止跟单原因', source: 'manual', value: row => row.stoppedReason || '' },
  { key: 'stoppedAt', label: '停止跟单时间', source: 'manual', value: row => formatMinute(row.stoppedAt), htmlValue: row => shortDate(formatMinute(row.stoppedAt)) },
  { key: 'stoppedByName', label: '停止跟单操作人', source: 'manual', value: row => row.stoppedByName || '' },
  { key: 'customer', label: '客户', source: 'h3', value: row => row.customerNameSnapshot || '' },
  { key: 'salesperson', label: '业务员', source: 'h3', value: row => row.salespersonNameSnapshot || '', htmlMergedInto: 'orderNo' },
  // 数量：网页版并入产品格的徽标（excelOnly ⇒ 网页格式不在选择器里露面）。
  { key: 'quantity', label: '数量', source: 'h3', value: row => `${row.plannedQuantity ?? ''}${row.unit || ''}`, htmlMergedInto: 'product', excelOnly: true },
  // 日期类（10-14 LQ）：网页版统一省年份只到 MM-DD，与列表 shortDate 同口径；xlsx 仍全量。
  { key: 'sourceCreatedAt', label: '生产任务单提交时间', source: 'h3', value: row => formatMinute(row.sourceCreatedAt), htmlValue: row => shortDate(formatMinute(row.sourceCreatedAt)) },
  { key: 'applicationDate', label: '生产工单申请时间', source: 'h3', value: row => row.applicationDate || '未填写', htmlValue: row => shortDate(row.applicationDate) || '未填写' },
  { key: 'scheduleTime', label: '排产时间', source: 'h3', value: row => formatMinute(row.scheduleTime) || '未排产', htmlValue: row => shortDate(formatMinute(row.scheduleTime)) || '未排产' },
  // 要求完成日：网页版把「业务员要求工期」挂到下一行（同一列，省一列）。
  {
    key: 'dueDate',
    label: '业务员要求完成',
    source: 'h3',
    htmlLabel: '要求完成 / 工期',
    value: row => row.dueDate || '未定',
    htmlValue: (row, selected) => ({ text: shortDate(row.dueDate) || '未定', sub: selected.has('requestedLeadDays') ? effectiveLeadDaysCellText(row) : '' })
  },
  // 要求工期：按「申请日/提交日 → 要求完成日」算出的真身 → 系统计算。
  {
    key: 'requestedLeadDays',
    label: '工期（自然日，含延期与真身）',
    source: 'derived',
    value: row => effectiveLeadDaysCellText(row),
    htmlMergedInto: 'dueDate'
  },
  // 车间预计完成：网页版把「生产倒计时」挂到下一行（同一列，省一列）。
  // 车间预计完成是导回可改的「延期至」（跟单登记）；倒计时是拿它和今天比出来的 → 系统计算。
  {
    key: 'expectedFinishDate',
    label: '车间预计完成',
    source: 'manual',
    htmlLabel: '车间预计完成 / 倒计时',
    value: row => currentCompletionDate(row) || '未定',
    htmlValue: (row, selected) => ({ text: shortDate(currentCompletionDate(row)) || '未定', sub: selected.has('countdown') ? productionCountdown(row) : '' })
  },
  { key: 'countdown', label: '生产倒计时', source: 'derived', value: row => productionCountdown(row), htmlMergedInto: 'expectedFinishDate' },
  { key: 'actualFinishDate', label: '氚云完工日期', source: 'h3', value: row => row.actualFinishDate || '', htmlValue: row => shortDate(row.actualFinishDate) },
  // 采购入库三列：从采购入库台账按任务号拼出来的事实 → 系统计算。
  { key: 'inboundDate', label: '采购入库日期', source: 'derived', value: row => row.inboundDate || '', htmlValue: row => shortDate(row.inboundDate) },
  { key: 'inboundLastDate', label: '末次采购入库日期', source: 'derived', value: row => row.inboundLastDate || '', htmlValue: row => shortDate(row.inboundLastDate) },
  { key: 'inboundCount', label: '关联采购入库单数', source: 'derived', value: row => row.inboundCount ?? 0 },
  // 实际交付 = min(跟单入库节点, 采购首次入库)，交付结果/逾期天数都由它推 → 系统计算。
  { key: 'deliveredDate', label: '实际生产交付日期', source: 'derived', value: row => row.deliveredDate || '', htmlValue: row => shortDate(row.deliveredDate) },
  { key: 'delivery', label: '生产交付结果', source: 'derived', value: row => deliveryOutcomeText(row), tone: row => deliveryOutcomeTone(row) },
  { key: 'deliveryOverdueDays', label: '逾期天数', source: 'derived', value: row => row.deliveryOverdueDays ?? '', tone: row => ((row.deliveryOverdueDays ?? 0) > 0 ? 'danger' : '') },
  // 延期原因：既是要看的列，也是**导回可写的四类列之一**（与后端 ProductionTrackingRoundTripService 的表头常量对齐）。
  { key: 'delayReason', label: '延期原因', source: 'manual', value: row => row.delayReason || '' },
  // 逾期原因（10-15 LQ：导出件里只有延期原因，逾期只有天数没有原因，线下没法分析）：
  // 取 OVERDUE 类核实记录的 reason —— 与详情页「交付逾期」录入框是同一份数据，不另开字段。
  { key: 'overdueReason', label: '逾期原因', source: 'manual', value: row => reviewReasonText(row, 'OVERDUE'), tone: row => (reviewReasonText(row, 'OVERDUE') ? 'danger' : '') },
  // 其余五类时间核实（源工单日期 / 接单等待 / 车间交期 / 报工核实 / 采购入库）的原因合并成一列。
  { key: 'otherReviewReason', label: '其他时间核实原因', source: 'manual', value: row => reviewOtherReasonsText(row, ['OVERDUE']) },
  // 节点备注：跟单员报进度时写在各节点上的说明，「为什么这道工序晚」只在这里。
  { key: 'stageNotes', label: '节点备注', source: 'manual', value: row => stageNotesText(row) },
  // 生产进度（各节点完成日期）：跟单员登记，导回也可写 → 跟单登记。
  { key: 'stages', label: '生产进度（各节点完成日期）', source: 'manual', value: () => '' },
  { key: 'assignee', label: '跟单员', source: 'manual', value: row => row.assigneeName || '' },
  // 最近跟进：最后一次登记动作的系统时间戳，不可手改 → 系统计算。
  { key: 'lastFollowupAt', label: '最近跟进', source: 'derived', value: row => formatMinute(row.lastFollowupAt), htmlValue: row => shortDate(formatMinute(row.lastFollowupAt)) },
  { key: 'remark', label: '跟单备注', source: 'manual', value: row => row.remark || '' },
  { key: 'sourceOptionalConfiguration', label: '生产任务单可选配置', source: 'h3', value: row => row.sourceOptionalConfiguration || '' },
  { key: 'sourceOrderRemark', label: '生产任务单备注', source: 'h3', value: row => row.sourceOrderRemark || '' }
]

// 网页版默认列宽按列 key 收窄（日期列 60 / 带副行的列 90 / 跟单节点 60）：
// 策略与「key 必须真实存在」的契约都在 @/utils/export/trackingHtmlWidths。

/**
 * 「生产进度」是**多列占位**：每个车间子表按该车间的节点序列展开
 * （封口机：备料 / 模具生产 / 氧化·喷特氟龙 / 组装 / 入库；机器车间是画图/钣金/调试那套）。
 * 列定义（code/label/顺序）取自该组第一行的 `stageProgress` —— `ProductionTrackingPolicy` 是唯一源，前端不复制节点表。
 */
function expandStageColumns(template: TrackingStageProgress[]): ExportColumn[] {
  return template.map(stage => ({
    key: `stage:${stage.code}`,
    label: stage.label,
    // 节点完成日期＝跟单员登记、导回可写 → 跟单登记（与 stages 占位列同源）。
    source: 'manual' as const,
    value: row => stageCellValue((row.stageProgress || []).find(item => item.code === stage.code)),
    // 网页版节点完成日期同样省年份（与其它日期列一致）；「无」等占位原样透传。
    htmlValue: row => shortDate(stageCellValue((row.stageProgress || []).find(item => item.code === stage.code)))
  }))
}
/** 单元格值：已完成 → 完成日期；按需工序本单不走 → 「无」；其余（未开始/进行中/未登记）留空。 */
function stageCellValue(stage?: TrackingStageProgress): string {
  if (!stage) return ''
  if (stage.status === 'DONE') return stage.completedDate || ''
  if (stage.optional && stage.applicable === false) return '无'
  return ''
}

/** 子表顺序：封口机在前（主车间），其余按名称排；车间名缺失兜底「未填车间」。 */
function sheetPriority(row: TrackingOrder): number { return (row.workshopType || '').includes('封口') ? 0 : 1 }

/**
 * 核心字段预设（10-14 LQ 在导出件里逐列勾选定稿；10-15 补「逾期原因」与产品附属的规格/模具）：
 * 任务号/业务员、产品/数量/规格/模具排布（网页版四者同一格；Excel 各自成列）、
 * 生产工单申请时间、要求完成/要求工期、车间预计完成/倒计时、延期原因、逾期原因、生产进度全部节点。
 * 车间不进核心 —— 网页版本来就按车间分子表，车间列是重复信息。
 * 「其他时间核实原因」「节点备注」不进核心：真库里这两类常年 0~17 条，进核心只会多出两列空白。
 */
const CORE_EXPORT_COLUMN_KEYS = ['orderNo', 'product', 'quantity', 'specification', 'moldLayout', 'applicationDate', 'dueDate',
  'expectedFinishDate', 'delayReason', 'overdueReason', 'stages']

/**
 * 勾选记忆：只保留仍存在的列；旧存档缺少的新列可在弹窗中自行勾选。
 *
 * ⚠️ 记忆结构升过版（v1＝纯 key 数组 → v2＝`{version,keys}`）。为什么必须升：
 * 10-15 新增「逾期原因 / 其他时间核实原因 / 节点备注」三列，而 LQ 浏览器里存着 v1 记忆。
 * 若只是简单取交集，他打开导出弹窗**永远看不到新列**，会直接以为功能没做。
 * 升版那一次把「本版新增列」补进勾选；之后一律按用户自己的勾选来（不会反复补回来）。
 */
const EXPORT_COLUMNS_STORAGE_KEY = 'lq.trackingExportColumns'
const EXPORT_COLUMNS_STORAGE_VERSION = 2
/** v1 → v2 补齐的列（10-15 新增）。旧的纯数组记忆读到时会带上它们，写回后即为 v2。 */
const EXPORT_COLUMNS_ADDED_IN_V2 = ['overdueReason', 'otherReviewReason', 'stageNotes']
const ALL_EXPORT_COLUMN_KEYS = EXPORT_COLUMNS.map(column => column.key)

/**
 * 导出弹窗的列选择器按**字段来源**分三段（氚云同步/跟单登记/系统计算）。
 * 分组本身不含列清单 —— 列定义仍是唯一来源，这里只是过滤重排，两边不会漂移。
 * 分组随**导出格式**变（网页格式不列出 Excel 专属列），所以是 computed，见下方 EXPORT_COLUMN_GROUPS。
 */
function restoreExportColumns(): string[] {
  try {
    const raw = localStorage.getItem(EXPORT_COLUMNS_STORAGE_KEY)
    if (!raw) return [...ALL_EXPORT_COLUMN_KEYS]
    const parsed = JSON.parse(raw) as unknown
    const legacy = Array.isArray(parsed)
    const saved = legacy ? parsed : (parsed as { keys?: unknown } | null)?.keys
    const list = Array.isArray(saved) ? saved.filter((key): key is string => typeof key === 'string') : []
    const valid = list.filter(key => ALL_EXPORT_COLUMN_KEYS.includes(key))
    if (!valid.length) return [...ALL_EXPORT_COLUMN_KEYS]
    if (legacy) return [...new Set([...valid, ...EXPORT_COLUMNS_ADDED_IN_V2.filter(key => ALL_EXPORT_COLUMN_KEYS.includes(key))])]
    return [...new Set(valid)]
  } catch { return [...ALL_EXPORT_COLUMN_KEYS] }
}

/** 导出分组：xlsx 与网页两条输出**共用这一份数据**（列定义、车间分组、节点展开都只在这里做一次）。 */
export interface ExportGroup {
  name: string
  headers: string[]
  body: HtmlCell[][]
  tones: (Tone | '')[][]
  /** 网页版每列的默认列宽（px），`null`＝按内容估算；xlsx 不需要（Excel 自己定列宽）。 */
  widths?: (number | null)[]
  /** 网页版每列的字段来源（表头着色/图例）；xlsx 不需要（靠「导出说明」子表文字说明）。 */
  headerSources?: (TrackingColumnSource | '')[]
}

export function useTrackingExport(options: {
  /** 与列表同一个筛选入参函数 —— 「导出的行」与「页面看到的行」同源的唯一保证。 */
  filters: () => Record<string, string | number | boolean>
  /** 后端返回的可见范围（ALL / 其它），只用于文件里的数据范围说明。 */
  visibility: Ref<string>
  /** 打开导出弹窗时列表里选中的行（用于「导出选中」）。 */
  selectedIds: () => number[]
}) {
  const exportVisible = ref(false)
  const exporting = ref(false)
  const exportScope = ref<'filtered' | 'selected'>('filtered')
  const exportSelectedIds = ref<number[]>([])
  const exportColumns = ref<string[]>(restoreExportColumns())
  /** 导出格式：显式单选 + 单个导出按钮，杜绝「并列两个按钮点错格式」。默认网页（LQ 要的就是网页效果）。 */
  const exportFormat = ref<'html' | 'xlsx'>('html')
  /**
   * 当前格式下**可勾选**的列：网页版把数量/规格/模具排布当产品格的一部分，不再单列出来，
   * 所以选择器里不显示它们（它们的勾选状态仍保留 —— 切到 Excel 就还在，不会丢用户设置）。
   */
  const visibleExportColumns = computed(() => exportFormat.value === 'html'
    ? EXPORT_COLUMNS.filter(column => !column.excelOnly)
    : EXPORT_COLUMNS)
  const EXPORT_COLUMN_GROUPS = computed(() => groupBySource(visibleExportColumns.value))
  /** 计数按**当前格式可见**的列算，否则网页格式下会看到「已选 11 / 33」这种对不上的数字。 */
  const exportSelectionSummary = computed(() => {
    const visible = visibleExportColumns.value
    return `已选 ${visible.filter(column => exportColumns.value.includes(column.key)).length} / ${visible.length} 列`
  })
  const allExportSelected = computed(() => visibleExportColumns.value.every(column => exportColumns.value.includes(column.key)))
  /**
   * 网页版的产品是**整体字段**：只勾了它的组成部分（数量/规格/模具排布）也要把产品补上。
   * 用户可能在上一个版本里取消过「产品」而留下别的勾选，不补的话弹窗显示「已选 0 列」却仍导出产品，说不通。
   */
  function ensureProductForHtml() {
    if (exportFormat.value !== 'html') return
    if (!PRODUCT_MERGED_KEYS.some(key => exportColumns.value.includes(key))) return
    if (!exportColumns.value.includes(PRODUCT_COMPOSITE_KEY)) exportColumns.value = [PRODUCT_COMPOSITE_KEY, ...exportColumns.value]
  }
  watch(exportFormat, ensureProductForHtml)
  const exportFormatHint = computed(() => exportFormat.value === 'html'
    ? '生成独立网页文件：双击用浏览器打开，带配色与状态标签，可直接打印或另存为 PDF。'
      + '为省纸，网页版把业务员、生产倒计时、要求工期并进任务号、车间预计完成、业务员要求完成列（挂行内小标或下一行）；'
      + '「产品」是一个整体字段，固定包含名称/数量/规格/模具排布四部分，所以数量、规格、模具排布在这里不单独列出（切 Excel 才可按列导出）。'
    : '生成 Excel 工作簿：每个车间一个子表；改完可用「导入跟单表（回写）」导回（文件里「导出说明」子表写明哪些列可回写）。'
      + 'Excel 里产品名称、数量、规格、模具排布各自成列，可按需勾选。')

  function openExport() {
    if (!exportColumns.value.length) exportColumns.value = [...ALL_EXPORT_COLUMN_KEYS]
    ensureProductForHtml()
    exportSelectedIds.value = options.selectedIds()
    exportScope.value = exportSelectedIds.value.length ? 'selected' : 'filtered'
    exportVisible.value = true
  }
  /**
   * 全选/全不选只作用于**当前格式可见**的列：网页格式下隐藏的 Excel 专属列保留原有勾选状态，
   * 免得用户在网页格式点一下「全不选」就把 Excel 的勾选也清掉了。
   */
  function toggleAllExportColumns() {
    const visibleKeys = visibleExportColumns.value.map(column => column.key)
    exportColumns.value = allExportSelected.value
      ? exportColumns.value.filter(key => !visibleKeys.includes(key))
      : [...new Set([...exportColumns.value, ...visibleKeys])]
  }
  function applyExportPreset(preset: 'list' | 'core') {
    exportColumns.value = preset === 'core' ? [...CORE_EXPORT_COLUMN_KEYS] : [...ALL_EXPORT_COLUMN_KEYS]
  }

  /**
   * 取数 + 按车间分组 + 展开节点列 —— Excel 与网页两条路都走这里。
   * 分开写两份「哪些列、怎么分组」迟早对不上（这正是「导出与列表口径漂移」的由来）。
   * `format` 只决定「取 value 还是 htmlValue」「是否收起已并入他列的列」：网页版用列表同款展示，xlsx 保持全量值。
   */
  async function collectExportGroups(chosen: ExportColumn[], selectedIds: number[] | null, format: 'xlsx' | 'html') {
    // 只传筛选条件、不传分页：拿到的是当前筛选下的全部行（后端一次给全，与列表同一套 SQL）。
    const result = await fetchTrackingExport({ ...options.filters(), ...(selectedIds ? { ids: selectedIds.join(',') } : {}) })
    // 网页版：内容已并入宿主列的列不再单独成列（宿主列没勾时保留，信息不丢）。
    // 产品格的组成部分（数量/规格/模具排布）另有铁律：它们是**产品列的一部分，永不单独成列** ——
    // 勾了其中任何一个就等于要产品列（历史记忆可能只存了 'quantity' 没存 'product'，
    // 若不在网页版补上宿主，页面上就会冒出一列孤零零的「数量」，与「产品是一个整体字段」相悖）。
    const htmlChosen = (() => {
      if (format !== 'html') return chosen
      const hasChild = chosen.some(column => column.htmlMergedInto === PRODUCT_COMPOSITE_KEY)
      if (!hasChild || chosen.some(column => column.key === PRODUCT_COMPOSITE_KEY)) return chosen
      const product = EXPORT_COLUMNS.find(column => column.key === PRODUCT_COMPOSITE_KEY)
      return product ? [product, ...chosen] : chosen
    })()
    const selectedKeys = new Set(htmlChosen.map(column => column.key))
    const mergedHosts = new Set(htmlChosen.map(column => column.htmlMergedInto).filter((key): key is string => Boolean(key)))
    // 表头只有「子列勾选后才并入」的列才用复合名（如「任务号 / 业务员」）。
    // 产品**不算**：它固定叫「产品」——格里的数量/规格/模具是它的一部分，不写进表头（10-15 LQ 定）。
    const columns = format === 'html'
      ? htmlChosen.filter(column => !column.htmlMergedInto || !htmlChosen.some(host => host.key === column.htmlMergedInto))
      : chosen
    // 按车间分组：每个车间一块；「生产进度」占位在该组内展开成该车间的节点完成日期列
    // （列定义取该组第一行的 stageProgress —— 同一车间节点序列相同，标签由后端 Policy 唯一源给出）。
    const groups = new Map<string, { template: TrackingStageProgress[]; rows: TrackingOrder[] }>()
    for (const row of result.items) {
      const name = row.workshopType || '未填车间'
      const group = groups.get(name)
      if (group) group.rows.push(row)
      else groups.set(name, { template: row.stageProgress || [], rows: [row] })
    }
    const cellValue = (column: ExportColumn, row: TrackingOrder) =>
      (format === 'html' && column.htmlValue ? column.htmlValue(row, selectedKeys) : column.value(row))
    const cellLabel = (column: ExportColumn) => (format === 'html' && mergedHosts.has(column.key) ? column.htmlLabel || column.label : column.label)
    let sheets: ExportGroup[] = [...groups.entries()]
      .sort(([nameA, groupA], [nameB, groupB]) =>
        sheetPriority(groupA.rows[0]) - sheetPriority(groupB.rows[0]) || nameA.localeCompare(nameB, 'zh-Hans-CN'))
      .map(([name, group]) => {
        const expanded = columns.flatMap(column => column.key === 'stages' ? expandStageColumns(group.template) : [column])
        return {
          name,
          headers: expanded.map(cellLabel),
          body: group.rows.map(row => expanded.map(column => cellValue(column, row))),
          tones: group.rows.map(row => expanded.map(column => (column.tone ? column.tone(row) : '') as Tone | '')),
          // 网页版给日期列/节点列一个统一的默认列宽；xlsx 不设（Excel 有自己的列宽策略）。
          widths: format === 'html' ? expanded.map(column => htmlColumnWidth(column.key)) : undefined,
          // 网页版表头按字段来源着色（氚云同步/跟单登记/系统计算）；xlsx 不着色。
          headerSources: format === 'html' ? expanded.map(column => column.source) : undefined
        }
      })
    // 筛选结果为空时也要给出文件：单表只落表头，让人一眼看出「没有数据」而不是「导出坏了」。
    if (!sheets.length) {
      const empty = columns.filter(column => column.key !== 'stages')
      sheets = [{
        name: '生产跟单',
        headers: empty.map(cellLabel),
        body: [],
        tones: [],
        widths: format === 'html' ? empty.map(column => htmlColumnWidth(column.key)) : undefined,
        headerSources: format === 'html' ? empty.map(column => column.source) : undefined
      }]
    }
    return { result, sheets }
  }

  /**
   * 导出：`html` → 独立网页（有配色/状态标签/可打印）；`xlsx` → 分车间子表的工作簿。
   * 两者只差最后一步「谁把二维数组写成文件」，数据完全同源。
   */
  async function doExport(format: 'xlsx' | 'html') {
    const chosen = EXPORT_COLUMNS.filter(column => exportColumns.value.includes(column.key))
    if (!chosen.length) { ElMessage.warning('请至少选择一列'); return }
    const selectedIds = exportScope.value === 'selected' ? [...exportSelectedIds.value] : null
    if (selectedIds && !selectedIds.length) { ElMessage.warning('请先勾选要导出的工单'); return }
    exporting.value = true
    try {
      const { result, sheets } = await collectExportGroups(chosen, selectedIds, format)
      if (selectedIds && result.exported !== selectedIds.length) { ElMessage.warning('部分已选工单不再符合当前筛选或可见范围，请刷新列表后重新选择'); return }
      const filename = format === 'html' ? trackingHtmlFileName() : trackingExportFileName()
      if (format === 'html') {
        downloadBlob(buildTrackingHtmlBlob({
          sheets,
          title: '生产跟单',
          // 只写「口径」不重抄筛选条件：工具栏标签是唯一来源，文件里再拼一份迟早不一致。
          // 条数/车间数/生成时间由渲染器并到表头右侧读数位（doc-info），这里不再重复。
          summary: `本文件按${selectedIds ? '已选工单' : '导出时的筛选条件'}生成，`
            + (options.visibility.value === 'ALL' ? '数据范围为全部生产任务' : '数据范围仅本人负责的生产任务'),
          note: '生产进度列：已完成显示完成日期；标注「无」的为本单不走的按需工序；空白表示尚未开始或尚未登记。'
        }), filename)
      } else {
        // Excel 版带上「导出说明」子表：把「哪些列能改完导回、怎么填」写进文件本身。
        // 导回解析按「有没有任务号列」认数据子表，说明表会被自动忽略。
        // sheets 的类型是网页口径（可能含结构化格子），但 xlsx 分支从不取 htmlValue，格子只可能是 string|number；
        // 这里收窄回 Excel 口径，让「导回按完整值匹配」这件事在类型上也显式。
        downloadBlob(buildTrackingWorkbook([
          trackingExportGuideSheet(),
          ...(sheets as Parameters<typeof buildTrackingWorkbook>[0])
        ]), filename)
      }
      // 写回 v2 结构（见 restoreExportColumns 的版本说明）：之后不会再把新列补回用户的勾选。
      localStorage.setItem(EXPORT_COLUMNS_STORAGE_KEY,
        JSON.stringify({ version: EXPORT_COLUMNS_STORAGE_VERSION, keys: exportColumns.value }))
      exportVisible.value = false
      if (result.truncated) ElMessage.warning(`共 ${result.total} 条，单次上限 ${result.maxRows} 条，已导出前 ${result.exported} 条；请先缩小筛选范围`)
      else ElMessage.success(`已导出 ${result.exported} 条${format === 'html' ? '网页' : '到 Excel'}：${filename}（${sheets.length} 个车间）`)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '导出失败，请稍后重试')
    } finally { exporting.value = false }
  }

  return {
    exportVisible, exporting, exportScope, exportSelectedIds, exportColumns, exportFormat,
    visibleExportColumns, EXPORT_COLUMN_GROUPS, exportSelectionSummary, allExportSelected, exportFormatHint,
    openExport, toggleAllExportColumns, applyExportPreset, doExport
  }
}
