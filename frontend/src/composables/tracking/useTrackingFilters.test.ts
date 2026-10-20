import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { useTrackingFilters } from './useTrackingFilters'

/** 关键词是任务号 / 产品 / 客户 / 规格的**唯一**搜索入口，不再有独立的「规格」标签。 */
const CHIP_KEYS = ['keyword', 'workshop', 'sourceStatus', 'assignee', 'mine', 'trackingStatus',
  'action', 'time', 'optionalStage', 'urgent', 'salesperson', 'countdown', 'delivery', 'createdDate', 'expectedDate']

function setup() {
  const onChange = vi.fn()
  return { onChange, filters: useTrackingFilters({ onChange }) }
}

/** 把全部筛选条件都打开，用于验证「标签 / 清除 / 重置」三处清单是否同步。 */
function enableAll(filters: ReturnType<typeof useTrackingFilters>) {
  filters.keyword.value = '封口'
  filters.workshop.value = '封口机车间'
  filters.sourceStatus.value = '已提交'
  filters.assignee.value = '7'
  filters.mine.value = true
  filters.trackingStatus.value = 'in_progress'
  filters.actionFilter.value = 'any'
  filters.timeFilter.value = 'time_attention'
  filters.optionalStageFilter.value = 'OXIDATION'
  filters.urgentOnly.value = true
  filters.salespersonFilter.value = ['张三']
  filters.countdownFilter.value = ['overdue']
  filters.deliveryFilter.value = ['ON_TIME']
  filters.dateFilters.value.created = { from: '2026-09-01', to: '' }
  filters.dateFilters.value.expected = { from: '2026-10-01', to: '' }
}

describe('tracking/useTrackingFilters · 筛选清单单一来源', () => {
  beforeEach(() => { vi.spyOn(ElMessage, 'warning').mockReturnValue({} as never) })

  it('每个筛选条件都会产生标签，且能被逐个清除（不留残留）', () => {
    const { onChange, filters } = setup()
    expect(filters.activeFilterChips.value).toEqual([{ key: 'trackingStatus', label: '跟单状态：未结束跟单' }])

    enableAll(filters)
    const chips = filters.activeFilterChips.value
    expect(chips.map(chip => chip.key).sort()).toEqual([...CHIP_KEYS].sort())

    for (const chip of chips) filters.clearFilterChip(chip.key)
    expect(filters.activeFilterChips.value).toEqual([])
    expect(onChange).toHaveBeenCalledTimes(chips.length)
  })

  it('重置与逐个清除等价：清完后既无标签，入参也回到默认值', () => {
    const { onChange, filters } = setup()
    enableAll(filters)
    filters.resetTrackingFilters()

    const defaults = {
      keyword: '', workshop: '', sourceStatus: '', trackingStatus: 'all', reminder: 'all',
      action: 'all', urgent: false, countdown: '', salesperson: '', deliveryResult: '', optionalStage: '',
      createdFrom: '', createdTo: '', expectedFrom: '', expectedTo: '',
      month: '', mine: false, assignee: ''
    }
    expect(filters.activeFilterChips.value).toEqual([])
    expect(filters.activeAdvancedFilterCount.value).toBe(0)
    expect(filters.trackingFilters()).toEqual(defaults)
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('按需工序重置后不会在标签里残留「按需工序」', () => {
    const { filters } = setup()
    filters.optionalStageFilter.value = 'WOODEN_CRATE'
    expect(filters.activeFilterChips.value.map(chip => chip.key)).toEqual(['trackingStatus', 'optionalStage'])
    filters.clearFilterChip('optionalStage')
    expect(filters.activeFilterChips.value.map(chip => chip.key)).toEqual(['trackingStatus'])
  })

  it('提交时间起止写反时清掉另一端并刷新', () => {
    const { onChange, filters } = setup()
    filters.dateFilters.value.created = { from: '2026-09-10', to: '2026-09-01' }
    filters.onCreatedDateChange('from')
    expect(filters.dateFilters.value.created).toEqual({ from: '2026-09-10', to: '' })
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('预计完成时间走 expectedFrom/expectedTo，且常用区两段日期互不干扰', () => {
    const { onChange, filters } = setup()
    filters.dateFilters.value.expected = { from: '2026-10-01', to: '2026-10-31' }
    expect(filters.trackingFilters()).toMatchObject({ expectedFrom: '2026-10-01', expectedTo: '2026-10-31' })
    expect(filters.activeFilterChips.value.find(chip => chip.key === 'expectedDate')?.label)
      .toBe('预计完成时间：2026-10-01～2026-10-31')
    expect(filters.trackingFilters().createdFrom).toBe('')
    // 两段日期都在常用区，不占「更多筛选」角标
    expect(filters.activeAdvancedFilterCount.value).toBe(0)

    // 写反时清掉另一端，且不影响提交时间
    filters.dateFilters.value.expected = { from: '2026-10-31', to: '2026-10-01' }
    filters.dateFilters.value.created = { from: '2026-09-01', to: '2026-09-30' }
    filters.onExpectedDateChange('to')
    expect(filters.dateFilters.value.expected).toEqual({ from: '', to: '2026-10-01' })
    expect(filters.dateFilters.value.created).toEqual({ from: '2026-09-01', to: '2026-09-30' })
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('快筛按钮与标签相互对应（同一状态只算一次）', () => {
    const { filters } = setup()
    filters.applyQuickFilter('overdue')
    expect(filters.quickFilterActive('overdue')).toBe(true)
    expect(filters.activeFilterChips.value.map(chip => chip.key)).toEqual(['trackingStatus', 'countdown'])

    // 再点一次＝取消
    filters.applyQuickFilter('overdue')
    expect(filters.quickFilterActive('overdue')).toBe(false)
    expect(filters.activeFilterChips.value.map(chip => chip.key)).toEqual(['trackingStatus'])
  })

  it('角标只统计「更多筛选」里的条件，常用区不计入（跟单状态的默认口径不算）', () => {
    const { filters } = setup()
    expect(filters.activeAdvancedFilterCount.value).toBe(0)
    filters.workshop.value = '封口机车间'
    filters.countdownFilter.value = ['today']
    expect(filters.activeAdvancedFilterCount.value).toBe(1)
    // 「跟单状态」「业务员」已收进更多筛选区，故计入角标；默认的「未结束跟单」不算
    filters.trackingStatus.value = 'pending'
    expect(filters.activeAdvancedFilterCount.value).toBe(2)
    filters.salespersonFilter.value = ['张三']
    expect(filters.activeAdvancedFilterCount.value).toBe(3)
  })

  it('时间核实分组名称与筛选参数对应', () => {
    const { filters } = setup()
    filters.timeFilter.value = 'time_source'
    expect(filters.trackingFilters().reminder).toBe('time_source')
    expect(filters.activeFilterChips.value.find(chip => chip.key === 'time')?.label).toBe('时间核实：日期异常（含已核实）')
    filters.timeFilter.value = 'time_production'
    expect(filters.activeFilterChips.value.find(chip => chip.key === 'time')?.label).toBe('时间核实：生产时间异常')
  })
})
