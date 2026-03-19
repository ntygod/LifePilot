<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cpu, Database, Keyboard, Palette, Radio, Sparkles } from 'lucide-vue-next'
import MetricCard from '@/components/common/MetricCard.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import { useSettingsStore } from '@/stores/settings'
import {
  getDensityDisplayLabel,
  getFontSizeDisplayLabel,
  getThemeDisplayLabel,
} from '@/lib/settingsDisplay'
import SettingsModelsView from '@/views/SettingsModelsView.vue'
import SettingsPreferencesView from '@/views/SettingsPreferencesView.vue'
import SettingsRerankerView from '@/views/SettingsRerankerView.vue'
import SettingsKnowledgeView from '@/views/SettingsKnowledgeView.vue'
import SettingsShortcutsView from '@/views/SettingsShortcutsView.vue'
import SettingsChannelsView from '@/views/SettingsChannelsView.vue'

const route = useRoute()
const router = useRouter()
const settingsStore = useSettingsStore()

const viewMap: Record<string, Component> = {
  '/settings': SettingsPreferencesView,
  '/settings/preferences': SettingsPreferencesView,
  '/settings/models': SettingsModelsView,
  '/settings/shortcuts': SettingsShortcutsView,
  '/settings/reranker': SettingsRerankerView,
  '/settings/knowledge': SettingsKnowledgeView,
  '/settings/channels': SettingsChannelsView,
}

const navigationItems = [
  {
    path: '/settings/preferences',
    label: '偏好设置',
    description: '主题、语言、字号和阅读习惯。',
    icon: Palette,
  },
  {
    path: '/settings/models',
    label: '模型服务',
    description: '默认模型、健康检查和提供商管理。',
    icon: Cpu,
  },
  {
    path: '/settings/shortcuts',
    label: '快捷键',
    description: '查阅现有键盘操作和组合键。',
    icon: Keyboard,
  },
  {
    path: '/settings/reranker',
    label: '精排设置',
    description: '全局精排模型、API 配置和记忆精排开关。',
    icon: Sparkles,
  },
  {
    path: '/settings/knowledge',
    label: '知识库',
    description: '分块策略、检索参数和向量索引全局配置。',
    icon: Database,
  },
  {
    path: '/settings/channels',
    label: '消息渠道',
    description: '飞书、企微、钉钉渠道凭证配置。',
    icon: Radio,
  },
] as const

const activeView = computed(() => viewMap[route.path] ?? SettingsPreferencesView)
const currentPath = computed(() => (route.path === '/settings' ? '/settings/preferences' : route.path))
const currentNavigationItem = computed(() => (
  navigationItems.find(item => item.path === currentPath.value) ?? navigationItems[0]
))
const themeDisplayValue = computed(() => getThemeDisplayLabel(settingsStore.theme))
const densityDisplayValue = computed(() => getDensityDisplayLabel(settingsStore.layoutDensity))
const fontSizeDisplayValue = computed(() => getFontSizeDisplayLabel(settingsStore.fontSize))
const quickSummary = computed(() => [
  {
    label: '主题',
    value: themeDisplayValue.value,
    hint: '当前界面的外观与色彩方案。',
  },
  {
    label: '密度',
    value: densityDisplayValue.value,
    hint: '导航与内容区的空间松紧。',
  },
  {
    label: '字号',
    value: fontSizeDisplayValue.value,
    hint: '全局阅读节奏与基础字级。',
  },
  {
    label: '默认模型',
    value: settingsStore.llmProvider || '未设置',
    hint: '没有单独指定时优先使用的提供商。',
  },
])

function isActiveItem(path: string) {
  return currentPath.value === path
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack mx-auto max-w-[1180px]">
        <PageHeader
          eyebrow="设置"
          title="偏好设置"
          description="把常用设置收在一起，优先处理界面、模型和快捷键。"
        >
          <template #meta>
            <MetricCard
              v-for="item in quickSummary"
              :key="item.label"
              :label="item.label"
              :value="item.value"
              :hint="item.hint"
              class="h-full"
            />
          </template>
        </PageHeader>

        <div class="grid gap-6 lg:grid-cols-[260px_minmax(0,1fr)] xl:grid-cols-[280px_minmax(0,1fr)]">
          <aside class="lg:sticky lg:top-6 lg:self-start">
            <section class="detail-card p-5">
              <div class="space-y-1">
                <div class="surface-label">设置分区</div>
                <h2 class="section-title text-foreground">场景设置</h2>
                <p class="text-sm leading-6 text-muted-foreground">
                  选择一个场景来调整设置。
                </p>
              </div>

              <nav class="mt-4 space-y-3">
                <button
                  v-for="item in navigationItems"
                  :key="item.path"
                  type="button"
                  class="list-card w-full px-4 py-3 text-left"
                  :class="isActiveItem(item.path) ? 'border-primary/30 bg-primary/6' : ''"
                  @click="router.push(item.path)"
                >
                  <div class="flex items-start gap-3">
                    <div
                      class="flex size-9 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-background/80 text-muted-foreground"
                      :class="isActiveItem(item.path) ? 'text-primary' : ''"
                    >
                      <component :is="item.icon" class="size-4" />
                    </div>
                    <div class="min-w-0">
                      <div class="text-sm font-medium text-foreground">
                        {{ item.label }}
                      </div>
                      <p class="mt-1 text-sm leading-6 text-muted-foreground">
                        {{ item.description }}
                      </p>
                    </div>
                  </div>
                </button>
              </nav>

              <div class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
                <div class="surface-label mb-2 text-[0.68rem]">当前分区</div>
                <div class="text-sm font-medium text-foreground">
                  {{ currentNavigationItem.label }}
                </div>
                <p class="mt-1 text-sm leading-6 text-muted-foreground">
                  {{ currentNavigationItem.description }}
                </p>
              </div>
            </section>
          </aside>

          <main class="min-w-0">
            <component :is="activeView" />
          </main>
        </div>
      </div>
    </PageContainer>
  </div>
</template>
