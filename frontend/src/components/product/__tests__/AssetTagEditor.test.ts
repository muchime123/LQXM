import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AssetTagEditor from '../AssetTagEditor.vue'

const LqDialogStub = {
  name: 'LqDialog',
  emits: ['confirm', 'close', 'cancel'],
  template: '<div><slot /><button data-testid="confirm" @click="$emit(\'confirm\')">确认</button></div>'
}

describe('AssetTagEditor', () => {
  it('saves content tags while keeping the current machine scope by default', async () => {
    const wrapper = mount(AssetTagEditor, {
      props: {
        visible: true,
        mode: 'single',
        asset: { id: 5221, fileName: 'product.png', category: 'main_image', machineTierId: 8, machineTierLabel: '500机型', contentTags: '设备正面' },
        selectedCount: 0,
        batchTagReplace: false,
        tagSuggestions: ['细节特写']
      },
      global: { stubs: { Teleport: true, LqDialog: LqDialogStub } }
    })

    await wrapper.get('.tag-suggestions button').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    expect(wrapper.emitted('submit-single')?.[0]?.[0]).toMatchObject({
      id: 5221,
      contentTags: '设备正面，细节特写',
      machineTierAction: 'KEEP',
      machineTierIds: []
    })
  })

  it('explicitly changes an asset to product-wide scope', async () => {
    const wrapper = mount(AssetTagEditor, {
      props: {
        visible: true,
        mode: 'single',
        asset: { id: 6001, fileName: 'device.mp4', category: 'device_video', machineTierId: 8, contentTags: '客户现场' },
        selectedCount: 0,
        batchTagReplace: false,
        tagSuggestions: []
      },
      global: { stubs: { Teleport: true, LqDialog: LqDialogStub } }
    })

    await wrapper.find('input[value="UNLINK"]').setValue(true)
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    expect(wrapper.emitted('submit-single')?.[0]?.[0]).toMatchObject({
      id: 6001,
      contentTags: '客户现场',
      machineTierAction: 'UNLINK',
      machineTierIds: []
    })
  })

  it('batch tag editing keeps machine scope unless explicitly changed', async () => {
    const wrapper = mount(AssetTagEditor, {
      props: {
        visible: true,
        mode: 'batch',
        asset: null,
        selectedCount: 2,
        batchTagReplace: false,
        tagSuggestions: ['操作面板']
      },
      global: { stubs: { Teleport: true, LqDialog: LqDialogStub } }
    })

    await wrapper.get('.tag-suggestions button').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    expect(wrapper.emitted('submit-batch')?.[0]?.[0]).toMatchObject({
      contentTags: '操作面板',
      tagAction: 'APPEND',
      machineTierAction: 'KEEP',
      machineTierIds: []
    })
  })
})
