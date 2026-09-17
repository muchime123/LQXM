// ====== 形状归一与关联推荐评分 ======
// 供 SpecMoldLinksPanel（规格侧选模具）与 useMoldVariantLink（模具侧选规格）双向复用，
// 评分口径与 PackagingMoldImportService.matchVariants 对齐：
// - 形状优先：纯圆口径禁匹配方盒规格，纯方盒口径禁匹配圆杯规格（不匹配返回 -1 排除）
// - 三级评分：完美 > 严格容差 > 宽松容差 > 不命中
// - 权重：length 40 / width 40 / diameter 50 / height 20

/** shapeType → 标准形状键 */
const SHAPE_ALIAS: Record<string, string[]> = {
  square: ['方盒', '方杯', '托盘', '方', '矩形', '长方', '正方', 'square', 'rectangle'],
  round: ['圆杯', '圆筒', '碗', '桶', '圆', '碟', '盅', 'round', 'circle'],
  bowl: ['碗', '餐盒', '深盒'],
  cover: ['盖', '杯盖', '盖膜', '密封盖'],
  film: ['封口膜', '易撕膜', '压花膜']
}

export function normalizeShape(value?: string | null): string | null {
  if (!value) return null
  const v = String(value).toLowerCase()
  for (const key of Object.keys(SHAPE_ALIAS)) {
    if (SHAPE_ALIAS[key].some(alias => v.includes(alias.toLowerCase()))) return key
  }
  if (value.includes('方')) return 'square'
  if (value.includes('圆') || value.includes('杯') || value.includes('碗') || value.includes('桶')) return 'round'
  if (value.includes('盖')) return 'cover'
  return null
}

/** 两形状是否匹配（任一为 null 时返回 true，避免排除未知） */
export function shapeMatch(a?: string | null, b?: string | null): boolean {
  const na = normalizeShape(a)
  const nb = normalizeShape(b)
  if (!na || !nb) return true
  return na === nb
}

// ====== 尺寸解析 ======

/** 从字符串中提取第一个数字（与后端 parseDim 对齐） */
export function parseDim(s?: string | null): number | null {
  if (!s) return null
  const m = String(s).match(/-?\d+(\.\d+)?/)
  return m ? parseFloat(m[0]) : null
}

/** 从口径字符串解析出圆形直径或方盒长宽 */
export function parseCaliber(caliber?: string | null): { diameter?: number; length?: number; width?: number } {
  if (!caliber) return {}
  const s = String(caliber).trim()
  // 圆形口径：C / Ø / Φ / R 前缀，或纯数字
  if (/^[CØΦRcøφ]/.test(s) || /^\d+(\.\d+)?$/.test(s.replace(/mm$/i, '').trim())) {
    const d = parseDim(s)
    return d != null ? { diameter: d } : {}
  }
  // 方盒口径：分隔符 × * x X ＊
  const parts = s.split(/[×*xX＊]/).map(p => parseDim(p))
  if (parts.length >= 2 && parts[0] != null && parts[1] != null) {
    return { length: parts[0], width: parts[1] }
  }
  return {}
}

/** 从高度字符串解析出所有高度值（取第一个用于评分，或找最接近的） */
export function parseHeights(height?: string | null): number[] {
  if (!height) return []
  return String(height)
    .split(/[\/+＋、，;；\s]+/)
    .map(p => parseDim(p))
    .filter((v): v is number => v != null)
}

// ====== 三级评分（与后端 scoreDim 对齐） ======

interface ScoreResult {
  score: number
  strict: boolean
  loose: boolean
}

/**
 * 三级评分：完美(≤0.01) > 严格容差(线性衰减) > 宽松容差(最多一半) > 未命中(0)
 * 与 PackagingMoldImportService.scoreDim 逻辑一致。
 */
function scoreDimension(delta: number, strictTol: number, looseTol: number, maxScore: number): ScoreResult {
  if (delta <= 0.01) return { score: maxScore, strict: true, loose: false }
  if (delta <= strictTol) {
    return { score: maxScore * (1 - delta / strictTol), strict: true, loose: false }
  }
  if (delta <= looseTol) {
    const ratio = (delta - strictTol) / (looseTol - strictTol)
    return { score: maxScore * 0.5 * (1 - ratio), strict: false, loose: true }
  }
  return { score: 0, strict: false, loose: false }
}

// 容差与权重常量（与后端对齐）
const DIMENSION_TOLERANCE = 2.0        // 长宽/直径严格容差 mm
const DIMENSION_TOLERANCE_LOOSE = 6.0  // 长宽/直径宽松容差 mm
const HEIGHT_TOLERANCE = 0.5           // 高度严格容差 mm
const HEIGHT_TOLERANCE_LOOSE = 2.0     // 高度宽松容差 mm

// ====== 模具尺寸信息（从 representativeCaliber/rawCaliber + representativeHeight/rawHeight 解析） ======

export interface MoldDimensionInfo {
  /** 口径字符串（representativeCaliber 或 rawCaliber） */
  caliber?: string | null
  /** 高度字符串（representativeHeight 或 rawHeight） */
  height?: string | null
}

export interface VariantDimensionInfo {
  lengthMm?: string | null
  widthMm?: string | null
  topDiameterMm?: string | null
  heightMm?: string | null
}

/**
 * 计算模具与规格的尺寸匹配评分（满分 100）。
 * 方盒：length 40 + width 40 + height 20 = 100
 * 圆杯：diameter 50 + height 20 = 70
 * 与后端 matchVariants 评分权重一致。
 */
function dimensionScore(
  moldDim: MoldDimensionInfo | null | undefined,
  variantDim: VariantDimensionInfo | null | undefined
): number {
  if (!moldDim || !variantDim) return 0

  const moldCaliber = parseCaliber(moldDim.caliber)
  const moldHeights = parseHeights(moldDim.height)
  const vLen = parseDim(variantDim.lengthMm)
  const vWidth = parseDim(variantDim.widthMm)
  const vDia = parseDim(variantDim.topDiameterMm)
  const vHeight = parseDim(variantDim.heightMm)

  let score = 0

  // 方盒匹配（长/宽）
  if (moldCaliber.length != null && moldCaliber.width != null) {
    if (vLen != null) {
      score += scoreDimension(Math.abs(vLen - moldCaliber.length), DIMENSION_TOLERANCE, DIMENSION_TOLERANCE_LOOSE, 40).score
    }
    if (vWidth != null) {
      score += scoreDimension(Math.abs(vWidth - moldCaliber.width), DIMENSION_TOLERANCE, DIMENSION_TOLERANCE_LOOSE, 40).score
    }
  }
  // 圆杯匹配（直径）
  else if (moldCaliber.diameter != null && vDia != null) {
    score += scoreDimension(Math.abs(vDia - moldCaliber.diameter), DIMENSION_TOLERANCE, DIMENSION_TOLERANCE_LOOSE, 50).score
  }

  // 高度匹配（从 mold 的多高度中找最接近 variant 高度的值）
  // 与后端 matchVariants 对齐：
  // - 命中严格/宽松容差：按三级评分加分
  // - 连宽松容差都不命中：罚 -15
  // - 模具有高度但规格无高度：+3（鼓励保留有高度数据的候选）
  if (moldHeights.length > 0 && vHeight != null) {
    const closestHeight = moldHeights.reduce((prev, curr) =>
      Math.abs(curr - vHeight) < Math.abs(prev - vHeight) ? curr : prev
    )
    const r = scoreDimension(Math.abs(vHeight - closestHeight), HEIGHT_TOLERANCE, HEIGHT_TOLERANCE_LOOSE, 20)
    if (r.strict || r.loose) {
      score += r.score
    } else {
      score -= 15
    }
  } else if (moldHeights.length > 0) {
    score += 3
  }

  return score
}

// ====== 推荐评分 ======

/**
 * 模具→规格推荐评分（模具侧选规格时使用）
 * 形状不匹配返回 -1（排除）；否则返回尺寸评分 + 类别加分。
 * 与后端 matchVariants 对齐：形状优先 + 三级尺寸评分。
 */
export function scoreVariantForMold(
  v: VariantDimensionInfo & { shapeType?: string | null },
  mold: ({ moldCategory?: string | null; moldTypeCode?: string | null } & MoldDimensionInfo) | null | undefined
): number {
  if (!mold) return 0
  // 形状硬过滤：不匹配时排除（与后端 pureRound/pureSquare 过滤对齐）
  if (!shapeMatch(v.shapeType, mold.moldCategory)) return -1
  // 尺寸评分
  let s = dimensionScore(mold, v)
  // 类别兜底加分（形状匹配但无尺寸数据时，保证有基础分）
  if (s === 0) {
    const cat = (mold.moldCategory || '').toLowerCase()
    if (cat.includes('桶') || cat.includes('碗')) s += 5
    if (mold.moldTypeCode) s += 5
  }
  return s
}

/**
 * 规格→模具推荐评分（规格侧选模具时使用）
 * 形状不匹配返回 -1（排除）；否则返回尺寸评分 + 状态/角色加分。
 * 与 scoreVariantForMold 对称，与后端 matchVariants 对齐。
 */
export function scoreMoldForVariant(
  m: ({ moldCategory?: string | null; status?: string | null; moldRole?: string | null; moldTypeCode?: string | null } & MoldDimensionInfo),
  variant: (VariantDimensionInfo & { shapeType?: string | null }) | null | undefined
): number {
  if (!variant) return 0
  // 形状硬过滤
  if (!shapeMatch(variant.shapeType, m.moldCategory)) return -1
  // 尺寸评分
  let s = dimensionScore(m, variant)
  // 状态/角色加分（尺寸评分相同时，优先选在用主模）
  if (!m.status || m.status === 'in_use') s += 20
  if (m.moldRole === 'main' || m.moldRole === '主模') s += 10
  if (s === 0 && m.moldTypeCode) s += 5  // 无尺寸数据时的兜底
  return s
}

// ====== 推荐徽标 ======

/** 推荐徽标文字（规格→模具场景使用） */
export function moldRecommendBadge(
  m: { moldCategory?: string | null; status?: string | null },
  variant: { shapeType?: string | null } | null | undefined
): string | null {
  const match = variant ? shapeMatch(variant.shapeType, m.moldCategory) && variant.shapeType != null : false
  const stopped = m.status === 'stopped' || m.status === 'repair'
  if (match && !stopped) return '⭐推荐'
  if (match) return '✓形状匹配'
  if (stopped) return '⚠停用'
  return null
}
