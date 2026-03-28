import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageList from './MessageList.vue'
import type { Message } from '@/types'

vi.mock('motion-v', () => ({
  motion: {
    div: {
      name: 'MotionDiv',
      template: '<div><slot /></div>',
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
