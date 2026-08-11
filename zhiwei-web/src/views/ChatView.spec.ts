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
  getSession: vi.fn(),
  getDiagnosticReport: vi.fn(),
  createDiagnosticBundle: vi.fn(),
  createBackup: vi.fn(),
  validateBackup: vi.fn(),
  uploadDocument: vi.fn(),
  recordKnowledgeSettlement: vi.fn(),
  chatStore: {
    sessions: [] as any[],
    activeSessionId: null as string | null,
    messages: [] as any[],
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
  memoryStore: {
    stats: null as any,
    statsLoading: false,
    memoryDisabled: false,
    loadStats: vi.fn(),
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
  chatStreaming: false,
  reasoningStatus: '',
  streamingReactSteps: [] as any[],
  sendMessage: vi.fn(),
  abort: vi.fn(),
  executeTurn: vi.fn(),
  resolvePermissionApproval: vi.fn(),
  confirmBrowserTakeover: vi.fn(),
  cancelBrowserTakeover: vi.fn(),
  setContent: vi.fn(),
  focusInput: vi.fn(),
  openSettings: vi.fn(),
  chatError: null as string | null,
  lastPrompt: '',
  copyToClipboard: vi.fn(),
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
    getSession: mocks.getSession,
    updateSession: vi.fn(),
    updateSessionConfig: vi.fn(),
    submitFeedback: vi.fn(),
    clearSessionMessages: vi.fn(),
    forkSession: vi.fn(),
    recordKnowledgeSettlement: mocks.recordKnowledgeSettlement,
  },
  modelServiceApi: {
    listEnabledServices: vi.fn().mockResolvedValue([]),
  },
  diagnosticsApi: {
    getReport: mocks.getDiagnosticReport,
    createDiagnosticBundle: mocks.createDiagnosticBundle,
    createBackup: mocks.createBackup,
    validateBackup: mocks.validateBackup,
  },
  knowledgeBaseApi: {
    uploadDocument: mocks.uploadDocument,
  },
}))

vi.mock('@/stores/chat', () => ({
  useChatStore: () => mocks.chatStore,
}))

vi.mock('@/stores/knowledgeBase', () => ({
  useKnowledgeBaseStore: () => mocks.kbStore,
}))

vi.mock('@/stores/memory', () => ({
  useMemoryStore: () => mocks.memoryStore,
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
      isStreaming: ref(mocks.chatStreaming),
      error: ref(mocks.chatError),
      abort: mocks.abort,
      lastModelId: ref(null),
      lastTokenUsage: ref(null),
      lastPrompt: ref(mocks.lastPrompt),
      reasoningStatusText: ref(mocks.reasoningStatus),
      reasoningEvents: ref([]),
      streamingReactSteps: ref(mocks.streamingReactSteps),
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
      openSettings: mocks.openSettings,
      openTasks: vi.fn(),
      openMemory: vi.fn(),
      close: vi.fn(),
    }),
  }
})

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: mocks.copyToClipboard,
}))

vi.mock('@/utils/logger', () => ({
  logger: {
    debug: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
  },
}))

function mountView() {
  const ChatInputStub = defineComponent({
    name: 'ChatInput',
    props: {
      placeholder: String,
    },
    emits: ['send', 'stop', 'draft-change'],
    setup(props, { emit, expose }) {
      expose({
        setContent: mocks.setContent,
        focus: mocks.focusInput,
      })
      return () => h('textarea', {
        placeholder: props.placeholder as string,
        onInput: (event: Event) => emit('draft-change', {
          content: (event.target as HTMLTextAreaElement).value,
          hasAttachments: false,
          contextCount: 0,
        }),
      })
    },
  })
  const OverlayHostStub = defineComponent({
    name: 'OverlayHost',
    setup(_props, { slots }) {
      return () => h('div', slots.default?.())
    },
  })
  const PromptGalleryStub = defineComponent({
    name: 'PromptGallery',
    props: {
      suggestions: Array,
    },
    emits: ['pick'],
    setup(props, { emit }) {
      return () => h(
        'div',
        { 'data-testid': 'prompt-gallery' },
        (props.suggestions as any[] | undefined)?.map(suggestion =>
          h('button', {
            type: 'button',
            onClick: () => emit('pick', suggestion),
          }, suggestion.label),
        ),
      )
    },
  })
  const StatePanelStub = defineComponent({
    name: 'StatePanel',
    props: {
      title: String,
      description: String,
      tone: String,
    },
    setup(props, { slots }) {
      return () => h('section', { 'data-testid': 'state-panel' }, [
        h('h2', props.title as string),
        h('p', props.description as string),
        slots.actions?.(),
        slots.default?.(),
      ])
    },
  })
  const ButtonStub = defineComponent({
    name: 'Button',
    emits: ['click'],
    setup(_props, { emit, slots }) {
      return () => h('button', { type: 'button', onClick: () => emit('click') }, slots.default?.())
    },
  })

  return shallowMount(ChatView, {
    global: {
      stubs: {
        Button: ButtonStub,
        ChatInput: ChatInputStub,
        OverlayHost: OverlayHostStub,
        PromptGallery: PromptGalleryStub,
        StatePanel: StatePanelStub,
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
  mocks.getSession.mockReset().mockResolvedValue({
    id: 'session-1',
    knowledgeBaseIds: [],
  })
  mocks.getDiagnosticReport.mockReset().mockResolvedValue({
    generatedAt: '2026-07-04T10:00:00Z',
    status: 'WARN',
    summary: '本地服务可用，但有配置或运行时风险',
    app: { name: 'zhiwei' },
    runtime: { javaVersion: '22' },
    counts: { 'modelServices.enabled': 0 },
    checks: [
      { id: 'database', label: '数据库', status: 'OK', detail: 'SQLite 连接可用', metadata: {} },
      { id: 'model-services', label: '模型服务', status: 'WARN', detail: '没有启用的生成模型服务', metadata: {} },
      {
        id: 'intelligence',
        label: '智能增强',
        status: 'OK',
        detail: '经验匹配不阻塞主对话，后台增强有独立超时保护',
        metadata: {
          experienceMatchTrigger: 'task-like',
          experienceMatchTimeoutMs: 0,
          experienceMatchBackgroundTimeoutMs: 1200,
          experienceMatchMaxPending: 1,
          experienceMatchRecentTtlSeconds: 300,
          experienceMatchRecentMax: 8,
        },
      },
    ],
    hints: ['模型服务：没有启用的生成模型服务'],
  })
  mocks.createDiagnosticBundle.mockReset().mockResolvedValue({
    createdAt: '2026-07-07T10:00:00Z',
    fileName: 'zhiwei-diagnostic-20260707-100000.zip',
    path: 'C:\\Users\\zsg\\.zhiwei\\diagnostics\\zhiwei-diagnostic-20260707-100000.zip',
    sizeBytes: 4096,
    includedFileCount: 3,
  })
  mocks.createBackup.mockReset().mockResolvedValue({
    createdAt: '2026-07-04T10:01:00Z',
    fileName: 'zhiwei-backup-20260704-100100.zip',
    path: 'C:\\Users\\zsg\\.zhiwei\\backups\\zhiwei-backup-20260704-100100.zip',
    sizeBytes: 1024,
    includedFileCount: 3,
  })
  mocks.validateBackup.mockReset().mockResolvedValue({
    fileName: 'zhiwei-backup-20260704-100100.zip',
    path: 'C:\\Users\\zsg\\.zhiwei\\backups\\zhiwei-backup-20260704-100100.zip',
    status: 'OK',
    detail: '备份文件结构正常',
    sizeBytes: 1024,
    entryCount: 4,
    manifestPresent: true,
    manifest: null,
    problems: [],
    restorePlan: null,
  })
  mocks.uploadDocument.mockReset().mockResolvedValue({
    id: 'doc-1',
    knowledgeBaseId: 'kb-product',
    fileName: '知微回复.md',
    fileSize: 128,
    mimeType: 'text/markdown',
    status: 'READY',
    chunkCount: 1,
    createdAt: '2026-07-04T10:02:00Z',
    updatedAt: '2026-07-04T10:02:00Z',
  })
  mocks.recordKnowledgeSettlement.mockReset().mockResolvedValue(undefined)
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
  mocks.kbStore.list = []
  mocks.kbStore.fetchList.mockReset().mockResolvedValue(undefined)
  mocks.memoryStore.stats = null
  mocks.memoryStore.statsLoading = false
  mocks.memoryStore.memoryDisabled = false
  mocks.memoryStore.loadStats.mockReset().mockResolvedValue(undefined)
  mocks.skillStore.fetchSkills.mockReset().mockResolvedValue(undefined)
  mocks.uiStore.showToast.mockReset()
  mocks.chatStreaming = false
  mocks.reasoningStatus = ''
  mocks.streamingReactSteps = []
  mocks.sendMessage.mockReset()
  mocks.abort.mockReset()
  mocks.executeTurn.mockReset()
  mocks.resolvePermissionApproval.mockReset()
  mocks.confirmBrowserTakeover.mockReset()
  mocks.cancelBrowserTakeover.mockReset()
  mocks.setContent.mockReset()
  mocks.focusInput.mockReset()
  mocks.openSettings.mockReset()
  mocks.chatError = null
  mocks.lastPrompt = ''
  mocks.copyToClipboard.mockReset().mockResolvedValue(true)
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

  it('从消息操作记住内容时，会预填可编辑草稿而不是直接写入', async () => {
    const message = {
      id: 'assistant-remember-chat',
      role: 'assistant',
      content: '用户希望主界面轻量，能力在对话中自然浮现。',
      timestamp: Date.now(),
    } as any
    mocks.chatStore.messages = [message]

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('remember', message)
    await flushPromises()

    expect(mocks.setContent).toHaveBeenCalledWith('请记住：用户希望主界面轻量，能力在对话中自然浮现。')
    expect(mocks.focusInput).toHaveBeenCalled()
    expect(wrapper.text()).toContain('确认后记住')
    expect(wrapper.text()).toContain('发送后后台整理，可查看/调整')
    expect(wrapper.text()).toContain('来源：知微回复')
    expect(wrapper.text()).toContain('用户希望主界面轻量，能力在对话中自然浮现。')
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('info', '已放入输入框，发送后后台整理为记忆')
    expect(mocks.sendMessage).not.toHaveBeenCalled()
  })

  it('发送待确认记忆草稿时，会进入对话后台沉淀而不是直接写入', async () => {
    const message = {
      id: 'assistant-remember-send',
      role: 'assistant',
      content: '用户希望主界面轻量，能力在对话中自然浮现。',
      timestamp: Date.now(),
    } as any
    mocks.chatStore.messages = [message]

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('remember', message)
    await flushPromises()

    const draft = '请记住：用户希望主界面轻量，能力在对话中自然浮现。'
    wrapper.findComponent({ name: 'ChatInput' }).vm.$emit('send', { content: draft })
    await flushPromises()

    expect(mocks.sendMessage).toHaveBeenCalledWith(draft, undefined, undefined, undefined)
    expect(wrapper.text()).not.toContain('确认后记住')
  })

  it('取消待确认记忆草稿时，会清空输入框且不写入记忆', async () => {
    const message = {
      id: 'assistant-remember-cancel',
      role: 'assistant',
      content: '用户希望记忆写入前可以确认和修改。',
      timestamp: Date.now(),
    } as any
    mocks.chatStore.messages = [message]

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('remember', message)
    await flushPromises()

    const close = wrapper.find('[aria-label="取消这条记忆草稿"]')
    expect(close.exists()).toBe(true)

    await close.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('确认后记住')
    expect(mocks.setContent).toHaveBeenLastCalledWith('')
    expect(mocks.sendMessage).not.toHaveBeenCalled()
  })

  it('取消待确认记忆草稿时，会恢复原本输入框草稿', async () => {
    const message = {
      id: 'assistant-remember-restore-draft',
      role: 'assistant',
      content: '用户希望主界面少一点入口。',
      timestamp: Date.now(),
    } as any
    mocks.chatStore.messages = [message]

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('textarea').setValue('我原本正在写的追问')
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('remember', message)
    await flushPromises()

    expect(mocks.setContent).toHaveBeenCalledWith('请记住：用户希望主界面少一点入口。')

    await wrapper.find('[aria-label="取消这条记忆草稿"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('确认后记住')
    expect(mocks.setContent).toHaveBeenLastCalledWith('我原本正在写的追问')
    expect(mocks.sendMessage).not.toHaveBeenCalled()
  })

  it('从回复里的继续动作点击后，会预填输入框而不自动发送', async () => {
    mocks.chatStore.messages = [{
      id: 'assistant-follow-up-chat',
      role: 'assistant',
      content: '可以继续整理成执行清单。',
      timestamp: Date.now(),
    } as any]

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('follow-up', '把上一条回答整理成可以执行的清单。')
    await flushPromises()

    expect(mocks.setContent).toHaveBeenCalledWith('把上一条回答整理成可以执行的清单。')
    expect(mocks.focusInput).toHaveBeenCalled()
    expect(mocks.sendMessage).not.toHaveBeenCalled()
  })

  it('回复存资料没有明确目标时会引导打开会话设置', async () => {
    const message = {
      id: 'assistant-save-without-target',
      role: 'assistant',
      content: '这份结论之后还会复用。',
      timestamp: Date.parse('2026-07-07T09:00:00Z'),
    } as any
    mocks.chatStore.messages = [message]
    mocks.kbStore.list = [
      { id: 'kb-product', name: '产品资料' },
      { id: 'kb-research', name: '研究资料' },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('save-knowledge', message)
    await flushPromises()

    expect(mocks.uploadDocument).not.toHaveBeenCalled()
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('info', '先为会话选择一个资料库，再存入回复')
    expect(mocks.openSettings).toHaveBeenCalledTimes(1)
  })

  it('空态不会额外渲染能力入口', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
  })

  it('新对话首帧直接进入空态输入，不闪出底部输入框', () => {
    const wrapper = mountView()

    expect(wrapper.find('.chat-empty__composer').exists()).toBe(true)
    expect(wrapper.find('.chat-composer-wrap').exists()).toBe(false)
    expect(wrapper.findAllComponents({ name: 'ChatInput' })).toHaveLength(1)
  })

  it('空态有本地资料库时用自然轻提示引导选择资料', async () => {
    mocks.kbStore.list = [
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
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const gallery = wrapper.findComponent({ name: 'PromptGallery' })
    const suggestions = gallery.props('suggestions') as Array<{ id: string; label: string; prompt: string }>
    expect(suggestions[0]).toMatchObject({ id: 'mention-knowledge', label: '选资料提问', prompt: '@' })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).toContain('plan-next-step')
    expect(suggestions.map(item => item.id)).not.toContain('remember-preference')
    expect(suggestions.map(item => item.label)).not.toContain('引用资料')
  })

  it('空态有本地记忆时自然浮现记忆轻提示', async () => {
    mocks.memoryStore.stats = {
      conversationCount: 2,
      entityCount: 1,
      entityCountByType: {},
      relationCount: 0,
      templateCount: 0,
      preferenceCount: 1,
      forgettingLogCount: 0,
      lastForgettingTime: null,
    }

    const wrapper = mountView()
    await flushPromises()

    expect(mocks.memoryStore.loadStats).not.toHaveBeenCalled()
    const gallery = wrapper.findComponent({ name: 'PromptGallery' })
    const suggestions = gallery.props('suggestions') as Array<{ id: string; label: string; prompt: string }>
    expect(suggestions[0]).toMatchObject({
      id: 'use-memory',
      label: '按我的习惯推进',
      prompt: '请结合你记得的我的偏好、背景和已有信息，帮我推进这件事：',
    })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).toContain('sort-material')
    expect(suggestions.map(item => item.label)).not.toContain('用我的记忆')
  })

  it('空态同时有记忆和资料库时只露出一个上下文入口', async () => {
    mocks.memoryStore.stats = {
      conversationCount: 2,
      entityCount: 1,
      entityCountByType: {},
      relationCount: 0,
      templateCount: 0,
      preferenceCount: 1,
      forgettingLogCount: 0,
      lastForgettingTime: null,
    }
    mocks.kbStore.list = [
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
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const gallery = wrapper.findComponent({ name: 'PromptGallery' })
    const suggestions = gallery.props('suggestions') as Array<{ id: string; label: string; prompt: string }>
    expect(suggestions[0]).toMatchObject({ id: 'mention-knowledge', label: '选资料提问', prompt: '@' })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).not.toContain('use-memory')
    expect(suggestions.map(item => item.id)).toContain('plan-next-step')
  })

  it('空态没有记忆统计时只在后台加载，不提前展示记忆入口', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(mocks.memoryStore.loadStats).toHaveBeenCalledTimes(1)
    const gallery = wrapper.findComponent({ name: 'PromptGallery' })
    const suggestions = gallery.props('suggestions') as Array<{ id: string }>
    expect(suggestions.map(item => item.id)).not.toContain('use-memory')
  })

  it('空态开始输入后收起轻提示，避免主界面继续堆能力入口', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findComponent({ name: 'PromptGallery' }).exists()).toBe(true)

    await wrapper.find('textarea').setValue('我想整理一下这段材料')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'PromptGallery' }).exists()).toBe(false)
  })

  it('会把记忆来源 query 传给消息列表用于定位证据消息', async () => {
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1?turnId=turn-source&entryId=entry-assistant'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.route.query = {
      turnId: 'turn-source',
      entryId: 'entry-assistant',
    }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [
      {
        id: 'entry-assistant',
        turnId: 'turn-source',
        role: 'assistant',
        content: '我会记住这个偏好。',
        timestamp: Date.now(),
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const list = wrapper.findComponent({ name: 'MessageList' })
    expect(list.props('focusedTurnId')).toBe('turn-source')
    expect(list.props('focusedEntryId')).toBe('entry-assistant')
  })

  it('当前会话只绑定一个资料库时，会把它作为产物沉淀目标', async () => {
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [
      {
        id: 'assistant-artifact-target',
        role: 'assistant',
        content: '报告已生成。',
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
        ],
      },
    ] as any
    mocks.kbStore.list = [
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
      {
        id: 'kb-other',
        name: '其他资料',
        description: '其他文档',
        embeddingModel: 'bge',
        documentCount: 0,
        totalChunks: 0,
        createdAt: '2026-07-04T00:00:00Z',
        updatedAt: '2026-07-04T00:00:00Z',
      },
    ] as any
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      title: '产品对话',
      knowledgeBaseIds: ['kb-product'],
    })

    const wrapper = mountView()
    await flushPromises()

    const list = wrapper.findComponent({ name: 'MessageList' })
    expect(list.props('artifactKnowledgeBaseId')).toBe('kb-product')
    expect(list.props('artifactKnowledgeBaseName')).toBe('产品资料')
  })

  it('可以把助手文本回复整理成 Markdown 存入当前资料库', async () => {
    const message = {
      id: 'assistant-save-knowledge-chat',
      role: 'assistant',
      content: '## 阶段结论\n\n主对话需要形成输入、执行、产出、沉淀闭环。',
      timestamp: Date.parse('2026-07-04T13:00:00Z'),
    } as any
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [message]
    mocks.kbStore.list = [
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
    ] as any
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      title: '主对话闭环',
      knowledgeBaseIds: ['kb-product'],
    })

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('save-knowledge', message)
    await flushPromises()

    expect(mocks.uploadDocument).toHaveBeenCalledWith('kb-product', expect.any(File))
    expect(mocks.recordKnowledgeSettlement).toHaveBeenCalledWith('assistant-save-knowledge-chat', {
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料',
      sourceType: 'MESSAGE_TEXT',
    })
    const uploadedFile = mocks.uploadDocument.mock.calls[0][1] as File
    expect(uploadedFile.name).toMatch(/^阶段结论-\d{8}-\d{4}\.md$/)
    expect(uploadedFile.type).toBe('text/markdown')
    await expect(uploadedFile.text()).resolves.toContain('> 来源：知微对话 / 主对话闭环')
    await expect(uploadedFile.text()).resolves.toContain('主对话需要形成输入、执行、产出、沉淀闭环。')
    expect(mocks.kbStore.fetchList).toHaveBeenCalled()
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('success', '已存入资料库：产品资料')
    expect(wrapper.findComponent({ name: 'MessageList' }).props('savedKnowledgeMessages')).toEqual({
      'assistant-save-knowledge-chat': {
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })
    expect(mocks.sendMessage).not.toHaveBeenCalled()
  })

  it('助手文本回复存资料失败后在消息列表保留原因，重试成功后清理', async () => {
    const message = {
      id: 'assistant-save-knowledge-retry-chat',
      role: 'assistant',
      content: '## 阶段结论\n\n这条回复第一次沉淀失败后应该允许在原消息重试。',
      timestamp: Date.parse('2026-07-04T13:00:00Z'),
    } as any
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [message]
    mocks.kbStore.list = [
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
    ] as any
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      title: '主对话闭环',
      knowledgeBaseIds: ['kb-product'],
    })
    mocks.uploadDocument.mockRejectedValueOnce(new Error('索引服务不可用'))

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('save-knowledge', message)
    await flushPromises()

    expect(wrapper.findComponent({ name: 'MessageList' }).props('saveKnowledgeErrors')).toEqual({
      'assistant-save-knowledge-retry-chat': '索引服务不可用',
    })
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('error', '索引服务不可用')

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('save-knowledge', message)
    await flushPromises()

    expect(mocks.uploadDocument).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'MessageList' }).props('saveKnowledgeErrors')).toEqual({})
    expect(wrapper.findComponent({ name: 'MessageList' }).props('savedKnowledgeMessages')).toEqual({
      'assistant-save-knowledge-retry-chat': {
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })
  })

  it('助手文本回复上传成功但沉淀记录失败时不会显示已存入', async () => {
    const message = {
      id: 'assistant-save-knowledge-record-failed',
      role: 'assistant',
      content: '## 阶段结论\n\n上传成功后也必须等会话记录落库，才能显示已存入。',
      timestamp: Date.parse('2026-07-04T13:00:00Z'),
    } as any
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [message]
    mocks.kbStore.list = [
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
    ] as any
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      title: '主对话闭环',
      knowledgeBaseIds: ['kb-product'],
    })
    mocks.recordKnowledgeSettlement.mockRejectedValueOnce(new Error('会话记录暂不可写'))

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('save-knowledge', message)
    await flushPromises()

    expect(mocks.uploadDocument).toHaveBeenCalledWith('kb-product', expect.any(File))
    expect(mocks.recordKnowledgeSettlement).toHaveBeenCalledWith('assistant-save-knowledge-record-failed', {
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料',
      sourceType: 'MESSAGE_TEXT',
    })
    expect(wrapper.findComponent({ name: 'MessageList' }).props('saveKnowledgeErrors')).toEqual({
      'assistant-save-knowledge-record-failed': '会话记录暂不可写',
    })
    expect(wrapper.findComponent({ name: 'MessageList' }).props('savedKnowledgeMessages')).toEqual({})
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('error', '会话记录暂不可写')
  })

  it('文件产物存入资料库后会在主对话记录这条消息的沉淀状态', async () => {
    const message = {
      id: 'assistant-save-artifact-chat',
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
    } as any
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [message]
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      title: '主对话闭环',
      knowledgeBaseIds: ['kb-product'],
    })

    const wrapper = mountView()
    await flushPromises()
    mocks.kbStore.fetchList.mockClear()
    mocks.uploadDocument.mockClear()

    const payload = {
      artifactId: 'artifact-report',
      fileName: 'report.md',
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料',
    }
    const list = wrapper.findComponent({ name: 'MessageList' })
    const persistArtifactKnowledgeSettlement = list.props('persistArtifactKnowledgeSettlement') as
      ((target: typeof message, saved: typeof payload) => Promise<void>)

    await persistArtifactKnowledgeSettlement(message, payload)
    list.vm.$emit('save-artifact-knowledge', message, payload)
    await flushPromises()

    expect(mocks.uploadDocument).not.toHaveBeenCalled()
    expect(mocks.recordKnowledgeSettlement).toHaveBeenCalledWith('assistant-save-artifact-chat', {
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料',
      sourceType: 'ARTIFACT',
      artifactId: 'artifact-report',
      fileName: 'report.md',
    })
    expect(mocks.kbStore.fetchList).toHaveBeenCalledTimes(1)
    expect(wrapper.findComponent({ name: 'MessageList' }).props('savedKnowledgeMessages')).toEqual({
      'assistant-save-artifact-chat': {
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })
  })

  it('空态会优先浮现当前会话关联的资料库', async () => {
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.kbStore.list = [
      {
        id: 'kb-product',
        name: '产品资料库',
        description: '产品文档',
        embeddingModel: 'bge',
        documentCount: 1,
        totalChunks: 8,
        createdAt: '2026-07-04T00:00:00Z',
        updatedAt: '2026-07-04T00:00:00Z',
      },
    ] as any
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      knowledgeBaseIds: ['kb-product'],
    })

    const wrapper = mountView()
    await flushPromises()

    const gallery = wrapper.findComponent({ name: 'PromptGallery' })
    const suggestions = gallery.props('suggestions') as Array<{ id: string; label: string; prompt: string }>
    expect(suggestions[0]).toMatchObject({
      id: 'ask-linked-knowledge',
      label: '基于「产品资料库」',
      prompt: '请基于「产品资料库」帮我回答：',
    })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).toContain('sort-material')
  })

  it('流式中只有泛化准备状态时保持主输入区安静', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我整理一下',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStreaming = true
    mocks.reasoningStatus = '加载上下文中'

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
  })

  it('流式早期没有真实状态事件时不展示理解阶段兜底', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我整理一下',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStreaming = true
    mocks.reasoningStatus = ''

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
  })

  it('流式早期没有真实执行信号时不浮现能力状态', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我调研最新资料',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStreaming = true
    mocks.reasoningStatus = ''

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
  })

  it('真实执行信号只传给消息流，不在输入区浮现能力条', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我调研最新资料',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStreaming = true
    mocks.streamingReactSteps = [
      {
        type: 'TOOL_CALL',
        index: 0,
        toolId: 'web.search',
        toolName: '联网搜索',
        inputSummary: '搜索最新资料',
        latencyMs: 0,
      },
    ]

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MessageList' }).props('streamingReactSteps')).toEqual(mocks.streamingReactSteps)
  })

  it('不会把内部意图状态重新浮现在主对话里', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我整理一下',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStreaming = true
    mocks.reasoningStatus = '意图识别中'

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
  })

  it('会话概览状态不会显示内部意图识别文本', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我整理一下',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStreaming = true
    mocks.reasoningStatus = '意图识别中'

    const wrapper = mountView()
    await flushPromises()

    const sidebar = wrapper.findComponent({ name: 'SessionSidebar' })
    expect(sidebar.exists()).toBe(true)
    expect(sidebar.props('statusText')).toBe('正在回应')
  })

  it('流式已开始输出时仍只把内容交给消息流', async () => {
    mocks.chatStore.messages = [
      {
        id: 'user-1',
        role: 'user',
        content: '帮我整理一下',
        timestamp: Date.now(),
      },
    ] as any
    mocks.chatStore.streamingContent = '这是整理好的第一段'
    mocks.chatStreaming = true
    mocks.reasoningStatus = ''

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.chat-composer-wrap__capabilities').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MessageList' }).props('streamingContent')).toBe('这是整理好的第一段')
  })

  it('全局错误面板可以复制轻量诊断信息', async () => {
    mocks.chatError = '模型服务连接失败'
    mocks.lastPrompt = '帮我整理今天的资料'
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '处理到一半失败了',
        timestamp: Date.now(),
        traceId: 'trace-1',
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('复制诊断'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const diagnostic = mocks.copyToClipboard.mock.calls[0][0]
    expect(diagnostic).toContain('[知微诊断]')
    expect(diagnostic).toContain('范围: 全局错误')
    expect(diagnostic).toContain('会话: session-1')
    expect(diagnostic).toContain('路由: /conversations/session-1')
    expect(diagnostic).toContain('Trace: trace-1')
    expect(diagnostic).toContain('错误: 模型服务连接失败')
    expect(diagnostic).toContain('最近输入摘要: 帮我整理今天的资料')
    expect(diagnostic).toContain('本地诊断状态: WARN')
    expect(diagnostic).toContain('本地诊断检查: 数据库=OK; 模型服务=WARN; 智能增强=OK')
    expect(diagnostic).toContain('智能增强状态: 智能增强=OK；说明=经验匹配不阻塞主对话，后台增强有独立超时保护；经验匹配=task-like；前台=0ms；经验后台上限=1；经验后台超时=1200ms；最近复用=300s/8')
    expect(diagnostic).toContain('下一步建议: 1. 到模型服务设置中启用一个生成模型，再重试当前消息。')
    expect(wrapper.text()).toContain('打开模型设置')
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('success', '诊断信息已复制')
  })

  it('全局错误面板分析本机后可以打开对应设置页', async () => {
    mocks.chatError = '模型服务连接失败'
    mocks.chatStore.messages = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '处理到一半失败了',
        timestamp: Date.now(),
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const analyzeButton = wrapper.findAll('button').find(item => item.text().includes('分析本机'))
    expect(analyzeButton).toBeTruthy()
    await analyzeButton!.trigger('click')
    await flushPromises()

    expect(mocks.getDiagnosticReport).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('本机状态 WARN：本地服务可用，但有配置或运行时风险')
    expect(wrapper.text()).toContain('打开模型设置')

    const repairButton = wrapper.findAll('button').find(item => item.text().includes('打开模型设置'))
    expect(repairButton).toBeTruthy()
    await repairButton!.trigger('click')

    expect(mocks.routerPush).toHaveBeenCalledWith({ name: 'settingsModels' })
  })

  it('全局错误面板可以创建本地备份', async () => {
    mocks.chatError = '模型服务连接失败'
    mocks.chatStore.messages = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '处理到一半失败了',
        timestamp: Date.now(),
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('创建备份'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.createBackup).toHaveBeenCalledTimes(1)
    expect(mocks.validateBackup).toHaveBeenCalledWith('zhiwei-backup-20260704-100100.zip')
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith(
      'success',
      '已创建并校验本地备份：zhiwei-backup-20260704-100100.zip',
    )
  })

  it('全局错误面板可以生成本地诊断包', async () => {
    mocks.chatError = '模型服务连接失败'
    mocks.chatStore.messages = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '处理到一半失败了',
        timestamp: Date.now(),
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('生成诊断包'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.createDiagnosticBundle).toHaveBeenCalledTimes(1)
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith(
      'success',
      '已生成诊断包：zhiwei-diagnostic-20260707-100000.zip',
    )
  })

  it('诊断包生成失败时会提示错误原因', async () => {
    mocks.chatError = '模型服务连接失败'
    mocks.createDiagnosticBundle.mockRejectedValueOnce(new Error('日志目录不可写'))
    mocks.chatStore.messages = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '处理到一半失败了',
        timestamp: Date.now(),
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('生成诊断包'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.createDiagnosticBundle).toHaveBeenCalledTimes(1)
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('error', '日志目录不可写')
  })

  it('本地备份失败时会提示错误原因', async () => {
    mocks.chatError = '模型服务连接失败'
    mocks.createBackup.mockRejectedValueOnce(new Error('磁盘空间不足'))
    mocks.chatStore.messages = [
      {
        id: 'assistant-1',
        turnId: 'turn-1',
        role: 'assistant',
        content: '处理到一半失败了',
        timestamp: Date.now(),
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('创建备份'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.createBackup).toHaveBeenCalledTimes(1)
    expect(mocks.uiStore.showToast).toHaveBeenCalledWith('error', '磁盘空间不足')
  })

  it('工具恢复动作会带着失败上下文触发 RESUME', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我跑测试',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-1',
      turnId: 'turn-1',
      role: 'assistant',
      content: '测试失败，可以继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
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
    }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('resume', assistantMessage, {
      id: 'resume',
      label: '修正后继续',
      mode: 'resume',
      category: 'COMMAND',
      toolId: 'shell.exec',
      callId: 'call-shell-1',
      toolName: 'Shell 执行',
      inputSummary: '执行 `npm test`',
      outputSummary: '测试失败',
      interrupted: true,
      outputDetail: 'AssertionError: expected true to be false',
      workingDirectory: 'D:\\WorkSpace\\Project\\News',
      nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
    })
    await flushPromises()

    expect(mocks.executeTurn).toHaveBeenCalledWith(
      'turn-1',
      'RESUME',
      expect.objectContaining({
        content: undefined,
        recoveryAction: expect.objectContaining({
          label: '修正后继续',
          toolId: 'shell.exec',
          callId: 'call-shell-1',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          interrupted: true,
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
        userMessageId: 'user-1',
        preserveUserMessageContent: true,
      }),
    )
    const options = mocks.executeTurn.mock.calls[0][2]
    expect(options.content).toBeUndefined()
    expect(options.recoveryAction.outputDetail).toBe('AssertionError: expected true to be false')
  })

  it('打开任务步骤时会把 toolsSummary 传给 TracePanel', async () => {
    const assistantMessage = {
      id: 'assistant-tools-summary',
      turnId: 'turn-1',
      role: 'assistant',
      content: '我已经整理好了。',
      timestamp: Date.now(),
      turnRecoveryContext: {
        action: 'RESUME',
        sourceTraceId: 'trace-before',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          action: '查找资料',
          failureCategory: 'NETWORK',
          inputSummary: '搜索知微资料',
          outputSummary: '上次搜索中断',
        },
        nextActions: ['更换资料来源或重试失败请求'],
      },
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
    }
    mocks.chatStore.messages = [assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('show-trace', 'assistant-tools-summary')
    await flushPromises()

    const tracePanel = wrapper.findComponent({ name: 'TracePanel' })
    expect(tracePanel.exists()).toBe(true)
    expect(tracePanel.props('toolSummaries')).toEqual(assistantMessage.toolsSummary)
    expect(tracePanel.props('turnRecoveryContext')).toEqual(assistantMessage.turnRecoveryContext)
  })

  it('技能恢复动作会带着技能主体触发 RESUME', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我用调研技能整理资料',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-1',
      turnId: 'turn-1',
      role: 'assistant',
      content: '技能加载失败，可以继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('resume', assistantMessage, {
      id: 'resume',
      label: '检查技能后继续',
      mode: 'resume',
      category: 'SKILL',
      toolId: 'skill.load',
      toolName: '加载 Skill',
      executionKind: 'SKILL',
      subjectLabel: '技能',
      subjectNames: ['research-assistant'],
      outputSummary: '技能 research-assistant 不存在',
      recoveryHint: '技能加载没有完成，可以检查技能名称或依赖后继续。',
    })
    await flushPromises()

    expect(mocks.executeTurn).toHaveBeenCalledWith(
      'turn-1',
      'RESUME',
      expect.objectContaining({
        content: undefined,
        recoveryAction: expect.objectContaining({
          label: '检查技能后继续',
          toolId: 'skill.load',
          subjectNames: ['research-assistant'],
          outputSummary: '技能 research-assistant 不存在',
        }),
        userMessageId: 'user-1',
        preserveUserMessageContent: true,
      }),
    )
    const options = mocks.executeTurn.mock.calls[0][2]
    expect(options.content).toBeUndefined()
    expect(options.recoveryAction.recoveryHint).toBe('技能加载没有完成，可以检查技能名称或依赖后继续。')
  })

  it('工具重新开始动作会带着失败上下文触发 RESTART', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我跑测试',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-1',
      turnId: 'turn-1',
      role: 'assistant',
      content: '测试失败，可以重新开始。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('restart', assistantMessage, {
      id: 'restart',
      label: '重新开始',
      mode: 'restart',
      category: 'COMMAND',
      toolId: 'shell.exec',
      toolName: 'Shell 执行',
      outputSummary: '测试失败',
    })
    await flushPromises()

    expect(mocks.executeTurn).toHaveBeenCalledWith(
      'turn-1',
      'RESTART',
      expect.objectContaining({
        content: undefined,
        recoveryAction: expect.objectContaining({
          label: '重新开始',
          toolId: 'shell.exec',
          outputSummary: '测试失败',
        }),
        userMessageId: 'user-1',
        preserveUserMessageContent: true,
        visibleContent: '帮我跑测试',
      }),
    )
    const options = mocks.executeTurn.mock.calls[0][2]
    expect(options.content).toBeUndefined()
    expect(options.recoveryAction.outputSummary).toBe('测试失败')
  })

  it('浏览器接管挂起不会把顶部提示变成普通继续入口', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我打开后台处理登录',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-browser',
      turnId: 'turn-1',
      role: 'assistant',
      content: '需要你在浏览器里完成登录。',
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
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('canResume')).toBe(false)
    expect(hint.props('statusLabel')).toBe('浏览器接管')
    expect(wrapper.findComponent({ name: 'SessionSidebar' }).props('statusText')).toBe('浏览器接管')
    expect(wrapper.find('textarea').attributes('placeholder')).toBe('继续说…')

    hint.vm.$emit('resume')
    await flushPromises()

    expect(mocks.executeTurn).not.toHaveBeenCalled()
  })

  it('等待用户补充时输入框提示直接补充即可续接', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我准备仓库检查',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-await-user',
      turnId: 'turn-1',
      role: 'assistant',
      content: '请补充仓库地址。',
      timestamp: Date.now(),
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      suspendReasonSourceId: '__await_user_input__',
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '你直接回复仓库地址，知微会接着当前进度继续。',
        actionLabel: '等待',
        canResume: false,
        canRestart: true,
        resumeMode: 'user_reply',
      },
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('statusLabel')).toBe('等你补充')
    expect(hint.props('inputHint')).toBe('在输入框补充，发送后会自动续接。')
    expect(wrapper.findComponent({ name: 'SessionSidebar' }).props('statusText')).toBe('等你补充')
    expect(wrapper.find('textarea').attributes('placeholder')).toBe('补充信息，发送后继续…')
  })

  it('仅恢复摘要标记等待用户补充时也显示自动续接提示', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我改写发布稿',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-await-user-by-recovery',
      turnId: 'turn-1',
      role: 'assistant',
      content: '还缺发布渠道。',
      timestamp: Date.now(),
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '补充发布渠道后继续。',
        actionLabel: '等待',
        canResume: true,
        canRestart: true,
        reasonSourceId: '__await_user_input__',
      },
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('canResume')).toBe(false)
    expect(hint.props('statusLabel')).toBe('等你补充')
    expect(hint.props('inputHint')).toBe('在输入框补充，发送后会自动续接。')
    expect(wrapper.findComponent({ name: 'SessionSidebar' }).props('statusText')).toBe('等你补充')
    expect(wrapper.find('textarea').attributes('placeholder')).toBe('补充信息，发送后继续…')
  })

  it('手动恢复挂起仍然可以从顶部提示继续执行', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我跑测试',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-manual',
      turnId: 'turn-1',
      role: 'assistant',
      content: '测试失败，可以继续。',
      timestamp: Date.now(),
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      taskRecovery: {
        status: 'SUSPENDED',
        title: 'Shell 执行没有完成',
        detail: '命令失败，可以修正后继续。',
        actionLabel: '继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          callId: 'call-shell-1',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          interrupted: true,
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证', '补跑相关测试'],
      },
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('canResume')).toBe(true)
    expect(hint.props('statusLabel')).toBe(null)
    expect(hint.props('detail')).toBe('命令失败，可以修正后继续。')
    expect(hint.props('checkpointLabel')).toBe('执行命令 · Shell 执行')
    expect(hint.props('checkpointDetail')).toBe('测试失败')
    expect(hint.props('nextActions')).toEqual(['查看命令输出并修正报错原因', '从失败命令后继续执行验证'])
    expect(hint.props('retainedContext')).toEqual(['带上原始输入', '带上失败输出', '工作目录 News'])

    hint.vm.$emit('resume')
    await flushPromises()

    expect(mocks.executeTurn).toHaveBeenCalledWith(
      'turn-1',
      'RESUME',
      expect.objectContaining({
        content: undefined,
        recoveryAction: expect.objectContaining({
          id: 'task-recovery-resume',
          label: '继续',
          description: '保留当前进度，按恢复计划从卡住的位置继续。',
          mode: 'resume',
          category: 'COMMAND',
          toolId: 'shell.exec',
          callId: 'call-shell-1',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          interrupted: true,
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
          recoveryHint: '命令失败，可以修正后继续。',
          nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证', '补跑相关测试'],
        }),
        userMessageId: 'user-1',
        preserveUserMessageContent: true,
      }),
    )
  })

  it('工具或技能失败降级后也会在输入框上方浮现继续入口', async () => {
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我用调研技能整理资料',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-degraded-skill',
      turnId: 'turn-1',
      role: 'assistant',
      content: '调研技能没有完成，可以从失败处继续。',
      timestamp: Date.now(),
      turnStatus: 'FAILED',
      completionMode: 'DEGRADED',
      taskRecovery: {
        status: 'DEGRADED',
        title: '技能 research-assistant 没有完成',
        detail: '技能执行没有完成，可以检查输入、依赖或技能步骤后继续。',
        actionLabel: '检查技能后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'skill.run',
          callId: 'call-skill-run-1',
          toolName: '执行 Skill',
          executionKind: 'SKILL',
          action: '执行技能',
          failureCategory: 'SKILL',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          inputSummary: '执行技能「research-assistant」',
          outputSummary: '资料源不可用',
        },
        nextActions: ['检查技能 research-assistant 的输入、依赖和执行步骤', '保留当前进度并从失败技能步骤继续'],
      },
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('title')).toBe('技能 research-assistant 没有完成')
    expect(hint.props('actionLabel')).toBe('检查技能后继续')
    expect(hint.props('checkpointLabel')).toBe('技能 research-assistant')
    expect(hint.props('checkpointDetail')).toBe('资料源不可用')
    expect(hint.props('repairLabel')).toBe(null)
    expect(hint.props('nextActions')).toEqual([
      '检查技能 research-assistant 的输入、依赖和执行步骤',
      '保留当前进度并从失败技能步骤继续',
    ])
    expect(hint.props('retainedContext')).toEqual(['保留技能 research-assistant', '带上原始输入', '带上失败输出'])
    expect(wrapper.findComponent({ name: 'SessionSidebar' }).props('statusText')).toBe('可继续')
    expect(wrapper.find('textarea').attributes('placeholder')).toBe('继续说…')

    hint.vm.$emit('resume')
    await flushPromises()

    expect(mocks.executeTurn).toHaveBeenCalledWith(
      'turn-1',
      'RESUME',
      expect.objectContaining({
        content: undefined,
        recoveryAction: expect.objectContaining({
          id: 'task-recovery-resume',
          label: '检查技能后继续',
          mode: 'resume',
          category: 'SKILL',
          toolId: 'skill.run',
          callId: 'call-skill-run-1',
          executionKind: 'SKILL',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          inputSummary: '执行技能「research-assistant」',
          outputSummary: '资料源不可用',
          recoveryHint: '技能执行没有完成，可以检查输入、依赖或技能步骤后继续。',
        }),
        userMessageId: 'user-1',
        preserveUserMessageContent: true,
      }),
    )
  })

  it('能力缺口失败的恢复提示可以直接打开能力中心', async () => {
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我联网查资料',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-capability',
      turnId: 'turn-1',
      role: 'assistant',
      content: '搜索工具没有加载，我可以在能力修复后继续。',
      timestamp: Date.now(),
      turnStatus: 'FAILED',
      completionMode: 'DEGRADED',
      taskRecovery: {
        status: 'DEGRADED',
        title: '能力需要修复',
        detail: '依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。',
        actionLabel: '修复能力后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'web.search',
          callId: 'call-web-search-1',
          toolName: '网页搜索',
          executionKind: 'TOOL',
          action: '搜索资料',
          failureCategory: 'CAPABILITY',
          inputSummary: '查询最新资料',
          outputSummary: '工具未注册',
        },
        nextActions: ['确认能力中心能看到所需工具和技能', '修正 Skill suggestedTools 或恢复缺失的工具提供方'],
      },
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('repairLabel')).toBe('能力中心')
    expect(hint.props('repairTitle')).toBe('打开能力中心，检查工具、技能状态和 Skill 引用')
    expect(hint.props('actionLabel')).toBe('修复能力后继续')
    expect(hint.props('checkpointLabel')).toBe('搜索资料 · 网页搜索')
    expect(hint.props('checkpointDetail')).toBe('工具未注册')

    hint.vm.$emit('repair')
    await flushPromises()

    expect(mocks.routerPush).toHaveBeenCalledWith({
      name: 'capabilities',
      query: {
        from: 'task-recovery',
        returnSessionId: 'session-1',
        returnTurnId: 'turn-1',
        returnEntryId: 'assistant-capability',
      },
    })
    expect(mocks.executeTurn).not.toHaveBeenCalled()
  })

  it('续接上下文里的能力缺口也会定位到能力中心', async () => {
    mocks.route.name = 'conversationDetail'
    mocks.route.fullPath = '/conversations/session-1'
    mocks.route.params = { sessionId: 'session-1' }
    mocks.chatStore.activeSessionId = 'session-1'
    const userMessage = {
      id: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我调研一下 AI Agent 最新资料',
      timestamp: Date.now(),
    }
    const assistantMessage = {
      id: 'assistant-turn-recovery-capability',
      turnId: 'turn-1',
      role: 'assistant',
      content: '已保留断点，修复搜索能力后可以继续。',
      timestamp: Date.now(),
      turnStatus: 'FAILED',
      completionMode: 'DEGRADED',
      turnRecoveryContext: {
        action: 'RESUME',
        title: '本轮可以继续',
        detail: '搜索工具当前不可用，修复后从失败步骤继续。',
        nextActions: ['补齐缺失能力：web.search', '修复后从失败步骤继续'],
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'skill.run',
          toolName: '技能执行',
          executionKind: 'SKILL',
          action: '执行技能',
          failureCategory: 'CAPABILITY',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          outputSummary: '工具未注册：web.search',
          missingCapabilities: [
            {
              kind: 'TOOL',
              id: 'web.search',
              source: 'suggested_tools',
              skillName: 'research-assistant',
            },
          ],
        },
      },
    }
    mocks.chatStore.messages = [userMessage, assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    const hint = wrapper.findComponent({ name: 'ContinuationHint' })
    expect(hint.exists()).toBe(true)
    expect(hint.props('title')).toBe('本轮可以继续')
    expect(hint.props('repairLabel')).toBe('能力中心')
    expect(hint.props('checkpointLabel')).toBe('技能 research-assistant')
    expect(hint.props('checkpointDetail')).toBe('工具未注册：web.search')
    expect(hint.props('nextActions')).toEqual(['补齐缺失能力：web.search', '修复后从失败步骤继续'])

    hint.vm.$emit('repair')
    await flushPromises()

    expect(mocks.routerPush).toHaveBeenCalledWith({
      name: 'capabilities',
      query: {
        from: 'task-recovery',
        returnSessionId: 'session-1',
        returnTurnId: 'turn-1',
        returnEntryId: 'assistant-turn-recovery-capability',
        missing: 'web.search',
        skill: 'research-assistant',
      },
    })
  })

  it('对话内忘记记忆后同步移除消息中的记忆标签', async () => {
    const memorySource = {
      type: 'memory',
      id: 'memory-1',
      name: '主界面偏好',
    }
    const kbSource = {
      type: 'knowledgeBase',
      id: 'kb-1',
      name: '产品资料',
    }
    const otherMemorySource = {
      type: 'memory',
      id: 'memory-2',
      name: '写作偏好',
    }
    const assistantMessage = {
      id: 'assistant-memory',
      role: 'assistant',
      content: '我参考了你的偏好。',
      timestamp: Date.now(),
      sources: [memorySource, kbSource],
      memoryChanges: [memorySource, otherMemorySource],
    }
    mocks.chatStore.messages = [assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('inspect-memory', memorySource)
    await flushPromises()

    const panel = wrapper.findComponent({ name: 'MemoryInsightPanel' })
    expect(panel.props('source')).toMatchObject({ id: 'memory-1' })
    expect(panel.props('projectId')).toBeNull()

    panel.vm.$emit('deleted', 'memory-1')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'MemoryInsightPanel' }).props('source')).toMatchObject({
      id: 'memory-1',
      extra: {
        operation: 'DELETE',
        operationLabel: '忘记',
      },
    })
    expect(mocks.chatStore.updateMessage).toHaveBeenCalledWith('assistant-memory', {
      sources: [kbSource],
      memoryChanges: [otherMemorySource],
    })
  })

  it('对话内编辑记忆后同步刷新消息中的记忆标签', async () => {
    const memorySource = {
      type: 'memory',
      id: 'memory-1',
      name: '旧主界面偏好',
      extra: {
        operationLabel: '新增',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '主界面做轻',
      },
    }
    const kbSource = {
      type: 'knowledgeBase',
      id: 'kb-1',
      name: '产品资料',
    }
    const assistantMessage = {
      id: 'assistant-memory-update',
      role: 'assistant',
      content: '我记住了你的偏好。',
      timestamp: Date.now(),
      sources: [memorySource, kbSource],
      memoryChanges: [memorySource],
    }
    mocks.chatStore.messages = [assistantMessage] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('inspect-memory', memorySource)
    await flushPromises()

    wrapper.findComponent({ name: 'MemoryInsightPanel' }).vm.$emit('updated', {
      id: 'memory-1',
      type: 'PREFERENCE',
      typeLabel: '偏好',
      name: '极简主界面偏好',
      description: '用户希望主界面轻量、少入口。',
      memoryScope: 'USER_PROFILE',
      temporality: 'PERSISTENT',
      expiresAt: null,
      importanceScore: 0.88,
      trustLevel: 'EXPLICIT',
      trustScore: 0.93,
      evidenceCount: 2,
    })
    await flushPromises()

    expect(mocks.chatStore.updateMessage).toHaveBeenCalledWith('assistant-memory-update', {
      sources: [
        expect.objectContaining({
          id: 'memory-1',
          name: '极简主界面偏好',
          extra: expect.objectContaining({
            description: '用户希望主界面轻量、少入口。',
            memoryScope: 'USER_PROFILE',
            temporality: 'PERSISTENT',
            importanceScore: 0.88,
            trustLevel: 'EXPLICIT',
            trustScore: 0.93,
          }),
        }),
        kbSource,
      ],
      memoryChanges: [
        expect.objectContaining({
          id: 'memory-1',
          name: '极简主界面偏好',
          extra: expect.objectContaining({
            operationLabel: '新增',
            evidenceExcerpt: '主界面做轻',
            description: '用户希望主界面轻量、少入口。',
            evidenceCount: 2,
          }),
        }),
      ],
    })
  })

  it('项目会话内打开记忆面板时传递项目上下文', async () => {
    const memorySource = {
      type: 'memory',
      id: 'memory-1',
      name: '项目偏好',
    }
    mocks.chatStore.activeSessionId = 'session-1'
    mocks.chatStore.sessions = [{
      id: 'session-1',
      title: '项目对话',
      createdAt: '2026-07-04T00:00:00Z',
      updatedAt: '2026-07-04T00:00:00Z',
      projectId: 'project-1',
    }]
    mocks.chatStore.messages = [{
      id: 'assistant-project-memory',
      role: 'assistant',
      content: '我会按项目偏好处理。',
      timestamp: Date.now(),
      memoryChanges: [memorySource],
    }] as any
    mocks.getSession.mockResolvedValueOnce({
      id: 'session-1',
      title: '项目对话',
      createdAt: '2026-07-04T00:00:00Z',
      updatedAt: '2026-07-04T00:00:00Z',
      projectId: 'project-1',
      knowledgeBaseIds: [],
      messageCount: 0,
      totalTokens: 0,
    })

    const wrapper = mountView()
    await flushPromises()

    const messageList = wrapper.findComponent({ name: 'MessageList' })
    expect(messageList.props('projectId')).toBe('project-1')

    messageList.vm.$emit('inspect-memory', memorySource)
    await flushPromises()

    const panel = wrapper.findComponent({ name: 'MemoryInsightPanel' })
    expect(panel.props('source')).toMatchObject({ id: 'memory-1' })
    expect(panel.props('projectId')).toBe('project-1')
  })

  it('忘记最后一条本轮沉淀后同步清理沉淀状态', async () => {
    const memorySource = {
      type: 'memory',
      id: 'memory-1',
      name: '主界面偏好',
    }
    mocks.chatStore.messages = [
      {
        id: 'assistant-memory-last-change',
        role: 'assistant',
        content: '我记住了你的偏好。',
        timestamp: Date.now(),
        memoryChanges: [memorySource],
        memoryChangeStatus: 'settled',
      },
    ] as any

    const wrapper = mountView()
    await flushPromises()

    wrapper.findComponent({ name: 'MessageList' }).vm.$emit('inspect-memory', memorySource)
    await flushPromises()

    wrapper.findComponent({ name: 'MemoryInsightPanel' }).vm.$emit('deleted', 'memory-1')
    await flushPromises()

    expect(mocks.chatStore.updateMessage).toHaveBeenCalledWith('assistant-memory-last-change', {
      memoryChanges: undefined,
      memoryChangeStatus: undefined,
    })
  })
})
