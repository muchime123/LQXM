import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 「生产跟单列表默认可见列」的前后端契约。
 *
 * 列 key 在两侧各有一份清单：前端 `LIST_COLUMN_DEFS`（决定渲染与勾选顺序）、
 * 后端 `ProductionTrackingService.LIST_COLUMN_KEYS`（决定默认列配置的合法取值）。
 * 两边漂移**不会报错**，只会变成线上才现的静默故障：
 *   - 后端多一列 → 前端保存时永远不提交它，那一列等于没配；
 *   - 前端多一列 → 保存整个矩阵时后端报「列标识无效」，管理员直接存不上。
 * 所以直接读后端源码比对，把漂移钉在构建期（而不是等真机点一次才发现）。
 *
 * 2026-10 起前端列清单从页面搬到 `useTrackingListColumns`（列表列显隐整体下沉），
 * 故这里改读那个模块 —— 标记文本未变，契约本身不动。
 */
const here = dirname(fileURLToPath(import.meta.url))
const viewSource = readFileSync(
  resolve(here, '../composables/tracking/useTrackingListColumns.ts'), 'utf8')
const serviceSource = readFileSync(
  resolve(here, '../../../backend/src/main/java/com/liqiang/archive/service/ProductionTrackingService.java'), 'utf8')

/** 截块再抓，避免误抓同一个文件里其它同类字面量。 */
function slice(source: string, startMarker: string, endMarker: string) {
  const block = source.split(startMarker)[1]
  if (block === undefined) throw new Error(`未找到起点标记：${startMarker}`)
  return block.split(endMarker)[0]
}
function matchAll(text: string, pattern: RegExp) {
  return [...text.matchAll(pattern)].map(match => match[1])
}

const frontendColumnKeys = () =>
  matchAll(slice(viewSource, 'const LIST_COLUMN_DEFS', 'const LIST_COLUMNS_HIDDEN_KEY'),
    /\{ key: '([A-Za-z0-9_]+)', label: '/g)
const backendColumnKeys = () =>
  matchAll(slice(serviceSource, 'static final List<String> LIST_COLUMN_KEYS=List.of(', ');'),
    /"([A-Za-z0-9_]+)"/g)
const frontendFallbackHidden = () =>
  matchAll(slice(viewSource, 'const DEFAULT_HIDDEN_LIST_COLUMNS = [', ']'), /'([A-Za-z0-9_]+)'/g)
const backendFallbackHidden = () =>
  matchAll(slice(serviceSource, 'static final Set<String> LIST_COLUMN_FALLBACK_HIDDEN=Set.of(', ');'),
    /"([A-Za-z0-9_]+)"/g)

describe('生产跟单列表列清单：前后端契约', () => {
  it('前后端列 key 完全一致（含顺序）', () => {
    const frontend = frontendColumnKeys()
    expect(frontend.length).toBeGreaterThan(10)
    expect(frontend).toEqual(backendColumnKeys())
  })

  it('前端默认隐藏名单与后端兜底名单一致（同一份口径，两边各写一次）', () => {
    expect(frontendFallbackHidden().sort()).toEqual(backendFallbackHidden().sort())
  })

  it('兜底隐藏的每一列都是合法列', () => {
    const keys = frontendColumnKeys()
    backendFallbackHidden().forEach(key => expect(keys).toContain(key))
  })
})
