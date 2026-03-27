import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from './ConfirmDialog.vue'

describe('ConfirmDialog', () => {
  it('点击确认按钮会触发 confirm 事件', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: {
        title: '删除知识库',
        message: '确认删除吗？',
        show: true,
        confirmLabel: '删除',
      },
      global: {
        stubs: {
          AlertDialog: { template: '<div><slot /></div>' },
          AlertDialogContent: { template: '<div><slot /></div>' },
          AlertDialogHeader: { template: '<div><slot /></div>' },
          AlertDialogTitle: { template: '<div><slot /></div>' },
          AlertDialogDescription: { template: '<div><slot /></div>' },
          AlertDialogFooter: { template: '<div><slot /></div>' },
          AlertDialogCancel: { template: '<div><slot /></div>' },
          AlertDialogAction: { template: '<div><slot /></div>' },
        },
      },
    })

    const confirmButton = wrapper.findAll('button').find(button => button.text() === '删除')
    expect(confirmButton?.exists()).toBe(true)

    await confirmButton!.trigger('click')

    expect(wrapper.emitted('confirm')).toBeTruthy()
    expect(wrapper.emitted('update:show')?.[0]).toEqual([false])
  })
})
