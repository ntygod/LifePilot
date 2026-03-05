<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useToolStore } from '@/stores/tool'
import ToolTestDialog from '@/components/tool/ToolTestDialog.vue'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'

const route = useRoute()
const router = useRouter()
const toolStore = useToolStore()

const toolId = computed(() => route.params.id as string)
const tool = computed(() => toolStore.currentTool)
const showTestDialog = ref(false)
const usage = ref<any | null>(null)
const usageLoading = ref(true)

// 面包屑导航
const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '工具', to: { name: 'tools' } },
  { label: tool.value?.displayName || tool.value?.name || '...' }
])

onMounted(async () => {
  await toolStore.fetchToolDetail(toolId.value)
  try {
    usage.value = await toolStore.fetchToolUsage(toolId.value)
  } catch {
    // 使用情况查询失败时不阻塞详情展示
  } finally {
    usageLoading.value = false
  }
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
    <!-- 头部 -->
    <div class="flex-shrink-0 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <!-- 面包屑导航 -->
        <Breadcrumb :items="breadcrumbItems" class="mb-2" />
        <div class="flex items-center justify-between gap-sm">
          <div class="flex items-center gap-3">
            <h2 class="text-2xl font-semibold text-foreground leading-tight">
              {{ tool?.displayName || tool?.name || '加载中...' }}
            </h2>
            <Badge variant="default">默认可用</Badge>
          </div>
          <Button variant="outline" @click="showTestDialog = true">
            测试调用
          </Button>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <!-- Skeleton 加载占位符 -->
      <div v-if="!tool" class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6">
        <Card>
          <CardHeader>
            <Skeleton class="h-6 w-1/4" />
          </CardHeader>
          <CardContent class="space-y-3">
            <Skeleton class="h-4 w-1/3" />
            <Skeleton class="h-4 w-1/2" />
            <Skeleton class="h-4 w-full" />
            <div class="flex gap-4">
              <Skeleton class="h-5 w-20 rounded-full" />
              <Skeleton class="h-5 w-20 rounded-full" />
              <Skeleton class="h-5 w-16" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <Skeleton class="h-6 w-1/5" />
          </CardHeader>
          <CardContent>
            <Skeleton class="h-24 w-full rounded-md" />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <Skeleton class="h-6 w-1/5" />
          </CardHeader>
          <CardContent class="space-y-2">
            <Skeleton class="h-4 w-1/2" />
            <Skeleton class="h-4 w-1/3" />
          </CardContent>
        </Card>
      </div>

      <!-- 详情内容 -->
      <div v-else class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6">
        <!-- 基础信息 -->
        <Card>
          <CardHeader>
            <CardTitle>基础信息</CardTitle>
          </CardHeader>
          <CardContent>
            <div class="space-y-3 text-sm">
              <div>
                <span class="text-muted-foreground">工具 ID：</span>
                <span class="font-mono">{{ tool.id }}</span>
              </div>
              <div>
                <span class="text-muted-foreground">显示名：</span>
                <span>{{ tool.displayName || tool.name }}</span>
              </div>
              <div>
                <span class="text-muted-foreground">描述：</span>
                <p class="mt-1">{{ tool.description || '无描述' }}</p>
              </div>
              <div class="flex items-center gap-4 flex-wrap">
                <div class="flex items-center gap-1.5">
                  <span class="text-muted-foreground">来源：</span>
                  <Badge variant="secondary">
                    {{ sourceLabel[tool.source] ?? tool.source }}
                  </Badge>
                </div>
                <div class="flex items-center gap-1.5">
                  <span class="text-muted-foreground">风险等级：</span>
                  <Badge
                    :variant="riskBadge[tool.riskLevel]?.variant ?? 'outline'"
                  >
                    {{ riskBadge[tool.riskLevel]?.label ?? tool.riskLevel }}
                  </Badge>
                </div>
                <div>
                  <span class="text-muted-foreground">幂等性：</span>
                  <span>{{ tool.idempotent ? '是' : '否' }}</span>
                </div>
              </div>
              <div v-if="tool.exportable !== undefined">
                <span class="text-muted-foreground">可导出：</span>
                <span>{{ tool.exportable ? '是' : '否' }}</span>
              </div>
              <div v-if="tool.tags && tool.tags.length > 0">
                <span class="text-muted-foreground">标签：</span>
                <div class="flex flex-wrap gap-1 mt-1">
                  <Badge
                    v-for="tag in tool.tags"
                    :key="tag"
                    variant="secondary"
                  >
                    {{ tag }}
                  </Badge>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        <!-- 行为与副作用说明 -->
        <Card v-if="tool.sideEffects && tool.sideEffects.length > 0">
          <CardHeader>
            <CardTitle>行为与副作用说明</CardTitle>
          </CardHeader>
          <CardContent>
            <ul class="space-y-2 text-sm">
              <li
                v-for="(effect, index) in tool.sideEffects"
                :key="index"
                class="flex items-start gap-2"
              >
                <span class="text-muted-foreground">•</span>
                <span>{{ effect }}</span>
              </li>
            </ul>
          </CardContent>
        </Card>

        <!-- Schema 信息 -->
        <Card v-if="tool.inputSchema || tool.outputSchema">
          <CardHeader>
            <CardTitle>Schema</CardTitle>
          </CardHeader>
          <CardContent>
            <div class="space-y-4">
              <div v-if="tool.inputSchema">
                <h4 class="text-sm font-medium text-foreground mb-2">输入 Schema</h4>
                <pre class="p-3 rounded-md bg-muted text-xs overflow-x-auto">{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre>
              </div>
              <div v-if="tool.outputSchema">
                <h4 class="text-sm font-medium text-foreground mb-2">输出 Schema</h4>
                <pre class="p-3 rounded-md bg-muted text-xs overflow-x-auto">{{ JSON.stringify(tool.outputSchema, null, 2) }}</pre>
              </div>
            </div>
          </CardContent>
        </Card>

        <!-- 预算配置 -->
        <Card v-if="tool.budget">
          <CardHeader>
            <CardTitle>预算配置</CardTitle>
          </CardHeader>
          <CardContent>
            <div class="space-y-2 text-sm">
              <div v-if="tool.budget.timeoutSeconds">
                <span class="text-muted-foreground">超时时间：</span>
                <span>{{ tool.budget.timeoutSeconds }} 秒</span>
              </div>
              <div v-if="tool.budget.maxRetries">
                <span class="text-muted-foreground">最大重试次数：</span>
                <span>{{ tool.budget.maxRetries }}</span>
              </div>
              <div v-if="tool.budget.maxCostCents">
                <span class="text-muted-foreground">最大成本：</span>
                <span>{{ (tool.budget.maxCostCents / 100).toFixed(2) }} 元</span>
              </div>
            </div>
          </CardContent>
        </Card>

        <!-- 使用情况 -->
        <Card>
          <CardHeader>
            <CardTitle>使用情况</CardTitle>
          </CardHeader>
          <CardContent>
            <!-- Skeleton 加载占位符 -->
            <div v-if="usageLoading" class="space-y-3">
              <Skeleton class="h-4 w-1/2" />
              <div class="grid grid-cols-1 md:grid-cols-2 gap-md">
                <div class="space-y-2">
                  <Skeleton class="h-4 w-1/4" />
                  <Skeleton class="h-9 w-full" />
                  <Skeleton class="h-9 w-full" />
                </div>
                <div class="space-y-2">
                  <Skeleton class="h-4 w-1/4" />
                  <Skeleton class="h-9 w-full" />
                </div>
              </div>
            </div>

            <div v-else-if="usage" class="space-y-3 text-sm">
              <div class="text-muted-foreground">
                被 {{ usage.skillCount ?? 0 }} 个 Skill 和 {{ usage.workflowCount ?? 0 }} 个 Workflow 使用。
              </div>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-md">
                <div>
                  <h4 class="text-sm font-medium text-foreground mb-xs">Skills</h4>
                  <div v-if="usage.usedBySkills && usage.usedBySkills.length > 0" class="space-y-xs">
                    <Button
                      v-for="skill in usage.usedBySkills"
                      :key="skill.id"
                      variant="outline"
                      class="w-full justify-between"
                      @click="router.push(`/skills/${skill.id}`)"
                    >
                      <span class="truncate">{{ skill.name }}</span>
                      <span class="text-xs text-muted-foreground">查看</span>
                    </Button>
                  </div>
                  <div v-else class="text-xs text-muted-foreground">
                    暂无 Skill 使用此 Tool
                  </div>
                </div>
                <div>
                  <h4 class="text-sm font-medium text-foreground mb-xs">Workflows</h4>
                  <div v-if="usage.usedByWorkflows && usage.usedByWorkflows.length > 0" class="space-y-xs">
                    <Button
                      v-for="wf in usage.usedByWorkflows"
                      :key="wf.id"
                      variant="outline"
                      class="w-full justify-between"
                      @click="router.push(`/workflows/${wf.id}`)"
                    >
                      <span class="truncate">{{ wf.name }}</span>
                      <span class="text-xs text-muted-foreground">查看</span>
                    </Button>
                  </div>
                  <div v-else class="text-xs text-muted-foreground">
                    暂无 Workflow 使用此 Tool
                  </div>
                </div>
              </div>
            </div>

            <div v-else class="text-sm text-muted-foreground">
              使用情况查询失败
            </div>
          </CardContent>
        </Card>
      </div>
    </div>

    <!-- 测试对话框 -->
    <ToolTestDialog
      v-if="showTestDialog && tool"
      :tool="tool"
      @close="showTestDialog = false"
    />
  </div>
</template>
