<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowDetail, WorkflowItem } from '@/types'
import WorkflowForm from '@/components/workflow/WorkflowForm.vue'
import ExecutionDetail from '@/components/workflow/ExecutionDetail.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import Pagination from '@/components/common/Pagination.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Edit2,
  Plus,
  Trash2,
} from 'lucide-vue-next'
import { stateConfig } from '@/constants/workflowState'

const store = useWorkflowStore()
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)

const showForm = ref(false)
const formMode = ref<'create' | 'edit' | 'duplicate'>('create')
const selectedWorkflow = ref<WorkflowDetail | null>(null)

// 删除确认对话框
const showDeleteConfirm = ref(false)

// 执行历史分页
const EXEC_PAGE_SIZE = 10
const execPage = ref(0)

const pagedExecutions = computed(() => {
  const start = execPage.value * EXEC_PAGE_SIZE
  return store.executions.slice(start, start + EXEC_PAGE_SIZE)
})

const execPageCount = computed(() =>
  Math.ceil(store.executions.length / EXEC_PAGE_SIZE)
)

const showExecPagination = computed(() =>
  store.executions.length > EXEC_PAGE_SIZE
)

// 执行详情展开状态
const expandedExecId = ref<string | null>(null)

function toggleExecDetail(execId: string) {
  expandedExecId.value = expandedExecId.value === execId ? null : execId
}

onMounted(() => store.fetchList())

async function selectWorkflow(wf: WorkflowItem) {
  await store.fetchDetail(wf.id)
  activeTab.value = 'detail'
}

function backToList() {
  store.current = null
  store.executions = []
  expandedExecId.value = null
  execPage.value = 0
}

async function handleToggle(id: string, enabled: boolean) {
  if (enabled) {
    await store.disable(id)
  } else {
    await store.enable(id)
  }
}

async function handleTrigger(id: string) {
  triggerLoading.value = true
  await store.trigger(id)
  triggerLoading.value = false
}

async function showExecutions(id: string) {
  activeTab.value = 'executions'
  execPage.value = 0
  expandedExecId.value = null
  await store.fetchExecutions(id)
}

function openCreate() {
  formMode.value = 'create'
  selectedWorkflow.value = null
  showForm.value = true
}

function openEdit() {
  if (!store.current) return
  formMode.value = 'edit'
  selectedWorkflow.value = store.current
  showForm.value = true
}

function openDuplicate() {
  if (!store.current) return
  formMode.value = 'duplicate'
  selectedWorkflow.value = store.current
  showForm.value = true
}

function requestDelete() {
  if (!store.current) return
  showDeleteConfirm.value = true
}

async function confirmDelete() {
  if (!store.current) return
  const wf = store.current
  showDeleteConfirm.value = false
  await store.remove(wf.id)
  backToList()
  await store.fetchList()
}

async function onWorkflowSaved(wf: WorkflowDetail) {
  await store.fetchDetail(wf.id)
  showForm.value = false
}

const stateBadge = Object.fromEntries(
  Object.entries(stateConfig).map(([k, v]) => [k, { label: v.label, variant: v.badgeVariant }])
) as Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }>

function formatDuration(start?: string, end?: string): string {
  if (!start || !end) return '-'
  const ms = new Date(end).getTime() - new Date(start).getTime()
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- 错误提示（详情页内联错误） -->
        <div v-if="store.error && store.current" class="mb-sm p-sm rounded-lg bg-destructive/10 text-destructive text-sm">
          {{ store.error }}
        </div>

        <!-- 工作流列表 -->
        <template v-if="!store.current">
          <div class="flex items-center justify-between mb-md">
            <h2 class="text-2xl font-semibold text-foreground leading-tight">工作流管理</h2>
            <Button @click="openCreate">
              <Plus class="w-4 h-4 mr-1" />
              新建工作流
            </Button>
          </div>

          <!-- Skeleton 加载占位符 -->
          <div v-if="store.loading" class="space-y-sm">
            <Card v-for="i in 4" :key="i">
              <CardHeader class="pb-2">
                <div class="flex items-center gap-sm">
                  <Skeleton class="h-5 w-1/4" />
                  <Skeleton class="h-5 w-16 rounded-full" />
                  <Skeleton class="h-5 w-12" />
                </div>
              </CardHeader>
              <CardContent class="pb-3">
                <Skeleton class="h-4 w-full mb-2" />
                <div class="flex gap-xs">
                  <Skeleton class="h-5 w-16 rounded-full" />
                  <Skeleton class="h-5 w-16 rounded-full" />
                </div>
              </CardContent>
            </Card>
          </div>

          <!-- 错误状态 -->
          <ErrorState
            v-else-if="store.error"
            :description="store.error"
            action-label="重试"
            :show-action="true"
            @action="store.fetchList()"
          />

          <!-- 空状态 -->
          <EmptyState
            v-else-if="store.list.length === 0"
            icon="⚙️"
            title="暂无工作流"
            description="通过 YAML 定义你的第一个自动化流程。你可以先从「手动触发 + 工具步骤」的模板开始。"
            action-label="新建工作流"
            :show-action="true"
            @action="openCreate"
          />

          <!-- 工作流卡片列表 -->
          <div v-else class="space-y-sm">
            <Card
              v-for="wf in store.list"
              :key="wf.id"
              class="cursor-pointer hover:-translate-y-0.5 hover:shadow-md hover:border-primary/50 transition-all duration-200 group"
              @click="selectWorkflow(wf)"
            >
              <CardHeader class="pb-2">
                <div class="flex items-center gap-sm">
                  <CardTitle class="text-sm leading-snug">{{ wf.name }}</CardTitle>
                  <Badge :variant="wf.enabled ? 'default' : 'secondary'">
                    {{ wf.enabled ? '已启用' : '已禁用' }}
                  </Badge>
                  <span class="text-xs text-muted-foreground">v{{ wf.version }}</span>
                </div>
              </CardHeader>
              <CardContent class="pb-3">
                <p class="text-sm text-muted-foreground mb-xs leading-normal">
                  {{ wf.description || '无描述' }}
                </p>
                <div class="flex items-center flex-wrap gap-xs">
                  <Badge
                    v-for="trigger in wf.triggerTypes"
                    :key="trigger"
                    variant="outline"
                  >
                    {{ trigger }}
                  </Badge>
                </div>
              </CardContent>
            </Card>
          </div>
        </template>

        <!-- 工作流详情 -->
        <template v-else>
          <div class="flex items-center gap-sm mb-sm">
            <Button variant="ghost" size="sm" @click="backToList">
              ← 返回
            </Button>
            <h2 class="text-2xl font-semibold text-foreground leading-tight">{{ store.current.name }}</h2>
            <Badge :variant="store.current.enabled ? 'default' : 'secondary'">
              {{ store.current.enabled ? '已启用' : '已禁用' }}
            </Badge>
          </div>

          <!-- 操作按钮 -->
          <div class="flex items-center gap-sm mb-md">
            <Button
              variant="outline"
              size="sm"
              @click="handleToggle(store.current!.id, store.current!.enabled)"
            >
              {{ store.current.enabled ? '禁用' : '启用' }}
            </Button>
            <Button
              size="sm"
              :disabled="!store.current.enabled || triggerLoading"
              @click="handleTrigger(store.current!.id)"
            >
              {{ triggerLoading ? '触发中...' : '手动触发' }}
            </Button>
            <div class="flex-1" />
            <Button variant="outline" size="sm" @click="openEdit">
              <Edit2 class="w-4 h-4 mr-1" />
              编辑
            </Button>
            <Button variant="outline" size="sm" @click="openDuplicate">
              <Copy class="w-4 h-4 mr-1" />
              复制
            </Button>
            <Button variant="outline" size="sm" class="border-destructive/30 text-destructive hover:bg-destructive/10" @click="requestDelete">
              <Trash2 class="w-4 h-4 mr-1" />
              删除
            </Button>
          </div>

          <!-- Tab 切换 -->
          <Tabs :model-value="activeTab" @update:model-value="(v) => { const val = String(v); if (val === 'executions') showExecutions(store.current!.id); else activeTab = val as 'detail' | 'executions'; }">
            <TabsList class="mb-md">
              <TabsTrigger value="detail">详情</TabsTrigger>
              <TabsTrigger value="executions">执行历史</TabsTrigger>
            </TabsList>

            <!-- 详情 Tab -->
            <TabsContent value="detail" class="space-y-sm text-sm">
              <Card>
                <CardContent class="pt-md space-y-sm">
                  <div>
                    <span class="text-muted-foreground">描述：</span>
                    <p class="mt-xs leading-normal">{{ store.current.description || '无描述' }}</p>
                  </div>
                  <div>
                    <span class="text-muted-foreground">触发器：</span>
                    <div class="flex flex-wrap gap-xs mt-xs">
                      <Badge
                        v-for="trigger in store.current.triggerTypes"
                        :key="trigger"
                        variant="outline"
                      >
                        {{ trigger }}
                      </Badge>
                    </div>
                  </div>
                </CardContent>
              </Card>

              <Card v-if="store.current.steps.length > 0">
                <CardHeader class="pb-2">
                  <CardTitle class="text-sm">步骤（{{ store.current.steps.length }}）</CardTitle>
                </CardHeader>
                <CardContent class="space-y-xs">
                  <div
                    v-for="(step, i) in store.current.steps"
                    :key="i"
                    class="p-sm rounded-lg border border-border bg-muted/30"
                  >
                    <div class="flex items-start gap-sm">
                      <div
                        class="flex-shrink-0 w-6 h-6 rounded-full bg-primary text-primary-foreground flex items-center justify-center text-xs font-medium"
                      >
                        {{ i + 1 }}
                      </div>
                      <div class="flex-1">
                        <div class="font-medium text-foreground mb-xs">
                          {{ (step as any).name || (step as any).type || `步骤 ${i + 1}` }}
                        </div>
                        <div v-if="(step as any).description" class="text-sm text-muted-foreground mb-xs">
                          {{ (step as any).description }}
                        </div>
                        <div v-if="(step as any).agentId" class="text-xs text-muted-foreground">
                          Agent: {{ (step as any).agentId }}
                        </div>
                        <div v-if="(step as any).toolId" class="text-xs text-muted-foreground">
                          工具: {{ (step as any).toolId }}
                        </div>
                        <details class="mt-xs">
                          <summary class="text-xs text-muted-foreground cursor-pointer hover:text-foreground">
                            查看完整配置
                          </summary>
                          <pre class="mt-xs p-sm rounded-lg bg-muted text-xs overflow-x-auto">{{ JSON.stringify(step, null, 2) }}</pre>
                        </details>
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            <!-- 执行历史 Tab -->
            <TabsContent value="executions" class="space-y-sm">
              <EmptyState
                v-if="store.executions.length === 0"
                icon="📋"
                title="暂无执行记录"
                description="手动触发或等待定时触发后，执行记录将在此展示。"
              />
              <template v-else>
                <Card
                  v-for="exec in pagedExecutions"
                  :key="exec.id"
                  class="hover:-translate-y-0.5 hover:shadow-md hover:border-primary/50 transition-all duration-200"
                >
                  <!-- 执行记录摘要行（可点击展开） -->
                  <div
                    class="flex items-start justify-between p-md cursor-pointer"
                    @click="toggleExecDetail(exec.id)"
                  >
                    <div class="flex items-center gap-sm flex-1">
                      <component
                        :is="expandedExecId === exec.id ? ChevronDown : ChevronRight"
                        class="w-4 h-4 text-muted-foreground flex-shrink-0"
                      />
                      <Badge
                        :variant="stateBadge[exec.state]?.variant ?? 'secondary'"
                      >
                        {{ stateBadge[exec.state]?.label ?? exec.state }}
                      </Badge>
                      <Badge v-if="exec.state === 'PAUSED'" variant="outline" class="border-amber-400 text-amber-700 text-xs">
                        审批中
                      </Badge>
                      <span class="text-sm text-muted-foreground">
                        步骤: {{ (exec.completedStepIds?.length ?? 0) }} / {{ store.current?.steps.length || '?' }}
                      </span>
                      <span
                        v-if="exec.startedAt && exec.completedAt"
                        class="text-xs text-muted-foreground"
                      >
                        耗时: {{ formatDuration(exec.startedAt, exec.completedAt) }}
                      </span>
                    </div>
                    <span class="text-xs text-muted-foreground shrink-0">
                      {{ new Date(exec.createdAt).toLocaleString() }}
                    </span>
                  </div>

                  <!-- 展开的执行详情 -->
                  <div v-if="expandedExecId === exec.id" class="px-md pb-md">
                    <ExecutionDetail
                      :execution="exec"
                      :total-steps="store.current?.steps.length ?? 0"
                      :steps="store.current?.steps"
                    />
                  </div>
                </Card>

                <!-- 分页 -->
                <Pagination
                  v-if="showExecPagination"
                  :page="execPage"
                  :page-count="execPageCount"
                  size="sm"
                  @change="execPage = $event"
                />
              </template>
            </TabsContent>
          </Tabs>
        </template>
      </div>
    </div>

    <!-- 工作流表单 -->
    <WorkflowForm
      v-if="showForm"
      :workflow="selectedWorkflow"
      :mode="formMode"
      @close="showForm = false"
      @saved="onWorkflowSaved"
    />

    <!-- 删除确认对话框 -->
    <ConfirmDialog
      v-if="showDeleteConfirm"
      :show="showDeleteConfirm"
      title="删除工作流"
      :message="`确认删除工作流「${store.current?.name}」？此操作不可撤销。`"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
      @update:show="showDeleteConfirm = $event"
    />
  </div>
</template>
