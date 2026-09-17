import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import LqPreview from '../LqPreview.vue'

const wrappers: VueWrapper[] = []

function factory(props: Record<string, unknown> = {}) {
  const trigger = document.createElement('button')
  trigger.textContent = '打开预览'
  document.body.appendChild(trigger)
  trigger.focus()

  const wrapper = mount(LqPreview, {
    props: {
      modelValue: false,
      title: '资料预览',
      showOpenInNewWindow: false,
      showDownload: false,
      ...props
    },
    slots: { default: '<div class="preview-content">预览内容</div>' },
    attachTo: document.body
  })
  wrappers.push(wrapper)
  return { wrapper, trigger }
}

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
  document.body.style.overflow = ''
})

describe('LqPreview', () => {
  it('provides modal semantics and focuses the preview when opened', async () => {
    const { wrapper } = factory()
    await wrapper.setProps({ modelValue: true })
    await nextTick()

    const dialog = document.querySelector<HTMLElement>('.lq-preview')!
    expect(dialog.getAttribute('role')).toBe('dialog')
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expect(dialog.getAttribute('aria-label')).toBe('资料预览')
    expect(document.activeElement).toBe(dialog)
    expect(document.body.style.overflow).toBe('hidden')
  })

  it('closes with Escape and restores focus and the previous body overflow', async () => {
    document.body.style.overflow = 'auto'
    const { wrapper, trigger } = factory()
    await wrapper.setProps({ modelValue: true })
    await nextTick()

    document
      .querySelector<HTMLElement>('.lq-preview')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    expect(wrapper.emitted('close')).toHaveLength(1)

    await wrapper.setProps({ modelValue: false })
    await nextTick()
    expect(document.body.style.overflow).toBe('auto')
    expect(document.activeElement).toBe(trigger)
  })

  it('emits gallery navigation only when multiple items exist', async () => {
    const { wrapper } = factory({ galleryCount: 3, galleryIndex: 1 })
    await wrapper.setProps({ modelValue: true })
    await nextTick()
    const dialog = document.querySelector<HTMLElement>('.lq-preview')!

    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))

    expect(wrapper.emitted('prev')).toHaveLength(1)
    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(document.body.textContent).toContain('2 / 3')
  })

  it('supports zoom, rotation, and reset for image previews', async () => {
    const { wrapper } = factory({ previewType: 'image' })
    await wrapper.setProps({ modelValue: true })
    await nextTick()

    const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('.lq-preview__toolbar button'))
    const button = (title: string) => buttons.find(item => item.title === title)!
    const body = document.querySelector<HTMLElement>('.lq-preview__body')!

    button('放大').click()
    button('向右旋转').click()
    await nextTick()
    expect(body.style.getPropertyValue('--img-scale')).toBe('1.1')
    expect(body.style.getPropertyValue('--img-rotate')).toBe('90deg')

    button('重置').click()
    await nextTick()
    expect(body.style.getPropertyValue('--img-scale')).toBe('1')
    expect(body.style.getPropertyValue('--img-rotate')).toBe('0deg')
  })
})
