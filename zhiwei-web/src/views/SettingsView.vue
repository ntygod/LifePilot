<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute } from 'vue-router'
import PageContainer from '@/components/layout/PageContainer.vue'
import SettingsChannelsView from '@/views/SettingsChannelsView.vue'
import SettingsGeneralView from '@/views/SettingsGeneralView.vue'
import SettingsKnowledgeView from '@/views/SettingsKnowledgeView.vue'
import SettingsModelsView from '@/views/SettingsModelsView.vue'
import SettingsPermissionsView from '@/views/SettingsPermissionsView.vue'
import CodeExecutionSettings from '@/views/settings/CodeExecutionSettings.vue'
import AnalyticsUsageView from '@/views/AnalyticsUsageView.vue'
import AnalyticsAgentsView from '@/views/AnalyticsAgentsView.vue'
import AnalyticsToolsView from '@/views/AnalyticsToolsView.vue'
import TraceReplayView from '@/views/TraceReplayView.vue'

const route = useRoute()

const viewMap: Record<string, Component> = {
  '/settings': SettingsGeneralView,
  '/settings/general': SettingsGeneralView,
  '/settings/models': SettingsModelsView,
  '/settings/knowledge': SettingsKnowledgeView,
  '/settings/channels': SettingsChannelsView,
  '/settings/permissions': SettingsPermissionsView,
  '/settings/code-execution': CodeExecutionSettings,
  '/analytics/usage': AnalyticsUsageView,
  '/analytics/agents': AnalyticsAgentsView,
  '/analytics/tools': AnalyticsToolsView,
  '/traces': TraceReplayView,
}

const labelMap: Record<string, string> = {
  '/settings': '通用',
  '/settings/general': '通用',
  '/settings/models': '模型与路由',
  '/settings/knowledge': '知识与检索',
  '/settings/channels': '集成渠道',
  '/settings/permissions': '授权与执行',
  '/settings/code-execution': '代码执行环境',
  '/analytics/usage': '用量统计',
  '/analytics/agents': '智能体分析',
  '/analytics/tools': '工具统计',
  '/traces': '轨迹回放',
}

const activeView = computed(() => viewMap[route.path] ?? SettingsGeneralView)
const currentLabel = computed(() => labelMap[route.path] ?? '通用')
const hasActiveView = computed(() => route.path in viewMap)
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-lg">
      <div class="mx-auto space-y-lg" :class="activeView === SettingsChannelsView ? 'max-w-[1200px]' : 'max-w-[860px]'">
        <h1 class="text-xl font-semibold tracking-tight text-foreground">{{ currentLabel }}</h1>
        <component v-if="hasActiveView" :is="activeView" />
      </div>
    </PageContainer>
  </div>
</template>
