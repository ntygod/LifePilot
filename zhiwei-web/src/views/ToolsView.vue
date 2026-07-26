<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowUpRight, FileText, SlidersHorizontal, Wrench } from 'lucide-vue-next'
import { useToolStore } from '@/stores/tool'
import SearchBar from '@/components/common/SearchBar.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'

const router = useRouter()
const route = useRoute()
const toolStore = useToolStore()

function routeQueryText(key: string) {
  const value = route.query[key]
  const text = Array.isArray(value) ? value[0] : value
  return typeof text === 'string' ? text.trim() : ''
}

function routeSearchQuery() {
  return routeQueryText('query') || routeQueryText('q')
}

const searchQuery = ref(routeSearchQuery())
const sourceFilter = ref('all')
const riskFilter = ref('all')
const showFilters = ref(false)

const sourceLabel: Record<string, string> = {
  builtin: 'Java 原生',
  yaml: 'YAML 工具',
  mcp: 'MCP 工具',
}

const riskTone: Record<string, string> = {
  LOW: 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
  MEDIUM: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  HIGH: 'border-red-200/80 bg-red-50/80 text-red-700 dark:border-red-500/20 dark:bg-red-500/10 dark:text-red-200',
}

const filteredTools = computed(() => {
  let result = toolStore.tools

  if (searchQuery.value.trim()) {
    const keyword = searchQuery.value.trim().toLowerCase()
    result = result.filter(tool =>
      tool.name.toLowerCase().includes(keyword)
      || tool.displayName?.toLowerCase().includes(keyword)
      || tool.description?.toLowerCase().includes(keyword),
    )
  }

  if (sourceFilter.value !== 'all') {
    result = result.filter(tool => tool.source === sourceFilter.value)
  }

  if (riskFilter.value !== 'all') {
    result = result.filter(tool => tool.riskLevel === riskFilter.value)
  }

  return result
})

const mcpCount = computed(() => toolStore.tools.filter(tool => tool.source === 'mcp').length)
const highRiskCount = computed(() => toolStore.tools.filter(tool => tool.riskLevel === 'HIGH').length)
const hasFilters = computed(() => Boolean(searchQuery.value.trim()) || sourceFilter.value !== 'all' || riskFilter.value !== 'all')
const sourceFilterLabel = computed(() => {
  if (sourceFilter.value === 'all') return '全部来源'
  return sourceLabel[sourceFilter.value] ?? sourceFilter.value
})
const riskFilterLabel = computed(() => {
  if (riskFilter.value === 'all') return '全部风险'
  if (riskFilter.value === 'LOW') return '低风险'
  if (riskFilter.value === 'MEDIUM') return '中风险'
  return '高风险'
})

function openTool(id: string) {
  void router.push(`/tools/${id}`)
}

function clearFilters() {
  searchQuery.value = ''
  sourceFilter.value = 'all'
  riskFilter.value = 'all'
}

onMounted(() => {
  void toolStore.fetchTools()
})

watch(
  () => [route.query.query, route.query.q],
  () => {
    const next = routeSearchQuery()
    if (next !== searchQuery.value) {
      searchQuery.value = next
    }
  },
)
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="工具"
          title="工具目录"
          :description="`${mcpCount} 个外部工具，共 ${toolStore.tools.length} 个`"
        />

        <section class="toolbar-strip">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div class="flex flex-1 items-center gap-3">
              <SearchBar
                v-model="searchQuery"
                placeholder="按工具名、显示名或描述搜索"
                class="flex-1"
              />
              <Button variant="outline" size="sm" @click="showFilters = !showFilters">
                <SlidersHorizontal class="size-4" />
                筛选
              </Button>
            </div>

            <div class="flex items-center gap-3 text-sm text-muted-foreground">
              <span>结果 {{ filteredTools.length }}</span>
              <Button v-if="hasFilters" type="button" variant="ghost" @click="clearFilters">
                清空筛选
              </Button>
            </div>
          </div>

          <div v-if="showFilters" class="mt-sm rounded-xl border border-border/40 bg-card/60 p-md">
            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:flex">
              <Select v-model="sourceFilter">
                <SelectTrigger class="w-full lg:w-[180px]">
                  <SelectValue placeholder="来源" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全部来源</SelectItem>
                  <SelectItem value="builtin">Java 原生</SelectItem>
                  <SelectItem value="yaml">YAML 工具</SelectItem>
                  <SelectItem value="mcp">MCP 工具</SelectItem>
                </SelectContent>
              </Select>
              <Select v-model="riskFilter">
                <SelectTrigger class="w-full lg:w-[180px]">
                  <SelectValue placeholder="风险" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全部风险</SelectItem>
                  <SelectItem value="LOW">低风险</SelectItem>
                  <SelectItem value="MEDIUM">中风险</SelectItem>
                  <SelectItem value="HIGH">高风险</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        </section>

        <section class="space-y-4">
          <div class="flex items-center justify-between gap-3">
            <div>
              <h2 class="text-lg font-semibold text-foreground">可用工具</h2>
              <p class="text-sm text-muted-foreground">
                点击卡片查看工具详情。
              </p>
            </div>
            <div class="text-sm text-muted-foreground">{{ filteredTools.length }} 条结果</div>
          </div>

          <div v-if="toolStore.loading" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <div
              v-for="index in 6"
              :key="index"
              class="list-card p-4"
            >
              <div class="space-y-4">
                <div class="flex items-center justify-between gap-2">
                  <div class="space-y-2">
                    <Skeleton class="h-5 w-32" />
                    <div class="flex items-center gap-2">
                      <Skeleton class="h-5 w-16 rounded-full" />
                      <Skeleton class="h-5 w-16 rounded-full" />
                    </div>
                  </div>
                  <Skeleton class="h-10 w-10 rounded-2xl" />
                </div>
                <div class="space-y-2">
                  <Skeleton class="h-5 w-32" />
                  <Skeleton class="h-4 w-full" />
                  <Skeleton class="h-4 w-2/3" />
                </div>
                <div class="grid gap-3 sm:grid-cols-2">
                  <Skeleton class="h-16 rounded-[calc(var(--radius)+4px)]" />
                  <Skeleton class="h-16 rounded-[calc(var(--radius)+4px)]" />
                </div>
              </div>
            </div>
          </div>

          <StatePanel
            v-else-if="toolStore.error && toolStore.tools.length === 0"
            title="工具目录暂时不可用"
            :description="toolStore.error"
            tone="danger"
          >
            <template #actions>
              <Button type="button" variant="outline" @click="toolStore.fetchTools()">
                重试
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="toolStore.tools.length === 0"
            title="暂无已注册工具"
            description="导入或接入工具后，会统一显示在列表中。"
          />

          <StatePanel
            v-else-if="filteredTools.length === 0"
            title="没有匹配当前筛选条件的工具"
            description="可以放宽来源或风险筛选，或者清空搜索关键词后重试。"
          >
            <template #actions>
              <Button type="button" variant="outline" @click="clearFilters">
                清空筛选
              </Button>
            </template>
          </StatePanel>

          <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <button
              v-for="tool in filteredTools"
              :key="tool.id"
              type="button"
              class="list-card group flex w-full flex-col gap-4 p-4 text-left"
              @click="openTool(tool.id)"
            >
              <div class="flex items-start justify-between gap-4">
                <div class="min-w-0 flex-1 space-y-3">
                  <div class="flex flex-wrap items-center gap-2">
                    <h3 class="text-base font-semibold text-foreground">
                      {{ tool.displayName || tool.name }}
                    </h3>
                    <Badge variant="outline">
                      {{ sourceLabel[tool.source] ?? tool.source }}
                    </Badge>
                    <Badge variant="outline" :class="riskTone[tool.riskLevel] ?? ''">
                      {{ tool.riskLevel }}
                    </Badge>
                  </div>

                  <div class="flex flex-wrap gap-2 text-xs">
                    <span class="surface-chip font-mono">{{ tool.id }}</span>
                    <span class="surface-chip">{{ tool.type }}</span>
                  </div>

                  <p class="line-clamp-3 text-sm leading-6 text-muted-foreground">
                    {{ tool.description || '这个工具暂时还没有说明。' }}
                  </p>
                </div>

                <div class="flex size-11 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-background/78 text-primary transition-transform duration-200 group-hover:-translate-y-0.5">
                  <Wrench class="size-5" />
                </div>
              </div>

              <div class="grid gap-3 sm:grid-cols-2">
                <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                  <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
                    <FileText class="size-4 text-primary" />
                    来源与类型
                  </div>
                  <p class="text-sm text-muted-foreground">
                    {{ sourceLabel[tool.source] ?? tool.source }} · {{ tool.type }}
                  </p>
                </div>

                <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                  <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
                    <AlertTriangle class="size-4 text-primary" />
                    执行特性
                  </div>
                  <p class="text-sm text-muted-foreground">
                    {{ tool.idempotent ? '可安全重复执行' : '可能修改数据' }}
                  </p>
                </div>
              </div>

              <div class="flex items-center justify-between gap-3 text-sm">
                <span class="text-muted-foreground">点击查看详情和配置</span>
                <span class="inline-flex items-center gap-1 font-medium text-primary transition-colors group-hover:text-primary/80">
                  查看详情
                  <ArrowUpRight class="size-4" />
                </span>
              </div>
            </button>
          </div>
        </section>
      </div>
    </PageContainer>
  </div>
</template>
