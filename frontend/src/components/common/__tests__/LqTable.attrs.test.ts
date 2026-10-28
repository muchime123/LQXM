import { mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import ElementPlus from 'element-plus'
import LqTable from '../LqTable.vue'

/**
 * LqTable 属性透传契约（B1 基线）
 *
 * 背景：LqTable 通过 `inheritAttrs:false` + `v-bind="$attrs"`（LqTable.vue:5,37）
 * 把未声明的属性与事件透传给 el-table。业务侧正在依赖这条通道：
 *   - span-method / max-height  → MoldImportPreviewTable.vue:70-72
 *   - expand-change             → CashflowAccountTable.vue:2
 *   - default-sort              → CompanyCertificationsTab.vue:78 / OfficeAssetView.vue:88
 *   - row-key                   → 11 个文件
 * 因此本文件的核心目的：把「透传能力」钉死，防止后续为完善类型而给这些能力
 * 声明 props/emits（一旦声明就会从 $attrs 移除，导致静默失效）。
 */

interface StubCall {
  method: string
  args: unknown[]
}

function createElTableStub() {
  const calls: StubCall[] = []

  const stub = {
    name: 'ElTable',
    props: [
      'data',
      'stripe',
      'border',
      'rowClassName',
      'rowKey',
      'maxHeight',
      'height',
      'spanMethod',
      'defaultSort',
      'showSummary',
      'treeProps'
    ],
    emits: ['selection-change', 'sort-change', 'row-click', 'row-dblclick', 'expand-change', 'select'],
    // 需渲染 empty / append 具名插槽，否则无法验证 LqTable 的空态接线
    template: '<div class="el-table"><slot /><slot name="empty" /><slot name="append" /></div>',
    methods: {
      clearSelection() {
        calls.push({ method: 'clearSelection', args: [] })
      },
      toggleRowSelection(row: unknown, selected?: boolean) {
        calls.push({ method: 'toggleRowSelection', args: [row, selected] })
      },
      toggleAllSelection() {
        calls.push({ method: 'toggleAllSelection', args: [] })
      },
      toggleRowExpansion(row: unknown, expanded?: boolean) {
        calls.push({ method: 'toggleRowExpansion', args: [row, expanded] })
      },
      setCurrentRow(row?: unknown) {
        calls.push({ method: 'setCurrentRow', args: [row] })
      },
      clearSort() {
        calls.push({ method: 'clearSort', args: [] })
      },
      clearFilter(columnKey?: string) {
        calls.push({ method: 'clearFilter', args: [columnKey] })
      },
      doLayout() {
        calls.push({ method: 'doLayout', args: [] })
      },
      sort(prop: string, order: string) {
        calls.push({ method: 'sort', args: [prop, order] })
      }
    }
  }

  return { stub, calls }
}

/** 以真实业务写法挂载：父组件用模板绑定属性，避免测试自身改造组件 API。 */
function mountWithAttrs(template: string, setup?: () => Record<string, unknown>) {
  const { stub, calls } = createElTableStub()
  const Host = defineComponent({
    components: { LqTable },
    setup: setup ?? (() => ({})),
    template
  })

  const wrapper = mount(Host, {
    global: {
      stubs: { 'el-table': stub },
      directives: { loading: { mounted() {}, updated() {}, unmounted() {} } }
    }
  })

  return {
    wrapper,
    calls,
    table: wrapper.findComponent(stub),
    // 泛型组件会让 VTU 的 findComponent 重载解析退化为 DOMWrapper，这里断言回组件 wrapper
    lqTable: wrapper.findComponent(LqTable) as unknown as VueWrapper
  }
}

describe('LqTable 属性透传契约', () => {
  it('透传 row-key', () => {
    const { table } = mountWithAttrs(`<LqTable :data="[]" row-key="id" />`)
    expect(table.props('rowKey')).toBe('id')
  })

  it('透传 max-height', () => {
    const { table } = mountWithAttrs(`<LqTable :data="[]" max-height="560px" />`)
    expect(table.props('maxHeight')).toBe('560px')
  })

  it('透传 span-method 且保持函数引用不变', () => {
    const spanMethod = () => ({ rowspan: 1, colspan: 1 })
    const { table } = mountWithAttrs(`<LqTable :data="[]" :span-method="spanMethod" />`, () => ({ spanMethod }))
    expect(table.props('spanMethod')).toBe(spanMethod)
  })

  it('透传 default-sort', () => {
    const defaultSort = { prop: 'createdAt', order: 'descending' }
    const { table } = mountWithAttrs(`<LqTable :data="[]" :default-sort="defaultSort" />`, () => ({ defaultSort }))
    expect(table.props('defaultSort')).toEqual(defaultSort)
  })

  it('透传未声明的其它 el-table 能力（height / show-summary / tree-props）', () => {
    const treeProps = { children: 'children' }
    const { table } = mountWithAttrs(
      `<LqTable :data="[]" height="400px" :show-summary="true" :tree-props="treeProps" />`,
      () => ({ treeProps })
    )
    expect(table.props('height')).toBe('400px')
    expect(table.props('showSummary')).toBe(true)
    expect(table.props('treeProps')).toEqual(treeProps)
  })

  it('已声明的 props 不被 attrs 覆盖（stripe / border 走显式绑定）', () => {
    const { table } = mountWithAttrs(`<LqTable :data="[]" :stripe="false" border />`)
    expect(table.props('stripe')).toBe(false)
    expect(table.props('border')).toBe(true)
  })

  it('rowClassName 经 mergedRowClass 委派：函数形式返回业务结果', () => {
    const rowClassName = ({ row }: { row: { id: number } }) => (row.id === 1 ? 'highlight' : '')
    const { table } = mountWithAttrs(`<LqTable :data="[]" :row-class-name="rowClassName" />`, () => ({ rowClassName }))

    const merged = table.props('rowClassName') as (data: { row: unknown; rowIndex: number }) => string

    // 契约：el-table 收到的始终是 LqTable 的 mergedRowClass，由它委派给业务传入的函数
    expect(typeof merged).toBe('function')
    expect(merged({ row: { id: 1 }, rowIndex: 0 })).toBe('highlight')
    expect(merged({ row: { id: 2 }, rowIndex: 1 })).toBe('')
  })

  it('rowClassName 经 mergedRowClass 委派：字符串形式原样返回', () => {
    const { table } = mountWithAttrs(`<LqTable :data="[]" row-class-name="static-row" />`)
    const merged = table.props('rowClassName') as (data: { row: unknown; rowIndex: number }) => string

    expect(merged({ row: {}, rowIndex: 0 })).toBe('static-row')
  })

  it('未传 rowClassName 时 mergedRowClass 返回空串', () => {
    const { table } = mountWithAttrs(`<LqTable :data="[]" />`)
    const merged = table.props('rowClassName') as (data: { row: unknown; rowIndex: number }) => string

    expect(merged({ row: {}, rowIndex: 0 })).toBe('')
  })

  it('未声明的事件仍留在 $attrs 上（透传通道未关闭）', () => {
    const { lqTable } = mountWithAttrs(`<LqTable :data="[]" @expand-change="onExpand" @select="onSelect" />`, () => ({
      onExpand: () => {},
      onSelect: () => {}
    }))
    const attrs = Object.keys(lqTable.vm.$attrs)

    expect(attrs).toContain('onExpandChange')
    expect(attrs).toContain('onSelect')
  })

  it('已声明的事件不残留在 $attrs（避免二次派发）', () => {
    const { lqTable } = mountWithAttrs(
      `<LqTable :data="[]" @selection-change="noop" @sort-change="noop" @row-click="noop" @row-dblclick="noop" />`,
      () => ({ noop: () => {} })
    )
    const attrs = Object.keys(lqTable.vm.$attrs)

    expect(attrs).not.toContain('onSelectionChange')
    expect(attrs).not.toContain('onSortChange')
    expect(attrs).not.toContain('onRowClick')
    expect(attrs).not.toContain('onRowDblclick')
  })
})

describe('LqTable 结构与空态契约', () => {
  it('按 density 输出面板 class', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" density="compact" />`)
    expect(wrapper.find('.lq-table-panel--compact').exists()).toBe(true)
  })

  it('默认 density 为 default', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" />`)
    expect(wrapper.find('.lq-table-panel--default').exists()).toBe(true)
  })

  it('默认渲染 EmptyState 且使用默认文案', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" />`)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.find('.empty-text').text()).toBe('暂无数据')
  })

  it('empty-text 覆盖默认空态文案', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" empty-text="没有可显示的行" />`)
    expect(wrapper.find('.empty-text').text()).toBe('没有可显示的行')
  })

  it('#empty 插槽覆盖默认空态', () => {
    const { wrapper } = mountWithAttrs(
      `<LqTable :data="[]"><template #empty><span class="custom-empty">自定义空态</span></template></LqTable>`
    )
    expect(wrapper.find('.custom-empty').exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(false)
  })

  it('默认插槽内容作为表格列渲染', () => {
    const { wrapper } = mountWithAttrs(
      `<LqTable :data="[]"><span class="fake-column">col</span></LqTable>`
    )
    expect(wrapper.find('.fake-column').exists()).toBe(true)
  })
})

describe('LqTable 暴露实例方法契约', () => {
  it('暴露 9 个 el-table 实例方法并正确委派', () => {
    const { lqTable, calls } = mountWithAttrs(`<LqTable ref="tableRef" :data="[]" />`)
    const vm = lqTable.vm as unknown as Record<string, (...args: unknown[]) => unknown>
    const row = { id: 1 }

    vm.clearSelection()
    vm.toggleRowSelection(row, true)
    vm.toggleAllSelection()
    vm.toggleRowExpansion(row, false)
    vm.setCurrentRow(row)
    vm.clearSort()
    vm.clearFilter('status')
    vm.doLayout()
    vm.sort('createdAt', 'ascending')

    expect(calls.map(call => call.method)).toEqual([
      'clearSelection',
      'toggleRowSelection',
      'toggleAllSelection',
      'toggleRowExpansion',
      'setCurrentRow',
      'clearSort',
      'clearFilter',
      'doLayout',
      'sort'
    ])
    expect(calls[1].args).toEqual([row, true])
    expect(calls[6].args).toEqual(['status'])
    expect(calls[8].args).toEqual(['createdAt', 'ascending'])
  })
})

describe('LqTable 与真实 Element Plus 集成', () => {
  it('row-key 与 max-height 到达真实 el-table（不依赖 stub 验证）', () => {
    const wrapper = mount(LqTable, {
      props: { data: [] },
      attrs: { 'row-key': 'id', 'max-height': '560px' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })

    const el = wrapper.findComponent({ name: 'ElTable' })

    expect(el.exists()).toBe(true)
    expect(el.props('rowKey')).toBe('id')
    expect(el.props('maxHeight')).toBe('560px')

    wrapper.unmount()
  })
})

// ---------------------------------------------------------------------------
// B3 落地的能力（原为 it.fails「期望失败」，实现后转为必须通过）
// 空态扩展复用现有 EmptyState，不创建第二套空状态视觉。
// ---------------------------------------------------------------------------
describe('LqTable 空态扩展能力（B3）', () => {
  it('emptyHint 渲染空态补充提示', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" empty-hint="调整筛选条件后再试" />`)
    expect(wrapper.find('.empty-hint').text()).toBe('调整筛选条件后再试')
  })

  it('emptySize 控制空态尺寸', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" empty-size="sm" />`)
    expect(wrapper.find('.empty-state--sm').exists()).toBe(true)
  })

  it('未传 emptyHint / emptySize 时保持原行为（无提示行、尺寸 md）', () => {
    const { wrapper } = mountWithAttrs(`<LqTable :data="[]" />`)
    expect(wrapper.find('.empty-hint').exists()).toBe(false)
    expect(wrapper.find('.empty-state--md').exists()).toBe(true)
  })

  it('#empty 插槽优先于 emptyHint / emptySize（保持插槽兼容）', () => {
    const { wrapper } = mountWithAttrs(
      `<LqTable :data="[]" empty-hint="提示" empty-size="sm"><template #empty><span class="custom-empty">自定义</span></template></LqTable>`
    )
    expect(wrapper.find('.custom-empty').exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(false)
  })

  it('emptyHint 与 emptyText / emptyIcon 可组合使用', () => {
    const { wrapper } = mountWithAttrs(
      `<LqTable :data="[]" empty-text="没有资质" empty-icon="📄" empty-hint="点击右上角新增" />`
    )
    expect(wrapper.find('.empty-text').text()).toBe('没有资质')
    expect(wrapper.find('.empty-icon--text').text()).toBe('📄')
    expect(wrapper.find('.empty-hint').text()).toBe('点击右上角新增')
  })
})

describe('LqTable 泛型类型契约（B3）', () => {
  // 运行时部分：行事件载荷就是 data 的元素本身。
  // 编译期部分（T 是否正确推导到 row-click / selection-change 载荷）
  // 由同目录的 LqTable.generic.types.ts 承担，随 npm run typecheck 一起校验。
  it('运行时：row-click 载荷即 data 的元素', async () => {
    interface AssetRow {
      id: number
      name: string
    }
    const rows: AssetRow[] = [
      { id: 1, name: '笔记本电脑' },
      { id: 2, name: '投影仪' }
    ]
    const clicked: AssetRow[] = []

    const { table } = mountWithAttrs(`<LqTable :data="rows" @row-click="row => clicked.push(row)" />`, () => ({
      rows,
      clicked
    }))

    await table.vm.$emit('row-click', rows[1])

    expect(clicked).toEqual([{ id: 2, name: '投影仪' }])
  })

  it('运行时：selection-change 载荷为行数组', async () => {
    interface AssetRow {
      id: number
      name: string
    }
    const rows: AssetRow[] = [{ id: 1, name: '笔记本电脑' }]
    const selected: AssetRow[] = []

    const { table } = mountWithAttrs(
      `<LqTable :data="rows" @selection-change="r => selected.push(...r)" />`,
      () => ({ rows, selected })
    )

    await table.vm.$emit('selection-change', rows)

    expect(selected).toEqual([{ id: 1, name: '笔记本电脑' }])
  })
})

describe('LqTable 真实 Element Plus 集成（空态与行事件）', () => {
  it('真实 el-table 下空态渲染 EmptyState 且 empty-hint 生效', () => {
    const wrapper = mount(LqTable, {
      props: { data: [], emptyHint: '调整筛选条件后再试', emptySize: 'sm' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.find('.empty-hint').text()).toBe('调整筛选条件后再试')
    expect(wrapper.find('.empty-state--sm').exists()).toBe(true)

    wrapper.unmount()
  })

  it('真实 el-table 下行点击事件只派发一次', async () => {
    const row = { id: 7, name: '资产 A' }
    const onClick = vi.fn()
    const wrapper = mount(LqTable, {
      props: { data: [row] },
      attrs: { onRowClick: onClick },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await wrapper.vm.$nextTick()

    const el = wrapper.findComponent({ name: 'ElTable' })
    el.vm.$emit('row-click', row)
    await wrapper.vm.$nextTick()

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(onClick).toHaveBeenCalledWith(row)

    wrapper.unmount()
  })
})
