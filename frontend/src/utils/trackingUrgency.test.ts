import { describe, expect, it } from 'vitest'
import { isSourceUrgent } from './trackingUrgency'

describe('生产任务单备注加急标记', () => {
  it('识别明确加急并忽略普通备注和否定句', () => {
    expect(isSourceUrgent('客户要求加急，优先排产')).toBe(true)
    expect(isSourceUrgent('加 急')).toBe(true)
    expect(isSourceUrgent('无需加急，正常排产')).toBe(false)
    expect(isSourceUrgent('不加急')).toBe(false)
    expect(isSourceUrgent('按正常交期生产')).toBe(false)
    expect(isSourceUrgent(null)).toBe(false)
  })
})
