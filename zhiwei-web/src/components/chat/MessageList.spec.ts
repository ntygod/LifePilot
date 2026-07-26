import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageList from './MessageList.vue'
import type { Message } from '@/types'

vi.mock('motion-v', () => ({
  motion: {
    div: {
      name: 'MotionDiv',
      template: '<div v-bind="$attrs"><slot /></div>',
    },
  },
}))

describe('MessageList 权限审批历史合并', () => {
  it('刷新后会把历史审批记录并回同一轮 assistant 消息', () => {
    const messages: Message[] = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '老板，gh CLI 状态正常。',
        timestamp: Date.parse('2026-03-25T13:00:00Z'),
      },
      {
        id: 'approval-1',
        turnId: 'turn-1',
        role: 'permission-approval',
        content: '',
        timestamp: Date.parse('2026-03-25T13:00:01Z'),
        permissionApprovalLogs: [
          {
            requestId: 'req-1',
            toolId: 'shell.exec',
            toolName: '执行 Shell 命令',
            actionType: 'EXECUTE_SHELL',
            resolution: 'approved',
            subjectType: 'SESSION',
            timestamp: '2026-03-25T13:00:01Z',
          },
        ],
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message'],
            template: `
              <div class="bubble">
                {{ message.role }}|{{ message.turnId }}|{{ message.permissionApprovalLogs?.length ?? 0 }}
              </div>
            `,
          },
        },
      },
    })

    const bubbles = wrapper.findAll('.bubble')
    expect(bubbles).toHaveLength(1)
    expect(bubbles[0].text()).toContain('assistant|turn-1|1')
  })

  it('审批记录早于最终回复时，仍会并回同一轮 assistant 消息', () => {
    const messages: Message[] = [
      {
        id: 'user-1',
        turnId: 'turn-2',
        role: 'user',
        content: '执行个 python 的 helloworld',
        timestamp: Date.parse('2026-03-25T13:10:00Z'),
      },
      {
        id: 'approval-2',
        turnId: 'turn-2',
        role: 'permission-approval',
        content: '',
        timestamp: Date.parse('2026-03-25T13:10:01Z'),
        permissionApprovalLogs: [
          {
            requestId: 'req-2',
            toolId: 'code.execute',
            toolName: '执行代码',
            actionType: 'EXECUTE_SHELL',
            resolution: 'approved',
            subjectType: 'SESSION',
            timestamp: '2026-03-25T13:10:01Z',
          },
        ],
      },
      {
        id: 'assistant-2',
        turnId: 'turn-2',
        role: 'assistant',
        content: 'Hello, World!',
        timestamp: Date.parse('2026-03-25T13:10:05Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message'],
            template: `
              <div class="bubble">
                {{ message.role }}|{{ message.turnId }}|{{ message.content }}|{{ message.permissionApprovalLogs?.length ?? 0 }}
              </div>
            `,
          },
        },
      },
    })

    const bubbles = wrapper.findAll('.bubble')
    expect(bubbles).toHaveLength(2)
    expect(bubbles[1].text()).toContain('assistant|turn-2|Hello, World!|1')
  })
})

describe('MessageList 来源定位', () => {
  it('为消息暴露稳定定位标记，并高亮目标 entry', () => {
    const messages: Message[] = [
      {
        id: 'entry-user',
        turnId: 'turn-source',
        role: 'user',
        content: '我喜欢轻量主界面',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
      },
      {
        id: 'entry-assistant',
        turnId: 'turn-source',
        role: 'assistant',
        content: '我会记住这个偏好。',
        timestamp: Date.parse('2026-07-04T13:00:03Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        focusedEntryId: 'entry-assistant',
        focusedTurnId: 'turn-source',
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message'],
            template: '<div class="bubble">{{ message.content }}</div>',
          },
        },
      },
    })

    const items = wrapper.findAll('.message-list__item')
    expect(items).toHaveLength(2)
    expect(items[0].attributes('data-entry-id')).toBe('entry-user')
    expect(items[0].attributes('data-turn-id')).toBe('turn-source')
    expect(items[1].classes()).toContain('message-list__item--focused')
  })

  it('会把项目上下文传给历史消息和流式占位', () => {
    const messages: Message[] = [
      {
        id: 'entry-user',
        role: 'user',
        content: '继续这个项目',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        isStreaming: true,
        projectId: 'project-1',
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message', 'projectId', 'streaming'],
            template: '<div class="bubble">{{ message.id }}|{{ projectId }}|{{ streaming ? "streaming" : "still" }}</div>',
          },
        },
      },
    })

    expect(wrapper.findAll('.bubble').map(item => item.text())).toEqual([
      'entry-user|project-1|still',
      'streaming|project-1|streaming',
    ])
  })
})

describe('MessageList 记忆沉淀入口', () => {
  it('会转发消息气泡里的记住事件', async () => {
    const messages: Message[] = [
      {
        id: 'assistant-remember-list',
        role: 'assistant',
        content: '用户希望输出先给结论。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message'],
            emits: ['remember'],
            template: '<button type="button" data-testid="remember" @click="$emit(\'remember\', message)">记住</button>',
          },
        },
      },
    })

    await wrapper.find('[data-testid="remember"]').trigger('click')

    expect(wrapper.emitted('remember')?.[0]?.[0]).toMatchObject({
      id: 'assistant-remember-list',
      content: '用户希望输出先给结论。',
    })
  })
})

describe('MessageList 对话内继续动作', () => {
  it('会转发消息气泡里的 follow-up 预填请求', async () => {
    const messages: Message[] = [
      {
        id: 'assistant-follow-up-list',
        role: 'assistant',
        content: '这里是一段可继续整理的回答。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message'],
            emits: ['follow-up'],
            template: '<button type="button" data-testid="follow-up" @click="$emit(\'follow-up\', \'继续整理上一条回答\')">继续</button>',
          },
        },
      },
    })

    await wrapper.find('[data-testid="follow-up"]').trigger('click')

    expect(wrapper.emitted('follow-up')?.[0]).toEqual(['继续整理上一条回答'])
  })
})

describe('MessageList 产物沉淀目标', () => {
  it('会把明确资料库目标传给消息气泡', () => {
    const messages: Message[] = [
      {
        id: 'assistant-artifact-target',
        role: 'assistant',
        content: '已生成报告。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
        artifactRefs: [
          {
            artifactId: 'artifact-report',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 8,
            downloadUrl: '/api/artifacts/artifact-report/download',
          },
        ],
      } as any,
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message', 'artifactKnowledgeBaseId', 'artifactKnowledgeBaseName'],
            template: '<div class="bubble">{{ message.id }}|{{ artifactKnowledgeBaseId }}|{{ artifactKnowledgeBaseName }}</div>',
          },
        },
      },
    })

    expect(wrapper.find('.bubble').text()).toContain('assistant-artifact-target|kb-product|产品资料')
  })

  it('会转发文本回复存为资料事件，并标记正在保存的消息', async () => {
    const messages: Message[] = [
      {
        id: 'assistant-save-knowledge-list',
        role: 'assistant',
        content: '这是可以沉淀的文本产出。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
        savingKnowledgeMessageId: 'assistant-save-knowledge-list',
        savedKnowledgeMessages: {
          'assistant-save-knowledge-list': {
            knowledgeBaseId: 'kb-product',
            knowledgeBaseName: '产品资料',
          },
        },
        saveKnowledgeErrors: {
          'assistant-save-knowledge-list': '索引服务不可用',
        },
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message', 'savingToKnowledge', 'savedKnowledgeBaseName', 'saveKnowledgeError'],
            emits: ['save-knowledge'],
            template: '<button type="button" data-testid="save-knowledge" @click="$emit(\'save-knowledge\', message)">{{ savingToKnowledge ? "saving" : "idle" }}|{{ savedKnowledgeBaseName }}|{{ saveKnowledgeError }}</button>',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="save-knowledge"]').text()).toBe('saving|产品资料|索引服务不可用')

    await wrapper.find('[data-testid="save-knowledge"]').trigger('click')

    expect(wrapper.emitted('save-knowledge')?.[0]?.[0]).toMatchObject({
      id: 'assistant-save-knowledge-list',
      content: '这是可以沉淀的文本产出。',
    })
  })

  it('会转发文件产物存入资料库事件，交给主对话统一记录沉淀状态', async () => {
    const messages: Message[] = [
      {
        id: 'assistant-save-artifact-list',
        role: 'assistant',
        content: '报告已经生成。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
        artifactRefs: [
          {
            artifactId: 'artifact-report',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 8,
            downloadUrl: '/api/artifacts/artifact-report/download',
          },
        ],
      } as any,
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message', 'savedKnowledgeBaseName'],
            emits: ['save-artifact-knowledge'],
            template: `
              <button
                type="button"
                data-testid="save-artifact"
                @click="$emit('save-artifact-knowledge', message, {
                  artifactId: 'artifact-report',
                  fileName: 'report.md',
                  knowledgeBaseId: 'kb-product',
                  knowledgeBaseName: '产品资料',
                })"
              >{{ savedKnowledgeBaseName ?? 'none' }}</button>
            `,
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="save-artifact"]').text()).toBe('none')

    await wrapper.find('[data-testid="save-artifact"]').trigger('click')

    expect(wrapper.emitted('save-artifact-knowledge')?.[0]).toEqual([
      expect.objectContaining({
        id: 'assistant-save-artifact-list',
      }),
      {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    ])
  })

  it('切换资料库目标后不会沿用旧资料库的已保存状态', () => {
    const messages: Message[] = [
      {
        id: 'assistant-save-knowledge-target',
        role: 'assistant',
        content: '这是可以沉淀到不同资料库的文本产出。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        artifactKnowledgeBaseId: 'kb-research',
        artifactKnowledgeBaseName: '调研资料',
        savedKnowledgeMessages: {
          'assistant-save-knowledge-target': {
            knowledgeBaseId: 'kb-product',
            knowledgeBaseName: '产品资料',
          },
        },
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message', 'savedKnowledgeBaseName'],
            template: '<div data-testid="saved-knowledge">{{ message.id }}|{{ savedKnowledgeBaseName ?? "none" }}</div>',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="saved-knowledge"]').text()).toBe('assistant-save-knowledge-target|none')
  })

  it('历史消息自带资料库沉淀记录时会恢复已保存状态', () => {
    const messages: Message[] = [
      {
        id: 'assistant-saved-from-history',
        role: 'assistant',
        content: '这条历史回复已经存入资料库。',
        timestamp: Date.parse('2026-07-04T13:00:00Z'),
        knowledgeSettlements: [
          {
            knowledgeBaseId: 'kb-product',
            knowledgeBaseName: '产品资料',
            sourceType: 'MESSAGE_TEXT',
            savedAt: '2026-07-07T04:00:00Z',
          },
        ],
      },
    ]

    const wrapper = mount(MessageList, {
      props: {
        messages,
        artifactKnowledgeBaseId: 'kb-product',
        artifactKnowledgeBaseName: '产品资料',
      },
      global: {
        stubs: {
          MessageBubble: {
            props: ['message', 'savedKnowledgeBaseId', 'savedKnowledgeBaseName'],
            template: '<div data-testid="saved-knowledge">{{ message.id }}|{{ savedKnowledgeBaseId }}|{{ savedKnowledgeBaseName }}</div>',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="saved-knowledge"]').text())
      .toBe('assistant-saved-from-history|kb-product|产品资料')
  })
})
