import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    /**
     * 根路径：产品介绍页（登录前）
     */
    {
      path: '/',
      name: 'landing',
      component: () => import('@/views/LandingView.vue')
    },

    /**
     * 对话 / 会话模块
     * - `/conversations`：会话列表页（占位）
     * - `/conversations/:sessionId`：会话详情页（当前复用 ChatView）
     * - `/chat/:sessionId?`：兼容旧链接，重定向到新路由
     */
    {
      path: '/conversations',
      name: 'conversations',
      component: () => import('@/views/ConversationsView.vue')
    },
    {
      path: '/conversations/:sessionId',
      name: 'conversationDetail',
      component: () => import('@/views/ChatView.vue')
    },
    {
      path: '/chat/:sessionId?',
      name: 'chat',
      redirect: (to) => {
        const sessionId = to.params.sessionId as string | undefined
        return sessionId
          ? { name: 'conversationDetail', params: { sessionId } }
          : { name: 'conversations' }
      }
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
     * Agent & 工作流 / 扩展
     */
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
    {
      path: '/workflows',
      name: 'workflows',
      component: () => import('@/views/WorkflowManageView.vue')
    },
    {
      path: '/workflows/:id',
      name: 'workflowDetail',
      component: () => import('@/views/WorkflowDetailView.vue')
    },
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
     * 依赖关系图
     */
    {
      path: '/dependencies',
      name: 'dependencies',
      component: () => import('@/views/DependencyView.vue')
    },

    /**
     * Analytics / 用量
     */
    {
      path: '/analytics/usage',
      name: 'analyticsUsage',
      component: () => import('@/views/AnalyticsUsageView.vue')
    },
    {
      path: '/analytics/agents',
      name: 'analyticsAgents',
      component: () => import('@/views/AnalyticsAgentsView.vue')
    },
    {
      path: '/analytics/tools',
      name: 'analyticsTools',
      component: () => import('@/views/AnalyticsToolsView.vue')
    },

    /**
     * 设置与偏好
     * 使用 Tabs 导航，所有设置子页面通过 Tab 切换，
     * 子路由重定向到主设置页（保留路径以支持 Sidebar 导航和 Tab 映射）。
     */
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/preferences',
      name: 'settingsPreferences',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/models',
      name: 'settingsModels',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/shortcuts',
      name: 'settingsShortcuts',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/settings/reranker',
      name: 'settingsReranker',
      component: () => import('@/views/SettingsView.vue')
    },

    /**
     * Trace / 轨迹
     * 仍保留现有实现
     */
    {
      path: '/traces',
      name: 'traces',
      component: () => import('@/views/TraceReplayView.vue')
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

export default router
