/**
 * 生产跟单的**筛选状态唯一来源**。
 *
 * 【为什么单独一个模块】筛选条件原先散在页面里，同一份状态要手写 6 遍：
 *   ① `trackingFilters()`（发给列表/导出的入参）② `activeAdvancedFilterCount`（「更多筛选」角标）
 *   ③ `activeFilterChips`（已筛选标签）④ `clearFilterChip`（关掉某一个标签）
 *   ⑤ `resetTrackingFilters`（重置）
 * 新增一个筛选条件就得同时改 5 处，漏一处不会报错、只会「筛选生效但标签不显示」或「重置后残留」。
 * 现在状态与这 6 处派生全部收在这里，页面只用 `<script setup>` 解构出来的同名 ref / 函数。
 *
 * 【边界】这里只管「筛选条件」，不管列表本身：分页（page/size）、请求（load/search）、
 * 数据（items/total）都留在页面。改筛选后要做什么由调用方通过 `onChange` 决定（页面传 `search`），
 * 所以本模块不 import 页面、不直接发请求（`loadOptions` 是筛选下拉选项的唯一例外，见下）。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchTrackingFilterOptions } from '@/api/productionTracking'
import type { TrackingActionCode, TrackingFilterOptions, TrackingOrder } from '@/api/productionTracking'
import { trackingActionLabel as actionLabel } from '@/utils/trackingAction'
import { shortWorkshop, TRACKING_STATUS_FILTER_LABEL, trackingStatusText } from '@/utils/tracking/rules'

export type TrackingDateFilter = { from: string; to: string }
export type FilterChip = { key: string; label: string }
export type QuickFilterKey = 'open' | 'inbound' | 'overdue'

const emptyTrackingDateFilter = (): TrackingDateFilter => ({ from: '', to: '' })

/**
 * 工作台的跟单状态默认口径（只查**还没交付**的任务，后端 open ＝ 未停工/未结案/无交付日）；
 * 「更多筛选」角标与重置判定都以此为基准，否则首次进入就会因为这个默认值而多出一个角标。
 */
const DEFAULT_TRACKING_STATUS = 'open'

/**
 * 「生产交付结果」常用区多选筛选：取值是后端固定的四态枚举（见 Service.DELIVERY_OUTCOME），
 * 与列表徽标一一对应，故这里是常量而非从数据里取。
 */
const DELIVERY_FILTER_OPTIONS = [
  { value: 'OVERDUE', label: '逾期交付' }, { value: 'ON_TIME', label: '按期交付' },
  { value: 'NO_BASELINE', label: '已交付（无基准）' }, { value: 'PENDING', label: '未交付' }
]
/**
 * 「生产倒计时」常用区多选筛选。
 * 「超期未完工」与快捷标签共用 overdue 值。
 */
const COUNTDOWN_FILTER_OPTIONS = [
  { value: 'overdue', label: '超期未完工' },
  { value: 'today', label: '今日到期' },
  { value: 'soon', label: '未来 1～3 天' }, { value: 'later', label: '3 天后' },
  { value: 'delivered', label: '已交付' }
]
/**
 * 「按需工序」筛选（10-15 跟单员需求：筛出需要氧化/喷特氟龙这道工序的所有跟单）。
 * 取值是固定工序码，与列表/导出的后端白名单一一对应；空串＝不筛。
 * 口径＝源单可选配置或备注写明需要这道工序，或已有该节点的跟单记录（后端 needsOptionalStage 唯一源）。
 */
/**
 * 已知工序的中文名兜底：后端没返回（老版本）或前端还没收录时直接显示编码。
 * 下拉的**取值与顺序一律以后端制程为准**——页面上新增的按需工序会直接出现在下拉里，
 * 不需要前端再同步改一份常量（2026-10-27 制程配置化）。
 */
const OPTIONAL_STAGE_LABELS: Record<string, string> = {
  OXIDATION: '需要氧化 / 喷特氟龙',
  WOODEN_CRATE: '需要木箱'
}
/**
 * 「节点跟进」筛选的三档（顺序＝下拉顺序）：未跟进 / 进行中 / 已完成。
 *
 * 由「模具生产」三档（10-21）泛化而来：跟单员反复问的是同一个问题——**我关心的那道节点做到哪了**，
 * 只是节点在换（模具 / 氧化 / 木箱 / 组装……）。故做成「节点 × 状态」一个条件，节点清单由
 * filter-options 的 `nodeProgressNodes` 提供（制程表唯一源），前端不再为每个节点维护常量；
 * 管理员加了节点，下拉直接出现，不用改代码。
 *
 * 共同前提仍是**该节点之后没有任何节点跟进**——后面一旦动了，说明这张单已越过这一关，不再筛出；
 * 该节点之前的节点（备料跟没跟）不看。与后端 `nodeProgress` 入参（节点编码:档位）一一对应。
 */
const NODE_PROGRESS_STATE_OPTIONS = [
  { value: 'NONE', label: '未跟进' },
  { value: 'WORKING', label: '进行中' },
  { value: 'DONE', label: '已完成·后面没推' }
]

export function useTrackingFilters(options: { onChange: () => void }) {
  /** 搜索框唯一关键词（10-19 LQ：任务号 / 产品 / 客户 / 规格共用一个框，后端同一段谓词匹配）。 */
  const keyword = ref('')
  const advancedFiltersVisible = ref(false)
  /**
   * 常用区的两段日期筛选（都在搜索行里，不必展开「更多筛选」）：
   * - created＝生产任务单提交时间（→ createdFrom/createdTo）；
   * - expected＝预计完成时间（→ expectedFrom/expectedTo），后端口径＝车间预计完成日，
   *   车间未填时回落有效的业务员要求日（见 ProductionTrackingService 的 DEADLINE）。
   */
  const dateFilters = ref<{ created: TrackingDateFilter; expected: TrackingDateFilter }>({
    created: emptyTrackingDateFilter(), expected: emptyTrackingDateFilter()
  })
  const countdownFilter = ref<string[]>([])
  const deliveryFilter = ref<string[]>([])
  const optionalStageFilter = ref('')
  /**
   * 节点跟进筛选：**选一道节点 + 它的状态**（三档可多选）。
   * 不变量：只有「选了节点 **且** 至少选了一档」时才构成条件；否则视为不筛（节点编码也一并清空，
   * 避免下拉里留着一个看起来生效、实际没生效的值）。
   */
  const nodeProgressCode = ref('')
  const nodeProgressStates = ref<string[]>([])
  /** 「业务员」筛选：取值来自数据（后端 filter-options 的 salespersons）。2026-10-21 上移到常用行。 */
  const salespersonFilter = ref<string[]>([])
  /**
   * 「额外模具」列头筛选（Excel 式下拉）：两态语义，与后端 filter 的 REQUIRED/NONE 一一对应。
   * 需要＝字段='是'；否＝字段为空或非'是'。空数组＝不筛。
   */
  const EXTRA_MOLD_OPTIONS = [
    { value: 'REQUIRED', label: '需要' },
    { value: 'NONE', label: '否' }
  ]
  const extraMoldFilter = ref<string[]>([])
  const workshop = ref('')
  /**
   * 2026-10-21 精简：删除「跟单员」「任务单状态」两个下拉（真库实测与现有条件完全重复：
   * 跟单员按车间硬分配——陈苏平≡封口机、胡健≡机器；任务单状态只是「跟单状态」的另一种写法）。
   * 但后端入参保留（恒空串），API 契约不动。
   */
  /** 跟单状态筛选；位置在「更多筛选」区，初始值 open＝工作台默认只查待处理。 */
  const trackingStatus = ref(DEFAULT_TRACKING_STATUS)
  const timeFilter = ref('all')
  /**
   * 跟单待办筛选：all＝不限 / 具体代码见 TRACKING_ACTION_CODES。
   * 下拉放在更多筛选，常用行继续保持紧凑；排产和交期条件也从这里筛选。
   */
  const actionFilter = ref('all')
  const urgentOnly = ref(false)
  const mine = ref(false)
  /** 搜索栏下拉选项（车间）；加载失败时保持空，不阻断列表。 */
  const filterOptions = ref<TrackingFilterOptions>({ workshops: [], sourceStatuses: [], assignees: [] })
  let filterOptionsLoaded = false

  /**
   * 快捷标签：
   * - open＝「需要处理」，与工作台默认视图同一口径；
   * - inbound＝「已报工待入库」，真库 23 条——节点走完、货还没入库，跟单员每天必催的那批，
   *   原先要展开「更多筛选 → 跟单待办」才能找到，现在一键直达。
   * 其余按钮（待跟单/跟单中/今日到期/已超期/待核实/氧化）与下拉逐字重复，已删。
   */
  const quickFilterPresets: { key: QuickFilterKey; label: string }[] = [
    { key: 'open', label: '需要处理' },
    { key: 'inbound', label: '已报工待入库' },
    { key: 'overdue', label: '超期未完工' },
  ]
  const timeFilterLabels: Record<string, string> = {
    time_attention: '待核实问题', time_source: '日期异常（含已核实）', time_production: '生产时间异常',
    time_application: '申请日期异常', time_required: '业务员要求日期异常',
    time_acceptance: '接单时间待核', time_commitment: '车间交期待核', time_reporting: '报工时间待核',
    time_inbound_reporting: '入库与报工待核', time_overdue: '逾期原因待记录', time_due: '核实事项应跟进',
  }

  /**
   * 工序下拉选项（带条数）：条数由后端 filter-options 用同一份 FROM + 同一段可见范围算出，
   * 数字必然等于点进去的条数；后端未升级时没有这个字段，则只显示工序名、不显示数字。
   */
  const optionalStageOptions = computed(() => {
    const counts = filterOptions.value.optionalStageCounts || []
    if (counts.length) {
      return counts.map(item => ({
        value: item.code,
        label: `${OPTIONAL_STAGE_LABELS[item.code] ?? item.code}（${item.count}）`
      }))
    }
    return Object.entries(OPTIONAL_STAGE_LABELS).map(([value, label]) => ({ value, label }))
  })
  function optionalStageLabel(value: string) {
    return OPTIONAL_STAGE_LABELS[value] || value
  }
  /** 节点下拉：取值来自制程表（filter-options），未加载时为空 —— 不写死节点清单。 */
  const nodeProgressOptions = computed(() =>
    (filterOptions.value.nodeProgressNodes ?? []).map(node => ({ value: node.code, label: node.label })))
  function nodeLabel(code: string) {
    return nodeProgressOptions.value.find(option => option.value === code)?.label || code
  }
  function nodeStateLabel(state: string) {
    return NODE_PROGRESS_STATE_OPTIONS.find(option => option.value === state)?.label || state
  }
  /** 是否构成一个有效的节点跟进条件（节点与档位都选了）。 */
  const nodeProgressActive = computed(() => Boolean(nodeProgressCode.value) && nodeProgressStates.value.length > 0)
  /**
   * 选了节点默认只勾「未跟进」——跟单员点开节点，通常就是要看**这道节点还没动**的那批单；
   * 要看进行中/已完成再自己加勾，不必先取消两档。
   * 换节点时同样归位到默认档：节点换了，上一节点的档位组合没有继承意义。
   */
  function onNodeProgressCodeChange() {
    nodeProgressStates.value = nodeProgressCode.value ? [NODE_PROGRESS_STATE_OPTIONS[0].value] : []
    options.onChange()
  }
  /** 档位清空＝不再按节点筛，节点编码一并清掉，保持上面的不变量。 */
  function onNodeProgressStatesChange() {
    if (!nodeProgressStates.value.length) nodeProgressCode.value = ''
    options.onChange()
  }
  const activeAdvancedFilterCount = computed(() => [Boolean(workshop.value), mine.value,
    timeFilter.value !== 'all', Boolean(optionalStageFilter.value), nodeProgressActive.value,
    // 「时间核实」已收进更多筛选区，计入角标；跟单状态排除工作台默认值，否则一进页面就多一个角标。
    // 注：「生产交付结果」（deliveryFilter）2026-10-30 已移至列表表头筛选，不再计入本角标。
    trackingStatus.value !== 'all' && trackingStatus.value !== DEFAULT_TRACKING_STATUS]
    .filter(Boolean).length)
  const activeFilterChips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = []
    if (keyword.value.trim()) chips.push({ key: 'keyword', label: `关键词：${keyword.value.trim()}` })
    if (workshop.value) chips.push({ key: 'workshop', label: `车间：${shortWorkshop(workshop.value)}` })
    if (mine.value) chips.push({ key: 'mine', label: '我的跟单' })
    if (trackingStatus.value !== 'all') chips.push({ key: 'trackingStatus', label: `跟单状态：${TRACKING_STATUS_FILTER_LABEL[trackingStatus.value] || trackingStatusText(trackingStatus.value as TrackingOrder['trackingStatus'])}` })
    if (actionFilter.value !== 'all') chips.push({ key: 'action', label: `跟单待办：${actionFilter.value === 'any' ? '有提醒' : actionLabel(actionFilter.value as TrackingActionCode)}` })
    if (timeFilter.value !== 'all') chips.push({ key: 'time', label: `时间核实：${timeFilterLabels[timeFilter.value] || timeFilter.value}` })
    if (optionalStageFilter.value) chips.push({ key: 'optionalStage', label: `按需工序：${optionalStageLabel(optionalStageFilter.value)}` })
    if (nodeProgressActive.value) chips.push({ key: 'nodeProgress', label: `节点跟进：${nodeLabel(nodeProgressCode.value)} · ${nodeProgressStates.value.map(state => nodeStateLabel(state)).join('、')}` })
    if (urgentOnly.value) chips.push({ key: 'urgent', label: '仅看加急' })
    if (salespersonFilter.value.length) chips.push({ key: 'salesperson', label: `业务员：${salespersonFilter.value.join('、')}` })
    if (countdownFilter.value.length) chips.push({ key: 'countdown', label: `生产倒计时：${countdownFilter.value.map(value => COUNTDOWN_FILTER_OPTIONS.find(option => option.value === value)?.label || value).join('、')}` })
    if (deliveryFilter.value.length) chips.push({ key: 'delivery', label: `生产交付结果：${deliveryFilter.value.map(value => DELIVERY_FILTER_OPTIONS.find(option => option.value === value)?.label || value).join('、')}` })
    if (extraMoldFilter.value.length) chips.push({ key: 'extraMold', label: `额外模具：${extraMoldFilter.value.map(value => EXTRA_MOLD_OPTIONS.find(option => option.value === value)?.label || value).join('、')}` })
    const created = dateFilters.value.created
    if (created.from || created.to) chips.push({ key: 'createdDate', label: `生产任务单提交时间：${created.from || '不限'}～${created.to || '不限'}` })
    const expected = dateFilters.value.expected
    if (expected.from || expected.to) chips.push({ key: 'expectedDate', label: `预计完成时间：${expected.from || '不限'}～${expected.to || '不限'}` })
    return chips
  })

  function quickFilterActive(key: QuickFilterKey) {
    if (key === 'open') return trackingStatus.value === 'open'
    if (key === 'overdue') return countdownFilter.value.includes('overdue')
    // inbound＝已报工待入库：节点走完、货还没入库，即跟单待办里的 INBOUND_PENDING。
    return actionFilter.value === 'INBOUND_PENDING'
  }
  function applyQuickFilter(key: QuickFilterKey) {
    if (key === 'open') trackingStatus.value = trackingStatus.value === 'open' ? 'all' : 'open'
    else if (key === 'overdue') {
      countdownFilter.value = countdownFilter.value.includes('overdue') ? [] : ['overdue']
      if (countdownFilter.value.length) trackingStatus.value = 'all'
    }
    else actionFilter.value = actionFilter.value === 'INBOUND_PENDING' ? 'all' : 'INBOUND_PENDING'
    options.onChange()
  }
  function clearFilterChip(key: string) {
    switch (key) {
      case 'createdDate': dateFilters.value.created = emptyTrackingDateFilter(); break
      case 'expectedDate': dateFilters.value.expected = emptyTrackingDateFilter(); break
      case 'keyword': keyword.value = ''; break
      case 'workshop': workshop.value = ''; break
      case 'mine': mine.value = false; break
      case 'trackingStatus': trackingStatus.value = 'all'; break
      case 'action': actionFilter.value = 'all'; break
      case 'time': timeFilter.value = 'all'; break
      case 'optionalStage': optionalStageFilter.value = ''; break
      case 'nodeProgress': nodeProgressCode.value = ''; nodeProgressStates.value = []; break
      case 'urgent': urgentOnly.value = false; break
      case 'salesperson': salespersonFilter.value = []; break
      case 'extraMold': extraMoldFilter.value = []; break
      case 'countdown': countdownFilter.value = []; break
      case 'delivery': deliveryFilter.value = []; break
    }
    options.onChange()
  }
  function resetFilters() {
    keyword.value = ''; workshop.value = ''
    mine.value = false; urgentOnly.value = false; trackingStatus.value = 'all'; actionFilter.value = 'all'; timeFilter.value = 'all'
    salespersonFilter.value = []; countdownFilter.value = []; deliveryFilter.value = []; optionalStageFilter.value = ''
    nodeProgressCode.value = ''; nodeProgressStates.value = []
    extraMoldFilter.value = []
    dateFilters.value = { created: emptyTrackingDateFilter(), expected: emptyTrackingDateFilter() }
  }
  function resetTrackingFilters() { resetFilters(); options.onChange() }
  /**
   * 起止写反时清掉另一端并刷新（两段日期筛选共用，避免复制两份同构代码）。
   * @param label 提示语里的条件名，用于让用户知道清的是哪一段日期。
   */
  function onDateChange(target: () => TrackingDateFilter, changed: 'from' | 'to', label: string) {
    const value = target()
    if (value.from && value.to && value.from > value.to) {
      value[changed === 'from' ? 'to' : 'from'] = ''
      ElMessage.warning(`${label}的起始日期不能晚于截止日期，已清除另一端日期`)
    }
    options.onChange()
  }
  function onCreatedDateChange(changed: 'from' | 'to') { onDateChange(() => dateFilters.value.created, changed, '生产任务单提交时间') }
  function onExpectedDateChange(changed: 'from' | 'to') { onDateChange(() => dateFilters.value.expected, changed, '预计完成时间') }
  /**
   * 列表与导出的筛选入参**唯一来源**：两处必须完全一致，
   * 否则「导出的行」和「页面上看到的行」会对不上，而这不会有任何报错。
   */
  function trackingFilters() {
    return {
      keyword: keyword.value, workshop: workshop.value, sourceStatus: '', trackingStatus: trackingStatus.value,
      reminder: timeFilter.value,
      action: actionFilter.value,
      urgent: urgentOnly.value,
      countdown: countdownFilter.value.join(','),
      salesperson: salespersonFilter.value.join(','),
      deliveryResult: deliveryFilter.value.join(','),
      optionalStage: optionalStageFilter.value,
      // 「节点 × 状态」：一个节点内多档＝或，多个节点＝且（后端按节点分组各出一段条件）。
      nodeProgress: nodeProgressActive.value
        ? nodeProgressStates.value.map(state => `${nodeProgressCode.value}:${state}`).join(',')
        : '',
      extraMold: extraMoldFilter.value.join(','),
      createdFrom: dateFilters.value.created.from || '', createdTo: dateFilters.value.created.to || '',
      expectedFrom: dateFilters.value.expected.from || '', expectedTo: dateFilters.value.expected.to || '',
      month: '',
      mine: mine.value, assignee: ''
    }
  }
  /** 下拉选项只在首次与「刷新」时拉取；失败时若从未成功过则回落空清单，不阻断列表。 */
  async function loadOptions(refresh: boolean) {
    if (filterOptionsLoaded && !refresh) return
    try { filterOptions.value = await fetchTrackingFilterOptions(); filterOptionsLoaded = true }
    catch { if (!filterOptionsLoaded) filterOptions.value = { workshops: [], sourceStatuses: [], assignees: [] } }
  }

  return {
    keyword, advancedFiltersVisible, dateFilters, countdownFilter, deliveryFilter, optionalStageFilter,
    nodeProgressCode, nodeProgressStates, nodeProgressOptions, nodeProgressActive,
    onNodeProgressCodeChange, onNodeProgressStatesChange,
    salespersonFilter, workshop, trackingStatus, timeFilter, actionFilter, urgentOnly, mine,
    extraMoldFilter, EXTRA_MOLD_OPTIONS,
    filterOptions, COUNTDOWN_FILTER_OPTIONS, DELIVERY_FILTER_OPTIONS, optionalStageOptions, optionalStageLabel,
    NODE_PROGRESS_STATE_OPTIONS, nodeLabel, nodeStateLabel,
    quickFilterPresets, timeFilterLabels, quickFilterActive, applyQuickFilter, activeAdvancedFilterCount,
    activeFilterChips, clearFilterChip, resetTrackingFilters, onCreatedDateChange, onExpectedDateChange,
    trackingFilters, loadOptions
  }
}
