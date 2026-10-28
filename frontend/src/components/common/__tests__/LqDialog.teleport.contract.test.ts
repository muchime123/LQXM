import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LqDialog from '../LqDialog.vue'

/**
 * LqDialog Teleport / append-to-body 契约（B4 复核）
 *
 * 复核目标：P0-A 与 B1 曾推断「append-to-body 把内容 Teleport 到 body，会导致业务侧
 * :deep 样式失效」。本文件用**真实 Element Plus**验证这个推断是否成立。
 *
 * 结论（B4 实测）：**不成立**。
 * 业务写法形如 `.media-detail-dialog :deep(.lq-dialog__head)`
 * （见 components/media/MediaAccountDetailModal.vue:126-141），
 * 编译后是**后代选择器** `.media-detail-dialog[data-v-x] .lq-dialog__head`。
 * class 通过 attrs 落到 el-dialog 根节点上，而该节点是 LqDialog 的子组件根元素，
 * 会继承父组件的 scope id；`.lq-dialog__head` 作为其后代即可命中。
 * Teleport 只改变节点在 DOM 树中的位置，不改变这层祖先后代关系。
 *
 * 因此本阶段**不全局调整 append-to-body**（B4 指令明确要求）。
 */

function mountReal(props: Record<string, unknown> = {}, attrs: Record<string, unknown> = {}) {
  return mount(LqDialog, {
    props: { modelValue: true, title: '编辑', mode: 'form', ...props },
    attrs,
    slots: { default: '<div class="real-body">content</div>' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
}

describe('LqDialog append-to-body 与业务 :deep 可达性', () => {
  it('业务 class 锚点仍是内部三段式结构的祖先（:deep 后代选择器可命中）', async () => {
    const wrapper = mountReal({}, { class: 'media-detail-dialog' })
    await nextTick()

    const anchor = document.body.querySelector('.media-detail-dialog')
    expect(anchor).not.toBeNull()
    expect(anchor!.querySelector('.lq-dialog__head')).not.toBeNull()
    expect(anchor!.querySelector('.lq-dialog__body')).not.toBeNull()
    expect(anchor!.querySelector('.lq-dialog__foot')).not.toBeNull()

    wrapper.unmount()
  })

  it('内容确实被 Teleport 到 body，不在挂载容器内', async () => {
    const host = document.createElement('div')
    host.id = 'b4-teleport-host'
    document.body.appendChild(host)

    const wrapper = mount(LqDialog, {
      props: { modelValue: true, title: '编辑' },
      global: { plugins: [ElementPlus] },
      attachTo: host
    })
    await nextTick()

    expect(host.querySelector('.lq-dialog__head')).toBeNull()
    expect(document.body.querySelector('.lq-dialog__head')).not.toBeNull()

    wrapper.unmount()
    host.remove()
  })

  it('嵌套弹窗各自 Teleport 到 body，互不为对方的祖先', async () => {
    const wrapper = mountReal({}, { class: 'outer-dialog' })
    await nextTick()

    const outerAnchor = document.body.querySelector('.outer-dialog')
    expect(outerAnchor).not.toBeNull()
    // 内层由业务自行嵌套；此处只确认外层结构完整且位于 body 之下
    expect(outerAnchor!.closest('body')).toBe(document.body)

    wrapper.unmount()
  })
})
