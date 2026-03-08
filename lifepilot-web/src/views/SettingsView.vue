<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute } from 'vue-router'
import SettingsPreferencesView from '@/views/SettingsPreferencesView.vue'
import SettingsModelsView from '@/views/SettingsModelsView.vue'
import SettingsShortcutsView from '@/views/SettingsShortcutsView.vue'

const route = useRoute()

// 路由路径 → 子组件映射
const viewMap: Record<string, Component> = {
  '/settings': SettingsPreferencesView,
  '/settings/preferences': SettingsPreferencesView,
  '/settings/models': SettingsModelsView,
  '/settings/shortcuts': SettingsShortcutsView,
}

const activeView = computed(() => viewMap[route.path] ?? SettingsPreferencesView)
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 页面标题 -->
    <div class="sticky top-0 z-10 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <h2 class="text-2xl font-semibold text-foreground leading-tight">设置</h2>
        <p class="text-sm text-muted-foreground mt-1">管理您的应用偏好和系统配置</p>
      </div>
    </div>

    <!-- 直接渲染子组件，无 Tabs -->
    <div class="flex-1 overflow-y-auto">
      <component :is="activeView" />
    </div>
  </div>
</template>
