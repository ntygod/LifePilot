import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PromptGallery from './PromptGallery.vue'

describe('PromptGallery 空态轻量建议', () => {
  it('默认展示内联轻提示而不是能力卡片', () => {
    const wrapper = mount(PromptGallery)

    expect(wrapper.find('.prompt-gallery__prefix').text()).toBe('试试')
    expect(wrapper.text()).toContain('整理资料')
    expect(wrapper.text()).toContain('规划下一步')
    expect(wrapper.text()).toContain('润色文字')
    expect(wrapper.text()).not.toContain('记住偏好')
    expect(wrapper.findAll('.prompt-suggestion')).toHaveLength(3)
    expect(wrapper.find('.prompt-card').exists()).toBe(false)
    expect(wrapper.find('.prompt-suggestion').exists()).toBe(true)
    expect(wrapper.find('nav[aria-label="对话建议"]').exists()).toBe(true)
    expect(wrapper.findAll('.prompt-gallery__separator')).toHaveLength(2)
  })

  it('点击建议时把 prompt 抛给上层输入框', async () => {
    const wrapper = mount(PromptGallery, {
      props: {
        suggestions: [
          {
            id: 'one',
            label: '整理资料',
            prompt: '请整理以下资料：',
          },
        ],
      },
    })

    await wrapper.find('button').trigger('click')

    expect(wrapper.emitted('pick')?.[0]).toEqual([
      {
        id: 'one',
        label: '整理资料',
        prompt: '请整理以下资料：',
      },
    ])
  })
})
