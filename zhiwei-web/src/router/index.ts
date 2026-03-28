import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    /**
     * 根路由：产品介绍页（登录前）
     */
    {
      path: '/',
      name: 'landing',
      component: () => import('@/views/LandingView.vue')
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
      path: '/conversations/:sessionId',
      name: 'conversationDetail',
      component: () => import('@/views/ChatView.vue')
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
    {
      path: '/datastores',
      name: 'datastores',
      component: () => import('@/views/DatastoreView.vue')
    },
    {
      path: '/datastores/:id',
      name: 'datastoreDetail',
      component: () => import('@/views/DatastoreDetailView.vue')
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

    /**
     * Eval / 评估
     */
    {
      path: '/eval',
      name: 'eval',
      component: () => import('@/views/eval/EvalView.vue')
    },
    {
      path: '/eval/:evalRunId',
      name: 'evalRunDetail',
      component: () => import('@/views/EvalRunDetailView.vue')
    },

    /**
     * Trace / 轨迹
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
