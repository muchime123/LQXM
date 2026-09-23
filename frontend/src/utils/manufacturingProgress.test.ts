import { describe, expect, it } from 'vitest'
import { processProgress, stepPercent } from './manufacturingProgress'
import type { ManufacturingOperation } from '@/api/manufacturing'

const op = (id: number, status: string, required = true, quantity = 0): ManufacturingOperation => ({ id, sequenceNo: id, operationNameSnapshot: '工序', status, required, requiredQuantity: 1, reportedQuantity: quantity, progressPercent: quantity * 100, isFinal: false })
describe('制程进度按工序完成状态计算', () => {
  it('工序报完与整机数量无关，选做不增加必做分母', () => {
    expect(processProgress([op(1, 'COMPLETED', true, 1), op(2, 'IN_PROGRESS', true, .9), op(3, 'COMPLETED', false, 1)])).toEqual({ required: 2, completed: 1, percent: 50 })
  })
  it('冲正回进行中或返工后不能继续计为完成', () => {
    expect(processProgress([op(1, 'IN_PROGRESS', true, 3), op(2, 'PENDING')]).completed).toBe(0)
  })
  it('无工序不伪装为100%，旧接口缺少字段不显示错误零值', () => {
    expect(processProgress([]).percent).toBeNull()
    expect(stepPercent(undefined, undefined)).toBeNull()
    expect(stepPercent(2, 6)).toBe(33)
  })
})
