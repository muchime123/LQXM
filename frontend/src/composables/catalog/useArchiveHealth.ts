/**
 * Product archive health rules.
 *
 * The current workflow treats the main product as the source of truth. Variant/SKU
 * checks are intentionally excluded until the model layer is redesigned.
 */

import { ASSET_CATEGORY_KEYS } from '../../constants/fields'

export const ARCHIVE_HEALTH_RULE_VERSION = '2026-07-11-main-product-v1'

export interface ArchiveCheckItem {
  key: string
  label: string
  issueLabel: string
  description: string
  action: string
  actionLabel: string
  listFilter?: boolean
  detailOnly?: boolean
  evaluate: (ctx: ArchiveContext) => ArchiveCheckResult
}

export interface ArchiveCheckResult {
  done: boolean
  doneText: string
  todoText: string
}

export interface ArchiveContext {
  model: Record<string, unknown>
  assetCount: number
  mainImageCount: number
  requiredFieldCount: number
  completedRequiredFields: number
  specFieldCount: number
  completedSpecFields: number
}

export interface ModelWithId extends Record<string, unknown> {
  id: number | string
  assetCount?: number
  mainImageCount?: number
  defaultImage?: string
  name?: string
  modelCode?: string
  category?: string
  status?: string
}

const hasValue = (value: unknown): boolean => value != null && String(value).trim().length > 0

const requiredMainFields = (model: Record<string, unknown>): unknown[] => [
  model.name,
  model.category,
  model.status
]

const specFields = (_model: Record<string, unknown>): unknown[] => []

export const ARCHIVE_CHECKS: ArchiveCheckItem[] = [
  {
    key: 'mainImage',
    label: '产品主图',
    issueLabel: '缺主图',
    description: '需要至少维护一张产品主图，用于列表、详情和资料识别。',
    action: 'editProduct',
    actionLabel: '补充主图',
    listFilter: true,
    evaluate: ctx => ({
      done: Boolean(ctx.model.defaultImage || ctx.mainImageCount),
      doneText: `${ctx.mainImageCount || 1} 张主图`,
      todoText: '缺少产品主图'
    })
  },
  {
    key: 'baseInfo',
    label: '基础信息',
    issueLabel: '基础信息不完整',
    description: '主产品需要维护名称、设备大类、车间、状态和分类标签。',
    action: 'editProduct',
    actionLabel: '补全基础信息',
    listFilter: true,
    evaluate: ctx => ({
      done: ctx.completedRequiredFields === ctx.requiredFieldCount,
      doneText: '基础信息已完整',
      todoText: `${ctx.requiredFieldCount - ctx.completedRequiredFields} 个基础字段待补`
    })
  },
  {
    key: 'specParams',
    label: '规格参数',
    issueLabel: '缺参数',
    description: '建议维护电压、功率、尺寸、重量、包装速度、卷膜和封口尺寸等主产品参数。',
    action: 'editProduct',
    actionLabel: '补全参数',
    listFilter: true,
    evaluate: ctx => ({
      done: ctx.completedSpecFields >= Math.min(6, ctx.specFieldCount),
      doneText: `${ctx.completedSpecFields}/${ctx.specFieldCount} 个参数已填写`,
      todoText: `${Math.max(0, Math.min(6, ctx.specFieldCount) - ctx.completedSpecFields)} 个关键参数待补`
    })
  },
  {
    key: 'assets',
    label: '素材附件',
    issueLabel: '素材偏少',
    description: '建议至少补充主图、细节图、参数表、图纸或视频等资料素材。',
    action: 'assets',
    actionLabel: '补充素材',
    listFilter: true,
    evaluate: ctx => ({
      done: ctx.assetCount >= 3,
      doneText: `${ctx.assetCount} 个素材`,
      todoText: '素材数量偏少'
    })
  },
  {
    key: 'applicableScope',
    label: '适用范围',
    issueLabel: '缺适用范围',
    description: '建议填写适用范围，方便维护说明书附件和对外资料。',
    action: 'editProduct',
    actionLabel: '填写适用范围',
    detailOnly: true,
    evaluate: ctx => ({
      done: hasValue(ctx.model.applicableScope),
      doneText: '已填写适用范围',
      todoText: '缺少适用范围说明'
    })
  }
]

interface BuildArchiveContextOptions {
  variants?: Record<string, unknown>[]
  assets?: Record<string, unknown>[]
  variantCount?: number
  assetCount?: number
  mainImageCount?: number
  skuImageCount?: number
}

export function buildArchiveContext(
  model: Record<string, unknown>,
  options: BuildArchiveContextOptions = {}
): ArchiveContext {
  const assets = options.assets || []
  const requiredFields = requiredMainFields(model)
  const specs = specFields(model)
  return {
    model,
    assetCount: options.assetCount ?? assets.length ?? (model.assetCount as number) ?? 0,
    mainImageCount:
      options.mainImageCount ??
      assets.filter((asset: Record<string, unknown>) => asset.category === ASSET_CATEGORY_KEYS.mainImage).length,
    requiredFieldCount: requiredFields.length,
    completedRequiredFields: requiredFields.filter(hasValue).length,
    specFieldCount: specs.length,
    completedSpecFields: specs.filter(hasValue).length
  }
}

interface BuildArchiveChecksOptions {
  variants?: Record<string, unknown>[]
  assets?: Record<string, unknown>[]
  variantCount?: number
  assetCount?: number
  mainImageCount?: number
  skuImageCount?: number
  includeDetailOnly?: boolean
}

export function buildArchiveChecks(model: Record<string, unknown>, options: BuildArchiveChecksOptions = {}) {
  const context = buildArchiveContext(model, options)
  return ARCHIVE_CHECKS.filter(item => options.includeDetailOnly || !item.detailOnly).map(item => ({
    key: item.key,
    label: item.label,
    issueLabel: item.issueLabel,
    description: item.description,
    action: item.action,
    actionLabel: item.actionLabel,
    ...item.evaluate(context)
  }))
}

export interface ScoreResult {
  score: number
  level: 'good' | 'warning' | 'danger'
}

export function calcScoreFromChecks(checks: Array<{ done: boolean }>): ScoreResult {
  const allDone = checks.length > 0 && checks.every(item => item.done)
  if (allDone) return { score: 100, level: 'good' }
  if (checks.length === 0) return { score: 100, level: 'good' }
  const doneCount = checks.filter(item => item.done).length
  const score = Math.round((doneCount / checks.length) * 100)
  return {
    score,
    level: score >= 80 ? 'good' : score >= 60 ? 'warning' : 'danger'
  }
}

export interface HealthResult {
  issues: unknown[]
  score: ScoreResult
}

export function computeHealthFromChecks(checks: Array<{ done: boolean }>): HealthResult {
  const issues = checks.filter(item => !item.done)
  const score = calcScoreFromChecks(checks)
  return { issues, score }
}

export function useArchiveHealthCache() {
  const modelHealthCache = new Map<number | string, HealthResult & { _rev: string }>()

  function computeModelHealth(model: ModelWithId): HealthResult {
    const checks = buildArchiveChecks(model, {
      assetCount: model.assetCount || 0,
      mainImageCount: model.mainImageCount ?? (model.defaultImage ? 1 : 0)
    })
    return computeHealthFromChecks(checks)
  }

  function cacheModelHealth(models: ModelWithId[]): void {
    models.forEach(model => {
      const cacheKey = [
        ARCHIVE_HEALTH_RULE_VERSION,
        model.assetCount || 0,
        model.mainImageCount || 0,
        model.defaultImage || '',
        model.name || '',
        model.category || '',
        model.status || '',
        ''
      ].join('|')
      const existing = modelHealthCache.get(model.id)
      if (!existing || existing._rev !== cacheKey) {
        modelHealthCache.set(model.id, { ...computeModelHealth(model), _rev: cacheKey })
      }
    })
  }

  function getCachedHealth(modelId: number | string): (HealthResult & { _rev: string }) | undefined {
    return modelHealthCache.get(modelId)
  }

  function clearCache(): void {
    modelHealthCache.clear()
  }

  return {
    modelHealthCache,
    computeModelHealth,
    cacheModelHealth,
    getCachedHealth,
    clearCache
  }
}

export interface TodoDefinition {
  key: string
  issueLabel: string
}

export interface ListHealthFilter {
  key: string
  label: string
  count: number
}

export interface ListHealthGroup {
  key: string
  issueLabel: string
  label: string
  items: ModelWithId[]
}

export interface ListHealthSummary {
  filters: ListHealthFilter[]
  groups: ListHealthGroup[]
  totalTodo: number
}

export function computeListHealthSummary(
  models: ModelWithId[],
  getCachedHealth: (modelId: number | string) => (HealthResult & { _rev: string }) | undefined,
  todoDefinitions: TodoDefinition[]
): ListHealthSummary {
  const issueCounts = new Map<string, number>()
  const modelIdsWithIssues = new Set<number | string>()
  const groupItemsMap = new Map<string, ModelWithId[]>(todoDefinitions.map(def => [def.key, []]))

  models.forEach(model => {
    const health = getCachedHealth(model.id)
    const issues = health?.issues ?? []
    if (issues.length > 0) {
      modelIdsWithIssues.add(model.id)
    }
    issues.forEach(issue => {
      const issueKey = (issue as { key: string }).key
      issueCounts.set(issueKey, (issueCounts.get(issueKey) || 0) + 1)
      const items = groupItemsMap.get(issueKey)
      if (items) {
        items.push(model)
      }
    })
  })

  const filters: ListHealthFilter[] = [
    { key: 'all', label: '全部', count: models.length },
    { key: 'needs', label: '待完善', count: modelIdsWithIssues.size },
    ...todoDefinitions.map(def => ({ key: def.key, label: def.issueLabel, count: issueCounts.get(def.key) || 0 }))
  ]

  const groups: ListHealthGroup[] = todoDefinitions
    .map(def => ({
      ...def,
      label: def.issueLabel,
      items: groupItemsMap.get(def.key) || []
    }))
    .filter(g => g.items.length > 0)

  return { filters, groups, totalTodo: modelIdsWithIssues.size }
}
