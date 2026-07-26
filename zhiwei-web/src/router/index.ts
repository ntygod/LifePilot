import { createRouter, createWebHistory } from 'vue-router'

/** 不需要引导拦截的白名单路由 */
const ONBOARDING_WHITELIST = new Set(['/', '/splash', '/setup', '/home'])

const router = createRouter({
  history: createWebHistory(),
  routes: [
    /**
     * 桌面端启动页（Tauri 环境下等待后端就绪）
     */
    {
      path: '/splash',
      name: 'splash',
      component: () => import('@/views/SplashView.vue')
    },

    /**
     * 桌面端首次启动引导（配置 AI 模型服务）
     */
    {
      path: '/setup',
      name: 'setup',
      component: () => import('@/components/desktop/SetupWizard.vue')
    },

    /**
     * 根路由：直接重定向到对话主入口，不再经过 LandingView（桌面客户端优先）
     */
    {
      path: '/',
      name: 'landing',
      redirect: '/home'
    },

    /**
     * 保留旧版 Landing 页（如需展示产品介绍可手动访问 /landing）
     */
    {
      path: '/landing',
      name: 'landingPage',
      component: () => import('@/views/LandingView.vue')
    },

    /**
     * 首屏入口：创建新会话并进入对话
     */
    {
      path: '/home',
      name: 'home',
      redirect: () => {
        return { name: 'newConversation' }
      },
    },

    /**
     * 对话 / 会话模块
     */
    {
      path: '/conversations',
      name: 'conversations',
      component: () => import('@/views/ConversationsView.vue')
    },
    {
      path: '/conversations/new',
      name: 'newConversation',
      component: () => import('@/views/ChatView.vue')
    },
    {
      path: '/conversations/:sessionId',
      name: 'conversationDetail',
      component: () => import('@/views/ChatView.vue')
    },

    /**
     * 项目工作空间 —— Plan 1 Task 20
     *
     * 承载"清爽款"项目详情页：顶部为项目名 + 资料/设置入口，
     * 主区为"开始新对话"按钮与该项目下的对话列表（列表将在后续 task 接入）。
     */
    {
      path: '/projects/:id',
      name: 'projectDetail',
      component: () => import('@/views/ProjectDetailView.vue')
    },

    /**
     * 定时任务全局管理 —— Plan 2+3 Task A5
     *
     * 侧栏 {@code scheduled-tasks-entry} 一级入口对应的页面，
     * 展示主账户 + 所有项目下的定时任务，带暂停/恢复/删除操作。
     */
    {
      path: '/scheduled-tasks',
      name: 'scheduledTasks',
      component: () => import('@/views/ScheduledTasksView.vue')
    },

    /**
     * 知识库模块
     */
    {
      path: '/knowledge-bases',
      name: 'knowledgeBases',
      component: () => import('@/views/KnowledgeBaseView.vue')
    },
    {
      path: '/knowledge-bases/:id',
      name: 'knowledgeBaseDetail',
      component: () => import('@/views/KnowledgeBaseDetailView.vue')
    },
    {
      path: '/knowledge-bases/:id/documents/:docId',
      name: 'knowledgeBaseDocumentDetail',
      component: () => import('@/views/KnowledgeBaseDocumentView.vue')
    },
    /**
     * 记忆管理
     */
    {
      path: '/memories',
      name: 'memories',
      component: () => import('@/views/memory/MemoryView.vue')
    },

    /**
     * Agent、工作流与扩展
     */
    {
      path: '/capabilities',
      name: 'capabilities',
      component: () => import('@/views/CapabilityCenterView.vue')
    },
    {
      path: '/agents',
      name: 'agents',
      component: () => import('@/views/AgentsView.vue')
    },
    {
      path: '/agents/:id',
      name: 'agentDetail',
      component: () => import('@/views/AgentDetailView.vue')
    },
    /**
     * Workflow 路由已于 Skill v2 fixup（2026-04-24）下架：workflow 引擎本体与 REST 端点保留，
     * LLM 工具 + 用户前端入口同步移除。WorkflowManageView.vue / WorkflowDetailView.vue
     * 组件文件与 stores/workflow.ts 保留以备未来恢复管理面，但不再注册路由。
     */
    {
      path: '/skills',
      name: 'skills',
      component: () => import('@/views/SkillManageView.vue')
    },
    {
      path: '/skills/:id',
      name: 'skillDetail',
      component: () => import('@/views/SkillDetailView.vue')
    },
    {
      path: '/marketplace',
      name: 'marketplace',
      component: () => import('@/views/MarketplaceView.vue')
    },
    {
      path: '/tools',
      name: 'tools',
      component: () => import('@/views/ToolsView.vue')
    },
    {
      path: '/tools/:id',
      name: 'toolDetail',
      component: () => import('@/views/ToolDetailView.vue')
    },
    {
      path: '/mcp-servers',
      name: 'mcpServers',
      component: () => import('@/views/McpServersView.vue')
    },
    {
      path: '/mcp-servers/:id',
      name: 'mcpServerDetail',
      component: () => import('@/views/McpServerDetailView.vue')
    },

    /**
     * Analytics / 用量（挂在 SettingsView 壳子下，保留左侧导航）
     */
    {
      path: '/analytics/usage',
      name: 'analyticsUsage',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/analytics/agents',
      name: 'analyticsAgents',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/analytics/tools',
      name: 'analyticsTools',
      component: () => import('@/views/SettingsView.vue')
    },

    /**
     * 设置与偏好
     * 统一使用设置页壳子承载不同子配置。
     */
    {
      path: '/settings',
      name: 'settings',
      redirect: '/settings/general'
    },
    {
      path: '/settings/general',
      name: 'settingsGeneral',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/models',
      name: 'settingsModels',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/knowledge',
      name: 'settingsKnowledge',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/channels',
      name: 'settingsChannels',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/permissions',
      name: 'settingsPermissions',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/code-execution',
      name: 'settingsCodeExecution',
      component: () => import('@/views/SettingsView.vue')
    },

    /**
     * Trace / 轨迹
     */
    {
      path: '/traces',
      name: 'traces',
      component: () => import('@/views/SettingsView.vue')
    },

    /**
     * 404
     */
    {
      path: '/:pathMatch(.*)*',
      name: 'notFound',
      component: () => import('@/views/NotFoundView.vue')
    }
  ]
})

/**
 * 首次启动引导守卫：未完成引导时强制跳转到 /setup
 */
router.beforeEach((to) => {
  if (ONBOARDING_WHITELIST.has(to.path)) return
  if (localStorage.getItem('zhiwei_onboarding_completed') === 'true') return
  return '/setup'
})

export default router
