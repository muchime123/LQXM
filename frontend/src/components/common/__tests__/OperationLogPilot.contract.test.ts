import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * B6-1 试点页面（OperationLogView）标准列表契约。
 *
 * 为什么放在本目录而不是页面同级 __tests__/：实测在 `src/apps/archive/views/system/`
 * 下**新建** `__tests__/` 目录并放入测试文件后，全量 `npx vitest run` 会在收集期整体崩溃
 * （78 文件全部 "Vitest failed to find the runner"，纯 util 用例亦不例外；排除该文件后
 * 立即恢复 701 passed / 2 failed）。改用既有测试目录后问题消失，故暂放此处。
 *
 * 为什么是源码级而不是挂载式：本轮先写了挂载 OperationLogView 的集成测试（含 Element Plus
 * 插件），同样会触发上述崩溃；页面集成验证因此改由
 * e2e/operation-log-standard-list.spec.ts 在真实浏览器中承担（9/9 通过）。
 * 这里保留确定性的源码契约，防止本轮修复被无意回退（与 PublicStyleTokens.contract.test.ts 同类）。
 */

const VIEW = resolve(__dirname, '../../../apps/archive/views/system/OperationLogView.vue')
const source = readFileSync(VIEW, 'utf-8')
const code = source.replace(/\/\*[\s\S]*?\*\//g, '')

describe('OperationLogView 标准列表契约（B6-1 试点）', () => {
  it('复用公共页面骨架 ListPageLayout，且开启 show-header', () => {
    expect(source).toContain('<ListPageLayout')
    // 关键：showHeader 默认为 false，缺少该属性会让 title / description / #eyebrow 全部成为死参数
    expect(source).toMatch(/<ListPageLayout[\s\S]*?\sshow-header[\s\S]*?>/)
  })

  it('页面头部只由公共 .page-surface-header 承载，不手抄 page-header 外观', () => {
    expect(code).not.toMatch(/\.page-header\s*\{/)
    expect(source).toContain('#eyebrow')
  })

  it('复用 SearchToolbar / DataState / LqTable / LqPagination 四大公共组件', () => {
    for (const component of ['SearchToolbar', 'DataState', 'LqTable', 'LqPagination']) {
      expect(source, `缺少公共组件 ${component}`).toContain(`<${component}`)
    }
  })

  it('「清除筛选」显隐绑定到页面 hasActiveFilters（含日期区间，工具栏本身感知不到）', () => {
    expect(source).toMatch(/:clear-visible="hasActiveFilters"/)
  })

  it('分页使用 useListPagination 的 0 基协议，不在页面手写 page±1', () => {
    expect(source).toContain('useListPagination')
    expect(source).toContain('getPageParams')
  })

  it('页面专属样式仅使用设计令牌（无十六进制色值 / px 圆角）', () => {
    // 用剔除注释后的源码切片：注释里可能提及色值（说明性文字），不构成硬编码
    const styleBlock = code.slice(code.indexOf('<style'))
    expect(styleBlock).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(styleBlock).not.toMatch(/border-radius:\s*\d+px/)
  })

  it('变更摘要代码块保留为页面专属样式（业务特有视觉，不上收为公共组件）', () => {
    expect(source).toContain('.changes-pre')
    expect(source).toContain('white-space: pre-wrap')
  })
})
