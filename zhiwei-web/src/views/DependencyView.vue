<!--
  依赖关系图页面。

  页面加载时调用 dependencyApi.getGraph() 获取依赖关系数据，
  集成 DependencyGraph 组件展示 Agent、Skill、Tool 之间的引用关系。

  @author zsg
  @since 2026-03-18
-->
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { dependencyApi } from '@/api/client'
import type { DependencyNode, DependencyEdge } from '@/types'
import { Skeleton } from '@/components/ui/skeleton'
import EmptyState from '@/components/common/EmptyState.vue'
import DependencyGraph from '@/components/graph/DependencyGraph.vue'

// 状态
const loading = ref(true)
const error = ref<string | null>(null)
const nodes = ref<DependencyNode[]>([])
const edges = ref<DependencyEdge[]>([])

// 加载依赖关系图数据
async function loadGraph() {
  loading.value = true
  error.value = null
  try {
    const resp = await dependencyApi.getGraph()
    nodes.value = resp.nodes
    edges.value = resp.edges
  } catch (e: any) {
    error.value = e.message || '加载依赖关系图失败'
    console.error('Failed to load dependency graph:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => loadGraph())
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden bg-background">
    <!-- 顶部标题 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <h1 class="text-2xl font-semibold text-foreground leading-tight">依赖关系图</h1>
        <p class="mt-1 text-sm text-muted-foreground">
          可视化 Agent、Skill 和 Tool 之间的引用关系
        </p>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-hidden">
      <!-- 加载状态 -->
      <div v-if="loading" class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-md">
        <Skeleton class="h-8 w-48" />
        <Skeleton class="h-[500px] w-full rounded-lg" />
      </div>

      <!-- 错误状态 -->
      <div v-else-if="error" class="py-lg">
        <EmptyState
          title="加载失败"
          :description="error"
          action-label="重试"
          :show-action="true"
          @action="loadGraph"
        />
      </div>

      <!-- 依赖关系图 -->
      <DependencyGraph
        v-else
        :nodes="nodes"
        :edges="edges"
      />
    </div>
  </div>
</template>
