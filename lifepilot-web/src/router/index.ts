import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'chat',
      component: () => import('@/views/ChatView.vue')
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue')
    },
    {
      path: '/knowledge-bases',
      name: 'knowledgeBases',
      component: () => import('@/views/KnowledgeBaseView.vue')
    },
    {
      path: '/skills',
      name: 'skills',
      component: () => import('@/views/SkillManageView.vue')
    },
    {
      path: '/traces',
      name: 'traces',
      component: () => import('@/views/TraceReplayView.vue')
    },
    {
      path: '/workflows',
      name: 'workflows',
      component: () => import('@/views/WorkflowManageView.vue')
    }
  ]
})

export default router
