import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { useTrackingFilters } from './useTrackingFilters'

const CHIP_KEYS = ['keyword', 'workshop', 'sourceStatus', 'assignee', 'mine', 'trackingStatus', 'specification',
  'action', 'time', 'optionalStage', 'urgent', 'salesperson', 'countdown', 'delivery', 'createdDate']

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
  filters.specification.value = '120×80'
  filters.actionFilter.value = 'any'
  filters.timeFilter.value = 'time_attention'
  filters.optionalStageFilter.value = 'OXIDATION'
  filters.urgentOnly.value = true
  filters.salespersonFilter.value = ['张三']
  filters.countdownFilter.value = ['overdue']
  filters.deliveryFilter.value = ['ON_TIME']
  filters.dateFilters.value.created = { from: '2026-09-01', to: '' }
}

describe('tracking/useTrackingFilters · 筛选清单单一来源', () => {
  beforeEach(() => { vi.spyOn(ElMessage, 'warning').mockReturnValue({} as never) })

  it('每个筛选条件都会产生标签，且能被逐个清除（不留残留）', () => {
    const { onChange, filters } = setup()
    expect(filters.activeFilterChips.value).toEqual([])

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
      keyword: '', specification: '', workshop: '', sourceStatus: '', trackingStatus: 'all', reminder: 'all',
      action: 'all', urgent: false, countdown: '', salesperson: '', deliveryResult: '', optionalStage: '',
      createdFrom: '', createdTo: '', month: '', mine: false, assignee: ''
    }
    expect(filters.activeFilterChips.value).toEqual([])
    expect(filters.activeAdvancedFilterCount.value).toBe(0)
    expect(filters.trackingFilters()).toEqual(defaults)
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('按需工序重置后不会在标签里残留「按需工序」', () => {
    const { filters } = setup()
    filters.optionalStageFilter.value = 'WOODEN_CRATE'
    expect(filters.activeFilterChips.value.map(chip => chip.key)).toEqual(['optionalStage'])
    filters.clearFilterChip('optionalStage')
    expect(filters.activeFilterChips.value).toEqual([])
  })

  it('提交时间起止写反时清掉另一端并刷新', () => {
    const { onChange, filters } = setup()
    filters.dateFilters.value.created = { from: '2026-09-10', to: '2026-09-01' }
    filters.onCreatedDateChange('from')
    expect(filters.dateFilters.value.created).toEqual({ from: '2026-09-10', to: '' })
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('快筛按钮与标签相互对应（同一状态只算一次）', () => {
    const { filters } = setup()
    filters.applyQuickFilter('overdue')
    expect(filters.quickFilterActive('overdue')).toBe(true)
    expect(filters.activeFilterChips.value.map(chip => chip.key)).toEqual(['countdown'])

    // 再点一次＝取消
    filters.applyQuickFilter('overdue')
    expect(filters.quickFilterActive('overdue')).toBe(false)
    expect(filters.activeFilterChips.value).toEqual([])
  })

  it('角标只统计「更多筛选」里的条件，常用区不计入', () => {
    const { filters } = setup()
    filters.workshop.value = '封口机车间'
    filters.trackingStatus.value = 'pending'
    expect(filters.activeAdvancedFilterCount.value).toBe(2)
    filters.countdownFilter.value = ['today']
    expect(filters.activeAdvancedFilterCount.value).toBe(2)
  })
})
