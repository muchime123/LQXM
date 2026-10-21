import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TrackingAnalyticsSpecCell from '@/components/production/TrackingAnalyticsSpecCell.vue'
import TrackingAnalyticsSalespersonCell from '@/components/production/TrackingAnalyticsSalespersonCell.vue'

/**
 * 下钻「产品规格 / 模具出数」与「业务员」两格。
 *
 * <p>这两格被**两个抽屉共用**（生产交期达成分析 + 生产跟单周复盘），都读同一个下钻接口。
 * 口径一旦分叉，就会出现「分析页有规格、周复盘空着」这种同源不同显。
 * 这里钉死三条：① 一行展示（LQ 2026-10-21 明确要求，不分两行）
 * ② 任一段缺失不留孤零零的分隔符 ③ 两段都缺时显示 `—` 而不是空白。
 */
describe('TrackingAnalyticsSpecCell', () => {
  it('规格与模具出数连成一行，用「 / 」分隔', () => {
    const wrapper = mount(TrackingAnalyticsSpecCell, {
      props: { specification: '120 / 圆形 / PP', moldLayout: '一出四' }
    })
    expect(wrapper.text()).toBe('120 / 圆形 / PP / 一出四')
    // 一行展示：不允许出现 <br> 或多段堆叠
    expect(wrapper.findAll('br')).toHaveLength(0)
  })

  it('只有规格时不留下孤零零的分隔符', () => {
    const wrapper = mount(TrackingAnalyticsSpecCell, { props: { specification: '95 / 方形 / PP', moldLayout: null } })
    expect(wrapper.text()).toBe('95 / 方形 / PP')
    expect(wrapper.text().endsWith('/')).toBe(false)
  })

  it('只有模具出数时同样不留分隔符', () => {
    const wrapper = mount(TrackingAnalyticsSpecCell, { props: { specification: null, moldLayout: '一出六' } })
    expect(wrapper.text()).toBe('一出六')
    expect(wrapper.text().startsWith('/')).toBe(false)
  })

  it('两段都缺（氚云没填，如产品名=模具的单）时显示「—」而不是空白', () => {
    // 显式标注成 string | null：不标的话数组字面量会被推断成 `null` 单值联合，
    // 第二轮道具（''/'  '）就会被 TS 判成不可赋值（vue-tsc 在测试文件上也做类型检查）。
    const cases: { specification: string | null; moldLayout: string | null }[] = [
      { specification: null, moldLayout: null },
      { specification: '', moldLayout: '' },
      { specification: '   ', moldLayout: '  ' }
    ]
    for (const props of cases) {
      const wrapper = mount(TrackingAnalyticsSpecCell, { props })
      expect(wrapper.text()).toBe('—')
    }
  })

  it('纯空格规格不产生「 / 」拼在模具前面', () => {
    const wrapper = mount(TrackingAnalyticsSpecCell, { props: { specification: '   ', moldLayout: '一出二' } })
    expect(wrapper.text()).toBe('一出二')
  })
})

describe('TrackingAnalyticsSalespersonCell', () => {
  it('有业务员时原样展示（去首尾空格）', () => {
    const wrapper = mount(TrackingAnalyticsSalespersonCell, { props: { salesperson: ' 徐恩永 ' } })
    expect(wrapper.text()).toBe('徐恩永')
  })

  it('业务员缺失时显示「—」', () => {
    for (const salesperson of [null, undefined, '', '  ']) {
      const wrapper = mount(TrackingAnalyticsSalespersonCell, { props: { salesperson } })
      expect(wrapper.text()).toBe('—')
    }
  })
})
