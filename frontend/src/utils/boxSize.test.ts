import { describe, expect, it } from 'vitest'
import {
  BOX_SHAPES, boxRectSides, boxShapeLabel, boxSizeFieldProblem, boxSizeProblem, boxSizeText,
  isValidMm, specificationText, tidyMm, type BoxSizeFields
} from './boxSize'

const fields = (patch: Partial<BoxSizeFields> = {}): BoxSizeFields => ({
  boxShape: 'RECT', boxLengthMm: '', boxWidthMm: '', boxDiameterMm: '',
  boxShapeNote: '', boxSizeNote: '', ...patch
})

describe('盒子形状取稳定代码，与常规尺寸表同一套词表', () => {
  it('选项值必须是 RECT/ROUND/OTHER', () => {
    expect(BOX_SHAPES.map(s => s.value)).toEqual(['RECT', 'ROUND', 'OTHER'])
  })
  it('历史快照里的中文形状值仍能显示', () => {
    expect(boxShapeLabel('方形')).toBe('方形')
    expect(boxShapeLabel('RECT')).toBe('方形')
    expect(boxShapeLabel('圆形')).toBe('圆形')
    expect(boxShapeLabel('')).toBe('')
    // 认不出的值原样返回，不静默吞掉
    expect(boxShapeLabel('六角形')).toBe('六角形')
  })
})

describe('毫米数值校验与归一', () => {
  it('只接受正数、最多 2 位小数', () => {
    expect([isValidMm('94'), isValidMm('141.2'), isValidMm('88.75')]).toEqual([true, true, true])
    expect([isValidMm(''), isValidMm('abc'), isValidMm('94.123'), isValidMm('-5'), isValidMm('94mm')])
      .toEqual([false, false, false, false, false])
  })
  it('0 不是正数，不算合格', () => {
    expect([isValidMm('0'), isValidMm('0.0')]).toEqual([false, false])
  })
  it('归一去掉尾随 0 与前导 0，但不动 0.5 这类真值', () => {
    expect(tidyMm('141.20')).toBe('141.2')
    expect(tidyMm('094')).toBe('94')
    expect(tidyMm('0.50')).toBe('0.5')
    expect(tidyMm('250')).toBe('250')
  })
})

describe('方形尺寸按「长x宽」保序，不重排（09-24 LQ 定「长乘宽」）', () => {
  it('长在前、宽在后 —— 与台账/库存名写法一致（200x140 / 184x128）', () => {
    expect(boxSizeText(fields({ boxLengthMm: '184', boxWidthMm: '128' }))).toBe('184x128')
    expect(boxSizeText(fields({ boxLengthMm: '200', boxWidthMm: '140' }))).toBe('200x140')
  })
  it('填反了不静默交换：原样输出，由校验当场拦下', () => {
    expect(boxSizeText(fields({ boxLengthMm: '128', boxWidthMm: '184' }))).toBe('128x184')
    expect(boxSizeProblem(fields({ boxLengthMm: '128', boxWidthMm: '184' }))).toBe('盒子长应为较大的一边（填反了？）')
    expect(boxSizeFieldProblem('boxLengthMm', fields({ boxLengthMm: '128', boxWidthMm: '184' })))
      .toBe('盒子长应为较大的一边（填反了？）')
  })
  it('正方形保留两个值，不压成单值（单值在清单里是圆口径）', () => {
    expect(boxSizeText(fields({ boxLengthMm: '94', boxWidthMm: '94' }))).toBe('94x94')
    expect(boxSizeProblem(fields({ boxLengthMm: '94', boxWidthMm: '94' }))).toBeNull()
  })
  it('落库的两个值原样保序，各自归一到最简写法', () => {
    expect(boxRectSides(fields({ boxLengthMm: '184', boxWidthMm: '128' }))).toEqual({ lengthMm: '184', widthMm: '128' })
    expect(boxRectSides(fields({ boxLengthMm: '094', boxWidthMm: '128.50' }))).toEqual({ lengthMm: '94', widthMm: '128.5' })
    expect(boxRectSides(fields({ boxLengthMm: '94' }))).toBeNull()
  })
  it('保留小数', () => {
    expect(boxSizeText(fields({ boxLengthMm: '201.4', boxWidthMm: '141.20' }))).toBe('201.4x141.2')
  })
  it('缺一个值就不给尺寸文本（由校验器拦下）', () => {
    expect(boxSizeText(fields({ boxLengthMm: '94' }))).toBe('')
  })
})

describe('圆形只填直径，尺寸文本是裸数字', () => {
  it('裸数字与清单里的圆口径形态一致（带 Φ 会比对不上）', () => {
    expect(boxSizeText(fields({ boxShape: 'ROUND', boxDiameterMm: '250' }))).toBe('250')
  })
  it('填了长宽也不参与 —— 形状说了算', () => {
    expect(boxSizeText(fields({ boxShape: 'ROUND', boxDiameterMm: '70', boxLengthMm: '999', boxWidthMm: '888' }))).toBe('70')
  })
})

describe('「其他」形状不进常规尺寸比对', () => {
  it('不产出尺寸文本，避免自由文本被抽成数字后误命中常规尺寸', () => {
    const other = fields({ boxShape: 'OTHER', boxShapeNote: '六角形', boxSizeNote: '对边 120mm' })
    expect(boxSizeText(other)).toBe('')
  })
  it('形状说明与尺寸描述都要填', () => {
    expect(boxSizeProblem(fields({ boxShape: 'OTHER', boxShapeNote: '六角形' }))).toBe('请填写尺寸描述')
    expect(boxSizeProblem(fields({ boxShape: 'OTHER', boxSizeNote: '对边 120mm' }))).toBe('请填写盒子形状说明')
    expect(boxSizeProblem(fields({ boxShape: 'OTHER', boxShapeNote: '六角形', boxSizeNote: '对边 120mm' }))).toBeNull()
  })
})

describe('校验只针对当前形状要填的字段', () => {
  it('没选形状先拦形状', () => {
    expect(boxSizeProblem(fields({ boxShape: '' }))).toBe('请选择盒子形状')
  })
  it('方形两个值缺一不可，非法数值给明确文案', () => {
    expect(boxSizeProblem(fields({ boxLengthMm: '94' }))).toBe('请填写盒子宽')
    expect(boxSizeProblem(fields({ boxWidthMm: '94' }))).toBe('请填写盒子长')
    expect(boxSizeProblem(fields({ boxLengthMm: '94', boxWidthMm: 'abc' }))).toBe('盒子宽只能是正数，最多 2 位小数')
    expect(boxSizeProblem(fields({ boxLengthMm: '94', boxWidthMm: '94' }))).toBeNull()
  })
  it('圆形不看长宽，只要直径', () => {
    expect(boxSizeProblem(fields({ boxShape: 'ROUND' }))).toBe('请填写盒子直径')
    expect(boxSizeProblem(fields({ boxShape: 'ROUND', boxDiameterMm: '250' }))).toBeNull()
    expect(boxSizeProblem(fields({ boxShape: 'ROUND', boxDiameterMm: '0' }))).toBe('盒子直径只能是正数，最多 2 位小数')
    expect(boxSizeProblem(fields({ boxShape: 'ROUND', boxDiameterMm: '2.5.5' }))).toBe('盒子直径只能是正数，最多 2 位小数')
  })
})

describe('单字段校验与整体校验同源（表单红框用前者）', () => {
  it('不属于当前形状的字段不冒错', () => {
    const round = fields({ boxShape: 'ROUND', boxDiameterMm: '250' })
    expect(boxSizeFieldProblem('boxLengthMm', round)).toBeNull()
    expect(boxSizeFieldProblem('boxSizeNote', round)).toBeNull()
    // 形状定了圆形，方形那一对值不该拦
    expect(boxSizeProblem(round)).toBeNull()
  })
  it('字段级文案与整体校验的首个问题一致', () => {
    const bad = fields({ boxLengthMm: '94', boxWidthMm: 'abc' })
    expect(boxSizeFieldProblem('boxWidthMm', bad)).toBe(boxSizeProblem(bad))
    const noShape = fields({ boxShape: '' })
    expect(boxSizeFieldProblem('boxShape', noShape)).toBe(boxSizeProblem(noShape))
  })
})

describe('规格摘要给人看，形状决定形态', () => {
  it('方形用 x 连接；圆形带 Φ 一眼看出是圆口径', () => {
    expect(specificationText(fields({ boxLengthMm: '184', boxWidthMm: '128' }), '60')).toBe('184x128，高60')
    expect(specificationText(fields({ boxShape: 'ROUND', boxDiameterMm: '250' }), '60')).toBe('Φ250，高60')
    expect(specificationText(fields({ boxShape: 'OTHER', boxShapeNote: '六角形', boxSizeNote: '对边 120mm' }), '60'))
      .toBe('六角形 对边 120mm，高60')
  })
  it('没填高度就只给尺寸，不拼出空段', () => {
    expect(specificationText(fields({ boxLengthMm: '94', boxWidthMm: '94' }), '')).toBe('94x94')
  })
})
