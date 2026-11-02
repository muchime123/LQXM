import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * B7-B1b：搜索 / 筛选 / 分页联动契约。
 *
 * 为什么是源码级而不是挂载式：与本目录 `OperationLogPilot.contract.test.ts` 同因——
 * 在本仓库里新建 `src/apps/archive/views/**\/__tests__/` 并在其中挂载业务页面，
 * 会在收集期触发 Vitest 崩溃（根因见 B7-B0：Windows 盘符大小写导致的模块双实例，
 * 与文件数量无关）。页面级行为验证由 e2e/b7-b1b-search-pagination.spec.ts
 * 在真实浏览器 + 真实 XHR 上承担。
 *
 * 这里钉死的是**接线契约**，而非具体命名：
 *   1. 任何「改变查询条件」的入口（搜索 / 筛选变更 / 单个标签移除 / 清除筛选），
 *      其处理链路最终必须调用 `resetPage()`；
 *   2. 「翻页」入口的处理链路**不得**调用 `resetPage()`
 *      —— 页码由 LqPagination 内部回写，再复位就会被弹回第 1 页。
 */

interface Case {
  /** 页面名（用于断言信息） */
  name: string
  /** 相对测试目录的源文件路径 */
  file: string
  /** 「改变查询条件」的事件名集合 */
  conditionEvents: string[]
  /** 翻页事件名 */
  paginationEvent: string
  /** 翻页处理器所在标签（用于精确定位） */
  paginationTag?: string
}

const CASES: Case[] = [
  {
    name: 'WebsiteInquiryView',
    file: '../../../apps/archive/views/marketing/WebsiteInquiryView.vue',
    conditionEvents: ['search', 'filter-change', 'clear'],
    paginationEvent: 'change'
  },
  {
    name: 'CertificateView',
    file: '../../../apps/archive/views/product/CertificateView.vue',
    conditionEvents: ['search', 'filter-change', 'clear'],
    paginationEvent: 'change'
  },
  {
    name: 'CustomerView',
    file: '../../../apps/archive/views/customer/CustomerView.vue',
    conditionEvents: ['search', 'filter-change', 'clear'],
    paginationEvent: 'pagination-change'
  },
  {
    // B6-2 试点页：作为「正确接线」的对照，防止 B7-B1a 的表单改动回退了联动
    name: 'OfficeAssetView',
    file: '../../../apps/archive/views/product/OfficeAssetView.vue',
    conditionEvents: ['search', 'filter-change', 'clear'],
    paginationEvent: 'change'
  }
]

/** 取出 `function NAME(...) { ... }` 的源码（按花括号配平） */
function functionSource(source: string, name: string): string {
  const match = source.match(new RegExp(`function\\s+${name}\\s*\\(`))
  if (!match || match.index === undefined) return ''
  const bodyStart = source.indexOf('{', match.index)
  if (bodyStart < 0) return ''
  let depth = 0
  for (let i = bodyStart; i < source.length; i++) {
    const ch = source[i]
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return source.slice(bodyStart, i + 1)
    }
  }
  return ''
}

/**
 * 展开一条处理链路：把函数体内调用到的**本文件内**其它函数的源码一并纳入，
 * 最多展开两层（`handleResetFilters` → `applyFilters` → `resetPage` 这类）。
 */
function expand(source: string, name: string, depth = 2): string {
  let acc = functionSource(source, name)
  if (!acc || depth === 0) return acc
  const called = new Set<string>()
  // 取全部标识符：既要覆盖 `foo()` 形式的调用，也要覆盖作为回调传入的 `resetFilters(applyFilters)`
  for (const m of acc.matchAll(/\b([A-Za-z_$][\w$]*)\b/g)) called.add(m[1])
  for (const callee of called) {
    if (callee === name) continue
    const body = functionSource(source, callee)
    if (body && body !== acc && !acc.includes(body)) acc += '\n' + body
  }
  return acc
}

function handlerOf(source: string, event: string, tag?: string): string {
  const scope = tag ? tagSource(source, tag) ?? source : source
  const m = scope.match(new RegExp(`@${event}="([\\w$]+)"`))
  return m?.[1] ?? ''
}

/** 取出某个标签的完整片段（按尖括号配平，忽略属性里的 >） */
function tagSource(source: string, tag: string): string | null {
  const start = source.indexOf('<' + tag)
  if (start < 0) return null
  let i = start
  let depth = 0
  let inAttr = false
  let quote = ''
  for (; i < source.length; i++) {
    const ch = source[i]
    if (quote) {
      if (ch === quote) quote = ''
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      continue
    }
    if (ch === '{' || ch === '}') inAttr = ch === '{'
    if (ch === '<') depth++
    else if (ch === '>') {
      if (depth === 0 && !inAttr) break
    }
    else if (ch === '/' && source[i + 1] === '>') break
  }
  return source.slice(start, i + 1)
}

describe('搜索 / 筛选 / 分页联动契约（B7-B1b）', () => {
  for (const item of CASES) {
    const source = readFileSync(resolve(__dirname, item.file), 'utf8')

    describe(item.name, () => {
      it('改查询条件的每个入口最终都复位页码', () => {
        for (const event of item.conditionEvents) {
          const handler = handlerOf(source, event)
          expect(handler, `${item.name}：未绑定 @${event}`).not.toBe('')
          const chain = expand(source, handler)
          expect(chain, `${item.name}：@${event} → ${handler}() 未复位页码`).toContain('resetPage')
        }
      })

      it('翻页入口不复位页码（否则会被弹回第 1 页）', () => {
        const handler = handlerOf(source, item.paginationEvent, item.paginationTag)
        expect(handler, `${item.name}：未绑定 @${item.paginationEvent}`).not.toBe('')
        const chain = expand(source, handler)
        expect(chain, `${item.name}：@${item.paginationEvent} → ${handler}() 不应复位页码`).not.toContain('resetPage')
      })
    })
  }
})
