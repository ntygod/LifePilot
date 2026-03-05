<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import SettingsPreferencesView from '@/views/SettingsPreferencesView.vue'
import SettingsModelsView from '@/views/SettingsModelsView.vue'
import SettingsShortcutsView from '@/views/SettingsShortcutsView.vue'

// 根据路由参数决定默认 Tab（支持从 Sidebar 直接导航到特定 Tab）
const route = useRoute()
const tabMap: Record<string, string> = {
  '/settings/preferences': 'preferences',
  '/settings/models': 'models',
  '/settings/shortcuts': 'shortcuts',
}
const initialTab = tabMap[route.path] || 'preferences'
const activeTab = ref(initialTab)
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

    <!-- Tabs 导航 + 内容 -->
    <div class="flex-1 overflow-hidden">
      <Tabs v-model="activeTab" default-value="preferences" class="flex flex-col h-full">
        <div class="border-b bg-background">
          <div class="max-w-[1200px] mx-auto px-md md:px-lg">
            <TabsList class="h-10 bg-transparent p-0 gap-4">
              <TabsTrigger
                value="preferences"
                class="relative h-10 rounded-none border-b-2 border-transparent px-0 pb-3 pt-2 font-medium text-muted-foreground data-[state=active]:border-primary data-[state=active]:text-foreground data-[state=active]:shadow-none bg-transparent"
              >
                偏好设置
              </TabsTrigger>
              <TabsTrigger
                value="models"
                class="relative h-10 rounded-none border-b-2 border-transparent px-0 pb-3 pt-2 font-medium text-muted-foreground data-[state=active]:border-primary data-[state=active]:text-foreground data-[state=active]:shadow-none bg-transparent"
              >
                模型配置
              </TabsTrigger>
              <TabsTrigger
                value="shortcuts"
                class="relative h-10 rounded-none border-b-2 border-transparent px-0 pb-3 pt-2 font-medium text-muted-foreground data-[state=active]:border-primary data-[state=active]:text-foreground data-[state=active]:shadow-none bg-transparent"
              >
                快捷键
              </TabsTrigger>
            </TabsList>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto">
          <TabsContent value="preferences" class="mt-0 h-full">
            <SettingsPreferencesView />
          </TabsContent>
          <TabsContent value="models" class="mt-0 h-full">
            <SettingsModelsView />
          </TabsContent>
          <TabsContent value="shortcuts" class="mt-0 h-full">
            <SettingsShortcutsView />
          </TabsContent>
        </div>
      </Tabs>
    </div>
  </div>
</template>
