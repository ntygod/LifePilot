<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkflowStore } from '@/stores/workflow'
import { workflowApi } from '@/api/client'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import YamlEditor from '@/components/editor/YamlEditor.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

const route = useRoute()
const router = useRouter()
const workflowStore = useWorkflowStore()

const workflowId = computed(() => route.params.id as string)
const workflow = computed(() => workflowStore.current)
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)

// YAML 编辑器状态
const yamlDefinition = ref('')
const yamlLoading = ref(true)

// 面包屑导航
const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '工作流', to: { name: 'workflows' } },
  { label: workflow.value?.name ?? '...' }
])

onMounted(async () => {
  await workflowStore.fetchDetail(workflowId.value)
  // 加载工作流 YAML 定义
  try {
    yamlDefinition.value = await workflowApi.getWorkflowYaml(workflowId.value)
  } catch {
    // YAML 加载失败时保持空内容
  } finally {
    yamlLoading.value = false
  }
})

async function handleToggle() {
  if (!workflow.value) return
  try {
    if (workflow.value.enabled) {
      await workflowStore.disable(workflow.value.id)
    } else {
      await workflowStore.enable(workflow.value.id)
    }
  } catch (e: any) {
    alert(e.message || '操作失败')
  }
}

async function handleTrigger() {
  if (!workflow.value || triggerLoading.value) return
  triggerLoading.value = true
  try {
    await workflowStore.trigger(workflow.value.id)
    await workflowStore.fetchExecutions(workflow.value.id)
    activeTab.value = 'executions'
  } catch (e: any) {
    alert(e.message || '触发失败')
  } finally {
    triggerLoading.value = false
  }
}

async function showExecutions() {
  if (!workflow.value) return
  activeTab.value = 'executions'
  await workflowStore.fetchExecutions(workflow.value.id)
}

const stateBadge: Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }> = {
  PENDING: { label: '等待中', variant: 'secondary' },
  RUNNING: { label: '运行中', variant: 'default' },
  COMPLETED: { label: '已完成', variant: 'outline' },
  FAILED: { label: '失败', variant: 'destructive' },
  CANCELLED: { label: '已取消', variant: 'secondary' },
}

function formatDuration(start?: string, end?: string): string {
  if (!start || !end) return '-'
  const ms = new Date(end).getTime() - new Date(start).getTime()
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部 -->
    <div class="flex-shrink-0 p-6 border-b border-border">
      <!-- 面包屑导航 -->
      <Breadcrumb :items="breadcrumbItems" class="mb-2" />
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-3">
          <h2 class="text-2xl font-semibold text-foreground">
            {{ workflow?.name || '加载中...' }}
          </h2>
          <Badge
            v-if="workflow"
            :variant="workflow.enabled ? 'default' : 'secondary'"
          >
            {{ workflow.enabled ? '已启用' : '已禁用' }}
          </Badge>
        </div>
        <div class="flex gap-2">
          <Button variant="outline" @click="handleToggle">
            {{ workflow?.enabled ? '禁用' : '启用' }}
          </Button>
          <Button
            :disabled="!workflow?.enabled || triggerLoading"
            @click="handleTrigger"
          >
            {{ triggerLoading ? '触发中...' : '手动触发' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto p-6">
      <!-- Skeleton 加载占位符 -->
      <div v-if="!workflow" class="max-w-4xl mx-auto space-y-6">
        <div class="flex gap-2 mb-4">
          <Skeleton class="h-9 w-20 rounded-md" />
          <Skeleton class="h-9 w-24 rounded-md" />
        </div>
        <Card>
          <CardHeader>
            <Skeleton class="h-6 w-1/4" />
          </CardHeader>
          <CardContent class="space-y-3">
            <Skeleton class="h-4 w-full" />
            <Skeleton class="h-4 w-1/3" />
            <div class="flex gap-2">
              <Skeleton class="h-5 w-16 rounded-full" />
              <Skeleton class="h-5 w-16 rounded-full" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <Skeleton class="h-6 w-1/5" />
          </CardHeader>
          <CardContent class="space-y-3">
            <Skeleton class="h-16 w-full rounded-md" />
            <Skeleton class="h-16 w-full rounded-md" />
          </CardContent>
        </Card>
      </div>

      <!-- 详情内容 -->
      <div v-else class="max-w-4xl mx-auto">
        <!-- Tab 切换 -->
        <Tabs :model-value="activeTab" @update:model-value="(v) => { const val = String(v); if (val === 'executions') showExecutions(); else activeTab = val as 'detail' | 'executions'; }">
          <TabsList class="mb-md">
            <TabsTrigger value="detail">详情</TabsTrigger>
            <TabsTrigger value="executions">执行历史</TabsTrigger>
          </TabsList>

          <!-- 详情 Tab -->
          <TabsContent value="detail" class="space-y-6">
            <!-- 基本信息 -->
            <Card>
              <CardHeader>
                <CardTitle>基本信息</CardTitle>
              </CardHeader>
              <CardContent class="space-y-2 text-sm">
                <div>
                  <span class="text-muted-foreground">描述：</span>
                  <p class="mt-1">{{ workflow.description || '无描述' }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">版本：</span>
                  <span>{{ workflow.version }}</span>
                </div>
                <div>
                  <span class="text-muted-foreground">触发器：</span>
                  <div class="flex flex-wrap gap-1 mt-1">
                    <Badge
                      v-for="trigger in workflow.triggerTypes"
                      :key="trigger"
                      variant="outline"
                    >
                      {{ trigger }}
                    </Badge>
                  </div>
                </div>
              </CardContent>
            </Card>

            <!-- 步骤列表 -->
            <Card v-if="workflow.steps.length > 0">
              <CardHeader>
                <CardTitle>步骤列表（{{ workflow.steps.length }}）</CardTitle>
              </CardHeader>
              <CardContent class="space-y-3">
                <div
                  v-for="(step, i) in workflow.steps"
                  :key="i"
                  class="p-4 rounded-md border border-border bg-muted/30"
                >
                  <div class="flex items-start gap-3">
                    <div class="flex-shrink-0 w-8 h-8 rounded-full bg-primary text-primary-foreground flex items-center justify-center text-sm font-medium">
                      {{ i + 1 }}
                    </div>
                    <div class="flex-1">
                      <div class="font-medium text-foreground mb-1">
                        {{ (step as any).name || (step as any).type || `步骤 ${i + 1}` }}
                      </div>
                      <div v-if="(step as any).description" class="text-sm text-muted-foreground mb-2">
                        {{ (step as any).description }}
                      </div>
                      <div class="flex flex-wrap gap-3 text-xs text-muted-foreground">
                        <span v-if="(step as any).agentId">
                          Agent: <span class="font-mono">{{ (step as any).agentId }}</span>
                        </span>
                        <span v-if="(step as any).toolId">
                          工具: <span class="font-mono">{{ (step as any).toolId }}</span>
                        </span>
                        <span v-if="(step as any).condition">
                          条件: <span class="font-mono">{{ (step as any).condition }}</span>
                        </span>
                      </div>
                      <details class="mt-2">
                        <summary class="text-xs text-muted-foreground cursor-pointer hover:text-foreground">
                          查看完整配置
                        </summary>
                        <pre class="mt-2 p-3 rounded-md bg-muted text-xs overflow-x-auto">{{ JSON.stringify(step, null, 2) }}</pre>
                      </details>
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>

            <!-- YAML 定义 -->
            <Card>
              <CardHeader>
                <div class="flex items-center justify-between">
                  <CardTitle>YAML 定义</CardTitle>
                  <Badge variant="outline">v{{ workflow.version }}</Badge>
                </div>
              </CardHeader>
              <CardContent>
                <Skeleton v-if="yamlLoading" class="h-64 w-full" />
                <YamlEditor
                  v-else
                  v-model="yamlDefinition"
                  title="工作流 YAML"
                  :on-save="async (content: string) => {
                    await workflowApi.updateWorkflowYaml(workflowId, content)
                    await workflowStore.fetchDetail(workflowId)
                  }"
                />
              </CardContent>
            </Card>
          </TabsContent>

          <!-- 执行历史 Tab -->
          <TabsContent value="executions" class="space-y-3">
            <EmptyState
              v-if="workflowStore.executions.length === 0"
              icon="📋"
              title="暂无执行记录"
              description="手动触发或等待定时触发后，执行记录将在此展示。"
            />
            <Card
              v-for="exec in workflowStore.executions"
              :key="exec.id"
              class="hover:border-primary/50 hover:shadow-sm transition-all duration-200"
            >
              <CardContent class="pt-md">
                <div class="flex items-start justify-between mb-2">
                  <div class="flex items-center gap-3">
                    <Badge
                      :variant="stateBadge[exec.state]?.variant ?? 'secondary'"
                    >
                      {{ stateBadge[exec.state]?.label ?? exec.state }}
                    </Badge>
                    <span class="text-sm text-muted-foreground">
                      当前步骤: {{ exec.currentStepIndex + 1 }} / {{ workflow.steps.length }}
                    </span>
                  </div>
                  <span class="text-xs text-muted-foreground">{{ new Date(exec.createdAt).toLocaleString() }}</span>
                </div>
                <div class="flex items-center gap-4 text-xs text-muted-foreground">
                  <span v-if="exec.startedAt">
                    开始: {{ new Date(exec.startedAt).toLocaleString() }}
                  </span>
                  <span v-if="exec.completedAt">
                    完成: {{ new Date(exec.completedAt).toLocaleString() }}
                  </span>
                  <span v-if="exec.startedAt && exec.completedAt">
                    耗时: {{ formatDuration(exec.startedAt, exec.completedAt) }}
                  </span>
                </div>
                <div v-if="exec.failureReason" class="mt-2 p-2 rounded-md bg-destructive/10 text-destructive text-sm">
                  <div class="font-medium mb-1">失败原因：</div>
                  <div>{{ exec.failureReason }}</div>
                </div>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </div>
  </div>
</template>
