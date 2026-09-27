/**
 * 盒子形状与尺寸录入的**唯一实现**（2026-09-24，LQ 定「先选形状，再按形状填尺寸」）。
 *
 * 背景：盒子尺寸原来是一个自由文本框，业务员写法五花八门（`94×94` / `94*94` / `2014` /
 * `Φ250` / `250`），而后端要拿它去比对「车间常规尺寸」判据表（`shop_standard_sizes`，
 * 见 V339）来判定这一单算不算「订做」。写法不统一 → 同一个盒子会被认出多个尺寸。
 *
 * 所以改成：**形状先行，形状决定录哪几个数值**。
 *   - 方形（RECT）→ 长 + 宽两个值
 *   - 圆形（ROUND）→ 直径一个值
 *   - 其他（OTHER）→ 形状说明 + 尺寸描述（自由文本）
 *
 * 口径纪律：
 * 1. 形状取值用**稳定代码** `RECT` / `ROUND` / `OTHER`，与 `shop_standard_sizes.shape_type`
 *    同一套词表（禁自造第二套中文枚举）。
 * 2. 方盒的 `boxSize` 文本是**「长x宽」**（长 = 较大的一边）—— 与台账/库存名的实际写法一致
 *    （`200x140`、`330x225`、`184x128`；简写 `2014` 就是「20x14」压掉乘号，长在前）。
 *    ⚠️ **不做大小重排**（2026-09-24 LQ 定「长乘宽」）：重排会静默改掉业务员的录入顺序，
 *    把「填反了」这件事掩盖掉；填反由校验当场拦下，库里只保留「长x宽」一种形态。
 * 3. 圆盒的 `boxSize` 文本是**裸数字**（`250`，不带 φ/Φ）。这是照 `normalizeSize` 与
 *    V339 清单的实际形态对齐的（清单里是 `圆|250`）；写成 `Φ250` 会让判定比对不上。
 * 4. 「其他」形状**不产出 `boxSize`**（留空）。异形必然不在常规尺寸表里，留空才能让它
 *    稳定落到「非标/订做」分支；若把自由文本塞进 boxSize，`对边120mm` 这种会被抽成 `120`
 *    从而**误命中**常规尺寸。尺寸描述另存 `boxSizeNote`。
 *
 * ⚠️ 本文件只负责**录入侧的形态规范**，不生成产品身份键/显示名（那是后端
 * `ProductIdentityKeys` 的职责，前端禁自造第二套）。
 */

/** 盒子形状：与 `shop_standard_sizes.shape_type` 同词表。 */
export type BoxShape = 'RECT' | 'ROUND' | 'OTHER'

export const BOX_SHAPES: ReadonlyArray<{ value: BoxShape; label: string }> = [
  { value: 'RECT', label: '方形' },
  { value: 'ROUND', label: '圆形' },
  { value: 'OTHER', label: '其他' }
]

/** 盒子形状/尺寸的录入字段（与提交体 `productConfig` 的同名字段一一对应）。 */
export interface BoxSizeFields {
  boxShape: BoxShape | ''
  /** 方形：长（较大的一边，mm） */
  boxLengthMm: string
  /** 方形：宽（较小的一边，mm） */
  boxWidthMm: string
  /** 圆形：直径（mm） */
  boxDiameterMm: string
  /** 其他：形状说明（如「六角形」） */
  boxShapeNote: string
  /** 其他：尺寸描述（如「对边 120mm」） */
  boxSizeNote: string
}

/** 毫米数值：正数，最多 2 位小数（与氚云及台账的实际写法一致）。 */
const MM_PATTERN = /^\d+(\.\d{1,2})?$/

/** 历史快照里的中文形状值 → 稳定代码（只读兼容；新数据一律写代码）。 */
const LEGACY_SHAPE_LABEL: Record<string, string> = {
  RECT: '方形', ROUND: '圆形', OTHER: '其他',
  方形: '方形', 圆形: '圆形', 其他: '其他',
  方: '方形', 圆: '圆形'
}

/** 形状值 → 中文标签。兼容历史快照里的中文值（`方形`/`圆形`/`其他`）。 */
export function boxShapeLabel(value: unknown): string {
  const raw = String(value ?? '').trim()
  if (!raw) return ''
  return LEGACY_SHAPE_LABEL[raw] ?? raw
}

/** 是否是一个合格的毫米输入（正数、最多 2 位小数）。空串与 0 都不算合格。 */
export function isValidMm(value: string): boolean {
  const raw = tidyRaw(value)
  if (!MM_PATTERN.test(raw)) return false
  return Number(raw) > 0
}

/** 去掉首尾/内部空白，供校验与归一使用。 */
function tidyRaw(value: string): string {
  return String(value ?? '').replace(/[\s\u3000\u00a0]/g, '')
}

/**
 * 毫米数值归一：去空白 → 去尾随 0 与孤立小数点（`141.20` → `141.2`）→ 去前导 0（`094` → `94`，
 * 但 `0.5` 保持不变）。不合法的输入原样返回，交给校验器报错，不在这里静默改写。
 */
export function tidyMm(value: string): string {
  const raw = tidyRaw(value)
  if (!MM_PATTERN.test(raw)) return raw
  let out = raw
  if (out.includes('.')) out = out.replace(/0+$/, '').replace(/\.$/, '')
  out = out.replace(/^0+(?=\d)/, '')
  return out
}

/**
 * 方形两边的落库值：**长、宽原样**（长 = 较大的一边，由校验保证）。
 *
 * 与台账/库存名的写法同源 —— 历史里方盒一律「长x宽」（`200x140` / `330x225` / `184x128`），
 * `2014` 这类简写也是「20x14」压掉乘号、长在前。
 *
 * ⚠️ 这里**不重排**（2026-09-24 LQ 定）：重排会把业务员填反的顺序静默纠正，看起来"更方便"，
 * 但掩盖了录入错误，且与原文脱钩。填反由 {@link boxSizeFieldProblem} 当场报错要求改正。
 * 任一值不合法时返回 `null`。
 */
export function boxRectSides(fields: BoxSizeFields): { lengthMm: string; widthMm: string } | null {
  if (!isValidMm(fields.boxLengthMm) || !isValidMm(fields.boxWidthMm)) return null
  return { lengthMm: tidyMm(fields.boxLengthMm), widthMm: tidyMm(fields.boxWidthMm) }
}

/**
 * 规范尺寸文本（提交体的 `boxSize`，也是后续判定「常规/订做」的入参）。
 *
 * - 方形 → `长x宽`（长 = 较大的一边，如 `200x140`；正方形如 `94x94`）
 * - 圆形 → 裸直径（`250`）
 * - 其他 → `''`（异形不进常规尺寸比对，见文件头口径 4）
 */
export function boxSizeText(fields: BoxSizeFields): string {
  if (fields.boxShape === 'RECT') {
    const sides = boxRectSides(fields)
    return sides ? `${sides.lengthMm}x${sides.widthMm}` : ''
  }
  if (fields.boxShape === 'ROUND') {
    return isValidMm(fields.boxDiameterMm) ? tidyMm(fields.boxDiameterMm) : ''
  }
  return ''
}

/** 形状/尺寸字段的校验顺序（整体校验取第一个非空结果）。 */
const FIELD_ORDER: ReadonlyArray<keyof BoxSizeFields> = [
  'boxShape', 'boxLengthMm', 'boxWidthMm', 'boxDiameterMm', 'boxShapeNote', 'boxSizeNote'
]

/**
 * **单字段**校验（给表单红框提示用）：返回该字段自己的错误文案，`null` = 通过。
 *
 * ⚠️ 判断逻辑只写在这里 —— 整体校验 {@link boxSizeProblem} 由它组合而成，两处文案必然一致。
 */
export function boxSizeFieldProblem(field: keyof BoxSizeFields, fields: BoxSizeFields): string | null {
  if (field === 'boxShape') return fields.boxShape ? null : '请选择盒子形状'
  if (fields.boxShape === 'RECT') {
    if (field !== 'boxLengthMm' && field !== 'boxWidthMm') return null
    const label = field === 'boxLengthMm' ? '盒子长' : '盒子宽'
    const value = field === 'boxLengthMm' ? fields.boxLengthMm : fields.boxWidthMm
    if (!tidyMm(value)) return `请填写${label}`
    if (!isValidMm(value)) return `${label}只能是正数，最多 2 位小数`
    // 长 = 较大的一边（与台账写法 `200x140` 一致）。填反了当场要求改正，**不静默交换** ——
    // 静默交换会让同一盒子的两种填法落库成同一串，反而掩盖录入错误。
    if (field === 'boxLengthMm' && isValidMm(fields.boxWidthMm)
      && Number(tidyMm(fields.boxLengthMm)) < Number(tidyMm(fields.boxWidthMm))) {
      return '盒子长应为较大的一边（填反了？）'
    }
    return null
  }
  if (fields.boxShape === 'ROUND') {
    if (field !== 'boxDiameterMm') return null
    if (!tidyMm(fields.boxDiameterMm)) return '请填写盒子直径'
    return isValidMm(fields.boxDiameterMm) ? null : '盒子直径只能是正数，最多 2 位小数'
  }
  if (fields.boxShape === 'OTHER') {
    if (field === 'boxShapeNote') return fields.boxShapeNote.trim() ? null : '请填写盒子形状说明'
    if (field === 'boxSizeNote') return fields.boxSizeNote.trim() ? null : '请填写尺寸描述'
  }
  return null
}

/**
 * 整体校验：返回错误文案（`null` = 通过）。
 *
 * 按形状只校验**该形状真正要填**的字段，所以即使对应表单项没有渲染也不会漏校验。
 */
export function boxSizeProblem(fields: BoxSizeFields): string | null {
  for (const field of FIELD_ORDER) {
    const problem = boxSizeFieldProblem(field, fields)
    if (problem) return problem
  }
  return null
}

/**
 * 人读规格摘要（提交体的 `specification`）。
 *
 * - 方形 → `94x94，高60`
 * - 圆形 → `Φ250，高60`（这里带 Φ：给人看的场景要一眼看出是圆口径，与用于比对的裸数字分工不同）
 * - 其他 → `六角形 对边120mm，高60`
 */
export function specificationText(fields: BoxSizeFields, boxHeight = ''): string {
  const parts: string[] = []
  if (fields.boxShape === 'RECT') {
    const size = boxSizeText(fields)
    if (size) parts.push(size)
  } else if (fields.boxShape === 'ROUND') {
    const diameter = boxSizeText(fields)
    if (diameter) parts.push(`Φ${diameter}`)
  } else if (fields.boxShape === 'OTHER') {
    const note = [fields.boxShapeNote.trim(), fields.boxSizeNote.trim()].filter(Boolean).join(' ')
    if (note) parts.push(note)
  }
  const height = String(boxHeight ?? '').trim()
  if (height) parts.push(`高${height}`)
  return parts.join('，')
}
