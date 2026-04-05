<script setup lang="ts">
import { computed, type Component } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  BarChart3,
  Bot,
  FlaskConical,
  GitBranch,
  Key,
  MessageSquare,
  MonitorCog,
  BookOpen,
  Settings,
  Wrench,
} from 'lucide-vue-next'
import PageContainer from '@/components/layout/PageContainer.vue'
import SettingsChannelsView from '@/views/SettingsChannelsView.vue'
import SettingsGeneralView from '@/views/SettingsGeneralView.vue'
import SettingsKnowledgeView from '@/views/SettingsKnowledgeView.vue'
import SettingsModelsView from '@/views/SettingsModelsView.vue'
import SettingsPermissionsView from '@/views/SettingsPermissionsView.vue'
import AnalyticsUsageView from '@/views/AnalyticsUsageView.vue'
import AnalyticsAgentsView from '@/views/AnalyticsAgentsView.vue'
import AnalyticsToolsView from '@/views/AnalyticsToolsView.vue'
import EvalView from '@/views/eval/EvalView.vue'
import TraceReplayView from '@/views/TraceReplayView.vue'

const route = useRoute()

const viewMap: Record<string, Component> = {
  '/settings': SettingsGeneralView,
  '/settings/general': SettingsGeneralView,
  '/settings/models': SettingsModelsView,
  '/settings/knowledge': SettingsKnowledgeView,
  '/settings/channels': SettingsChannelsView,
  '/settings/permissions': SettingsPermissionsView,
  '/analytics/usage': AnalyticsUsageView,
  '/analytics/agents': AnalyticsAgentsView,
  '/analytics/tools': AnalyticsToolsView,
  '/eval': EvalView,
  '/traces': TraceReplayView,
}

interface SettingsNavItem {
  label: string
  path: string
  icon: Component
}

interface SettingsNavSection {
  title: string
  items: SettingsNavItem[]
}

const settingsNav: SettingsNavSection[] = [
  {
    title: '偏好设置',
    items: [
      { label: '通用', path: '/settings/general', icon: Settings },
      { label: '模型与路由', path: '/settings/models', icon: MonitorCog },
      { label: '知识与检索', path: '/settings/knowledge', icon: BookOpen },
      { label: '集成渠道', path: '/settings/channels', icon: MessageSquare },
      { label: '授权与执行', path: '/settings/permissions', icon: Key },
    ],
  },
  {
    title: '回顾与分析',
    items: [
      { label: '用量统计', path: '/analytics/usage', icon: BarChart3 },
      { label: '智能体分析', path: '/analytics/agents', icon: Bot },
      { label: '工具统计', path: '/analytics/tools', icon: Wrench },
      { label: '评估测试', path: '/eval', icon: FlaskConical },
      { label: '轨迹回放', path: '/traces', icon: GitBranch },
    ],
  },
]

const activeView = computed(() => viewMap[route.path] ?? SettingsGeneralView)
const currentLabel = computed(() => {
  for (const section of settingsNav) {
    const item = section.items.find(i => i.path === route.path)
    if (item) return item.label
  }
  return '通用'
})

/** 当前路由是否在 viewMap 中（需要内嵌渲染） */
const hasActiveView = computed(() => route.path in viewMap)
</script>

<template>
  <div class="flex h-full">
    <!-- 左侧导航 -->
    <nav class="hidden w-[200px] shrink-0 border-r border-border/40 lg:block">
      <div class="h-full overflow-y-auto py-lg px-sm">
        <div v-for="section in settingsNav" :key="section.title" class="mb-lg">
          <div class="mb-xs px-sm text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
            {{ section.title }}
          </div>
          <div class="space-y-0.5">
            <RouterLink
              v-for="item in section.items"
              :key="item.path"
              :to="item.path"
              class="nav-link w-full"
              :class="{ 'nav-link-active': route.path === item.path }"
            >
              <component :is="item.icon" class="size-4 shrink-0 text-muted-foreground" />
              <span class="truncate text-[13px]">{{ item.label }}</span>
            </RouterLink>
          </div>
        </div>
      </div>
    </nav>

    <!-- 右侧内容 -->
    <div class="flex-1 overflow-y-auto">
      <PageContainer size="wide" class="py-lg">
        <div class="mx-auto max-w-[860px] space-y-lg">
          <h1 class="text-xl font-semibold tracking-tight text-foreground">{{ currentLabel }}</h1>
          <component v-if="hasActiveView" :is="activeView" />
        </div>
      </PageContainer>
    </div>
  </div>
</template>
