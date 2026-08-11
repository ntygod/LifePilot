import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ChatInput from './ChatInput.vue'

const mocks = vi.hoisted(() => ({
  route: {
    query: {} as Record<string, string>,
  },
  uploadAttachment: vi.fn(),
  createSession: vi.fn(),
  listSessions: vi.fn(),
  getSessionMessages: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
}))

vi.mock('@/api/client', () => ({
  chatApi: {
    uploadAttachment: mocks.uploadAttachment,
    createSession: mocks.createSession,
    listSessions: mocks.listSessions,
    getSessionMessages: mocks.getSessionMessages,
  },
}))

vi.mock('@/composables/useVoice', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')
  return {
    useVoice: () => ({
      isRecording: ref(false),
      recordingDuration: ref(0),
      audioBlob: ref(null),
      isSupported: ref(false),
      analyserNode: ref(null),
      startRecording: vi.fn(),
      stopRecording: vi.fn(),
    }),
  }
})

vi.mock('@/composables/useWhisperDownload', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')
  return {
    useWhisperDownload: () => ({
      available: ref(true),
      status: ref('ready'),
      checkAvailability: vi.fn(),
      triggerDownload: vi.fn(),
    }),
  }
})

function mountInput(props: Partial<InstanceType<typeof ChatInput>['$props']> = {}) {
  return mount(ChatInput, {
    props,
    global: {
      plugins: [createPinia()],
      stubs: {
        AudioWaveform: true,
        ConfirmDialog: true,
      },
    },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  mocks.route.query = {}
  mocks.uploadAttachment.mockReset()
  mocks.createSession.mockReset().mockResolvedValue({ id: 'session-1', title: '新对话' })
  mocks.listSessions.mockReset().mockResolvedValue([])
  mocks.getSessionMessages.mockReset().mockResolvedValue([])
})

describe('ChatInput 轻量上下文输入', () => {
  it('空输入时不显示内部能力提示条', () => {
    const wrapper = mountInput()

    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
  })

  it('用户表达偏好时不再用输入框猜测记忆能力', async () => {
    const wrapper = mountInput()

    await wrapper.find('textarea').setValue('请记住：我希望主界面保持轻量。')

    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('可能沉淀记忆')
    expect(wrapper.text()).not.toContain('可能查资料')
  })

  it('输入变化时向上层同步草稿状态', async () => {
    const wrapper = mountInput()

    await wrapper.find('textarea').setValue('我想整理一下这段材料')

    expect(wrapper.emitted('draft-change')?.at(-1)).toEqual([{
      content: '我想整理一下这段材料',
      hasAttachments: false,
      contextCount: 0,
    }])
  })

  it('流式回复中仍可编辑下一条草稿但不会误发送', async () => {
    const wrapper = mountInput({
      streaming: true,
    })
    const textarea = wrapper.find('textarea')

    expect((textarea.element as HTMLTextAreaElement).disabled).toBe(false)
    expect(wrapper.find('button[aria-label="停止生成"]').exists()).toBe(true)

    await textarea.setValue('等你回复完后，我还想继续追问这个点')
    await textarea.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('send')).toBeUndefined()
    expect(wrapper.emitted('draft-change')?.at(-1)).toEqual([{
      content: '等你回复完后，我还想继续追问这个点',
      hasAttachments: false,
      contextCount: 0,
    }])

    await wrapper.find('button[aria-label="停止生成"]').trigger('click')

    expect(wrapper.emitted('stop')).toHaveLength(1)
  })

  it('用户自然表达喜欢或希望时也不显示猜测提示', async () => {
    const wrapper = mountInput()

    await wrapper.find('textarea').setValue('我喜欢轻量主界面，以后方案也尽量简洁。')

    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('可能沉淀记忆')
  })

  it('选择知识库后只展示明确选中的上下文', async () => {
    const wrapper = mountInput({
      knowledgeBases: [
        {
          id: 'kb-product',
          name: '产品资料',
          description: '产品文档',
          embeddingModel: 'bge',
          documentCount: 1,
          totalChunks: 8,
          createdAt: '2026-07-04T00:00:00Z',
          updatedAt: '2026-07-04T00:00:00Z',
        },
      ],
    })

    await wrapper.find('button[aria-label="选择上下文"]').trigger('click')
    expect(wrapper.text()).toContain('仅对当前消息生效')
    await wrapper.findAll('button').find(button => button.text().includes('产品资料'))!.trigger('click')

    expect(wrapper.text()).toContain('本轮使用 1 个上下文')
    expect(wrapper.text()).toContain('发送后清空')
    expect(wrapper.text()).toContain('产品资料')
    expect(wrapper.text()).toContain('知识库')
    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
  })

  it('可以把我的记忆作为本轮上下文随消息发送', async () => {
    const wrapper = mountInput()

    await wrapper.find('button[aria-label="选择上下文"]').trigger('click')
    expect(wrapper.text()).toContain('我的记忆')

    await wrapper.findAll('button').find(button => button.text().includes('我的记忆'))!.trigger('click')
    expect(wrapper.text()).toContain('本轮使用 1 个上下文')
    expect(wrapper.text()).toContain('我的记忆')
    expect(wrapper.text()).toContain('记忆')

    await wrapper.find('textarea').setValue('按我的偏好整理一下这段文字')
    await wrapper.find('button[aria-label="发送消息"]').trigger('click')

    expect(wrapper.emitted('send')?.at(-1)).toEqual([{
      content: '按我的偏好整理一下这段文字',
      attachmentIds: undefined,
      attachments: undefined,
      singleTurnOverride: {
        memoryContextMode: 'focused',
      },
    }])
    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
  })

  it('@记忆 会选择我的记忆并清理输入里的 mention', async () => {
    const wrapper = mountInput()

    await wrapper.find('textarea').setValue('@记')
    expect(wrapper.text()).toContain('我的记忆')

    await wrapper.find('textarea').trigger('keydown', { key: 'Enter' })

    expect(wrapper.text()).toContain('本轮使用 1 个上下文')
    expect(wrapper.text()).toContain('我的记忆')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('本轮不用记忆会替换我的记忆并随消息发送 off 模式', async () => {
    const wrapper = mountInput()

    await wrapper.find('button[aria-label="选择上下文"]').trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('我的记忆'))!.trigger('click')
    expect(wrapper.text()).toContain('我的记忆')

    await wrapper.find('button[aria-label="选择上下文"]').trigger('click')
    expect(wrapper.text()).toContain('关闭记忆')
    await wrapper.findAll('button').find(button => button.text().includes('本轮不用记忆'))!.trigger('click')

    expect(wrapper.text()).toContain('本轮不使用长期记忆')
    expect(wrapper.text()).toContain('本轮不用记忆')
    expect(wrapper.text()).toContain('关闭记忆')
    expect(wrapper.find('[data-context-mode="off"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('我的记忆')

    await wrapper.find('textarea').setValue('只根据这段文字总结')
    await wrapper.find('button[aria-label="发送消息"]').trigger('click')

    expect(wrapper.emitted('send')?.at(-1)).toEqual([{
      content: '只根据这段文字总结',
      attachmentIds: undefined,
      attachments: undefined,
      singleTurnOverride: {
        memoryContextMode: 'off',
      },
    }])
  })

  it('添加附件后只展示附件本身', async () => {
    const wrapper = mountInput()
    const input = wrapper.find('input[type="file"]')
    const file = new File(['hello'], 'brief.txt', { type: 'text/plain' })
    Object.defineProperty(input.element, 'files', {
      value: [file],
      configurable: true,
    })

    await input.trigger('change')

    expect(wrapper.text()).toContain('brief.txt')
    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
  })

  it('工程类输入不再展示推测能力条', async () => {
    const wrapper = mountInput()

    await wrapper.find('textarea').setValue('帮我调研最新资料，运行测试，修复以后提交 PR。')

    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('可能查资料')
    expect(wrapper.text()).not.toContain('可能用工具处理')
    expect(wrapper.text()).not.toContain('可能处理工程流')
  })

  it('输入框不再承载旧版接续提示', () => {
    const wrapper = mountInput()

    expect(wrapper.find('.chat-composer-continuation').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('接续中')
    expect(wrapper.find('.chat-composer-insight').exists()).toBe(false)
  })
})
