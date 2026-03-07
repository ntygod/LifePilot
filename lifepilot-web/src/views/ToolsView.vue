<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useToolStore } from '@/stores/tool'
import SearchBar from '@/components/common/SearchBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'

const router = useRouter()
const toolStore = useToolStore()

const searchQuery = ref('')
const sourceFilter = ref<string>('all')
const riskFilter = ref<string>('all')

onMounted(() => {
  toolStore.fetchTools()
})

const filteredTools = computed(() => {
  let result = toolStore.tools

  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    result = result.filter(t =>
      t.name.toLowerCase().includes(q) ||
      t.displayName?.toLowerCase().includes(q) ||
      t.description?.toLowerCase().includes(q)
    )
  }

  if (sourceFilter.value && sourceFilter.value !== 'all') {
    result = result.filter(t => t.source === sourceFilter.value)
  }

  if (riskFilter.value && riskFilter.value !== 'all') {
    result = result.filter(t => t.riskLevel === riskFilter.value)
  }

  return result
})

const sourceLabel: Record<string, string> = {
  builtin: 'Java 原生',
  yaml: 'YAML Tool',
  mcp: 'MCP 工具',
}

const riskBadge: Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }> = {
  LOW: { label: '低风险', variant: 'secondary' },
  MEDIUM: { label: '中风险', variant: 'outline' },
  HIGH: { label: '高风险', variant: 'destructive' },
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部操作栏 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <h2 class="text-2xl font-semibold text-foreground mb-md leading-tight">工具管理</h2>

        <!-- 搜索和过滤 -->
        <div class="flex flex-wrap gap-sm">
          <SearchBar
            v-model="searchQuery"
            placeholder="搜索工具名称或描述..."
            class="flex-1 min-w-[220px]"
          />
          <Select v-model="sourceFilter">
            <SelectTrigger class="w-[140px]">
              <SelectValue placeholder="全部来源" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部来源</SelectItem>
              <SelectItem value="builtin">Java 原生</SelectItem>
              <SelectItem value="yaml">YAML Tool</SelectItem>
              <SelectItem value="mcp">MCP 工具</SelectItem>
            </SelectContent>
          </Select>
          <Select v-model="riskFilter">
            <SelectTrigger class="w-[150px]">
              <SelectValue placeholder="全部风险等级" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部风险等级</SelectItem>
              <SelectItem value="LOW">低风险</SelectItem>
              <SelectItem value="MEDIUM">中风险</SelectItem>
              <SelectItem value="HIGH">高风险</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>

    <!-- Tool 列表 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- Skeleton 加载占位符 -->
        <div v-if="toolStore.loading" class="space-y-sm">
          <Card v-for="i in 5" :key="i">
            <CardHeader class="pb-2">
              <div class="flex items-center gap-sm">
                <Skeleton class="h-5 w-1/4" />
                <Skeleton class="h-5 w-16 rounded-full" />
                <Skeleton class="h-5 w-16 rounded-full" />
                <Skeleton class="h-5 w-16 rounded-full" />
              </div>
            </CardHeader>
            <CardContent class="pb-3">
              <Skeleton class="h-4 w-full mb-2" />
              <Skeleton class="h-3 w-1/3" />
            </CardContent>
          </Card>
        </div>

        <!-- 错误状态 -->
        <ErrorState
          v-else-if="toolStore.error && filteredTools.length === 0"
          title="加载失败"
          :description="toolStore.error"
          action-label="重试"
          :show-action="true"
          @action="toolStore.fetchTools()"
        />

        <!-- 空状态 -->
        <EmptyState
          v-else-if="filteredTools.length === 0 && !searchQuery && sourceFilter === 'all' && riskFilter === 'all'"
          icon="🔧"
          title="暂无工具"
          description="工具会在 Skill 注册或 MCP Server 连接后自动出现"
        />

        <!-- 搜索/过滤无结果 -->
        <EmptyState
          v-else-if="filteredTools.length === 0"
          icon="🔍"
          title="未找到匹配的工具"
          description="尝试调整搜索关键词或筛选条件"
        />

        <!-- 工具卡片列表 -->
        <div v-else class="space-y-sm">
          <Card
            v-for="tool in filteredTools"
            :key="tool.id"
            class="cursor-pointer hover:-translate-y-0.5 hover:shadow-md hover:border-primary/50 transition-all duration-200 group"
            @click="router.push(`/tools/${tool.id}`)"
          >
            <CardHeader class="pb-2">
              <div class="flex items-start justify-between gap-sm">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-xs mb-xs flex-wrap">
                    <CardTitle class="text-sm leading-snug">
                      {{ tool.displayName || tool.name }}
                    </CardTitle>
                    <Badge variant="default" class="text-xs">默认可用</Badge>
                    <Badge variant="secondary">
                      {{ sourceLabel[tool.source] ?? tool.source }}
                    </Badge>
                    <Badge
                      :variant="riskBadge[tool.riskLevel]?.variant ?? 'outline'"
                    >
                      {{ riskBadge[tool.riskLevel]?.label ?? tool.riskLevel }}
                    </Badge>
                  </div>
                </div>
              </div>
            </CardHeader>
            <CardContent class="pb-3">
              <p class="text-sm text-muted-foreground mb-xs leading-normal">
                {{ tool.description || '无描述' }}
              </p>
              <div class="text-xs text-muted-foreground">
                ID: {{ tool.id }} | 来源: {{ tool.source }}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  </div>
</template>
