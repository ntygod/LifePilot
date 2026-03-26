<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute } from 'vue-router'
import PageContainer from '@/components/layout/PageContainer.vue'
import SettingsChannelsView from '@/views/SettingsChannelsView.vue'
import SettingsGeneralView from '@/views/SettingsGeneralView.vue'
import SettingsKnowledgeView from '@/views/SettingsKnowledgeView.vue'
import SettingsModelsView from '@/views/SettingsModelsView.vue'
import SettingsPermissionsView from '@/views/SettingsPermissionsView.vue'

const route = useRoute()

const viewMap: Record<string, Component> = {
  '/settings': SettingsGeneralView,
  '/settings/general': SettingsGeneralView,
  '/settings/models': SettingsModelsView,
  '/settings/knowledge': SettingsKnowledgeView,
  '/settings/channels': SettingsChannelsView,
  '/settings/permissions': SettingsPermissionsView,
}

const navigationItems = [
  {
    path: '/settings/general',
    label: '通用',
    description: '主题、密度、字号与本地显示偏好。',
  },
  {
    path: '/settings/models',
    label: '模型与路由',
    description: '模型服务、生成路由、向量路由和精排路由的独立配置。',
  },
  {
    path: '/settings/knowledge',
    label: '知识与检索',
    description: '分块策略、检索参数、向量索引和联网搜索设置。',
  },
  {
    path: '/settings/channels',
    label: '集成渠道',
    description: '飞书、企微、钉钉等渠道的凭据与开关配置。',
  },
  {
    path: '/settings/permissions',
    label: '授权与自动执行',
    description: '管理工具授权、无人值守范围和高风险操作审批记录。',
  },
] as const

const activeView = computed(() => viewMap[route.path] ?? SettingsGeneralView)
const currentPath = computed(() => (
  navigationItems.some(item => item.path === route.path) ? route.path : '/settings/general'
))
const currentNavigationItem = computed(() => (
  navigationItems.find(item => item.path === currentPath.value) ?? navigationItems[0]
))
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="mx-auto max-w-[1080px] space-y-4">
        <div class="space-y-1 px-1">
          <div class="surface-label">设置</div>
          <h1 class="text-2xl font-semibold text-foreground">{{ currentNavigationItem.label }}</h1>
          <p class="text-sm leading-6 text-muted-foreground">
            {{ currentNavigationItem.description }}
          </p>
        </div>

        <component :is="activeView" />
      </div>
    </PageContainer>
  </div>
</template>
