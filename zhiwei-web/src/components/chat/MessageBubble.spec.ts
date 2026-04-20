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

describe('MessageBubble 文档附件卡片', () => {
  it('docx 附件显示「AI 可读取」徽标和文件名', () => {
    const message: Message = {
      id: 'm-docx',
      role: 'user',
      content: '请看这份合同',
      timestamp: Date.now(),
      attachments: [
        {
          fileId: 'att-1',
          url: '/api/attachments/att-1',
          filename: '合同.docx',
          size: 102400,
          type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
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

    expect(wrapper.text()).toContain('合同.docx')
    expect(wrapper.text()).toContain('AI 可读取')
  })

  it('未知二进制附件不显示「AI 可读取」徽标', () => {
    const message: Message = {
      id: 'm-bin',
      role: 'user',
      content: '',
      timestamp: Date.now(),
      attachments: [
        {
          fileId: 'att-2',
          url: '/api/attachments/att-2',
          filename: 'data.bin',
          size: 2048,
          type: 'application/octet-stream',
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

    expect(wrapper.text()).toContain('data.bin')
    expect(wrapper.text()).not.toContain('AI 可读取')
  })

  it('xlsx 附件不显示「AI 可读取」徽标（Phase 0 无 xlsx parser）', () => {
    const message: Message = {
      id: 'm-xlsx',
      role: 'user',
      content: '这是一份报表',
      timestamp: Date.now(),
      attachments: [
        {
          fileId: 'att-xlsx',
          url: '/api/attachments/att-xlsx',
          filename: '报表.xlsx',
          size: 204800,
          type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
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

    // 文件名和通用卡片应正常渲染，但不能点亮「AI 可读取」徽标
    expect(wrapper.text()).toContain('报表.xlsx')
    expect(wrapper.text()).not.toContain('AI 可读取')
  })

  it('csv 附件显示「AI 可读取」徽标（PlainTextParser 支持 csv）', () => {
    const message: Message = {
      id: 'm-csv',
      role: 'user',
      content: '帮我看这份数据',
      timestamp: Date.now(),
      attachments: [
        {
          fileId: 'att-csv',
          url: '/api/attachments/att-csv',
          filename: '销售数据.csv',
          size: 8192,
          type: 'text/csv',
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

    expect(wrapper.text()).toContain('销售数据.csv')
    expect(wrapper.text()).toContain('AI 可读取')
  })
})

describe('MessageBubble 权限审批状态展示', () => {
  it('确认后会立即展示紧凑授权记录，不需要刷新页面', () => {
    const message: Message = {
      id: 'assistant-permission',
      role: 'assistant',
      content: '',
      timestamp: Date.now()
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: true,
        streamingPermissionApprovals: {
          'req-1': {
            requestId: 'req-1',
            toolId: 'code.execute',
            toolName: '执行代码',
            actionType: 'EXECUTE_SHELL',
            riskLevel: 'HIGH',
            message: '本次需要运行本地代码。请选择授权范围。',
            availableSubjectTypes: ['SESSION', 'USER'],
            recommendedSubjectType: 'SESSION',
            timestamp: new Date().toISOString()
          }
        },
        streamingPermissionApprovalResolutions: {
          'req-1': 'approved'
        }
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

    expect(wrapper.text()).toContain('已授权')
    expect(wrapper.text()).toContain('本会话')
    expect(wrapper.text()).toContain('命令 / 代码执行')
    expect(wrapper.text()).not.toContain('请选择授权范围')
  })
})
