import { describe, it, expect, beforeAll } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageBubble from './MessageBubble.vue'
import type { Message } from '@/types'
import { createPinia, setActivePinia } from 'pinia'

let pinia: ReturnType<typeof createPinia>

beforeAll(() => {
  pinia = createPinia()
  setActivePinia(pinia)
})

// Feature: multimodal-completion, Property 14: 视频附件渲染完整性
describe('MessageBubble 视频附件渲染（Property 14）', () => {
  it('为 video/* 附件渲染 <video> 元素并显示文件名与大小', () => {
    const baseMessage: Message = {
      id: 'm1',
      role: 'assistant',
      content: '带视频附件的消息',
      timestamp: Date.now()
    } as any

    const message: Message = {
      ...baseMessage,
      attachments: [
        {
          fileId: 'v1',
          url: 'https://example.com/video.mp4',
          filename: 'demo-video.mp4',
          size: 1024 * 512, // 512 KB
          type: 'video/mp4',
          isImage: false
        }
      ]
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>'
          }
        }
      }
    })

    // 1. 存在 <video> 播放器元素
    const video = wrapper.find('video')
    expect(video.exists()).toBe(true)
    expect(video.attributes('src')).toBe('https://example.com/video.mp4')

    // 2. 显示文件名
    expect(wrapper.text()).toContain('demo-video.mp4')

    // 3. 显示格式化后的大小（以 KB 为单位）
    expect(wrapper.text()).toMatch(/512\.0 KB/)
  })
})

