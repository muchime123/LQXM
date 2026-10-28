import { mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import LqTable from '../LqTable.vue'

const ElTableStub = {
  name: 'ElTable',
  props: ['data', 'stripe', 'border', 'rowClassName'],
  emits: ['selection-change', 'sort-change', 'row-click', 'row-dblclick', 'expand-change'],
  template: '<div class="el-table"><slot /></div>'
}

const global = {
  stubs: { 'el-table': ElTableStub },
  directives: { loading: { mounted() {}, updated() {}, unmounted() {} } }
}

/**
 * 以真实业务写法挂载：父组件在 LqTable 上监听四种已声明事件 + 一种未声明事件。
 * LqTable 内部使用 v-bind="$attrs" 透传，这里验证不会产生二次派发。
 */
function mountWithListeners() {
  const handlers = {
    selection: vi.fn(),
    sort: vi.fn(),
    rowClick: vi.fn(),
    rowDblClick: vi.fn(),
    expand: vi.fn()
  }

  const Host = defineComponent({
    components: { LqTable },
    setup: () => ({ handlers }),
    template: `
      <LqTable
        :data="[]"
        @selection-change="handlers.selection"
        @sort-change="handlers.sort"
        @row-click="handlers.rowClick"
        @row-dblclick="handlers.rowDblClick"
        @expand-change="handlers.expand"
      />
    `
  })

  const wrapper = mount(Host, { global })
  return { wrapper, handlers, table: wrapper.findComponent(ElTableStub) }
}

describe('LqTable event forwarding', () => {
  it.each([
    ['selection-change', 'selection'],
    ['sort-change', 'sort'],
    ['row-click', 'rowClick'],
    ['row-dblclick', 'rowDblClick']
  ] as const)('dispatches declared event %s exactly once', async (event, key) => {
    const { handlers, table } = mountWithListeners()

    await table.vm.$emit(event, { id: 1 })

    expect(handlers[key]).toHaveBeenCalledTimes(1)
  })

  it('keeps declared emit listeners out of $attrs', () => {
    // defineEmits 声明过的监听器不会残留在 $attrs，因此不会被 v-bind="$attrs" 再绑一次
    const { wrapper } = mountWithListeners()
    // 泛型组件会让 VTU 的 findComponent 重载解析退化为 DOMWrapper，这里断言回组件 wrapper
    const attrs = Object.keys((wrapper.findComponent(LqTable) as unknown as VueWrapper).vm.$attrs)

    expect(attrs).not.toContain('onSelectionChange')
    expect(attrs).not.toContain('onSortChange')
    expect(attrs).not.toContain('onRowClick')
    expect(attrs).not.toContain('onRowDblclick')
  })

  it('passes undeclared Element Plus events through exactly once', async () => {
    // 未声明的事件仍经 $attrs 透传，且只派发一次（不应被"剔除 onXxx"式改动波及）
    const { wrapper, handlers, table } = mountWithListeners()

    expect(Object.keys((wrapper.findComponent(LqTable) as unknown as VueWrapper).vm.$attrs)).toContain(
      'onExpandChange'
    )

    await table.vm.$emit('expand-change', [{ id: 1 }], [])

    expect(handlers.expand).toHaveBeenCalledTimes(1)
  })
})
