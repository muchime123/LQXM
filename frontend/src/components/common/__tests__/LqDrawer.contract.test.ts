import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LqDrawer from '../LqDrawer.vue'

/**
 * LqDrawer 契约（B5-A 基线）
 *
 * 契约边界说明：
 * LqDrawer 的职责是「把关闭意图翻译成对 el-drawer 的 props，并在 el-drawer 真正关闭时
 * 统一派发 close」。真正的 ESC 监听、遮罩点击、滚动锁定、焦点陷阱与动画由 Element Plus
 * 负责。因此本文件把两部分分开验证：
 *   1）LqDrawer 侧契约：转发给 el-drawer 的 withHeader / showClose / closeOnPressEscape /
 *      closeOnClickModal / destroyOnClose / size / direction / appendToBody 是否正确
 *      （用 stub 可精确断言）；
 *   2）真实行为验证：用真实 Element Plus 挂载，验证 Teleport、三段式结构、
 *      真实 document ESC 链路、嵌套与卸载生命周期。
 *
 * 已核实的关键差异（Element Plus 2.14.2 源码，与 el-dialog **不同**，不要照搬 LqDialog）：
 *   - el-drawer 的 appendToBody 默认 false（不 Teleport）；本组件恒定传 append-to-body。
 *   - el-drawer 的 update:modelValue 由框架在**过渡结束后**（afterLeave）派发，
 *     而 close 在**过渡开始前**（beforeLeave）派发 —— 顺序与 LqDialog 的 stub 契约相反。
 *   - el-drawer 的 before-close 只接收 hide(shouldCancel)，**不认返回值**；
 *     本组件与 LqDialog 一样自建拦截层，统一支持 false / true / Promise / 回调式。
 *   - 因此本组件刻意**不调用**框架传入的 hide()，四条关闭路径
 *     （关闭按钮 / ESC / 遮罩 / 取消）统一由 update:modelValue 驱动，事件顺序一致。
 */

const ElDrawerStub = {
  name: 'ElDrawer',
  props: [
    'modelValue',
    'title',
    'size',
    'direction',
    'withHeader',
    'showClose',
    'closeOnClickModal',
    'closeOnPressEscape',
    'destroyOnClose',
    'beforeClose',
    'headerClass',
    'bodyClass',
    'footerClass',
    'zIndex',
    'class',
    'appendToBody'
  ],
  template: `
    <div class="el-drawer" :class="$props.class">
      <slot name="header" :titleId="'lq-drawer-title'" />
      <slot />
      <slot name="footer" />
    </div>
  `
}

function factory(props: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  return mount(LqDrawer, {
    props: { modelValue: true, ...props },
    global: { stubs: { 'el-drawer': ElDrawerStub } },
    slots: { default: '<div class="test-body">content</div>', ...slots }
  })
}

function elDrawerOf(wrapper: ReturnType<typeof factory>) {
  return wrapper.findComponent(ElDrawerStub)
}

/** 模拟框架的 ESC / 遮罩通道：直接调用转发给 el-drawer 的 before-close */
function emitEscapeFromFramework(wrapper: ReturnType<typeof factory>) {
  const beforeClose = elDrawerOf(wrapper).props('beforeClose') as (done: (cancel?: boolean) => void) => void
  // 框架传入的 hide(shouldCancel)；按设计本组件不会调用它
  beforeClose(() => {})
}

afterEach(() => {
  document.querySelectorAll('.lq-drawer').forEach(node => node.remove())
})

describe('LqDrawer 属性转发契约', () => {
  it('默认从右侧滑出（rtl）', () => {
    expect(elDrawerOf(factory()).props('direction')).toBe('rtl')
  })

  it('可指定弹出方向', () => {
    expect(elDrawerOf(factory({ direction: 'btt' })).props('direction')).toBe('btt')
  })

  it('默认尺寸预设 md 映射到 --drawer-width-md', () => {
    expect(elDrawerOf(factory()).props('size')).toBe('var(--drawer-width-md)')
  })

  it('size 预设依次映射到 --drawer-width-* 令牌', () => {
    expect(elDrawerOf(factory({ size: 'sm' })).props('size')).toBe('var(--drawer-width-sm)')
    expect(elDrawerOf(factory({ size: 'md' })).props('size')).toBe('var(--drawer-width-md)')
    expect(elDrawerOf(factory({ size: 'lg' })).props('size')).toBe('var(--drawer-width-lg)')
    expect(elDrawerOf(factory({ size: 'xl' })).props('size')).toBe('var(--drawer-width-xl)')
  })

  it('width 覆盖 size 预设，支持百分比与函数表达式', () => {
    expect(elDrawerOf(factory({ size: 'lg', width: '90%' })).props('size')).toBe('90%')
    expect(elDrawerOf(factory({ width: 'min(1120px, 98vw)' })).props('size')).toBe('min(1120px, 98vw)')
  })

  it('默认禁用遮罩关闭', () => {
    expect(elDrawerOf(factory()).props('closeOnClickModal')).toBe(false)
  })

  it('closeOnClickModal=true 时开启遮罩关闭', () => {
    expect(elDrawerOf(factory({ closeOnClickModal: true })).props('closeOnClickModal')).toBe(true)
  })

  it('loading 期间即使 closeOnClickModal=true 也关闭遮罩通道', () => {
    expect(elDrawerOf(factory({ closeOnClickModal: true, loading: true })).props('closeOnClickModal')).toBe(false)
  })

  it('默认允许 ESC 关闭', () => {
    expect(elDrawerOf(factory()).props('closeOnPressEscape')).toBe(true)
  })

  it('loading 期间关闭 ESC 通道', () => {
    expect(elDrawerOf(factory({ loading: true })).props('closeOnPressEscape')).toBe(false)
  })

  it('透传 destroyOnClose', () => {
    expect(elDrawerOf(factory({ destroyOnClose: true })).props('destroyOnClose')).toBe(true)
  })

  it('始终传入 append-to-body（el-drawer 默认不 Teleport，与 el-dialog 不同）', () => {
    // 模板以裸属性 `append-to-body` 传入；stub 未声明 Boolean 类型故此处取值为 ''，
    // 真实 el-drawer 侧为 Boolean → true，由「与真实 Element Plus 集成」用例验证 Teleport。
    expect(elDrawerOf(factory()).props('appendToBody')).toBe('')
  })

  it('showHeader=false 时整块头部不渲染（业务可完全自绘）', () => {
    const shown = factory({ showHeader: true, title: '标题' })
    const hidden = factory({ showHeader: false, title: '标题' })
    expect(shown.find('.lq-drawer__head').exists()).toBe(true)
    expect(elDrawerOf(hidden).props('withHeader')).toBe(false)
    expect(hidden.find('.lq-drawer__head').exists()).toBe(false)
  })

  it('嵌套/层级场景可显式指定 zIndex', () => {
    expect(elDrawerOf(factory({ zIndex: 2200 })).props('zIndex')).toBe(2200)
  })

  it('mode 生成布局类，且 header/footer 默认随 mode 变化', () => {
    expect(factory({ mode: 'form' }).find('.lq-drawer--form').exists()).toBe(true)
    expect(factory({ mode: 'detail' }).find('.lq-drawer--detail').exists()).toBe(true)
    expect(factory({ mode: 'workspace' }).find('.lq-drawer--workspace').exists()).toBe(true)
    // detail 默认无 footer，form 默认有 footer
    expect(factory({ mode: 'detail' }).find('.lq-drawer__foot').exists()).toBe(false)
    expect(factory({ mode: 'form' }).find('.lq-drawer__foot').exists()).toBe(true)
  })

  it('showFooter 可显式覆盖 mode 的默认值', () => {
    expect(factory({ mode: 'detail', showFooter: true }).find('.lq-drawer__foot').exists()).toBe(true)
    expect(factory({ mode: 'form', showFooter: false }).find('.lq-drawer__foot').exists()).toBe(false)
  })
})

describe('LqDrawer 关闭事件唯一性', () => {
  beforeEach(() => vi.clearAllMocks())

  it('关闭按钮（父级响应 v-model）：close 仅由 el-drawer @close 派发一次', async () => {
    const wrapper = factory({ showClose: true })
    const elDrawer = elDrawerOf(wrapper)

    const click = wrapper.find('.lq-drawer__close').trigger('click')
    await wrapper.setProps({ modelValue: false })
    await click
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])

    await elDrawer.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('关闭按钮（父级不回写）：兜底派发 close 一次，且不重复', async () => {
    const wrapper = factory({ showClose: true })
    const elDrawer = elDrawerOf(wrapper)

    await wrapper.find('.lq-drawer__close').trigger('click')
    await nextTick()
    expect(wrapper.emitted('close')).toHaveLength(1)

    // 父级随后才响应，el-drawer 真正关闭时不再重复派发
    await wrapper.setProps({ modelValue: false })
    await elDrawer.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('取消按钮：cancel 一次 + close 一次，且 cancel 不会绕过 beforeClose', async () => {
    const wrapper = factory({ mode: 'form', showCancel: true })
    const elDrawer = elDrawerOf(wrapper)
    const buttons = wrapper.findAll('.lq-drawer__foot button')

    const click = buttons[0].trigger('click')
    await wrapper.setProps({ modelValue: false })
    await click
    await elDrawer.vm.$emit('close')

    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('确认按钮只派发 confirm，不自动关闭', async () => {
    const wrapper = factory({ mode: 'form', showConfirm: true })
    const buttons = wrapper.findAll('.lq-drawer__foot button')

    await buttons[buttons.length - 1].trigger('click')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('ESC / 遮罩通道与关闭按钮同序：先 update:modelValue，后 close', async () => {
    const wrapper = factory()
    const elDrawer = elDrawerOf(wrapper)

    emitEscapeFromFramework(wrapper)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])

    await wrapper.setProps({ modelValue: false })
    await elDrawer.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('loading 期间不渲染关闭按钮（关闭入口唯一由 el-drawer 通道控制）', () => {
    expect(factory({ loading: true, showClose: true }).find('.lq-drawer__close').exists()).toBe(false)
    expect(factory({ loading: false, showClose: true }).find('.lq-drawer__close').exists()).toBe(true)
  })

  it('抽屉重新打开后上一轮的 close 标记被重置，程序化关闭仍派发 close', async () => {
    const wrapper = factory({ showClose: true })
    const elDrawer = elDrawerOf(wrapper)

    // 第一轮：点关闭按钮
    await wrapper.find('.lq-drawer__close').trigger('click')
    await nextTick()
    await wrapper.setProps({ modelValue: false })
    await elDrawer.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)

    // 第二轮：重新打开后由父级程序化关闭（不经组件按钮）
    await wrapper.setProps({ modelValue: true })
    await wrapper.setProps({ modelValue: false })
    await elDrawer.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(2)
  })
})

describe('LqDrawer beforeClose 语义契约', () => {
  beforeEach(() => vi.clearAllMocks())

  it('返回 false：阻止关闭，且不派发 cancel / close', async () => {
    const beforeClose = vi.fn(() => false)
    const wrapper = factory({ beforeClose })

    await wrapper.find('.lq-drawer__close').trigger('click')
    await nextTick()

    expect(beforeClose).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('返回 true：立即关闭', async () => {
    const wrapper = factory({ beforeClose: () => true })

    await wrapper.find('.lq-drawer__close').trigger('click')
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('回调式（void 返回）：由钩子持 done，调用后才关闭', async () => {
    let done: (() => void) | undefined
    const wrapper = factory({
      beforeClose: (finish: () => void) => {
        done = finish
      }
    })

    await wrapper.find('.lq-drawer__close').trigger('click')
    await nextTick()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    done?.()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('Promise resolve(false)：阻止关闭', async () => {
    const wrapper = factory({ beforeClose: () => Promise.resolve(false) })

    await wrapper.find('.lq-drawer__close').trigger('click')
    await Promise.resolve()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('Promise resolve(true)：允许关闭', async () => {
    const wrapper = factory({ beforeClose: () => Promise.resolve(true) })

    await wrapper.find('.lq-drawer__close').trigger('click')
    await Promise.resolve()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('Promise reject：视为不关闭且不抛出（异常被内部吞掉）', async () => {
    const wrapper = factory({ beforeClose: () => Promise.reject(new Error('boom')) })

    await wrapper.find('.lq-drawer__close').trigger('click')
    await Promise.resolve()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('beforeClose 拦截时取消按钮也不派发 cancel', async () => {
    const wrapper = factory({ mode: 'form', showCancel: true, beforeClose: () => false })
    const buttons = wrapper.findAll('.lq-drawer__foot button')

    await buttons[0].trigger('click')
    await nextTick()

    expect(wrapper.emitted('cancel')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('ESC 通道同样受 beforeClose 拦截', async () => {
    const wrapper = factory({ beforeClose: () => false })

    emitEscapeFromFramework(wrapper)
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })
})

describe('LqDrawer 插槽契约', () => {
  it('#title 覆盖默认标题块，关闭按钮仍然保留', () => {
    const wrapper = factory({ title: '默认标题' }, {
      title: '<span class="custom-title">自定义标题</span>'
    })

    expect(wrapper.find('.custom-title').exists()).toBe(true)
    expect(wrapper.find('.lq-drawer__title-text').exists()).toBe(false)
    expect(wrapper.find('.lq-drawer__close').exists()).toBe(true)
  })

  it('#actions 渲染在头部右侧', () => {
    const wrapper = factory({}, { actions: '<button class="custom-action">刷新</button>' })
    expect(wrapper.find('.lq-drawer__actions .custom-action').exists()).toBe(true)
  })

  it('#footer 覆盖默认按钮组', () => {
    const wrapper = factory({ mode: 'form' }, {
      footer: '<button class="custom-footer">专属操作</button>'
    })

    expect(wrapper.find('.custom-footer').exists()).toBe(true)
    expect(wrapper.find('.lq-drawer__foot button.primary').exists()).toBe(false)
  })

  it('dirty 在头部显示「已修改」标记', () => {
    expect(factory({ dirty: true, mode: 'form' }).find('.lq-drawer__eyebrow.dirty').text()).toBe('已修改')
    expect(factory({ dirty: false, mode: 'form' }).find('.lq-drawer__eyebrow').exists()).toBe(false)
  })

  it('默认插槽内容渲染在 body 内', () => {
    const wrapper = factory()
    expect(wrapper.find('.lq-drawer__body .test-body').exists()).toBe(true)
  })
})

describe('LqDrawer 属性与事件透传', () => {
  it('未声明的 class 属性与事件留在 $attrs，透传给 el-drawer', () => {
    const Host = defineComponent({
      components: { LqDrawer },
      template: `<LqDrawer :model-value="true" header-class="tracking-drawer-header" @opened="onOpened" />`,
      setup: () => ({ onOpened: () => {} })
    })
    const wrapper = mount(Host, { global: { stubs: { 'el-drawer': ElDrawerStub } } })
    const elDrawer = wrapper.findComponent(ElDrawerStub)

    expect(elDrawer.props('headerClass')).toBe('tracking-drawer-header')
    expect((elDrawer.vm as unknown as { $attrs: Record<string, unknown> }).$attrs.onOpened).toBeTypeOf('function')
  })

  it('已声明的事件由组件内部消费，不会落到 el-drawer 上', () => {
    const Host = defineComponent({
      components: { LqDrawer },
      template: `<LqDrawer :model-value="true" @close="onClose" @cancel="onCancel" @confirm="onConfirm" />`,
      setup: () => ({ onClose: () => {}, onCancel: () => {}, onConfirm: () => {} })
    })
    const wrapper = mount(Host, { global: { stubs: { 'el-drawer': ElDrawerStub } } })
    const lqDrawer = wrapper.findComponent(LqDrawer)
    const keys = Object.keys((lqDrawer.vm as unknown as { $attrs: Record<string, unknown> }).$attrs)

    // 声明过 emits 的监听器会被组件消费，不会留在 $attrs 里再透传一层
    expect(keys).not.toContain('onClose')
    expect(keys).not.toContain('onCancel')
    expect(keys).not.toContain('onConfirm')
    expect(keys).not.toContain('onUpdate:modelValue')
  })
})

describe('LqDrawer 与真实 Element Plus 集成', () => {
  it('内容被 Teleport 到 body，且三段式结构完整', async () => {
    const wrapper = mount(LqDrawer, {
      props: { modelValue: true, title: '生产跟单', subtitle: '按车间归集', mode: 'form' },
      slots: { default: '<div class="real-body">content</div>' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()

    expect(document.body.querySelector('.lq-drawer__head')).not.toBeNull()
    expect(document.body.querySelector('.lq-drawer__body')).not.toBeNull()
    expect(document.body.querySelector('.lq-drawer__foot')).not.toBeNull()
    expect(document.body.querySelector('.real-body')).not.toBeNull()

    // append-to-body 生效：抽屉根节点挂在 body 下，而不是挂载容器内
    const root = document.body.querySelector('.lq-drawer')
    expect(root).not.toBeNull()
    expect(root!.closest('body')).toBe(document.body)

    wrapper.unmount()
  })

  it('真实 document ESC：走通 EP 的 escape → before-close → 本组件关闭意图，close 恰好一次', async () => {
    const wrapper = mount(LqDrawer, {
      props: { modelValue: true, title: '详情' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()
    await nextTick()

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true })
    )
    await nextTick()
    await nextTick()

    // 本组件不调用框架的 hide()，而是统一转为关闭意图
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    // 父级未回写（mount 时是普通 prop），兜底派发 close 一次
    expect(wrapper.emitted('close')).toHaveLength(1)

    wrapper.unmount()
  })

  it('loading 期间真实 ESC 不会触发关闭意图', async () => {
    const wrapper = mount(LqDrawer, {
      props: { modelValue: true, title: '详情', loading: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()
    await nextTick()

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true })
    )
    await nextTick()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()

    wrapper.unmount()
  })

  it('嵌套抽屉：子抽屉关闭不影响父抽屉', async () => {
    const Host = defineComponent({
      components: { LqDrawer },
      data: () => ({ outerVisible: true, innerVisible: true }),
      template: `
        <LqDrawer v-model="outerVisible" title="外层">
          <LqDrawer v-model="innerVisible" title="内层" />
        </LqDrawer>
      `
    })
    const wrapper = mount(Host, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await nextTick()

    const drawers = wrapper.findAllComponents(LqDrawer)
    expect(drawers).toHaveLength(2)

    const [outer, inner] = drawers

    // 两个抽屉都 Teleport 到 body，组件 wrapper 内查不到 DOM，改从 body 定位内层
    const panels = Array.from(document.body.querySelectorAll('.lq-drawer')) as HTMLElement[]
    const innerPanel = panels.find(
      panel => panel.querySelector('.lq-drawer__title-text')?.textContent?.trim() === '内层'
    )
    expect(innerPanel).toBeDefined()

    const closeBtn = innerPanel!.querySelector('.lq-drawer__close') as HTMLElement | null
    expect(closeBtn).not.toBeNull()
    closeBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    expect(inner.emitted('update:modelValue')).toEqual([[false]])
    // 外层未收到任何关闭意图
    expect(outer.emitted('update:modelValue')).toBeUndefined()

    wrapper.unmount()
  })

  it('真实 EP 下尺寸预设作为 el-drawer 的 size 应用（rtl → 宽度）', async () => {
    const wrapper = mount(LqDrawer, {
      props: { modelValue: true, title: '详情', size: 'lg' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()

    const root = document.body.querySelector('.lq-drawer') as HTMLElement | null
    expect(root).not.toBeNull()
    expect(root!.className).toContain('rtl')
    // EP 横向抽屉把 size 应用到 width
    expect(root!.style.width).toContain('--drawer-width-lg')

    wrapper.unmount()
  })

  it('destroy-on-close：关闭后内容不再保留在 DOM', async () => {
    const wrapper = mount(LqDrawer, {
      props: { modelValue: true, title: '详情', destroyOnClose: true },
      slots: { default: '<div class="destroy-me">content</div>' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()

    expect(document.body.querySelector('.destroy-me')).not.toBeNull()

    await wrapper.setProps({ modelValue: false })
    wrapper.unmount()

    expect(document.body.querySelector('.destroy-me')).toBeNull()
  })

  it('关闭请求未完成时卸载不抛错（生命周期安全）', async () => {
    const wrapper = factory({ showClose: true })

    await wrapper.find('.lq-drawer__close').trigger('click')

    expect(() => wrapper.unmount()).not.toThrow()
    await nextTick()
  })
})

describe('LqDrawer 静态资源契约（窄屏与主题适配）', () => {
  function readStyle(relative: string): string {
    return readFileSync(resolve(__dirname, '../../../styles', relative), 'utf-8')
  }

  it('每一档 --drawer-width-* 都带 vw 上限（窄屏不溢出）', () => {
    const css = readStyle('tokens.css')
    const declarations = css.match(/--drawer-width-(sm|md|lg|xl):\s*([^;]+);/g) ?? []

    expect(declarations).toHaveLength(4)
    for (const declaration of declarations) {
      expect(declaration).toContain('vw')
    }
  })

  it('drawer-layout.css 具备三段式布局与窄屏断点', () => {
    const css = readStyle('modules/drawer-layout.css')

    expect(css).toContain('.lq-drawer__head')
    expect(css).toContain('.lq-drawer__body')
    expect(css).toContain('.lq-drawer__foot')
    expect(css).toContain('@media (max-width: 767px)')
  })

  it('drawer-layout.css 只使用设计令牌，不硬编码主题色', () => {
    const css = readStyle('modules/drawer-layout.css')
    // 排除注释行后不应出现十六进制颜色字面量，保证浅色/深色主题统一走 tokens
    const code = css.replace(/\/\*[\s\S]*?\*\//g, '')

    expect(code).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
  })
})
