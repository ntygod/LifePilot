import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import type { Message } from '@/types'
import { createPinia, setActivePinia } from 'pinia'
import { copyToClipboard } from '@/utils/clipboard'

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: vi.fn(),
}))

let pinia: ReturnType<typeof createPinia>
const memorySettleTitle = '放入输入框，发送后后台整理为记忆'

beforeAll(() => {
  pinia = createPinia()
  setActivePinia(pinia)
})

beforeEach(() => {
  vi.mocked(copyToClipboard).mockReset()
  vi.mocked(copyToClipboard).mockResolvedValue(true)
})

function mockScrollIntoView() {
  const scrollIntoView = vi.fn()
  Object.defineProperty(window.HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  })
  return scrollIntoView
}

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

  it('xlsx 附件显示「AI 可读取」徽标', () => {
    const message: Message = {
      id: 'm3',
      role: 'user',
      content: '看销售表',
      timestamp: Date.now(),
      attachments: [
        {
          fileId: 'att-3',
          url: '/api/attachments/att-3',
          filename: '销售.xlsx',
          size: 51200,
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

    expect(wrapper.text()).toContain('销售.xlsx')
    expect(wrapper.text()).toContain('AI 可读取')
  })

  it('pptx 附件显示「AI 可读取」徽标', () => {
    const message: Message = {
      id: 'm4',
      role: 'user',
      content: '看方案',
      timestamp: Date.now(),
      attachments: [
        {
          fileId: 'att-4',
          url: '/api/attachments/att-4',
          filename: '方案.pptx',
          size: 204800,
          type: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
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

    expect(wrapper.text()).toContain('方案.pptx')
    expect(wrapper.text()).toContain('AI 可读取')
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

describe('MessageBubble 记忆沉淀入口', () => {
  it('assistant 消息可以触发记住事件', async () => {
    const message: Message = {
      id: 'assistant-memory-action',
      role: 'assistant',
      content: '以后写方案时优先给出简洁版本。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.find('button[title="记住这条消息"]').exists()).toBe(false)

    await wrapper.find(`button[title="${memorySettleTitle}"]`).trigger('click')

    expect(wrapper.emitted('remember')?.[0]?.[0]).toMatchObject({
      id: 'assistant-memory-action',
      content: '以后写方案时优先给出简洁版本。',
    })
  })

  it('用户消息也可以触发记住事件', async () => {
    const message: Message = {
      id: 'user-memory-action',
      role: 'user',
      content: '我偏好本地优先，界面要轻。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: defineComponent({
            name: 'RouterLink',
            props: {
              to: {
                type: [String, Object],
                required: false,
              },
            },
            setup(props, { attrs, slots }) {
              const target = props.to ?? attrs.to
              return () => h(
                'a',
                {
                  ...attrs,
                  'data-to': JSON.stringify(target),
                },
                slots.default?.(),
              )
            },
          }),
        },
      },
    })

    expect(wrapper.find('button[title="记住这条消息"]').exists()).toBe(false)

    await wrapper.find(`button[title="${memorySettleTitle}"]`).trigger('click')

    expect(wrapper.emitted('remember')?.[0]?.[0]).toMatchObject({
      id: 'user-memory-action',
      content: '我偏好本地优先，界面要轻。',
    })
  })
})

describe('MessageBubble 文本产出沉淀入口', () => {
  it('assistant 消息可以触发存为资料事件', async () => {
    const message: Message = {
      id: 'assistant-save-knowledge',
      role: 'assistant',
      content: '这是可以存入资料库的结论。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    await wrapper.find('button[title="沉淀这条消息"]').trigger('click')
    await wrapper.find('button[title="存为资料：产品资料"]').trigger('click')

    expect(wrapper.emitted('save-knowledge')?.[0]?.[0]).toMatchObject({
      id: 'assistant-save-knowledge',
      content: '这是可以存入资料库的结论。',
    })
  })

  it('assistant 消息存入资料后在沉淀入口显示完成态', async () => {
    const message: Message = {
      id: 'assistant-saved-knowledge',
      role: 'assistant',
      content: '这条回复已经沉淀进资料库。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
        savedKnowledgeBaseName: '产品资料',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const trigger = wrapper.find('button[title="已存为资料：产品资料"]')
    expect(trigger.exists()).toBe(true)
    expect(wrapper.text()).toContain('已存入资料库')
    expect(wrapper.text()).toContain('产品资料')

    const settledLink = wrapper.findAll('a')
      .find(link => link.text().includes('已存入资料库'))
    expect(settledLink?.attributes('title')).toBe('已存入资料库，点击打开资料库')

    await trigger.trigger('click')

    const savedButtons = wrapper.findAll('button[title="已存为资料：产品资料"]')
    expect(savedButtons).toHaveLength(2)
    await savedButtons[1].trigger('click')

    expect(wrapper.emitted('save-knowledge')).toBeUndefined()
  })

  it('assistant 消息存资料失败后在消息内显示原因并允许重试', async () => {
    const message: Message = {
      id: 'assistant-save-knowledge-failed',
      role: 'assistant',
      content: '这是可以重试存入资料库的结论。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
        saveKnowledgeError: '索引服务不可用',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('资料未存入')
    expect(wrapper.text()).toContain('索引服务不可用')

    await wrapper.find('button[title="沉淀这条消息（资料未存入）"]').trigger('click')
    await wrapper.find('button[title="重试存为资料：产品资料"]').trigger('click')

    expect(wrapper.emitted('save-knowledge')?.[0]).toEqual([message])
  })

  it('历史消息带资料库沉淀记录时即使当前未选资料库也显示完成态', () => {
    const message: Message = {
      id: 'assistant-history-saved-knowledge',
      role: 'assistant',
      content: '这条历史回复刷新后仍然应该看到资料库沉淀状态。',
      timestamp: Date.now(),
      knowledgeSettlements: [
        {
          knowledgeBaseId: 'kb-product',
          knowledgeBaseName: '产品资料',
          sourceType: 'MESSAGE_TEXT',
          savedAt: '2026-07-07T04:00:00Z',
        },
      ],
    }

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            props: ['to'],
            template: '<a :data-to="JSON.stringify(to)"><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('已存入资料库')
    expect(wrapper.text()).toContain('产品资料')
    expect(wrapper.find('a[data-to*="kb-product"]').exists()).toBe(true)
  })

  it('历史文件产物带资料库沉淀记录时产物卡片恢复完成态', () => {
    const message: Message = {
      id: 'assistant-history-saved-artifact',
      role: 'assistant',
      content: '报告已经生成并存入资料库。',
      timestamp: Date.now(),
      artifactRefs: [
        {
          artifactId: 'artifact-report',
          fileName: 'report.md',
          mimeType: 'text/markdown',
          kind: 'FILE',
          size: 8,
          downloadUrl: '/api/artifacts/artifact-report/download',
        },
        {
          artifactId: 'artifact-plan',
          fileName: 'plan.md',
          mimeType: 'text/markdown',
          kind: 'FILE',
          size: 12,
          downloadUrl: '/api/artifacts/artifact-plan/download',
        },
      ],
      knowledgeSettlements: [
        {
          knowledgeBaseId: 'kb-product',
          knowledgeBaseName: '产品资料',
          sourceType: 'ARTIFACT',
          artifactId: 'artifact-report',
          fileName: 'report.md',
          savedAt: '2026-07-07T04:00:00Z',
        },
        {
          knowledgeBaseId: 'kb-product',
          knowledgeBaseName: '产品资料',
          sourceType: 'ARTIFACT',
          artifactId: 'artifact-plan',
          fileName: 'plan.md',
          savedAt: '2026-07-07T04:01:00Z',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            props: ['to'],
            template: '<a :data-to="JSON.stringify(to)"><slot /></a>',
          },
          ArtifactCard: defineComponent({
            name: 'ArtifactCard',
            props: {
              artifactId: String,
              fileName: String,
              savedKnowledgeBaseName: String,
            },
            setup(props) {
              return () => h(
                'div',
                { class: 'artifact-card-stub' },
                `${props.artifactId}:${props.savedKnowledgeBaseName ?? ''}`,
              )
            },
          }),
        },
      },
    })

    expect(wrapper.text()).toContain('已存入资料库')
    expect(wrapper.text()).toContain('产品资料 · 2 个文件')
    expect(wrapper.text()).toContain('artifact-report:产品资料')
    expect(wrapper.text()).toContain('artifact-plan:产品资料')
    expect(wrapper.find('a[data-to*="kb-product"]').exists()).toBe(true)
  })
})

describe('MessageBubble 执行上下文浮现', () => {
  it('用户消息显示本轮强化使用记忆的上下文选择', () => {
    const message: Message = {
      id: 'user-memory-focused',
      role: 'user',
      content: '按我的偏好整理一下这段文字',
      timestamp: Date.now(),
      singleTurnOverride: {
        memoryContextMode: 'focused',
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('本轮用记忆')
    expect(wrapper.text()).toContain('会参考偏好和事实')
    expect(wrapper.find('.context-usage__item--success').exists()).toBe(true)
  })

  it('用户消息显示本轮不用记忆的上下文选择', () => {
    const message: Message = {
      id: 'user-memory-off',
      role: 'user',
      content: '只根据这段文字总结',
      timestamp: Date.now(),
      singleTurnOverride: {
        memoryContextMode: 'off',
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('本轮不用记忆')
    expect(wrapper.text()).toContain('只按当前消息处理')
    expect(wrapper.find('.context-usage__item--warning').exists()).toBe(true)
  })

  it('用户消息显示本轮带入的资料上下文', () => {
    const message: Message = {
      id: 'user-knowledge-context',
      role: 'user',
      content: '结合我选的资料整理一下',
      timestamp: Date.now(),
      singleTurnOverride: {
        knowledgeBaseIds: ['kb-product', ' kb-roadmap ', ''],
        memoryContextMode: 'focused',
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('本轮用资料')
    expect(wrapper.text()).toContain('2 个资料库')
    expect(wrapper.text()).toContain('本轮用记忆')
    expect(wrapper.text()).toContain('会参考偏好和事实')
  })

  it('助手消息显示本轮按要求未联网的执行边界', () => {
    const message: Message = {
      id: 'assistant-no-web',
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      executionConstraints: {
        disabledTools: [
          { id: 'web', label: '联网搜索', reason: '按本轮用户要求禁用' },
          { id: 'browser', label: '浏览器操作', reason: '按本轮用户要求禁用' },
        ],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('本轮未联网')
    expect(wrapper.text()).toContain('联网搜索、浏览器操作已关闭')
    expect(wrapper.find('.context-usage__item--warning').exists()).toBe(true)
  })

  it('在助手消息内展示资料、工具和产物摘要', async () => {
    const message: Message = {
      id: 'assistant-context',
      role: 'assistant',
      content: '我已经整理好了。',
      timestamp: Date.now(),
      sources: [
        {
          type: 'knowledgeBase',
          id: 'kb-1',
          name: '产品资料',
        },
        {
          type: 'memory',
          id: 'mem-1',
          name: '用户偏好',
          extra: {
            entityTypeLabel: '偏好',
            description: '用户希望主界面保持轻量。',
          },
        },
      ],
      memoryChanges: [
        {
          type: 'memory',
          id: 'mem-new-1',
          name: '主界面偏好',
          extra: {
            operationLabel: '新增',
            entityTypeLabel: '偏好',
            entityType: 'PREFERENCE',
            temporality: 'PERSISTENT',
            importanceScore: 0.82,
            evidenceExcerpt: '用户说喜欢轻量主界面',
          },
        },
      ],
      toolsSummary: [
        {
          toolId: 'web.search',
          success: true,
          latencyMs: 120,
        },
        {
          toolId: 'file.write',
          success: true,
          latencyMs: 80,
        },
      ],
      artifactRefs: [
        {
          artifactId: 'artifact-1',
          kind: 'DOCUMENT',
          fileName: '调研结果.md',
          mimeType: 'text/markdown',
          size: 2048,
          downloadUrl: '/api/artifacts/artifact-1/download',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a><slot /></a>',
          },
          ArtifactCard: defineComponent({
            name: 'ArtifactCard',
            props: {
              artifactId: String,
              fileName: String,
            },
            emits: ['saved-knowledge'],
            setup(props, { emit }) {
              return () => h(
                'button',
                {
                  type: 'button',
                  class: 'artifact-card-stub',
                  onClick: () => emit('saved-knowledge', {
                    artifactId: props.artifactId,
                    fileName: props.fileName,
                    knowledgeBaseId: 'kb-product',
                    knowledgeBaseName: '产品资料',
                  }),
                },
                props.fileName,
              )
            },
          }),
        },
      },
    })

    expect(wrapper.text()).toContain('参考了 1 个资料库')
    expect(wrapper.text()).toContain('参考了 1 条记忆')
    expect(wrapper.text()).toContain('产品资料')
    expect(wrapper.text()).toContain('用户偏好')
    expect(wrapper.text()).not.toContain('执行完成')
    expect(wrapper.text()).toContain('已查资料、处理文件并整理结果')
    expect(wrapper.text()).not.toContain('已查资料并整理回答')
    expect(wrapper.text()).toContain('生成 1 个文件')
    expect(wrapper.text()).toContain('沉淀 1 条记忆')
    expect(wrapper.text()).not.toContain('已完成 2 个工具')
    expect(wrapper.text()).toContain('用户偏好')
    expect(wrapper.text()).toContain('偏好')
    expect(wrapper.text()).toContain('沉淀 1 条记忆')
    expect(wrapper.text()).toContain('知微记住了')
    expect(wrapper.text()).toContain('新增 1 条偏好')
    expect(wrapper.text()).not.toContain('因为你本轮提到「用户说喜欢轻量主界面」，知微已新增这条偏好。')
    expect(wrapper.text()).not.toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高')
    expect(wrapper.text()).toContain('主界面偏好')
    expect(wrapper.text()).toContain('新增 · 偏好')
    expect(wrapper.text()).not.toContain('调用 2 个工具')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)

    const referenceLinks = wrapper.findAllComponents({ name: 'RouterLink' })
      .filter(link => link.text().includes('参考了 1 个资料库'))
    expect(referenceLinks).toHaveLength(1)
    expect(referenceLinks[0].props('to')).toEqual({
      name: 'knowledgeBaseDetail',
      params: {
        id: 'kb-1',
      },
    })

    const referenceButton = wrapper.find('button[aria-label="参考了 1 条记忆，点击查看和调整"]')
    expect(referenceButton.exists()).toBe(true)
    await referenceButton.trigger('click')
    expect(wrapper.emitted('inspect-memory')?.[0]).toEqual([message.sources![1]])

    const explainButton = wrapper.find('button[aria-label="展开记忆沉淀说明"]')
    expect(explainButton.exists()).toBe(true)
    await explainButton.trigger('click')

    expect(wrapper.text()).toContain('因为你本轮提到「用户说喜欢轻量主界面」，知微已新增这条偏好。')
    expect(wrapper.text()).toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高')
  })

  it('技能型来源在主对话摘要里显示为技能而不是记忆', () => {
    const message: Message = {
      id: 'assistant-skill-reference',
      role: 'assistant',
      content: '我参考了你的技能背景。',
      timestamp: Date.now(),
      sources: [
        {
          type: 'memory',
          id: 'skill-backend',
          name: '后端开发',
          extra: {
            entityType: 'SKILL',
            entityTypeLabel: '技能',
            usageReason: '这条回答参考了「后端开发」这条技能。',
          },
        },
        {
          type: 'memory',
          id: 'skill-algorithm',
          name: '算法工程',
          extra: {
            entityType: 'SKILL',
            entityTypeLabel: '技能',
            usageReason: '这条回答参考了「算法工程」这条技能。',
          },
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('参考了 2 条技能')
    expect(wrapper.text()).not.toContain('参考了 2 条记忆')
  })

  it('技能型本轮沉淀在主对话摘要里显示为技能而不是记忆', async () => {
    const message: Message = {
      id: 'assistant-skill-change',
      role: 'assistant',
      content: '我会按你的技能背景来处理后续任务。',
      timestamp: Date.now(),
      memoryChanges: [
        {
          type: 'memory',
          id: 'skill-backend',
          name: '后端开发',
          extra: {
            operationLabel: '新增',
            entityType: 'SKILL',
            entityTypeLabel: '技能',
            evidenceExcerpt: '擅长 Spring Boot 后端开发',
          },
        },
        {
          type: 'memory',
          id: 'skill-algorithm',
          name: '算法工程',
          extra: {
            operationLabel: '新增',
            entityType: 'SKILL',
            entityTypeLabel: '技能',
            description: '熟悉推荐系统和检索排序。',
          },
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('已沉淀 2 条技能')
    expect(wrapper.text()).toContain('知微掌握了技能')
    expect(wrapper.text()).toContain('新增 2 条技能')
    expect(wrapper.text()).not.toContain('已沉淀 2 条记忆')
    expect(wrapper.text()).not.toContain('本轮共沉淀 2 条技能')

    const explainButton = wrapper.find('button[aria-label="展开技能沉淀说明"]')
    expect(explainButton.exists()).toBe(true)
    await explainButton.trigger('click')

    expect(wrapper.find('.memory-change-panel').attributes('aria-label')).toBe('本轮技能整理结果')
    expect(wrapper.text()).toContain('本轮共沉淀 2 条技能')
    expect(wrapper.text()).not.toContain('本轮共沉淀 2 条记忆')
  })

  it('只有资料库来源时上下文摘要可跳转到资料来源', () => {
    const message: Message = {
      id: 'assistant-kb-reference',
      role: 'assistant',
      content: '我参考了产品资料。',
      timestamp: Date.now(),
      sources: [
        {
          type: 'knowledgeBase',
          id: 'kb-1',
          name: '产品资料',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('参考了 1 个资料库')
    const referenceLinks = wrapper.findAllComponents({ name: 'RouterLink' })
      .filter(link => link.text().includes('参考了 1 个资料库'))
    expect(referenceLinks).toHaveLength(1)
    expect(referenceLinks[0].props('to')).toEqual({
      name: 'knowledgeBaseDetail',
      params: {
        id: 'kb-1',
      },
    })
  })

  it('没有工具摘要但有产物时仍自然浮现本轮产出，并可定位到文件区', async () => {
    const scrollIntoView = mockScrollIntoView()
    const message: Message = {
      id: 'assistant-output-only',
      role: 'assistant',
      content: '文件已经生成。',
      timestamp: Date.now(),
      artifactRefs: [
        {
          artifactId: 'artifact-output-only',
          kind: 'FILE',
          fileName: '结果.md',
          mimeType: 'text/markdown',
          size: 1024,
          downloadUrl: '/api/artifacts/artifact-output-only/download',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a><slot /></a>',
          },
          ArtifactCard: defineComponent({
            name: 'ArtifactCard',
            props: {
              artifactId: String,
              fileName: String,
            },
            emits: ['saved-knowledge'],
            setup(props, { emit }) {
              return () => h(
                'button',
                {
                  type: 'button',
                  class: 'artifact-card-stub',
                  onClick: () => emit('saved-knowledge', {
                    artifactId: props.artifactId,
                    fileName: props.fileName,
                    knowledgeBaseId: 'kb-product',
                    knowledgeBaseName: '产品资料',
                  }),
                },
                props.fileName,
              )
            },
          }),
        },
      },
    })

    expect(wrapper.text()).toContain('生成 1 个文件')
    expect(wrapper.text()).toContain('结果.md')
    expect(wrapper.text()).not.toContain('已处理本轮任务')
    expect(wrapper.text()).not.toContain('已存入资料库')
    expect(wrapper.find('.context-usage__item--success').exists()).toBe(true)

    const outputButton = wrapper.find('button[aria-label="生成 1 个文件，点击查看产物"]')
    expect(outputButton.exists()).toBe(true)
    expect(wrapper.find('[aria-label="本轮生成的文件产物"]').exists()).toBe(true)

    await outputButton.trigger('click')
    expect(scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'nearest',
    })

    await wrapper.find('.artifact-card-stub').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已存入资料库')
    expect(wrapper.text()).toContain('产品资料')
    const settledLink = wrapper.findAllComponents({ name: 'RouterLink' })
      .find(link => link.text().includes('已存入资料库'))
    expect(settledLink?.props('to')).toEqual({
      name: 'knowledgeBaseDetail',
      params: { id: 'kb-product' },
    })
    expect(wrapper.emitted('save-artifact-knowledge')?.[0]).toEqual([
      expect.objectContaining({
        id: 'assistant-output-only',
      }),
      {
        artifactId: 'artifact-output-only',
        fileName: '结果.md',
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    ])
  })

  it('只有交互结果时产出摘要可定位到交互区', async () => {
    const scrollIntoView = mockScrollIntoView()
    const message: Message = {
      id: 'assistant-a2ui-output',
      role: 'assistant',
      content: '我做了一个可交互结果。',
      timestamp: Date.now(),
      a2uiComponents: [
        {
          id: 'a2ui-text-1',
          type: 'Text',
          properties: {
            text: '交互结果',
          },
          children: [],
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
          A2uiRenderer: {
            props: ['components'],
            template: '<div class="a2ui-renderer-stub">交互结果</div>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('生成交互结果')
    expect(wrapper.find('[aria-label="本轮生成的交互结果"]').exists()).toBe(true)

    const outputButton = wrapper.find('button[aria-label="生成交互结果，点击查看产物"]')
    expect(outputButton.exists()).toBe(true)
    await outputButton.trigger('click')

    expect(scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'nearest',
    })
  })

  it('没有工具调用但有记忆沉淀时只展示记忆沉淀，并可从状态跳到本轮记忆检查', async () => {
    const scrollIntoView = mockScrollIntoView()
    const change = {
      type: 'memory' as const,
      id: 'mem-new-1',
      name: '主界面偏好',
      extra: {
        operationLabel: '新增',
        entityTypeLabel: '偏好',
        entityType: 'PREFERENCE',
        temporality: 'PERSISTENT',
        importanceScore: 0.82,
        evidenceExcerpt: '主界面做轻，做好交互',
      },
    }
    const message: Message = {
      id: 'assistant-memory-outcome',
      role: 'assistant',
      content: '我会记住你更喜欢轻量主界面。',
      timestamp: Date.now(),
      memoryChanges: [change],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).not.toContain('本轮完成')
    expect(wrapper.text()).not.toContain('执行完成')
    expect(wrapper.text()).toContain('已沉淀 1 条记忆')
    expect(wrapper.text()).toContain('知微记住了')
    expect(wrapper.text()).toContain('新增 1 条偏好')
    expect(wrapper.text()).not.toContain('因为你本轮提到「主界面做轻，做好交互」，知微已新增这条偏好。')
    expect(wrapper.text()).not.toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高')
    expect(wrapper.text()).toContain('主界面偏好')
    expect(wrapper.text()).toContain('记忆管理')
    expect(wrapper.find('.memory-change-panel__manage').exists()).toBe(true)

    const explainButton = wrapper.find('button[aria-label="展开记忆沉淀说明"]')
    expect(explainButton.exists()).toBe(true)
    await explainButton.trigger('click')
    expect(wrapper.text()).toContain('因为你本轮提到「主界面做轻，做好交互」，知微已新增这条偏好。')
    expect(wrapper.text()).toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高')

    const settledButton = wrapper.find('button[aria-label="已沉淀 1 条记忆，点击查看本轮记忆"]')
    expect(settledButton.exists()).toBe(true)
    await settledButton.trigger('click')
    await nextTick()

    expect(scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'nearest',
    })
    expect(wrapper.emitted('inspect-memory')).toBeUndefined()
    expect(wrapper.find('.memory-change-panel').attributes('aria-label')).toBe('本轮记忆整理结果')
  })

  it('多条记忆沉淀默认收起说明并可展开查看各自依据', async () => {
    const message: Message = {
      id: 'assistant-memory-multiple',
      role: 'assistant',
      content: '我会记住这几个偏好。',
      timestamp: Date.now(),
      memoryChanges: [
        {
          type: 'memory',
          id: 'mem-new-1',
          name: '主界面偏好',
          extra: {
            operationLabel: '新增',
            entityTypeLabel: '偏好',
            entityType: 'PREFERENCE',
            temporality: 'PERSISTENT',
            importanceScore: 0.82,
            evidenceExcerpt: '主界面做轻，做好交互',
          },
        },
        {
          type: 'memory',
          id: 'mem-new-2',
          name: '任务恢复偏好',
          extra: {
            operationLabel: '新增',
            entityTypeLabel: '经验',
            entityType: 'PROCEDURE',
            description: '用户希望 tool/skill 能恢复任务。',
            temporality: 'PERSISTENT',
            importanceScore: 0.7,
          },
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).not.toContain('本轮共沉淀 2 条记忆')
    expect(wrapper.text()).toContain('已沉淀 2 条记忆')
    expect(wrapper.text()).not.toContain('主界面偏好：因为「主界面做轻，做好交互」')
    expect(wrapper.text()).not.toContain('任务恢复偏好：用户希望 tool/skill 能恢复任务。')
    expect(wrapper.text()).not.toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高；会帮助知微复用做事步骤')

    await wrapper.find('button[aria-label="展开记忆沉淀说明"]').trigger('click')

    expect(wrapper.text()).toContain('本轮共沉淀 2 条记忆')
    expect(wrapper.text()).toContain('主界面偏好：因为「主界面做轻，做好交互」')
    expect(wrapper.text()).toContain('任务恢复偏好：用户希望 tool/skill 能恢复任务。')
    expect(wrapper.text()).toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高；会帮助知微复用做事步骤')
  })

  it('记忆沉淀后台检查时显示可解释的轻量面板', () => {
    const message: Message = {
      id: 'assistant-memory-checking',
      role: 'assistant',
      content: '我先回答你的问题。',
      timestamp: Date.now(),
      memoryChangeStatus: 'checking',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('正在整理记忆')
    expect(wrapper.text()).toContain('后台处理中')
    expect(wrapper.text()).toContain('回答已完成，知微正在后台整理可长期复用的信息；你可以继续对话。')
    expect(wrapper.text()).toContain('整理完成后会在这里显示，可查看、调整或忘记。')
    expect(wrapper.text()).not.toContain('已沉淀 1 条记忆')
    expect(wrapper.text()).not.toContain('沉淀 1 条记忆')
    expect(wrapper.find('.memory-change-panel--checking').exists()).toBe(true)
    expect(wrapper.find('.context-usage__item--checking').exists()).toBe(false)
    expect(wrapper.find('.memory-change-panel__inspect').exists()).toBe(false)
    expect(wrapper.find('.memory-change-panel__manage').exists()).toBe(false)
  })

  it('本轮忘记记忆时在主对话内给出可解释确认', async () => {
    const message: Message = {
      id: 'assistant-memory-delete',
      role: 'assistant',
      content: '好的，这条我以后不再默认参考。',
      timestamp: Date.now(),
      memoryChanges: [
        {
          type: 'memory',
          id: 'memory-delete-1',
          name: '下午日志提醒偏好',
          extra: {
            operation: 'DELETE',
            operationLabel: '忘记',
            entityType: 'PREFERENCE',
            entityTypeLabel: '偏好',
            evidenceExcerpt: '以后不要再提醒我下午5点检查日志',
          },
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('知微忘记了')
    expect(wrapper.text()).toContain('已忘记 1 条记忆')
    expect(wrapper.text()).toContain('忘记 1 条偏好')
    expect(wrapper.text()).toContain('下午日志提醒偏好')
    expect(wrapper.text()).toContain('后续不再默认参考这条记忆')
    expect(wrapper.text()).not.toContain('需要时可以重新告诉知微要记住什么')

    await wrapper.find('button[aria-label="展开记忆沉淀说明"]').trigger('click')

    expect(wrapper.text()).toContain('知微已忘记这条偏好。')
    expect(wrapper.text()).toContain('后续影响：后续不再默认参考这条记忆；需要时可以重新告诉知微要记住什么')
  })

  it('记忆检查无新增时默认只给轻提示并可展开说明', async () => {
    const message: Message = {
      id: 'assistant-memory-empty',
      role: 'assistant',
      content: '我先回答你的问题。',
      timestamp: Date.now(),
      memoryChangeStatus: 'checked-empty',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('记忆已检查')
    expect(wrapper.text()).toContain('无需新增')
    expect(wrapper.text()).not.toContain('这轮没有发现需要写入长期记忆的新信息。')
    expect(wrapper.text()).not.toContain('结果：不会改动现有记忆')
    expect(wrapper.find('.memory-change-panel--checking').exists()).toBe(false)
    expect(wrapper.find('.memory-change-panel__inspect').exists()).toBe(false)
    expect(wrapper.find(`button.memory-change-panel__remember[aria-label="${memorySettleTitle}"]`).exists()).toBe(true)
    expect(wrapper.text()).toContain('记忆管理')
    expect(wrapper.find('.memory-change-panel__manage').exists()).toBe(true)

    await wrapper.find(`button.memory-change-panel__remember[aria-label="${memorySettleTitle}"]`).trigger('click')

    expect(wrapper.emitted('remember')?.[0]).toEqual([message])

    await wrapper.find('button[aria-label="展开记忆沉淀说明"]').trigger('click')

    expect(wrapper.text()).toContain('这轮没有发现需要写入长期记忆的新信息。')
    expect(wrapper.text()).toContain('结果：不会改动现有记忆')
  })

  it('记忆管理入口会保留项目上下文', () => {
    const message: Message = {
      id: 'assistant-memory-empty-project',
      role: 'assistant',
      content: '我先回答你的问题。',
      timestamp: Date.now(),
      memoryChangeStatus: 'checked-empty',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        projectId: ' project-1 ',
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const link = wrapper.findComponent({ name: 'RouterLink' })
    expect(link.props('to')).toEqual({
      name: 'memories',
      query: {
        tab: 'entities',
        projectId: 'project-1',
      },
    })
  })

  it('记忆沉淀完成后的管理入口会定位到本轮记忆', () => {
    const message: Message = {
      id: 'assistant-memory-settled-project',
      role: 'assistant',
      content: '我已经记住你的主界面偏好。',
      timestamp: Date.now(),
      memoryChanges: [
        {
          type: 'memory',
          id: 'mem-new-1',
          name: '主界面偏好',
          extra: {
            operationLabel: '新增',
            entityTypeLabel: '偏好',
          },
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        projectId: ' project-1 ',
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const links = wrapper.findAllComponents({ name: 'RouterLink' })
    const memoryLink = links.find(link => link.props('to')?.name === 'memories')
    expect(memoryLink?.props('to')).toEqual({
      name: 'memories',
      query: {
        tab: 'entities',
        entityId: 'mem-new-1',
        projectId: 'project-1',
      },
    })
  })

  it('记忆整理失败时说明回答不受影响', async () => {
    const message: Message = {
      id: 'assistant-memory-failed',
      role: 'assistant',
      content: '我先回答你的问题。',
      timestamp: Date.now(),
      memoryChangeStatus: 'failed',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('记忆整理未完成')
    expect(wrapper.text()).toContain('可手动记住')
    expect(wrapper.text()).not.toContain('稍后可重试')
    expect(wrapper.text()).not.toContain('这轮回答已经完成，但后台自动整理记忆没有成功。')
    expect(wrapper.find('.memory-change-panel--failed').exists()).toBe(true)
    expect(wrapper.find('.memory-change-panel__inspect').exists()).toBe(false)
    expect(wrapper.find(`button.memory-change-panel__remember[aria-label="${memorySettleTitle}"]`).exists()).toBe(true)
    expect(wrapper.text()).toContain('记忆管理')
    expect(wrapper.find('.memory-change-panel__manage').exists()).toBe(true)

    await wrapper.find(`button.memory-change-panel__remember[aria-label="${memorySettleTitle}"]`).trigger('click')

    expect(wrapper.emitted('remember')?.[0]).toEqual([message])

    await wrapper.find('button[aria-label="展开记忆沉淀说明"]').trigger('click')

    expect(wrapper.text()).toContain('这轮回答已经完成，但后台自动整理记忆没有成功。')
    expect(wrapper.text()).toContain('结果：不会影响本轮回答')
  })

  it('记忆沉淀被跳过时给出轻量解释', async () => {
    const message: Message = {
      id: 'assistant-memory-disabled',
      role: 'assistant',
      content: '我先回答你的问题。',
      timestamp: Date.now(),
      memoryChangeStatus: 'disabled',
      memoryChangeReason: 'memory_repository_unavailable',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('未写入记忆')
    expect(wrapper.text()).toContain('已跳过')
    expect(wrapper.text()).not.toContain('这轮没有写入长期记忆')
    expect(wrapper.find('.memory-change-panel--disabled').exists()).toBe(true)
    expect(wrapper.find('.memory-change-panel__inspect').exists()).toBe(false)
    expect(wrapper.find(`button.memory-change-panel__remember[aria-label="${memorySettleTitle}"]`).exists()).toBe(false)
    expect(wrapper.text()).toContain('记忆管理')
    expect(wrapper.find('.memory-change-panel__manage').exists()).toBe(true)

    await wrapper.find('button[aria-label="展开记忆沉淀说明"]').trigger('click')

    expect(wrapper.text()).toContain('这轮没有写入长期记忆。当前记忆存储暂不可用。')
    expect(wrapper.text()).toContain('结果：不会改动现有记忆')
  })

  it('用户要求不写入记忆时不暴露内部原因码', async () => {
    const message: Message = {
      id: 'assistant-memory-user-denied',
      role: 'assistant',
      content: '好的，本轮只按当前内容回答。',
      timestamp: Date.now(),
      memoryChangeStatus: 'disabled',
      memoryChangeReason: 'user_memory_write_denied',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('未写入记忆')
    expect(wrapper.text()).not.toContain('user_memory_write_denied')

    await wrapper.find('button[aria-label="展开记忆沉淀说明"]').trigger('click')

    expect(wrapper.text()).toContain('这轮没有写入长期记忆。已按你的要求跳过长期记忆写入。')
    expect(wrapper.text()).not.toContain('后台返回原因')
    expect(wrapper.text()).not.toContain('user_memory_write_denied')
  })

  it('流式执行时展示任务进展而不是工具数量', () => {
    const message: Message = {
      id: 'assistant-streaming-task',
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          status: 'RUNNING',
          success: true,
          latencyMs: 0,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: true,
        streamingContent: '正在整理结果',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('正在执行验证')
    expect(wrapper.text()).toContain('正在运行命令或测试，完成后会整理结果。')
    expect(wrapper.text()).not.toContain('已进入 1 个工具')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)
  })

  it('流式 reactSteps 会把真实执行步骤转成自然动作并忽略内部编排', () => {
    const message: Message = {
      id: 'assistant-streaming-natural-step',
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: true,
        streamingReactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'tool.search',
            toolName: '搜索工具',
            inputSummary: '查找可用工具',
            latencyMs: 12,
          },
          {
            type: 'TOOL_CALL',
            index: 1,
            toolId: 'web.search',
            toolName: '联网搜索',
            inputSummary: '搜索产品资料',
            latencyMs: 20,
          },
        ],
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('正在查资料')
    expect(wrapper.text()).toContain('正在检索和读取资料，完成后会整理回答。')
    expect(wrapper.text()).not.toContain('已接住，正在组织回答')
    expect(wrapper.text()).not.toContain('搜索工具')
    expect(wrapper.text()).not.toContain('查找可用工具')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)
  })

  it('流式刚开始且只有内部判断时显示轻量接收状态', () => {
    const message: Message = {
      id: 'assistant-streaming-warmup',
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: true,
        streamingReasoningEvents: [
          {
            id: 'event-capability-check',
            type: 'TOOL_CALL',
            title: '调用核查',
            toolName: '调用核查',
            createdAt: '2026-07-04T10:00:00Z',
            extra: {
              toolId: 'capability.assess',
            },
          },
          {
            id: 'event-progress',
            type: 'PROGRESS',
            title: '进度',
            description: '知微正在判断',
            createdAt: '2026-07-04T10:00:01Z',
          },
        ],
        streamingReactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'intent.match',
            toolName: '意图识别',
            inputSummary: '判断用户意图',
            latencyMs: 0,
          },
        ],
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('已收到，正在处理')
    expect(wrapper.text()).not.toContain('已接住，正在组织回答')
    expect(wrapper.text()).not.toContain('知微正在判断')
    expect(wrapper.text()).not.toContain('意图识别')
    expect(wrapper.find('button[aria-label="查看任务步骤"]').exists()).toBe(false)
  })

  it('流式资料工具显示为整理资料而不是泛化查资料', () => {
    const message: Message = {
      id: 'assistant-streaming-knowledge-step',
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: true,
        streamingReactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'knowledge.search',
            toolName: '知识库检索',
            inputSummary: '检索本地产品资料',
            latencyMs: 20,
          },
        ],
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('正在整理资料')
    expect(wrapper.text()).toContain('正在检索、读取或解析本地资料，完成后会整理回答。')
    expect(wrapper.text()).not.toContain('正在查资料')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)
  })

  it('点击记忆标签时留在对话页并请求查看记忆详情', async () => {
    const source = {
      type: 'memory' as const,
      id: 'mem-1',
      name: '用户偏好',
      extra: {
        entityTypeLabel: '偏好',
        description: '用户希望主界面保持轻量。',
      },
    }
    const message: Message = {
      id: 'assistant-memory-inspect',
      role: 'assistant',
      content: '我参考了你的偏好。',
      timestamp: Date.now(),
      sources: [source],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    await wrapper.find('.memory-source-tag').trigger('click')

    expect(wrapper.emitted('inspect-memory')?.[0]).toEqual([source])
  })

  it('点击本轮记忆的查看调整入口会查看第一条记忆', async () => {
    const firstChange = {
      type: 'memory' as const,
      id: 'mem-new-1',
      name: '主界面偏好',
      extra: {
        operationLabel: '新增',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '主界面做轻，做好交互',
      },
    }
    const secondChange = {
      type: 'memory' as const,
      id: 'mem-new-2',
      name: '交互偏好',
      extra: {
        operationLabel: '新增',
        entityTypeLabel: '偏好',
      },
    }
    const message: Message = {
      id: 'assistant-memory-change-inspect',
      role: 'assistant',
      content: '我记住了。',
      timestamp: Date.now(),
      memoryChanges: [firstChange, secondChange],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const inspectButton = wrapper.find('.memory-change-panel__inspect')
    expect(inspectButton.attributes('aria-label')).toBe('查看和调整本轮记忆')
    expect(inspectButton.text()).toContain('查看/调整')

    await inspectButton.trigger('click')

    expect(wrapper.emitted('inspect-memory')?.[0]).toEqual([firstChange])
  })

  it('最后一条回复会自然浮现记住要点动作', async () => {
    const message: Message = {
      id: 'assistant-followup-memory',
      role: 'assistant',
      content: '你希望主界面保持轻量，能力在对话中自然浮现，这属于后续设计时应复用的偏好。',
      timestamp: Date.now(),
      memoryChangeStatus: 'checked-empty',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('可以接着')
    expect(wrapper.text()).toContain('记住要点')

    await wrapper.find('button[aria-label="把这条回答放入输入框，确认后后台整理为记忆"]').trigger('click')

    expect(wrapper.emitted('remember')?.[0]).toEqual([message])
  })

  it('有资料库目标时会自然浮现存为资料动作', async () => {
    const message: Message = {
      id: 'assistant-followup-knowledge',
      role: 'assistant',
      content: '这是一段可以复用的调研结论，包含背景、判断依据、关键风险、落地路径、验证方式和复盘口径。它适合作为项目资料沉淀下来，之后做方案评审、阶段汇报或继续分析时都能直接引用。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('存为资料')

    await wrapper.find('button[aria-label="存入 产品资料"]').trigger('click')

    expect(wrapper.emitted('save-knowledge')?.[0]).toEqual([message])
  })

  it('同一条回复同时适合记忆和资料沉淀时只露出一个沉淀动作', async () => {
    const message: Message = {
      id: 'assistant-followup-settlement-choice',
      role: 'assistant',
      content: '你希望知微主界面保持轻量，能力在对话中自然浮现；这个偏好会影响后续产品设计、交互取舍和界面文案。同时这段结论也适合作为项目资料保存，之后评审主对话闭环、记忆沉淀和能力入口时可以复用。',
      timestamp: Date.now(),
      memoryChangeStatus: 'checked-empty',
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('存为资料')
    expect(wrapper.text()).not.toContain('记住要点')

    await wrapper.find('button[aria-label="存入 产品资料"]').trigger('click')

    expect(wrapper.emitted('save-knowledge')?.[0]).toEqual([message])
    expect(wrapper.emitted('remember')).toBeUndefined()
  })

  it('计划型回复会把下一步动作预填到输入框而不是自动执行', async () => {
    const message: Message = {
      id: 'assistant-followup-checklist',
      role: 'assistant',
      content: '1. 收拢首页入口。\n2. 隐藏内部判断状态。\n3. 把产出沉淀回记忆和资料。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('整理成清单')

    await wrapper.find('button[aria-label="把上一条回答整理成可执行清单"]').trigger('click')

    expect(wrapper.emitted('follow-up')?.[0]).toEqual(['把上一条回答整理成可以执行的清单。'])
    expect(wrapper.emitted('remember')).toBeUndefined()
    expect(wrapper.emitted('save-knowledge')).toBeUndefined()
  })

  it('有搜索或资料执行结果时把续接动作表达成提炼结论', async () => {
    const message: Message = {
      id: 'assistant-followup-execution',
      role: 'assistant',
      content: '我已经读取资料并整理出第一版结论。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'skill.load',
          toolName: '加载 Skill',
          executionKind: 'SKILL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 20,
          action: '加载技能',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          outputSummary: '已加载调研技能',
        },
        {
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 120,
          outputSummary: '找到 5 条资料',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('提炼结论')
    expect(wrapper.text()).not.toContain('继续下一步')

    await wrapper.find('button[aria-label="基于已查到的资料，整理结论、依据、风险和下一步"]').trigger('click')

    expect(wrapper.emitted('follow-up')?.[0]).toEqual([
      '基于上一条回答和已经查到的资料，整理成结论、依据、风险和下一步。',
    ])
    expect(wrapper.emitted('remember')).toBeUndefined()
    expect(wrapper.emitted('save-knowledge')).toBeUndefined()
  })

  it('只有技能执行结果时把续接动作表达成按技能继续', async () => {
    const message: Message = {
      id: 'assistant-followup-skill',
      role: 'assistant',
      content: '我已经按写作技能整理出第一版回复。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'skill.execute',
          toolName: '执行 Skill',
          executionKind: 'SKILL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 20,
          action: '执行技能',
          subjectLabel: '技能',
          subjectNames: ['writing-assistant'],
          outputSummary: '已执行写作技能',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('按技能继续')
    expect(wrapper.text()).not.toContain('继续处理')

    await wrapper.find('button[aria-label="基于已经加载或执行的技能继续完成下一步"]').trigger('click')

    expect(wrapper.emitted('follow-up')?.[0]).toEqual([
      '基于上一条回答和已经加载或执行的技能，继续完成下一步，并说明需要保留的上下文。',
    ])
  })

  it('本轮沉淀超过三条时可以展开隐藏记忆并继续查看编辑', async () => {
    const scrollIntoView = mockScrollIntoView()
    const changes = [
      {
        type: 'memory' as const,
        id: 'mem-new-1',
        name: '主界面偏好',
        extra: { operationLabel: '新增', entityTypeLabel: '偏好', evidenceExcerpt: '主界面做轻' },
      },
      {
        type: 'memory' as const,
        id: 'mem-new-2',
        name: '交互偏好',
        extra: { operationLabel: '新增', entityTypeLabel: '偏好' },
      },
      {
        type: 'memory' as const,
        id: 'mem-new-3',
        name: '恢复偏好',
        extra: { operationLabel: '新增', entityTypeLabel: '偏好' },
      },
      {
        type: 'memory' as const,
        id: 'mem-new-4',
        name: '写作偏好',
        extra: { operationLabel: '新增', entityTypeLabel: '偏好' },
      },
    ]
    const message: Message = {
      id: 'assistant-memory-change-expand',
      role: 'assistant',
      content: '我记住了这些偏好。',
      timestamp: Date.now(),
      memoryChanges: changes,
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.findAll('.memory-source-tag')).toHaveLength(3)
    expect(wrapper.text()).toContain('+1 条')
    expect(wrapper.text()).not.toContain('写作偏好')

    const settledButton = wrapper.find('button[aria-label="已沉淀 4 条记忆，点击查看本轮记忆"]')
    expect(settledButton.exists()).toBe(true)
    await settledButton.trigger('click')
    await nextTick()

    expect(scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'nearest',
    })
    expect(wrapper.findAll('.memory-source-tag')).toHaveLength(4)
    expect(wrapper.text()).toContain('写作偏好')

    const moreButton = wrapper.find('.memory-source-more')
    expect(moreButton.exists()).toBe(false)

    const tags = wrapper.findAll('.memory-source-tag')
    expect(tags).toHaveLength(4)

    await tags[3].trigger('click')

    expect(wrapper.emitted('inspect-memory')?.[0]).toEqual([changes[3]])
  })

  it('把 skill.load 作为轻量技能执行摘要展示', () => {
    const message: Message = {
      id: 'assistant-skill-execution',
      role: 'assistant',
      content: '我按调研策略整理好了。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'skill.load',
          toolName: '加载 Skill',
          executionKind: 'SKILL',
          status: 'SUCCEEDED',
          action: '加载技能',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          success: true,
          latencyMs: 20,
          outputSummary: '已加载 1 个技能',
        },
        {
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 120,
          outputSummary: '找到 5 条资料',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).not.toContain('执行完成')
    expect(wrapper.text()).toContain('已按相关技能查资料并整理回答')
    expect(wrapper.find('.context-usage__item--success').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('已处理本轮任务。')
    expect(wrapper.text()).not.toContain('已完成 1 个工具，1 个技能。')
    expect(wrapper.text()).not.toContain('调用 1 个工具，加载 1 个技能')
    expect(wrapper.text()).not.toContain('技能 research-assistant')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('调用 2 个工具')
  })

  it('skill.load 成功但建议工具缺失时轻提示能力缺口', () => {
    const message: Message = {
      id: 'assistant-skill-capability-warning',
      role: 'assistant',
      content: '我已经加载调研技能，先整理已有资料。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'skill.load',
          toolName: '加载 Skill',
          executionKind: 'SKILL',
          status: 'SUCCEEDED',
          action: '加载技能',
          subjectLabel: '技能',
          subjectNames: ['research'],
          success: true,
          latencyMs: 20,
          outputSummary: '已加载 1 个技能，缺少建议工具 web.search',
          missingCapabilities: [
            {
              kind: 'TOOL',
              id: 'web.search',
              source: 'skill_reference',
              reason: 'Skill 引用了当前不可用工具',
              skillName: 'research',
            },
          ],
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('能力待补齐：web.search')
    expect(wrapper.text()).toContain('技能 research 已加载，但 web.search 当前不可用')
    expect(wrapper.text()).toContain('修复 web.search 后，技能 research 后续步骤就能调用完整工具。')
    expect(wrapper.text()).toContain('能力中心')
    const repairLink = wrapper.find('a[aria-label="打开能力中心，检查工具、技能状态和 Skill 引用"]')
    expect(repairLink.exists()).toBe(true)
    expect(repairLink.attributes('data-capability-missing')).toBe('web.search')
    expect(repairLink.attributes('data-capability-skill')).toBe('research')
    expect(wrapper.text()).not.toContain('修复能力后继续')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)
    expect(wrapper.emitted('resume')).toBeUndefined()
  })

  it('成功验证类任务展示自然产出摘要', () => {
    const message: Message = {
      id: 'assistant-validation-success',
      role: 'assistant',
      content: '测试已经跑完。所有用例通过。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 320,
          inputSummary: '执行 `npm test`',
          outputSummary: '测试通过',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).not.toContain('执行完成')
    expect(wrapper.text()).toContain('已执行验证并整理结果')
    expect(wrapper.find('.context-usage__item--success').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('已处理本轮任务。')
    expect(wrapper.text()).not.toContain('调用 1 个工具')
  })

  it('多类工具完成后合并关键产出摘要', async () => {
    const message: Message = {
      id: 'assistant-research-validation-success',
      role: 'assistant',
      content: '我已经查完资料，并跑过验证。结论可以采用。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 110,
          inputSummary: '搜索最新资料',
          outputSummary: '找到 3 条资料',
        },
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 320,
          inputSummary: '执行验证命令',
          outputSummary: '验证通过',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('已查资料、执行验证并整理结果')
    expect(wrapper.text()).not.toContain('已查资料并整理回答')
    expect(wrapper.text()).not.toContain('已执行验证并整理结果')

    const traceButton = wrapper.find('button[aria-label="已查资料、执行验证并整理结果，点击查看任务步骤"]')
    expect(traceButton.exists()).toBe(true)

    await traceButton.trigger('click')

    expect(wrapper.emitted('show-trace')?.[0]).toEqual(['assistant-research-validation-success'])
  })

  it('资料处理完成后展示资料化产出摘要并可打开任务步骤', async () => {
    const message: Message = {
      id: 'assistant-knowledge-success',
      role: 'assistant',
      content: '我已经基于本地资料整理好了。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'knowledge.search',
          toolName: '知识库检索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 80,
          inputSummary: '检索本地产品资料',
          outputSummary: '命中 3 条资料',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('已整理资料并生成回答')
    expect(wrapper.text()).not.toContain('已查资料并整理回答')

    const traceButton = wrapper.find('button[aria-label="已整理资料并生成回答，点击查看任务步骤"]')
    expect(traceButton.exists()).toBe(true)

    await traceButton.trigger('click')

    expect(wrapper.emitted('show-trace')?.[0]).toEqual(['assistant-knowledge-success'])
  })

  it('工作流、连接器和仓库完成后给出具体能力摘要', () => {
    const cases = [
      {
        toolId: 'workflow.run',
        toolName: '执行工作流',
        outputSummary: '流程已完成',
        expected: '已推进流程并整理结果',
      },
      {
        toolId: 'mcp.call',
        toolName: 'MCP 调用',
        outputSummary: '连接器返回结果',
        expected: '已调用连接器并整理结果',
      },
      {
        toolId: 'git.clone',
        toolName: '仓库准备',
        outputSummary: '仓库已准备',
        expected: '已处理仓库并整理结果',
      },
    ]

    for (const item of cases) {
      const wrapper = mount(MessageBubble, {
        props: {
          message: {
            id: `assistant-${item.toolId}`,
            role: 'assistant',
            content: '处理好了。',
            timestamp: Date.now(),
            toolsSummary: [
              {
                toolId: item.toolId,
                toolName: item.toolName,
                executionKind: 'TOOL',
                status: 'SUCCEEDED',
                success: true,
                latencyMs: 40,
                outputSummary: item.outputSummary,
              },
            ],
          } as any,
          streaming: false,
        },
        global: {
          plugins: [pinia],
          stubs: {
            RouterLink: {
              template: '<a><slot /></a>',
            },
          },
        },
      })

      expect(wrapper.text()).toContain(item.expected)
      expect(wrapper.text()).not.toContain('已处理本轮任务。')
    }
  })

  it('完成后的执行摘要可直接打开任务步骤', async () => {
    const message: Message = {
      id: 'assistant-trace-result',
      role: 'assistant',
      content: '我查到资料并整理好了。',
      timestamp: Date.now(),
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'web.search',
          toolName: '联网搜索',
          inputSummary: '搜索知微产品定位',
          latencyMs: 24,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'web.search',
          toolName: '联网搜索',
          success: true,
          outputSummary: '找到 3 条资料',
          tokensUsed: 0,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const traceButton = wrapper.find('button[aria-label="已查资料并整理回答，点击查看任务步骤"]')
    expect(traceButton.exists()).toBe(true)

    await traceButton.trigger('click')

    expect(wrapper.emitted('show-trace')?.[0]).toEqual(['assistant-trace-result'])
  })

  it('只有 toolsSummary 的历史完成消息也能打开任务步骤', async () => {
    const message: Message = {
      id: 'assistant-summary-only',
      role: 'assistant',
      content: '我已经整理好了。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 80,
          inputSummary: '搜索知微资料',
          outputSummary: '找到资料',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const traceButton = wrapper.find('button[aria-label="已查资料并整理回答，点击查看任务步骤"]')
    expect(traceButton.exists()).toBe(true)

    await traceButton.trigger('click')

    expect(wrapper.emitted('show-trace')?.[0]).toEqual(['assistant-summary-only'])
  })

  it('历史恢复消息显示轻量续接提示', async () => {
    const message: Message = {
      id: 'assistant-resumed-history',
      role: 'assistant',
      content: '我接着上次失败的测试继续处理完了。',
      timestamp: Date.now(),
      turnRecoveryContext: {
        action: 'RESUME',
        sourceTraceId: 'trace-failed-1',
        resumeInput: '继续，先修 npm test',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          recoveryActionMode: 'resume',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
        },
        nextActions: ['从失败命令后继续执行验证', '补跑相关测试'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('从断点继续')
    expect(wrapper.text()).toContain('执行命令')
    expect(wrapper.text()).toContain('按计划：从失败命令后继续执行验证')
    const recoveryButton = wrapper.find('button[aria-label="从断点继续，点击查看任务步骤"]')
    expect(recoveryButton.exists()).toBe(true)
    await recoveryButton.trigger('click')
    expect(wrapper.emitted('show-trace')?.[0]).toEqual(['assistant-resumed-history'])
  })

  it('历史重启消息显示重启修正提示', () => {
    const message: Message = {
      id: 'assistant-restarted-history',
      role: 'assistant',
      content: '我已经重新开始并修复失败测试。',
      timestamp: Date.now(),
      turnRecoveryContext: {
        action: 'RESTART',
        sourceTraceId: 'trace-failed-1',
        resumeInput: '重新开始：重新开始。目标：Shell 执行（命令执行）。',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          recoveryActionMode: 'restart',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
        },
        nextActions: ['查看命令输出并修正报错原因'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('重新开始并修正')
    expect(wrapper.text()).toContain('按计划：查看命令输出并修正报错原因')
    expect(wrapper.text()).not.toContain('从断点继续')
  })

  it('仅有续接上下文的失败消息也显示恢复卡片并带回断点', async () => {
    const message: Message = {
      id: 'assistant-turn-context-only',
      turnId: 'turn-context-only',
      role: 'assistant',
      content: '我接着上次任务继续时测试仍失败，可以从断点继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      completionMode: 'DEGRADED',
      turnRecoveryContext: {
        action: 'RESUME',
        sourceTraceId: 'trace-failed-1',
        title: '本轮可以继续',
        detail: '测试仍失败，修正断言后继续验证。',
        resumeStrategy: '从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          recoveryActionId: 'resume',
          recoveryActionLabel: '继续验证',
          recoveryActionMode: 'resume',
          recoveryActionDescription: '带上失败输出继续验证。',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
        },
        nextActions: ['修正失败断言', '补跑相关测试'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('验证没有完成')
    expect(wrapper.text()).toContain('测试仍失败，修正断言后继续验证。')
    expect(wrapper.text()).toContain('卡住位置')
    expect(wrapper.text()).toContain('执行命令')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.text()).toContain('修正失败断言')
    expect(wrapper.text()).toContain('继续：带上失败输出继续验证。')

    await wrapper.find('button[title^="继续验证"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'turn-recovery-resume',
        label: '继续验证',
        description: '带上失败输出继续验证。',
        mode: 'resume',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        executionKind: 'TOOL',
        action: '执行命令',
        category: 'COMMAND',
        inputSummary: '执行 `npm test`',
        outputSummary: '测试失败',
        outputDetail: 'AssertionError: expected true to be false',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        recoveryHint: '测试仍失败，修正断言后继续验证。',
        nextActions: ['修正失败断言', '补跑相关测试'],
      }),
    ])

    await wrapper.find('button[title^="重新开始"]').trigger('click')

    expect(wrapper.emitted('restart')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'turn-recovery-restart',
        label: '重新开始',
        description: '保留恢复上下文和失败线索，重新开始这一轮。',
        mode: 'restart',
        toolId: 'shell.exec',
        category: 'COMMAND',
        nextActions: ['修正失败断言', '补跑相关测试'],
      }),
    ])
  })

  it('没有 toolsSummary 时从 reactSteps 兜底展示工具摘要并保留动作语义', async () => {
    const message: Message = {
      id: 'assistant-react-steps',
      role: 'assistant',
      content: '测试没有通过。',
      timestamp: Date.now(),
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
          latencyMs: 35,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          success: false,
          outputSummary: '测试失败',
          tokensUsed: 0,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('验证没有完成')
    expect(wrapper.text()).toContain('命令或代码没有完成，可以修正错误后继续执行。')
    expect(wrapper.text()).not.toContain('已执行 1 个工具')
    expect(wrapper.text()).not.toContain('调用 1 个工具，1 个失败')

    await wrapper.find('button[title^="修正后继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        label: '修正后继续',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        executionKind: 'TOOL',
        action: '执行命令',
        category: 'COMMAND',
        inputSummary: '执行 `npm test`',
        outputSummary: '测试失败',
      }),
    ])
  })

  it('没有 toolsSummary 时不会把内部工具搜索当成执行步骤展示', () => {
    const message: Message = {
      id: 'assistant-internal-tool-search',
      role: 'assistant',
      content: '我已经继续处理。',
      timestamp: Date.now(),
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'tool.search',
          toolName: '搜索工具',
          inputSummary: '查找可用工具',
          latencyMs: 20,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'tool.search',
          toolName: '搜索工具',
          success: true,
          outputSummary: '找到 2 个工具',
          tokensUsed: 0,
        },
        {
          type: 'TOOL_CALL',
          index: 2,
          toolId: 'capability.assess',
          toolName: '调用核查',
          inputSummary: '核查能力路径',
          latencyMs: 10,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('我已经继续处理。')
    expect(wrapper.text()).not.toContain('搜索工具')
    expect(wrapper.text()).not.toContain('调用核查')
    expect(wrapper.text()).not.toContain('执行中断')
    expect(wrapper.text()).not.toContain('查看任务步骤')
  })

  it('完成态只有内部编排步骤时不留下空气泡和操作栏', () => {
    const message: Message = {
      id: 'assistant-internal-only-empty',
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'tool.search',
          toolName: '搜索工具',
          inputSummary: '查找可用工具',
          latencyMs: 20,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'tool.search',
          toolName: '搜索工具',
          success: true,
          outputSummary: '找到 2 个工具',
          tokensUsed: 0,
        },
        {
          type: 'TOOL_CALL',
          index: 2,
          toolId: 'capability.assess',
          toolName: '调用核查',
          inputSummary: '核查能力路径',
          latencyMs: 10,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toBe('')
    expect(wrapper.find('.assistant-bubble').exists()).toBe(false)
    expect(wrapper.find('.message-actions-row').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('搜索工具')
    expect(wrapper.text()).not.toContain('调用核查')
    expect(wrapper.text()).not.toContain('查看任务步骤')
  })

  it('没有 toolsSummary 时从 reactSteps 兜底展示技能失败恢复语义', async () => {
    const message: Message = {
      id: 'assistant-skill-failure-react-steps',
      role: 'assistant',
      content: '技能加载失败。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'skill.load',
          callId: 'call-skill-load-1',
          toolName: '加载 Skill',
          inputSummary: '加载技能「research-assistant」',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          latencyMs: 12,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'skill.load',
          toolName: '加载 Skill',
          success: false,
          outputSummary: '技能 research-assistant 不存在',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          tokensUsed: 0,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('技能 research-assistant 卡住了')
    expect(wrapper.text()).toContain('技能加载没有完成，可以检查技能名称或依赖后继续。')
    expect(wrapper.text()).not.toContain('已执行 1 个技能')
    expect(wrapper.text()).not.toContain('加载 1 个技能，1 个失败')
    expect(wrapper.text()).toContain('技能加载没有完成，可以检查技能名称或依赖后继续。')
    expect(wrapper.text()).toContain('继续时会带上当前进度和下面的计划接着处理。')
    expect(wrapper.text()).toContain('接下来')
    expect(wrapper.find('[aria-label="续接计划"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('确认技能 research-assistant 的名称和依赖是否可用')
    expect(wrapper.text()).toContain('重新加载技能后继续当前任务')
    expect(wrapper.text()).toContain('卡住位置')
    expect(wrapper.find('[aria-label="卡住位置"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('技能 research-assistant')
    expect(wrapper.text()).not.toContain('技能：加载 Skill')
    expect(wrapper.text()).toContain('技能 research-assistant 不存在')

    await wrapper.find('button[title^="检查技能后继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        label: '检查技能后继续',
        toolId: 'skill.load',
        toolName: '加载 Skill',
        executionKind: 'SKILL',
        category: 'SKILL',
        outputSummary: '技能 research-assistant 不存在',
      }),
    ])
  })

  it('没有 observation 的非流式 reactSteps 会变成技能可恢复中断', async () => {
    const message: Message = {
      id: 'assistant-skill-interrupted-react-steps',
      role: 'assistant',
      content: '技能加载中断，我可以接着处理。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'skill.load',
          callId: 'call-skill-load-1',
          toolName: '加载 Skill',
          inputSummary: '加载技能「research-assistant」',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          latencyMs: 12,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('技能 research-assistant 卡住了')
    expect(wrapper.text()).toContain('技能 research-assistant 已经开始加载但没有返回结果，可以检查技能名称或依赖后继续。')
    expect(wrapper.text()).toContain('这一步已经开始，但没有返回执行结果。')
    expect(wrapper.text()).toContain('确认技能 research-assistant 的名称和依赖是否可用')
    expect(wrapper.text()).toContain('重新加载技能后继续当前任务')
    expect(wrapper.text()).not.toContain('进行中')

    await wrapper.find('button[title^="检查技能后继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        label: '检查技能后继续',
        toolId: 'skill.load',
        callId: 'call-skill-load-1',
        toolName: '加载 Skill',
        executionKind: 'SKILL',
        category: 'SKILL',
        interrupted: true,
        subjectLabel: '技能',
        subjectNames: ['research-assistant'],
        inputSummary: '加载技能「research-assistant」',
        outputSummary: '这一步已经开始，但没有返回执行结果。',
      }),
    ])
  })

  it('没有 toolsSummary 时优先按 callId 匹配同类工具观察结果', async () => {
    const message: Message = {
      id: 'assistant-call-id-match',
      role: 'assistant',
      content: '第二次搜索失败，可以从失败处继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'web.search',
          callId: 'search-call-1',
          toolName: '联网搜索',
          inputSummary: '搜索「A」',
          latencyMs: 12,
        },
        {
          type: 'TOOL_CALL',
          index: 1,
          toolId: 'web.search',
          callId: 'search-call-2',
          toolName: '联网搜索',
          inputSummary: '搜索「B」',
          latencyMs: 13,
        },
        {
          type: 'OBSERVATION',
          index: 2,
          toolId: 'web.search',
          callId: 'search-call-2',
          toolName: '联网搜索',
          success: false,
          outputSummary: '外部访问超时',
          tokensUsed: 0,
        },
        {
          type: 'OBSERVATION',
          index: 3,
          toolId: 'web.search',
          callId: 'search-call-1',
          toolName: '联网搜索',
          success: true,
          outputSummary: '找到 3 条结果',
          tokensUsed: 0,
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    await wrapper.find('button[title^="重试后继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        label: '重试后继续',
        toolId: 'web.search',
        callId: 'search-call-2',
        category: 'NETWORK',
        inputSummary: '搜索「B」',
        outputSummary: '外部访问超时',
      }),
    ])
  })

  it('降级且工具失败时展示恢复入口并触发继续', async () => {
    const message: Message = {
      id: 'assistant-recoverable',
      role: 'assistant',
      content: '命令没有跑完，我可以接着处理。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      toolsSummary: [
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          success: false,
          latencyMs: 35,
          outputSummary: '测试失败',
          recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('验证没有完成')
    expect(wrapper.text()).toContain('命令或代码没有完成，可以修正错误后继续执行。')
    expect(wrapper.text()).toContain('已保留已完成步骤和失败输出，继续时会从这里接上。')
    expect(wrapper.text()).toContain('修正卡点后点“修正后继续”，知微会带着当前进度接上。')
    expect(wrapper.text()).toContain('卡住位置')
    expect(wrapper.text()).not.toContain('工具：Shell 执行')
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(wrapper.text()).toContain('从失败命令后继续执行验证')
    expect(wrapper.text()).toContain('卡点')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.find('[aria-label="恢复动作说明"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('继续：保留已完成步骤，修正命令或代码错误后继续验证。')
    expect(wrapper.text()).toContain('重启：保留失败输出，重新开始并优先修正命令错误。')
    expect(wrapper.find('.tool-call-card').exists()).toBe(false)
    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)
    expect(wrapper.findAll('button[title^="重新开始"]')).toHaveLength(1)
    expect(wrapper.find('button[aria-label^="修正后继续：验证没有完成"]').exists()).toBe(true)
    expect(wrapper.find('button[aria-label^="重新开始：验证没有完成"]').exists()).toBe(true)

    const resumeButton = wrapper.find('button[title^="修正后继续"]')
    await resumeButton.trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        label: '修正后继续',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        category: 'COMMAND',
        outputSummary: '测试失败',
      }),
    ])
  })

  it('能力缺口失败时在主对话短条提示修复能力', async () => {
    const message: Message = {
      id: 'assistant-capability-recoverable',
      role: 'assistant',
      content: '搜索工具没有加载，我可以在能力修复后继续。',
      timestamp: Date.now(),
      turnId: 'turn-capability',
      turnStatus: 'DEGRADED',
      toolsSummary: [
        {
          toolId: 'web.search',
          toolName: '网页搜索',
          status: 'FAILED',
          success: false,
          latencyMs: 20,
          outputSummary: '工具 web.search 未注册',
          recoveryHint: '依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。',
          missingCapabilities: [
            {
              kind: 'TOOL',
              id: 'web.search',
              source: 'skill_reference',
              reason: 'Skill 引用了当前不可用工具',
              skillName: 'research',
            },
          ],
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
        recoveryReturnSessionId: 'session-current',
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: defineComponent({
            name: 'RouterLink',
            props: {
              to: {
                type: [String, Object],
                required: false,
              },
            },
            setup(props, { attrs, slots }) {
              return () => h(
                'a',
                {
                  ...attrs,
                  'data-to': JSON.stringify(props.to ?? attrs.to),
                },
                slots.default?.(),
              )
            },
          }),
        },
      },
    })

    expect(wrapper.text()).toContain('能力需要修复：web.search')
    expect(wrapper.text()).toContain('缺失 web.search，在能力中心修复缺失能力或修正 Skill suggestedTools 后可以从断点继续。')
    expect(wrapper.text()).toContain('缺失能力 web.search')
    expect(wrapper.text()).toContain('能力中心')
    expect(wrapper.text()).toContain('修复能力后继续')
    expect(wrapper.text()).toContain('先在能力中心修复 web.search，回来点“修复能力后继续”。')
    expect(wrapper.text()).not.toContain('执行中断')
    const repairLink = wrapper.find('a[aria-label="打开能力中心，检查工具、技能状态和 Skill 引用"]')
    expect(repairLink.exists()).toBe(true)
    expect(repairLink.attributes('data-capability-missing')).toBe('web.search')
    expect(repairLink.attributes('data-capability-skill')).toBe('research')
    expect(repairLink.attributes('data-return-session-id')).toBe('session-current')
    expect(repairLink.attributes('data-return-turn-id')).toBe('turn-capability')
    expect(repairLink.attributes('data-return-entry-id')).toBe('assistant-capability-recoverable')
    expect(repairLink.attributes('data-to')).toContain('task-recovery')
    expect(repairLink.attributes('data-to')).toContain('session-current')
    expect(repairLink.attributes('data-to')).toContain('turn-capability')
    expect(repairLink.attributes('data-to')).toContain('assistant-capability-recoverable')

    await wrapper.find('button[title^="修复能力后继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        label: '修复能力后继续',
        toolId: 'web.search',
        toolName: '网页搜索',
        category: 'CAPABILITY',
        outputSummary: '工具 web.search 未注册',
        missingCapabilities: [
          expect.objectContaining({
            id: 'web.search',
            source: 'skill_reference',
          }),
        ],
      }),
    ])
  })

  it('降级但没有失败工具时不显示 0 个步骤需要处理', () => {
    const message: Message = {
      id: 'assistant-degraded-without-failed-tool',
      role: 'assistant',
      content: '我已经完成了前面的资料整理，但这轮还没完全收束。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      toolsSummary: [
        {
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 120,
          outputSummary: '找到 3 条资料',
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('任务没有完整完成')
    expect(wrapper.text()).toContain('任务尚未完整完成，可以继续或重新开始。')
    expect(wrapper.text()).not.toContain('已执行 1 个工具')
    expect(wrapper.text()).not.toContain('0 个步骤需要处理')
  })

  it('工具步骤内的恢复动作会接到当前任务继续事件', async () => {
    const message: Message = {
      id: 'assistant-step-recovery',
      role: 'assistant',
      content: '这一步没有完成。',
      timestamp: Date.now(),
      toolsSummary: [
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          status: 'FAILED',
          success: false,
          latencyMs: 35,
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
          failureCategory: 'COMMAND',
          recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
          recoveryActions: [
            {
              id: 'resume',
              label: '修正后继续',
              mode: 'resume',
              category: 'COMMAND',
            },
          ],
        },
      ],
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.find('.tool-call-card').exists()).toBe(false)

    await wrapper.find('button[title^="修正后继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'resume',
        label: '修正后继续',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        category: 'COMMAND',
        outputSummary: '测试失败',
        outputDetail: 'AssertionError: expected true to be false',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      }),
    ])
  })

  it('展示任务恢复上下文和下一步计划，并把断点带回恢复事件', async () => {
    const message: Message = {
      id: 'assistant-recovery-checkpoint',
      role: 'assistant',
      content: '测试失败，我可以从失败处继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      taskRecovery: {
        status: 'DEGRADED',
        title: 'Shell 执行 没有完成',
        detail: '命令或代码没有完成，可以修正错误后继续执行。',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false\n    at src/example.spec.ts:12',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
          artifactRefs: [
            {
              artifactId: 'artifact-1',
              fileName: 'report.md',
              mimeType: 'text/markdown',
              kind: 'FILE',
              size: 128,
              downloadUrl: '/api/artifacts/artifact-1/download',
            },
          ],
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('验证没有完成')
    expect(wrapper.text()).not.toContain('Shell 执行 没有完成')
    expect(wrapper.text()).toContain('继续时会带上当前进度和下面的计划接着处理。')
    expect(wrapper.text()).toContain('已保留已完成步骤和失败输出，继续时会从这里接上。')
    expect(wrapper.text()).toContain('卡住位置')
    expect(wrapper.text()).toContain('接下来')
    expect(wrapper.find('[aria-label="续接计划"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="卡住位置"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="继续时保留"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('继续时保留')
    expect(wrapper.text()).toContain('带上原始输入')
    expect(wrapper.text()).toContain('带上失败输出')
    expect(wrapper.text()).toContain('工作目录 News')
    expect(wrapper.text()).toContain('保留产物 report.md')
    expect(wrapper.text()).toContain('位置')
    expect(wrapper.text()).toContain('执行命令')
    expect(wrapper.text()).not.toContain('工具：Shell 执行')
    expect(wrapper.text()).not.toContain('D:\\WorkSpace\\Project\\News')
    expect(wrapper.text()).toContain('已尝试')
    expect(wrapper.text()).toContain('执行 `npm test`')
    expect(wrapper.text()).toContain('卡点')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.text()).toContain('原因')
    expect(wrapper.text()).toContain('AssertionError: expected true to be false')
    expect(wrapper.text()).toContain('产物引用')
    expect(wrapper.text()).toContain('report.md')
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(wrapper.text()).toContain('从失败命令后继续执行验证')
    expect(wrapper.find('[aria-label="恢复动作说明"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('继续：保留当前进度，按恢复计划从卡住的位置继续。')
    expect(wrapper.text()).toContain('重启：保留恢复摘要和失败线索，重新开始这一轮。')
    expect(wrapper.text()).toContain('复制断点')

    await wrapper.find('button[title^="复制断点"]').trigger('click')

    expect(copyToClipboard).toHaveBeenCalledTimes(1)
    const recoverySummary = vi.mocked(copyToClipboard).mock.calls[0][0]
    expect(recoverySummary).toContain('[知微任务恢复]')
    expect(recoverySummary).toContain('标题: 验证没有完成')
    expect(recoverySummary).toContain('继续时保留:')
    expect(recoverySummary).toContain('- 带上原始输入')
    expect(recoverySummary).toContain('- 带上失败输出')
    expect(recoverySummary).toContain('- 工作目录 News')
    expect(recoverySummary).toContain('- 保留产物 report.md')
    expect(recoverySummary).toContain('- 位置: 执行命令')
    expect(recoverySummary).toContain('- 已尝试: 执行 `npm test`')
    expect(recoverySummary).toContain('- 卡点: 测试失败')
    expect(recoverySummary).toContain('- 原因: AssertionError: expected true to be false')
    expect(recoverySummary).toContain('- 产物引用: report.md')
    expect(recoverySummary).toContain('1. 查看命令输出并修正报错原因')
    expect(recoverySummary).toContain('2. 从失败命令后继续执行验证')
    expect(recoverySummary).not.toContain('D:\\WorkSpace\\Project\\News')

    await wrapper.find('button[title^="继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'task-recovery-resume',
        label: '继续',
        description: '保留当前进度，按恢复计划从卡住的位置继续。',
        mode: 'resume',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        executionKind: 'TOOL',
        action: '执行命令',
        category: 'COMMAND',
        inputSummary: '执行 `npm test`',
        outputSummary: '测试失败',
        outputDetail: 'AssertionError: expected true to be false\n    at src/example.spec.ts:12',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        artifactRefs: [
          {
            artifactId: 'artifact-1',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 128,
            downloadUrl: '/api/artifacts/artifact-1/download',
          },
        ],
        recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      }),
    ])

    await wrapper.find('button[title^="重新开始"]').trigger('click')

    expect(wrapper.emitted('restart')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'task-recovery-restart',
        label: '重新开始',
        description: '保留恢复摘要和失败线索，重新开始这一轮。',
        mode: 'restart',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        category: 'COMMAND',
        outputSummary: '测试失败',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        artifactRefs: [
          {
            artifactId: 'artifact-1',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 128,
            downloadUrl: '/api/artifacts/artifact-1/download',
          },
        ],
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      }),
    ])
  })

  it('任务恢复动作会复用消息级产物引用', async () => {
    const message: Message = {
      id: 'assistant-recovery-message-artifact',
      role: 'assistant',
      content: '报告已经生成，但后续验证失败，可以继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      artifactRefs: [
        {
          artifactId: 'artifact-report',
          fileName: 'report.md',
          mimeType: 'text/markdown',
          kind: 'FILE',
          size: 256,
          downloadUrl: '/api/artifacts/artifact-report/download',
        },
      ],
      taskRecovery: {
        status: 'DEGRADED',
        title: 'Shell 执行 没有完成',
        detail: '命令或代码没有完成，可以修正错误后继续执行。',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
          ArtifactCard: {
            template: '<div />',
          },
        },
      },
    })

    await wrapper.find('button[title^="继续"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'task-recovery-resume',
        toolId: 'shell.exec',
        artifactRefs: [
          {
            artifactId: 'artifact-report',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 256,
            downloadUrl: '/api/artifacts/artifact-report/download',
          },
        ],
      }),
    ])
  })

  it('任务恢复没有断点时仍把下一步计划带回恢复事件', async () => {
    const message: Message = {
      id: 'assistant-recovery-plan-only',
      role: 'assistant',
      content: '我暂时停在资料等待阶段，可以继续处理剩余任务。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      taskRecovery: {
        status: 'DEGRADED',
        title: '任务还没完整完成',
        detail: '已有结果会保留，知微可以继续处理剩余部分。',
        actionLabel: '继续处理',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        nextActions: ['复用已有结果', '继续处理剩余任务'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('任务还没完整完成')
    expect(wrapper.text()).toContain('继续时会按下面计划接着处理。')
    expect(wrapper.text()).toContain('会复用本轮已有结果继续处理。')
    expect(wrapper.text()).toContain('接下来')
    expect(wrapper.text()).not.toContain('已保留断点')
    expect(wrapper.find('[aria-label="续接计划"]').exists()).toBe(true)
    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('继续：保留当前进度，按恢复计划从卡住的位置继续。')
    expect(wrapper.text()).toContain('复制计划')

    await wrapper.find('button[title^="复制计划"]').trigger('click')

    expect(copyToClipboard).toHaveBeenCalledTimes(1)
    const recoveryPlan = vi.mocked(copyToClipboard).mock.calls[0][0]
    expect(recoveryPlan).toContain('[知微任务恢复]')
    expect(recoveryPlan).toContain('标题: 任务还没完整完成')
    expect(recoveryPlan).toContain('说明: 已有结果会保留')
    expect(recoveryPlan).not.toContain('卡住位置:')
    expect(recoveryPlan).toContain('1. 复用已有结果')
    expect(recoveryPlan).toContain('2. 继续处理剩余任务')

    await wrapper.find('button[title^="继续处理"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'task-recovery-resume',
        label: '继续处理',
        description: '保留当前进度，按恢复计划从卡住的位置继续。',
        mode: 'resume',
        recoveryHint: '已有结果会保留，知微可以继续处理剩余部分。',
        nextActions: ['复用已有结果', '继续处理剩余任务'],
      }),
    ])
  })

  it('仅恢复摘要标记可恢复时也展示任务恢复面板', async () => {
    const message: Message = {
      id: 'assistant-recovery-summary-only',
      role: 'assistant',
      content: '我暂时只完成了部分步骤，可以继续处理剩余任务。',
      timestamp: Date.now(),
      taskRecovery: {
        status: 'DEGRADED',
        title: '任务还没完整完成',
        detail: '已有结果会保留，知微可以继续处理剩余部分。',
        actionLabel: '继续处理',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        nextActions: ['复用已有结果', '继续处理剩余任务'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.find('.recovery-prompt').exists()).toBe(true)
    expect(wrapper.text()).toContain('任务还没完整完成')
    expect(wrapper.text()).toContain('继续时会按下面计划接着处理。')
    expect(wrapper.text()).toContain('会复用本轮已有结果继续处理。')
    expect(wrapper.text()).toContain('复用已有结果')
    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)

    await wrapper.find('button[title^="继续处理"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([
      message,
      expect.objectContaining({
        id: 'task-recovery-resume',
        label: '继续处理',
        description: '保留当前进度，按恢复计划从卡住的位置继续。',
        mode: 'resume',
        recoveryHint: '已有结果会保留，知微可以继续处理剩余部分。',
        nextActions: ['复用已有结果', '继续处理剩余任务'],
      }),
    ])
  })

  it('任务恢复只有计划时不展示失败点和断点文案', () => {
    const message: Message = {
      id: 'assistant-recovery-plan-only-no-detail',
      role: 'assistant',
      content: '我可以继续处理剩余任务。',
      timestamp: Date.now(),
      turnStatus: 'SUSPENDED',
      taskRecovery: {
        status: 'SUSPENDED',
        actionLabel: '继续处理',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        nextActions: ['复用已有结果', '继续处理剩余任务'],
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('可以继续这一轮')
    expect(wrapper.text()).toContain('可以按计划继续处理，或重新开始这一轮。')
    expect(wrapper.text()).toContain('继续时会按下面计划接着处理。')
    expect(wrapper.text()).toContain('复用已有结果')
    expect(wrapper.text()).not.toContain('失败处')
    expect(wrapper.text()).not.toContain('断点')
  })

  it('挂起等待用户补充时展示恢复摘要但不显示普通恢复按钮', () => {
    const message: Message = {
      id: 'assistant-await-user',
      role: 'assistant',
      content: '我需要你补充仓库地址后再继续。',
      timestamp: Date.now(),
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      suspendReasonSourceId: '__await_user_input__',
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '你直接回复补充内容，知微会接着当前进度继续。',
        actionLabel: '等待',
        canResume: false,
        canRestart: true,
        reasonType: 'ExternalDataWait',
        reasonSourceId: '__await_user_input__',
        resumeMode: 'user_reply',
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('等待你补充信息')
    expect(wrapper.text()).toContain('等你补充')
    expect(wrapper.text()).toContain('你直接回复补充内容，知微会接着当前进度继续。')
    expect(wrapper.text()).toContain('已保留当前进度，等你补充后知微会接着处理。')
    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)
    expect(wrapper.find('button[title^="重新开始"]').exists()).toBe(false)
  })

  it('浏览器接管挂起不显示普通恢复按钮', () => {
    const message: Message = {
      id: 'assistant-browser-takeover',
      role: 'assistant',
      content: '需要你在浏览器里完成登录后继续。',
      timestamp: Date.now(),
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待浏览器操作',
        detail: '请在浏览器里完成当前步骤，再回到接管窗口继续。',
        actionLabel: '继续',
        canResume: true,
        canRestart: true,
        reasonType: 'BrowserTakeover',
        resumeMode: 'browser',
      },
    } as any

    const wrapper = mount(MessageBubble, {
      props: {
        message,
        streaming: false,
        isLastAssistant: true,
      },
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('等待浏览器操作')
    expect(wrapper.text()).toContain('浏览器接管')
    expect(wrapper.text()).toContain('已保留当前进度，等浏览器步骤完成后再接着处理。')
    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)
    expect(wrapper.find('button[title^="重新开始"]').exists()).toBe(false)
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
