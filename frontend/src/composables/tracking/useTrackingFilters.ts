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
import { shortWorkshop, trackingStatusText } from '@/utils/tracking/rules'

export type TrackingDateFilter = { from: string; to: string }
export type FilterChip = { key: string; label: string }
export type QuickFilterKey = 'pending' | 'in_progress' | 'today' | 'overdue' | 'time_attention' | 'stopped' | 'oxidation'

const emptyTrackingDateFilter = (): TrackingDateFilter => ({ from: '', to: '' })

/**
 * 「生产交付结果」常用区多选筛选：取值是后端固定的四态枚举（见 Service.DELIVERY_OUTCOME），
 * 与列表徽标一一对应，故这里是常量而非从数据里取。
 */
const DELIVERY_FILTER_OPTIONS = [
  { value: 'OVERDUE', label: '逾期完工' }, { value: 'ON_TIME', label: '按期完工' },
  { value: 'NO_BASELINE', label: '已完工（无基准）' }, { value: 'PENDING', label: '未完工' }
]
const COUNTDOWN_FILTER_OPTIONS = [
  { value: 'overdue', label: '已超期' }, { value: 'today', label: '今日到期' },
  { value: 'soon', label: '未来 1～3 天' }, { value: 'later', label: '3 天后' },
  { value: 'undated', label: '待定交期' }, { value: 'delivered', label: '已交付' }
]
/**
 * 「按需工序」筛选（10-15 跟单员需求：筛出需要氧化/喷特氟龙这道工序的所有跟单）。
 * 取值是固定工序码，与列表/导出的后端白名单一一对应；空串＝不筛。
 * 口径＝源单可选配置写明需要这道工序 **或** 已有该节点的跟单记录（后端 needsOptionalStage 唯一源）。
 */
const OPTIONAL_STAGE_OPTIONS = [
  { value: 'OXIDATION', label: '需要氧化 / 喷特氟龙' },
  { value: 'WOODEN_CRATE', label: '需要木箱' }
]

export function useTrackingFilters(options: { onChange: () => void }) {
  const keyword = ref('')
  const specification = ref('')
  const advancedFiltersVisible = ref(false)
  const dateFilters = ref<{ created: TrackingDateFilter }>({ created: emptyTrackingDateFilter() })
  const countdownFilter = ref<string[]>([])
  const deliveryFilter = ref<string[]>([])
  const optionalStageFilter = ref('')
  /** 「业务员」常用区多选筛选：取值来自数据（后端 filter-options 的 salespersons），故用可选值清单。 */
  const salespersonFilter = ref<string[]>([])
  const workshop = ref('')
  const sourceStatus = ref('')
  /** 跟单员筛选：''＝全部 / 员工 id 字符串。 */
  const assignee = ref('')
  const trackingStatus = ref('all')
  const timeFilter = ref('all')
  /** 跟单待办筛选：all＝不限 / any＝只要有提醒 / 具体代码见 TRACKING_ACTION_CODES。 */
  const actionFilter = ref('all')
  const urgentOnly = ref(false)
  const mine = ref(false)
  /** 搜索栏下拉选项（车间 / 跟单员）；加载失败时保持空，不阻断列表。 */
  const filterOptions = ref<TrackingFilterOptions>({ workshops: [], sourceStatuses: [], assignees: [] })
  let filterOptionsLoaded = false

  const quickFilterPresets: { key: QuickFilterKey; label: string }[] = [
    { key: 'pending', label: '待跟单' }, { key: 'in_progress', label: '跟单中' },
    { key: 'today', label: '今日到期' }, { key: 'overdue', label: '已超期' },
    { key: 'time_attention', label: '时间待核实' }, { key: 'stopped', label: '停止跟单' },
    { key: 'oxidation', label: '需要氧化/喷特氟龙' },
  ]
  const timeFilterLabels: Record<string, string> = {
    time_attention: '时间待核实', time_application: '申请日期问题', time_required: '业务员要求日期问题',
    time_acceptance: '接单时间待核', time_commitment: '车间交期待核', time_reporting: '报工时间待核',
    time_inbound_reporting: '入库与报工待核', time_overdue: '逾期待记录原因', time_due: '核实事项应跟进',
  }

  /**
   * 工序下拉选项（带条数）：条数由后端 filter-options 用同一份 FROM + 同一段可见范围算出，
   * 数字必然等于点进去的条数；后端未升级时没有这个字段，则只显示工序名、不显示数字。
   */
  const optionalStageOptions = computed(() => {
    const counts = new Map((filterOptions.value.optionalStageCounts || []).map(item => [item.code, item.count]))
    return OPTIONAL_STAGE_OPTIONS.map(option => {
      const count = counts.get(option.value)
      return { ...option, label: count === undefined ? option.label : `${option.label}（${count}）` }
    })
  })
  function optionalStageLabel(value: string) {
    return OPTIONAL_STAGE_OPTIONS.find(option => option.value === value)?.label || value
  }
  const activeAdvancedFilterCount = computed(() => [Boolean(workshop.value), Boolean(sourceStatus.value), Boolean(assignee.value), trackingStatus.value !== 'all', mine.value,
    actionFilter.value !== 'all', timeFilter.value !== 'all', Boolean(optionalStageFilter.value)]
    .filter(Boolean).length)
  const activeFilterChips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = []
    if (keyword.value.trim()) chips.push({ key: 'keyword', label: `关键词：${keyword.value.trim()}` })
    if (workshop.value) chips.push({ key: 'workshop', label: `车间：${shortWorkshop(workshop.value)}` })
    if (sourceStatus.value) chips.push({ key: 'sourceStatus', label: `任务单状态：${sourceStatus.value}` })
    if (assignee.value) chips.push({ key: 'assignee', label: `跟单员：${filterOptions.value.assignees.find(person => String(person.id) === assignee.value)?.name || assignee.value}` })
    if (mine.value) chips.push({ key: 'mine', label: '我的跟单' })
    if (trackingStatus.value !== 'all') chips.push({ key: 'trackingStatus', label: `跟单状态：${trackingStatus.value === 'open' ? '跟单进行中' : trackingStatusText(trackingStatus.value as TrackingOrder['trackingStatus'])}` })
    if (specification.value.trim()) chips.push({ key: 'specification', label: `规格：${specification.value.trim()}` })
    if (actionFilter.value !== 'all') chips.push({ key: 'action', label: `跟单待办：${actionFilter.value === 'any' ? '有提醒' : actionLabel(actionFilter.value as TrackingActionCode)}` })
    if (timeFilter.value !== 'all') chips.push({ key: 'time', label: `时间核实：${timeFilterLabels[timeFilter.value] || timeFilter.value}` })
    if (optionalStageFilter.value) chips.push({ key: 'optionalStage', label: `按需工序：${optionalStageLabel(optionalStageFilter.value)}` })
    if (urgentOnly.value) chips.push({ key: 'urgent', label: '仅看加急' })
    if (salespersonFilter.value.length) chips.push({ key: 'salesperson', label: `业务员：${salespersonFilter.value.join('、')}` })
    if (countdownFilter.value.length) chips.push({ key: 'countdown', label: `生产倒计时：${countdownFilter.value.map(value => COUNTDOWN_FILTER_OPTIONS.find(option => option.value === value)?.label || value).join('、')}` })
    if (deliveryFilter.value.length) chips.push({ key: 'delivery', label: `交付结果：${deliveryFilter.value.map(value => DELIVERY_FILTER_OPTIONS.find(option => option.value === value)?.label || value).join('、')}` })
    const created = dateFilters.value.created
    if (created.from || created.to) chips.push({ key: 'createdDate', label: `生产任务单提交时间：${created.from || '不限'}～${created.to || '不限'}` })
    return chips
  })

  function quickFilterActive(key: QuickFilterKey) {
    if (key === 'pending' || key === 'in_progress' || key === 'stopped') return trackingStatus.value === key
    if (key === 'time_attention') return timeFilter.value === key
    if (key === 'oxidation') return optionalStageFilter.value === 'OXIDATION'
    return countdownFilter.value.includes(key)
  }
  function applyQuickFilter(key: QuickFilterKey) {
    if (key === 'pending' || key === 'in_progress' || key === 'stopped') trackingStatus.value = trackingStatus.value === key ? 'all' : key
    else if (key === 'time_attention') timeFilter.value = timeFilter.value === key ? 'all' : key
    else if (key === 'oxidation') optionalStageFilter.value = optionalStageFilter.value === 'OXIDATION' ? '' : 'OXIDATION'
    else countdownFilter.value = countdownFilter.value.includes(key) ? countdownFilter.value.filter(value => value !== key) : [...countdownFilter.value, key]
    options.onChange()
  }
  function clearFilterChip(key: string) {
    switch (key) {
      case 'createdDate': dateFilters.value.created = emptyTrackingDateFilter(); break
      case 'keyword': keyword.value = ''; break
      case 'workshop': workshop.value = ''; break
      case 'sourceStatus': sourceStatus.value = ''; break
      case 'assignee': assignee.value = ''; break
      case 'mine': mine.value = false; break
      case 'trackingStatus': trackingStatus.value = 'all'; break
      case 'specification': specification.value = ''; break
      case 'action': actionFilter.value = 'all'; break
      case 'time': timeFilter.value = 'all'; break
      case 'optionalStage': optionalStageFilter.value = ''; break
      case 'urgent': urgentOnly.value = false; break
      case 'salesperson': salespersonFilter.value = []; break
      case 'countdown': countdownFilter.value = []; break
      case 'delivery': deliveryFilter.value = []; break
    }
    options.onChange()
  }
  function resetFilters() {
    keyword.value = ''; specification.value = ''; workshop.value = ''; sourceStatus.value = ''; assignee.value = ''
    mine.value = false; urgentOnly.value = false; trackingStatus.value = 'all'; actionFilter.value = 'all'; timeFilter.value = 'all'
    salespersonFilter.value = []; countdownFilter.value = []; deliveryFilter.value = []; optionalStageFilter.value = ''
    dateFilters.value = { created: emptyTrackingDateFilter() }
  }
  function resetTrackingFilters() { resetFilters(); options.onChange() }
  function onCreatedDateChange(changed: 'from' | 'to') {
    const value = dateFilters.value.created
    if (value.from && value.to && value.from > value.to) {
      value[changed === 'from' ? 'to' : 'from'] = ''
      ElMessage.warning('起始日期不能晚于截止日期，已清除另一端日期')
    }
    options.onChange()
  }
  /**
   * 列表与导出的筛选入参**唯一来源**：两处必须完全一致，
   * 否则「导出的行」和「页面上看到的行」会对不上，而这不会有任何报错。
   */
  function trackingFilters() {
    return {
      keyword: keyword.value, specification: specification.value, workshop: workshop.value, sourceStatus: sourceStatus.value, trackingStatus: trackingStatus.value,
      reminder: timeFilter.value,
      action: actionFilter.value,
      urgent: urgentOnly.value,
      countdown: countdownFilter.value.join(','),
      salesperson: salespersonFilter.value.join(','),
      deliveryResult: deliveryFilter.value.join(','),
      optionalStage: optionalStageFilter.value,
      createdFrom: dateFilters.value.created.from || '', createdTo: dateFilters.value.created.to || '',
      month: '',
      mine: mine.value, assignee: assignee.value
    }
  }
  /** 下拉选项只在首次与「刷新」时拉取；失败时若从未成功过则回落空清单，不阻断列表。 */
  async function loadOptions(refresh: boolean) {
    if (filterOptionsLoaded && !refresh) return
    try { filterOptions.value = await fetchTrackingFilterOptions(); filterOptionsLoaded = true }
    catch { if (!filterOptionsLoaded) filterOptions.value = { workshops: [], sourceStatuses: [], assignees: [] } }
  }

  return {
    keyword, specification, advancedFiltersVisible, dateFilters, countdownFilter, deliveryFilter, optionalStageFilter,
    salespersonFilter, workshop, sourceStatus, assignee, trackingStatus, timeFilter, actionFilter, urgentOnly, mine,
    filterOptions, COUNTDOWN_FILTER_OPTIONS, DELIVERY_FILTER_OPTIONS, optionalStageOptions, optionalStageLabel,
    quickFilterPresets, timeFilterLabels, quickFilterActive, applyQuickFilter, activeAdvancedFilterCount,
    activeFilterChips, clearFilterChip, resetTrackingFilters, onCreatedDateChange, trackingFilters, loadOptions
  }
}
