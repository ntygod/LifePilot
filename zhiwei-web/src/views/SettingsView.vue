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

const labelMap: Record<string, string> = {
  '/settings/general': '通用',
  '/settings/models': '模型与路由',
  '/settings/knowledge': '知识与检索',
  '/settings/channels': '集成渠道（高级）',
  '/settings/permissions': '授权与自动执行（高级）',
}

const activeView = computed(() => viewMap[route.path] ?? SettingsGeneralView)
const currentLabel = computed(() => labelMap[route.path] ?? '通用')
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="mx-auto max-w-[1080px] space-y-md">
        <h1 class="text-xl font-semibold tracking-tight text-foreground">{{ currentLabel }}</h1>
        <component :is="activeView" />
      </div>
    </PageContainer>
  </div>
</template>
