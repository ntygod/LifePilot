import { defineComponent, h } from 'vue'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatView from './ChatView.vue'

const mocks = vi.hoisted(() => ({
  route: {
    name: 'newConversation' as string,
    fullPath: '/conversations/new',
    params: {} as Record<string, string>,
    query: {} as Record<string, string>,
  },
  routerReplace: vi.fn(),
  routerPush: vi.fn(),
  chatStore: {
    sessions: [],
    activeSessionId: null as string | null,
    messages: [],
    streamingContent: '',
    pendingFirstMessage: null as string | null,
    pendingDraftMessage: null as string | null,
    pendingFirstSend: null,
    updateMessage: vi.fn(),
    clearCurrentSessionMessages: vi.fn(),
    loadSessions: vi.fn(),
  },
  kbStore: {
    list: [],
    fetchList: vi.fn(),
  },
  skillStore: {
    fetchSkills: vi.fn(),
  },
  uiStore: {
    showToast: vi.fn(),
  },
  processTaskStore: {
    tasksOrdered: [],
    runningCount: 0,
  },
  sendMessage: vi.fn(),
  abort: vi.fn(),
  executeTurn: vi.fn(),
  resolvePermissionApproval: vi.fn(),
  confirmBrowserTakeover: vi.fn(),
  cancelBrowserTakeover: vi.fn(),
  setContent: vi.fn(),
  focusInput: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({
    replace: mocks.routerReplace,
    push: mocks.routerPush,
  }),
}))

vi.mock('@/api/client', () => ({
  chatApi: {
    getSession: vi.fn(),
    updateSession: vi.fn(),
    updateSessionConfig: vi.fn(),
    submitFeedback: vi.fn(),
    clearSessionMessages: vi.fn(),
    forkSession: vi.fn(),
  },
  modelServiceApi: {
    listEnabledServices: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('@/stores/chat', () => ({
  useChatStore: () => mocks.chatStore,
}))

vi.mock('@/stores/knowledgeBase', () => ({
  useKnowledgeBaseStore: () => mocks.kbStore,
}))

vi.mock('@/stores/skill', () => ({
  useSkillStore: () => mocks.skillStore,
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => mocks.uiStore,
}))

vi.mock('@/stores/processTask', () => ({
  useProcessTaskStore: () => mocks.processTaskStore,
}))

vi.mock('@/composables/useChat', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')
  return {
    useChat: () => ({
      sendMessage: mocks.sendMessage,
      executeTurn: mocks.executeTurn,
      isStreaming: ref(false),
      error: ref(null),
      abort: mocks.abort,
      lastModelId: ref(null),
      lastTokenUsage: ref(null),
      lastPrompt: ref(''),
      reasoningStatusText: ref(''),
      reasoningEvents: ref([]),
      streamingReactSteps: ref([]),
      capabilitySuggestions: ref([]),
      streamingA2uiComponents: ref([]),
      streamingArtifactRefs: ref([]),
      pendingPermissionApprovals: ref([]),
      pendingPermissionApprovalResolutions: ref([]),
      resolvePermissionApproval: mocks.resolvePermissionApproval,
      activeBrowserTakeover: ref(null),
      browserTakeoverError: ref(null),
      confirmBrowserTakeover: mocks.confirmBrowserTakeover,
      cancelBrowserTakeover: mocks.cancelBrowserTakeover,
    }),
  }
})

vi.mock('@/composables/useChatOverlays', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')
  return {
    useChatOverlays: () => ({
      activeOverlay: ref(null),
      openTrace: vi.fn(),
      openInfo: vi.fn(),
      openSettings: vi.fn(),
      openTasks: vi.fn(),
      close: vi.fn(),
    }),
  }
})

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: vi.fn(),
}))

function mountView() {
  const ChatInputStub = defineComponent({
    name: 'ChatInput',
    emits: ['send', 'stop'],
    setup(_props, { expose }) {
      expose({
        setContent: mocks.setContent,
        focus: mocks.focusInput,
      })
      return () => h('textarea')
    },
  })
  const CapabilityHintStripStub = defineComponent({
    name: 'CapabilityHintStrip',
    props: {
      compact: Boolean,
      activeCapabilities: Array,
    },
    setup() {
      return () => h('div', { 'data-testid': 'capability-hint' }, '正在调用能力')
    },
  })

  return shallowMount(ChatView, {
    global: {
      stubs: {
        CapabilityHintStrip: CapabilityHintStripStub,
        ChatInput: ChatInputStub,
      },
    },
  })
}

beforeEach(() => {
  mocks.route.name = 'newConversation'
  mocks.route.fullPath = '/conversations/new'
  mocks.route.params = {}
  mocks.route.query = {}
  mocks.routerReplace.mockReset()
  mocks.routerPush.mockReset()
  mocks.chatStore.sessions = []
  mocks.chatStore.activeSessionId = null
  mocks.chatStore.messages = []
  mocks.chatStore.streamingContent = ''
  mocks.chatStore.pendingFirstMessage = null
  mocks.chatStore.pendingDraftMessage = null
  mocks.chatStore.pendingFirstSend = null
  mocks.chatStore.updateMessage.mockReset()
  mocks.chatStore.clearCurrentSessionMessages.mockReset()
  mocks.chatStore.loadSessions.mockReset()
  mocks.kbStore.fetchList.mockReset().mockResolvedValue(undefined)
  mocks.skillStore.fetchSkills.mockReset().mockResolvedValue(undefined)
  mocks.uiStore.showToast.mockReset()
  mocks.sendMessage.mockReset()
  mocks.abort.mockReset()
  mocks.executeTurn.mockReset()
  mocks.resolvePermissionApproval.mockReset()
  mocks.confirmBrowserTakeover.mockReset()
  mocks.cancelBrowserTakeover.mockReset()
  mocks.setContent.mockReset()
  mocks.focusInput.mockReset()
})

describe('ChatView 草稿承接', () => {
  it('会把跨路由草稿预填到空态输入框且不自动发送', async () => {
    mocks.chatStore.pendingDraftMessage = '请审计你对我的理解'

    mountView()
    await flushPromises()

    expect(mocks.setContent).toHaveBeenCalledWith('请审计你对我的理解')
    expect(mocks.focusInput).toHaveBeenCalled()
    expect(mocks.chatStore.pendingDraftMessage).toBeNull()
    expect(mocks.sendMessage).not.toHaveBeenCalled()
  })

  it('空态不会额外渲染能力入口', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findComponent({ name: 'CapabilityHintStrip' }).exists()).toBe(false)
  })
})
