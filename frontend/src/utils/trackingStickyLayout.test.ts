import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 生产跟单页「筛选栏 + 列表表头」吸顶的结构契约。
 *
 * 吸顶是三层配合，缺一层就静默退化成「跟着滚走」（不报错、只有滚到列表深处才看得出来）：
 *   1. `.tracking-page` 必须是 flex 列——grid 项的包含块是自己的网格区（高度=元素本身），
 *      sticky 没有位移空间，筛选栏吸顶直接失效；
 *   2. `.el-table` 必须放开自带的 overflow:hidden——否则表头 sticky 被拘在表格内部，
 *      而表格自身不滚动（高度随内容撑开），表头会纹丝不动地跟着页面滚走；
 *   3. 表头停靠位必须用 `--tracking-sticky-top` 变量（脚本 ResizeObserver 量筛选栏实高写入），
 *      写死像素会在「更多筛选」展开 / 窄屏换行时把表头压到筛选栏下面。
 * 故直读页面源码，把这三层钉在构建期。
 */
const here = dirname(fileURLToPath(import.meta.url))
const viewSource = readFileSync(
  resolve(here, '../apps/archive/views/production/ProductionTrackingView.vue'), 'utf8')

describe('生产跟单页吸顶布局契约', () => {
  it('页面根必须是 flex 列（grid 会让 sticky 失去位移空间）', () => {
    expect(viewSource).toMatch(/\.tracking-page\s*\{[^}]*display:\s*flex;\s*flex-direction:\s*column/)
    expect(viewSource).not.toMatch(/\.tracking-page\s*\{[^}]*display:\s*grid/)
  })

  it('筛选栏必须 sticky，且 top 用内边距令牌取负（盖住滚动区顶端的露缝）', () => {
    expect(viewSource).toMatch(/\.tracking-filter-panel\s*\{[^}]*position:\s*sticky/)
    expect(viewSource).toMatch(/\.tracking-filter-panel\s*\{[^}]*top:\s*calc\(-1\s*\*\s*var\(--page-block-padding/)
  })

  it('el-table 必须放开 overflow:hidden，表头 sticky 才能回到滚动容器', () => {
    expect(viewSource).toMatch(/\.tracking-table\.el-table\s*\{\s*overflow:\s*visible;\s*\}/)
  })

  it('表头停靠位必须跟随 --tracking-sticky-top（扣除内边距后=面板底沿）', () => {
    expect(viewSource).toMatch(
      /\.tracking-table\s+:deep\(\.el-table__header-wrapper\)\s*\{[^}]*top:\s*calc\(var\(--tracking-sticky-top[^)]*\)\s*-\s*var\(--page-block-padding/)
  })

  it('脚本必须用 ResizeObserver 把筛选栏实高写进变量（禁止写死偏移）', () => {
    expect(viewSource).toContain('ref="filterPanelRef"')
    expect(viewSource).toContain(':style="stickyHeaderStyle"')
    expect(viewSource).toMatch(/new ResizeObserver\(sync\)/)
    expect(viewSource).toMatch(/filterPanelObserver\.observe\(panel\)/)
    expect(viewSource).toMatch(/'--tracking-sticky-top': `\$\{filterPanelHeight\.value\}px`/)
  })
})
