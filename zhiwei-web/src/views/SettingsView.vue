<script setup lang="ts">
import { computed, type Component } from 'vue'
import { RouterLink } from 'vue-router'
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
        <section class="settings-header">
          <div class="flex flex-col gap-4">
            <div class="space-y-2">
              <div class="surface-label">设置</div>
              <div class="flex flex-wrap items-center gap-3">
                <h1 class="text-2xl font-semibold text-foreground">{{ currentNavigationItem.label }}</h1>
              </div>
            </div>

            <nav class="flex flex-wrap gap-2">
              <RouterLink
                v-for="item in navigationItems"
                :key="item.path"
                :to="item.path"
                class="settings-nav-pill"
                :class="currentPath === item.path ? 'settings-nav-pill-active' : ''"
              >
                <span class="text-sm font-medium">{{ item.label }}</span>
                <span class="text-[11px] text-muted-foreground">{{ item.description }}</span>
              </RouterLink>
            </nav>
          </div>
        </section>

        <div class="space-y-1 px-1">
          <div class="surface-label text-[0.7rem]">当前分组</div>
          <p class="text-sm leading-6 text-muted-foreground">
            {{ currentNavigationItem.description }}
          </p>
        </div>

        <component :is="activeView" />
      </div>
    </PageContainer>
  </div>
</template>

<style scoped>
.settings-header {
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.64);
  padding-bottom: 1rem;
}

.settings-nav-pill {
  display: flex;
  min-width: 10.75rem;
  flex: 1 1 10.75rem;
  flex-direction: column;
  gap: 0.28rem;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  border-radius: calc(var(--radius) + 3px);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.74), hsl(from var(--background) h s l / 0.64));
  padding: 0.72rem 0.82rem;
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.66);
}

.settings-nav-pill-active {
  border-color: hsl(from var(--primary) h s l / 0.2);
  background: linear-gradient(180deg, hsl(from var(--primary) h s l / 0.08), hsl(from var(--card) h s l / 0.74));
}

.settings-nav-pill-active span:first-child {
  color: hsl(from var(--primary) h s l / 0.92);
}
</style>
