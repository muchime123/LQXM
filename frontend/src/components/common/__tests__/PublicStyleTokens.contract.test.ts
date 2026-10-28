import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * B5-B2 公共样式与设计令牌治理契约
 *
 * 目的：把 B5-B 治理成果固化为可回归的自动化约束，防止后续改动把已收敛的
 * 令牌引用再次退回硬编码，或在未同步深色主题的情况下新增颜色类令牌。
 *
 * 约束范围仅限「公共层」：
 *   - styles/modules/{drawer-layout,dialog-layout,list-page-surfaces}.css
 *   - styles/tokens.css、styles/themes/dark.css
 *   - components/common/{PageHeader,ListPageLayout}.vue
 * 不约束业务页面 scoped 样式（那是 B6 迁移阶段的范围）。
 */

const STYLES = resolve(__dirname, '../../../styles')
const COMMON = resolve(__dirname, '..')

function read(p: string): string {
  return readFileSync(p, 'utf8')
}

/** 去掉 CSS 注释，避免把说明文字里的示例色值误判为代码 */
function code(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

const LAYOUT_MODULES = ['modules/drawer-layout.css', 'modules/dialog-layout.css', 'modules/list-page-surfaces.css']

/**
 * 合理保留的硬编码（B5-B3 分类 D：技术性例外），逐项有据可查，不允许随意扩充。
 *
 * - #dc2626 / #b91c1c / #991b1b / #ef4444：footer danger 按钮的三档红色阶。
 *   不能机械替换为 var(--color-danger)：dark.css 中 --color-danger 被覆盖为 #ef4444，
 *   替换会**改变**深色主题下 danger 按钮现有的底色取值（现为 #dc2626）。
 *   该块已自带 [data-theme='dark'] 覆盖（dark:hover 用 #ef4444），属自洽的组件专属色阶。
 * - 14px / 50%：弹窗面板圆角（无等价令牌）与加载圈圆形，组件专属布局值（B 类）。
 */
const ALLOWED_HEX_BY_MODULE: Record<string, string[]> = {
  'modules/drawer-layout.css': [],
  'modules/dialog-layout.css': ['#dc2626', '#b91c1c', '#991b1b', '#ef4444'],
  'modules/list-page-surfaces.css': []
}

const ALLOWED_PX_RADIUS_BY_MODULE: Record<string, string[]> = {
  'modules/drawer-layout.css': [],
  'modules/dialog-layout.css': ['14px'],
  'modules/list-page-surfaces.css': []
}

describe('公共布局模块只使用设计令牌，不硬编码主题色', () => {
  it.each(LAYOUT_MODULES)('%s 不含超出允许清单的十六进制颜色字面量', module => {
    const css = code(read(resolve(STYLES, module)))
    const found = css.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []
    const allowed = ALLOWED_HEX_BY_MODULE[module] ?? []
    const violation = [...new Set(found)].filter(h => !allowed.includes(h.toLowerCase()))
    expect(violation, `${module} 出现未登记的硬编码色值: ${violation.join(', ')}`).toEqual([])
  })

  it.each(LAYOUT_MODULES)('%s 的 transition / opacity / box-shadow 走令牌', module => {
    const css = code(read(resolve(STYLES, module)))
    expect(css).not.toMatch(/box-shadow:\s*(?!var\()0\s/)
    expect(css).not.toMatch(/transition:[^;]*\d+(\.\d+)?s\s+ease/)
    expect(css).not.toMatch(/opacity:\s*0\.5\s*;/)
  })

  it.each(LAYOUT_MODULES)('%s 的 px 圆角不超过允许清单（交互控件一律用 --radius-* 令牌）', module => {
    const css = code(read(resolve(STYLES, module)))
    const found = (css.match(/border-radius:\s*(\d+px)/g) ?? []).map(s => s.replace('border-radius:', '').trim())
    const allowed = ALLOWED_PX_RADIUS_BY_MODULE[module] ?? []
    const violation = [...new Set(found)].filter(v => !allowed.includes(v))
    expect(violation, `${module} 出现未登记的 px 圆角: ${violation.join(', ')}`).toEqual([])
  })

  it('dialog-layout.css 的弹窗投影使用 --shadow-dialog（B5-B 前为硬编码，深色主题不生效）', () => {
    const css = read(resolve(STYLES, 'modules/dialog-layout.css'))
    expect(css).toContain('box-shadow: var(--shadow-dialog)')
  })

  it('drawer-layout.css 的抽屉投影使用 --shadow-drawer（B5-A 曾硬编码绕过已主题化的令牌）', () => {
    const css = read(resolve(STYLES, 'modules/drawer-layout.css'))
    expect(css).toContain('box-shadow: var(--shadow-drawer)')
  })
})

describe('弹窗/抽屉投影令牌具备深浅双主题定义', () => {
  it('tokens.css 定义了 --shadow-dialog 与 --shadow-drawer', () => {
    const tokens = read(resolve(STYLES, 'tokens.css'))
    expect(tokens).toMatch(/--shadow-dialog:\s*0 24px 80px rgba\(15, 23, 42, 0\.32\)/)
    expect(tokens).toMatch(/--shadow-drawer:\s*0 24px 72px rgba\(15, 23, 42, 0\.26\)/)
  })

  it('themes/dark.css 同时覆盖这两个投影令牌', () => {
    const dark = read(resolve(STYLES, 'themes/dark.css'))
    expect(dark).toMatch(/--shadow-dialog:\s*[^;]+;/)
    expect(dark).toMatch(/--shadow-drawer:\s*[^;]+;/)
  })

  it('dark.css 覆盖的颜色类令牌都必须先在 tokens.css 定义（防孤儿覆盖）', () => {
    const tokens = read(resolve(STYLES, 'tokens.css'))
    const dark = read(resolve(STYLES, 'themes/dark.css'))
    const defined = new Set(
      [...tokens.matchAll(/^\s{2}(--[a-z0-9-]+)\s*:/gm)].map(m => m[1]).filter(n => !n.startsWith('--el-'))
    )
    const overridden = [...dark.matchAll(/^\s{2}(--[a-z0-9-]+)\s*:/gm)]
      .map(m => m[1])
      // --el-* 是 Element Plus 组件内部变量，只在本文件组件作用域内临时覆盖，不属于设计令牌体系
      .filter(n => !n.startsWith('--el-'))
    const orphan = overridden.filter(name => !defined.has(name))
    expect(orphan, `dark.css 覆盖了未在 tokens.css 定义的令牌: ${orphan.join(', ')}`).toEqual([])
  })
})

describe('本轮令牌替换必须是等价替换（防止未来改令牌值造成视觉漂移）', () => {
  it('--radius-lg / --transition-fast / --opacity-disabled / --tracking-tight 保持历史取值', () => {
    const tokens = read(resolve(STYLES, 'tokens.css'))
    expect(tokens).toMatch(/--radius-lg:\s*8px;/)
    expect(tokens).toMatch(/--transition-fast:\s*0\.15s ease;/)
    expect(tokens).toMatch(/--opacity-disabled:\s*0\.5;/)
    expect(tokens).toMatch(/--tracking-tight:\s*-0\.02em;/)
  })
})

describe('页面头部外观收敛到公共模块（B5-B 前两个组件各维护一份 72/77 行重复样式）', () => {
  it('PageHeader 与 ListPageLayout 都使用 .page-surface-header', () => {
    expect(read(resolve(COMMON, 'PageHeader.vue'))).toContain('page-surface-header')
    expect(read(resolve(COMMON, 'ListPageLayout.vue'))).toContain('page-surface-header')
  })

  it('两个组件的 scoped 样式都不再自带头部外观', () => {
    for (const name of ['PageHeader.vue', 'ListPageLayout.vue']) {
      const scoped = (read(resolve(COMMON, name)).match(/<style[\s\S]*?<\/style>/g) ?? []).join('\n')
      expect(scoped, `${name} 仍在 scoped 样式中维护头部外观`).not.toContain('min-height: 76px')
      expect(scoped, `${name} 仍在 scoped 样式中维护头部渐变`).not.toContain('linear-gradient(135deg')
      expect(scoped, `${name} 仍在 scoped 样式中硬编码字距`).not.toContain('-0.02em')
    }
  })

  it('list-page-surfaces.css 提供完整的共享头部外观（含窄屏断点）', () => {
    const css = read(resolve(STYLES, 'modules/list-page-surfaces.css'))
    expect(css).toContain('.page-surface-header')
    expect(css).toContain('.page-surface-header::before')
    expect(css).toContain('.page-surface-header__title')
    expect(css).toContain('.page-surface-header__actions')
    expect(css).toContain('@media (max-width: 767px)')
  })
})
