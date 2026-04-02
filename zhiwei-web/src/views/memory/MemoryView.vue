<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Brain,
  GitFork,
  MessageSquare,
  FileCode2,
  Heart,
  Search,
  RefreshCw,
  AlertTriangle,
} from 'lucide-vue-next'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { useMemoryStore } from '@/stores/memory'
import type { MemorySearchResult } from '@/types'
import EntityPanel from './EntityPanel.vue'
import RelationPanel from './RelationPanel.vue'
import ConversationPanel from './ConversationPanel.vue'
import TemplatePanel from './TemplatePanel.vue'
import PreferencePanel from './PreferencePanel.vue'
import ForgettingLogPanel from './ForgettingLogPanel.vue'

const MEMORY_SCOPE_LABELS: Record<string, string> = {
  USER_PROFILE: '用户画像',
  USER_FACT: '用户事实',
  AGENT_EXPERIENCE: '执行经验',
  DOMAIN_MEMORY: '领域记忆',
}

const REALITY_TYPE_LABELS: Record<string, string> = {
  REAL: '真实',
  FICTIONAL: '虚构',
  SIMULATED: '模拟',
  UNKNOWN: '未标注',
}

const store = useMemoryStore()
const route = useRoute()
const VALID_TABS = new Set(['entities', 'relations', 'conversations', 'templates', 'preferences', 'forgetting-logs'])

// 搜索状态
const searchQuery = ref('')
const searchResults = ref<MemorySearchResult[]>([])
const searching = ref(false)

onMounted(() => {
  store.loadStats()
})

watch(
  () => route.query.tab,
  (tab) => {
    const nextTab = Array.isArray(tab) ? tab[0] : tab
    if (typeof nextTab === 'string' && VALID_TABS.has(nextTab)) {
      store.activeTab = nextTab
    }
  },
  { immediate: true }
)

watch(
  () => route.query.entityId,
  (entityId) => {
    const nextEntityId = Array.isArray(entityId) ? entityId[0] : entityId
    if (typeof nextEntityId === 'string' && nextEntityId.trim()) {
      store.requestEntityDetail(nextEntityId.trim())
    }
  },
  { immediate: true }
)

async function handleSearch() {
  const q = searchQuery.value.trim()
  if (!q) {
    searchResults.value = []
    return
  }
  searching.value = true
  try {
    searchResults.value = await store.search(q)
  } finally {
    searching.value = false
  }
}

function clearSearch() {
  searchQuery.value = ''
  searchResults.value = []
}

function openSearchResult(item: MemorySearchResult) {
  store.requestEntityDetail(item.entityId)
}

function formatMemoryScope(scope?: string | null) {
  if (!scope) return '未分配'
  return MEMORY_SCOPE_LABELS[scope] || scope
}

function formatRealityType(realityType?: string | null) {
  if (!realityType) return '未标注'
  return REALITY_TYPE_LABELS[realityType] || realityType
}

function formatSpaceId(spaceId?: string | null) {
  if (!spaceId) return '默认空间'
  return spaceId
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <!-- 记忆系统未启用提示 -->
        <div v-if="store.memoryDisabled" class="detail-card px-6 py-8 text-center">
          <AlertTriangle class="mx-auto mb-3 size-10 text-muted-foreground" />
          <h2 class="text-lg font-medium text-foreground">记忆系统未启用</h2>
          <p class="mt-2 text-sm text-muted-foreground">
            当前记忆系统处于关闭状态，请在系统配置中启用记忆功能后再访问此页面。
          </p>
        </div>

        <template v-else>
          <!-- 页面头部 -->
          <PageHeader
            eyebrow="记忆管理"
            title="记忆数据"
            description="浏览、搜索和管理记忆系统中的实体、关系、对话、模板和偏好数据。"
          >
            <template #actions>
              <Button
                variant="outline"
                :disabled="store.consolidating"
                @click="store.triggerConsolidation()"
              >
                <RefreshCw class="mr-2 size-4" :class="{ 'animate-spin': store.consolidating }" />
                {{ store.consolidating ? '巩固中...' : '手动巩固' }}
              </Button>
            </template>

            <template #meta>
              <!-- 统计卡片：加载中展示 Skeleton -->
              <template v-if="store.statsLoading">
                <div v-for="i in 5" :key="i" class="detail-card p-4 space-y-3">
                  <Skeleton class="h-3 w-16" />
                  <Skeleton class="h-7 w-20" />
                </div>
              </template>
              <template v-else-if="store.stats">
                <MetricCard
                  label="实体"
                  :value="store.stats.entityCount"
                  hint="L3 语义记忆中的知识实体总数"
                >
                  <template #icon><Brain class="size-4" /></template>
                </MetricCard>
                <MetricCard
                  label="关系"
                  :value="store.stats.relationCount"
                  hint="实体之间的关联关系总数"
                >
                  <template #icon><GitFork class="size-4" /></template>
                </MetricCard>
                <MetricCard
                  label="对话"
                  :value="store.stats.conversationCount"
                  hint="L2 情景记忆中的对话记录总数"
                >
                  <template #icon><MessageSquare class="size-4" /></template>
                </MetricCard>
                <MetricCard
                  label="模板"
                  :value="store.stats.templateCount"
                  hint="L4 程序记忆中的操作模板总数"
                >
                  <template #icon><FileCode2 class="size-4" /></template>
                </MetricCard>
                <MetricCard
                  label="偏好"
                  :value="store.stats.preferenceCount"
                  hint="L4 程序记忆中的偏好规则总数"
                >
                  <template #icon><Heart class="size-4" /></template>
                </MetricCard>
              </template>
            </template>
          </PageHeader>

          <!-- 全局搜索 -->
          <section class="toolbar-strip">
            <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
              <div class="flex flex-1 items-center gap-3">
                <div class="relative flex-1">
                  <Search class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    v-model="searchQuery"
                    placeholder="跨层搜索记忆数据..."
                    class="pl-9"
                    @keydown.enter="handleSearch"
                  />
                </div>
                <Button @click="handleSearch" :disabled="searching">
                  {{ searching ? '搜索中...' : '搜索' }}
                </Button>
              </div>

              <div class="flex items-center gap-3">
                <div class="toolbar-counter">
                  <div class="surface-label text-[0.68rem]">结果</div>
                  <div class="toolbar-counter-value">{{ searchResults.length }}</div>
                </div>
                <Button v-if="searchResults.length > 0 || searchQuery.trim()" variant="ghost" @click="clearSearch">
                  清除
                </Button>
              </div>
            </div>

            <!-- 搜索结果列表 -->
            <div v-if="searchResults.length > 0" class="mt-4 space-y-2">
              <div class="surface-label mb-2">
                找到 {{ searchResults.length }} 条结果
              </div>
              <div
                v-for="item in searchResults"
                :key="item.entityId"
                class="list-card cursor-pointer px-4 py-3 transition-colors hover:bg-muted/50"
                @click="openSearchResult(item)"
              >
                <div class="flex items-start justify-between gap-4">
                  <div class="min-w-0 flex-1">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-medium text-foreground">{{ item.name }}</span>
                      <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                        {{ item.entityType }}
                      </span>
                    </div>
                    <div class="mt-2 flex flex-wrap items-center gap-1.5">
                      <Badge variant="outline">{{ formatMemoryScope(item.memoryScope) }}</Badge>
                      <Badge variant="secondary">{{ formatRealityType(item.realityType) }}</Badge>
                      <span class="text-xs text-muted-foreground break-all">
                        {{ formatSpaceId(item.spaceId) }}
                      </span>
                    </div>
                    <p v-if="item.description" class="mt-1 text-sm text-muted-foreground line-clamp-2">
                      {{ item.description }}
                    </p>
                  </div>
                  <div class="shrink-0 text-xs text-muted-foreground">
                    相关性 {{ (item.relevanceScore * 100).toFixed(0) }}%
                  </div>
                </div>
              </div>
            </div>
          </section>

          <!-- Tab 导航 -->
          <Tabs v-model="store.activeTab" class="w-full">
            <TabsList class="w-full justify-start">
              <TabsTrigger value="entities">实体</TabsTrigger>
              <TabsTrigger value="relations">关系</TabsTrigger>
              <TabsTrigger value="conversations">对话</TabsTrigger>
              <TabsTrigger value="templates">模板</TabsTrigger>
              <TabsTrigger value="preferences">偏好</TabsTrigger>
              <TabsTrigger value="forgetting-logs">遗忘日志</TabsTrigger>
            </TabsList>

            <TabsContent value="entities">
              <EntityPanel />
            </TabsContent>
            <TabsContent value="relations">
              <RelationPanel />
            </TabsContent>
            <TabsContent value="conversations">
              <ConversationPanel />
            </TabsContent>
            <TabsContent value="templates">
              <TemplatePanel />
            </TabsContent>
            <TabsContent value="preferences">
              <PreferencePanel />
            </TabsContent>
            <TabsContent value="forgetting-logs">
              <ForgettingLogPanel />
            </TabsContent>
          </Tabs>
        </template>
      </div>
    </PageContainer>
  </div>
</template>
