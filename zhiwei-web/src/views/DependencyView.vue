<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { Network } from 'lucide-vue-next'
import { dependencyApi } from '@/api/client'
import type { DependencyEdge, DependencyNode } from '@/types'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

const DependencyGraph = defineAsyncComponent(() => import('@/components/graph/DependencyGraph.vue'))

const loading = ref(true)
const error = ref<string | null>(null)
const nodes = ref<DependencyNode[]>([])
const edges = ref<DependencyEdge[]>([])

const agentCount = computed(() => nodes.value.filter(node => node.type === 'AGENT').length)
const skillCount = computed(() => nodes.value.filter(node => node.type === 'SKILL').length)
const toolCount = computed(() => nodes.value.filter(node => node.type === 'TOOL').length)
const enabledCount = computed(() => nodes.value.filter(node => node.enabled).length)

const summaryItems = computed(() => [
  { label: '智能体', value: agentCount.value, description: '图中已出现的智能体节点。' },
  { label: '技能', value: skillCount.value, description: '和其他对象存在引用关系的技能。' },
  { label: '工具', value: toolCount.value, description: '当前关联到的工具节点。' },
  { label: '已启用节点', value: enabledCount.value, description: '当前处于启用状态的节点。' },
])

async function loadGraph() {
  loading.value = true
  error.value = null

  try {
    const response = await dependencyApi.getGraph()
    nodes.value = response.nodes
    edges.value = response.edges
  } catch (event: any) {
    error.value = event?.message || '加载依赖图失败。'
    console.error('Failed to load dependency graph:', event)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadGraph()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <header class="space-y-5 border-b border-border/70 pb-6">
          <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
            <div class="space-y-3">
              <div class="surface-label">依赖图</div>
              <div class="space-y-2">
                <h1 class="text-3xl font-semibold tracking-tight text-foreground">
                  关系地图
                </h1>
                <p class="max-w-[52rem] text-sm leading-7 text-muted-foreground">
                  查看智能体、技能和工具之间的引用关系。
                </p>
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-3">
              <Button variant="outline" @click="loadGraph">
                刷新依赖图
              </Button>
            </div>
          </div>

          <section v-if="loading" class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
          </section>

          <section v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <article
              v-for="item in summaryItems"
              :key="item.label"
              class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3"
            >
              <div class="text-sm font-medium text-foreground">{{ item.label }}</div>
              <div class="text-2xl font-semibold tracking-tight text-foreground">{{ item.value }}</div>
              <p class="text-sm text-muted-foreground">{{ item.description }}</p>
            </article>
          </section>
        </header>

        <StatePanel
          v-if="error"
          title="依赖图暂时不可用"
          :description="error"
          tone="danger"
        >
          <template #icon>
            <Network class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" @click="loadGraph">
              重试
            </Button>
          </template>
        </StatePanel>

        <StatePanel
          v-else-if="!loading && nodes.length === 0"
          title="暂时没有依赖数据"
          description="新增引用关系后，可查看它们之间的连接。"
        >
          <template #icon>
            <Network class="size-5" />
          </template>
        </StatePanel>

        <template v-else-if="loading">
          <div class="detail-card overflow-hidden">
            <div class="border-b border-border/70 px-5 py-4">
              <div class="space-y-2">
                <Skeleton class="h-5 w-36" />
                <Skeleton class="h-4 w-80 max-w-full" />
              </div>
            </div>
            <div class="p-5">
              <Skeleton class="h-[720px] w-full rounded-[calc(var(--radius)+6px)]" />
            </div>
          </div>
        </template>

        <section v-else class="detail-card overflow-hidden">
          <div class="flex flex-col gap-3 border-b border-border/70 px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
            <div class="space-y-1">
              <h2 class="section-title text-foreground">
                依赖关系图
              </h2>
              <p class="text-sm text-muted-foreground">
                直接在图上查看密集区域、定位节点和追踪引用关系。
              </p>
            </div>

            <div class="flex flex-wrap items-center gap-4 text-sm text-muted-foreground">
              <span>节点 {{ nodes.length }}</span>
              <span>连线 {{ edges.length }}</span>
            </div>
          </div>

          <div class="h-[760px] overflow-hidden">
            <DependencyGraph :nodes="nodes" :edges="edges" />
          </div>
        </section>
      </div>
    </PageContainer>
  </div>
</template>
