<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowDetail, WorkflowItem } from '@/types'
import WorkflowForm from '@/components/workflow/WorkflowForm.vue'
import ExecutionDetail from '@/components/workflow/ExecutionDetail.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import Pagination from '@/components/common/Pagination.vue'
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Edit2,
  Plus,
  Trash2,
  Workflow as WorkflowIcon,
} from 'lucide-vue-next'

const store = useWorkflowStore()
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)

const showForm = ref(false)
const formMode = ref<'create' | 'edit' | 'duplicate'>('create')
const selectedWorkflow = ref<WorkflowDetail | null>(null)

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

async function confirmDelete() {
  if (!store.current) return
  const wf = store.current
  const ok = window.confirm(`确认删除工作流「${wf.name}」？此操作不可撤销。`)
  if (!ok) return

  await store.remove(wf.id)
  backToList()
  await store.fetchList()
}

async function onWorkflowSaved(wf: WorkflowDetail) {
  await store.fetchDetail(wf.id)
  showForm.value = false
}

const stateLabel: Record<string, { label: string; class: string }> = {
  PENDING: { label: '等待中', class: 'bg-gray-100 text-gray-800' },
  RUNNING: { label: '运行中', class: 'bg-blue-100 text-blue-800' },
  COMPLETED: { label: '已完成', class: 'bg-green-100 text-green-800' },
  FAILED: { label: '失败', class: 'bg-red-100 text-red-800' },
  CANCELLED: { label: '已取消', class: 'bg-yellow-100 text-yellow-800' },
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
            <button
              class="rounded-lg px-md py-sm text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md transition-all duration-200 active:scale-[0.98]"
              @click="openCreate"
            >
              <span class="inline-flex items-center gap-sm">
                <Plus class="w-4 h-4" />
                新建工作流
              </span>
            </button>
          </div>

          <!-- 加载状态 -->
          <LoadingSpinner v-if="store.loading" text="加载中..." />

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
            description="通过 YAML 定义你的第一个自动化流程。你可以先从"手动触发 + 工具步骤"的模板开始。"
            action-label="新建工作流"
            :show-action="true"
            @action="openCreate"
          />

          <!-- 工作流列表 -->
          <div v-else class="space-y-sm">
            <div
              v-for="wf in store.list"
              :key="wf.id"
              class="border border-border rounded-lg p-md cursor-pointer hover:border-primary/50 hover:shadow-sm transition-all duration-200 bg-card"
              @click="selectWorkflow(wf)"
            >
              <div class="flex items-center gap-sm">
                <h3 class="font-medium text-foreground text-sm leading-snug">{{ wf.name }}</h3>
                <span
                  class="text-xs px-sm py-xs rounded-full"
                  :class="wf.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
                >
                  {{ wf.enabled ? '已启用' : '已禁用' }}
                </span>
                <span class="text-xs text-muted-foreground">v{{ wf.version }}</span>
              </div>
              <p class="text-sm text-muted-foreground mt-xs leading-normal">
                {{ wf.description || '无描述' }}
              </p>
              <div class="flex items-center flex-wrap gap-xs mt-xs">
                <span
                  v-for="trigger in wf.triggerTypes"
                  :key="trigger"
                  class="text-xs px-sm py-xs rounded-full bg-accent text-accent-foreground"
                >
                  {{ trigger }}
                </span>
              </div>
            </div>
          </div>
        </template>

        <!-- 工作流详情 -->
        <template v-else>
          <div class="flex items-center gap-sm mb-sm">
            <button
              class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              @click="backToList"
            >
              ← 返回
            </button>
            <h2 class="text-2xl font-semibold text-foreground leading-tight">{{ store.current.name }}</h2>
            <span
              class="text-xs px-sm py-xs rounded-full"
              :class="store.current.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
            >
              {{ store.current.enabled ? '已启用' : '已禁用' }}
            </span>
          </div>

          <!-- 操作按钮 -->
          <div class="flex items-center gap-sm mb-md">
            <button
              class="text-sm px-md py-xs rounded-lg border border-input bg-background hover:bg-accent hover:shadow-sm transition-all duration-200 active:scale-[0.98]"
              @click="handleToggle(store.current!.id, store.current!.enabled)"
            >
              {{ store.current.enabled ? '禁用' : '启用' }}
            </button>
            <button
              class="text-sm px-md py-xs rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md transition-all duration-200 active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
              :disabled="!store.current.enabled || triggerLoading"
              @click="handleTrigger(store.current!.id)"
            >
              {{ triggerLoading ? '触发中...' : '手动触发' }}
            </button>
            <div class="flex-1" />
            <button
              class="text-sm px-md py-xs rounded-lg border border-input bg-background hover:bg-accent hover:shadow-sm transition-all duration-200 active:scale-[0.98]"
              @click="openEdit"
            >
              <span class="inline-flex items-center gap-sm">
                <Edit2 class="w-4 h-4" />
                编辑
              </span>
            </button>
            <button
              class="text-sm px-md py-xs rounded-lg border border-input bg-background hover:bg-accent hover:shadow-sm transition-all duration-200 active:scale-[0.98]"
              @click="openDuplicate"
            >
              <span class="inline-flex items-center gap-sm">
                <Copy class="w-4 h-4" />
                复制
              </span>
            </button>
            <button
              class="text-sm px-md py-xs rounded-lg border border-destructive/30 bg-background text-destructive hover:bg-destructive/10 hover:shadow-sm transition-all duration-200 active:scale-[0.98]"
              @click="confirmDelete"
            >
              <span class="inline-flex items-center gap-sm">
                <Trash2 class="w-4 h-4" />
                删除
              </span>
            </button>
          </div>

          <!-- Tab 切换 -->
          <div class="flex gap-xs mb-md border-b border-border">
            <button
              class="px-md py-sm text-sm transition-colors"
              :class="activeTab === 'detail' ? 'border-b-2 border-primary text-foreground' : 'text-muted-foreground hover:text-foreground'"
              @click="activeTab = 'detail'"
            >
              详情
            </button>
            <button
              class="px-md py-sm text-sm transition-colors"
              :class="activeTab === 'executions' ? 'border-b-2 border-primary text-foreground' : 'text-muted-foreground hover:text-foreground'"
              @click="showExecutions(store.current!.id)"
            >
              执行历史
            </button>
          </div>

          <!-- 详情 Tab -->
          <div v-if="activeTab === 'detail'" class="space-y-sm text-sm">
            <div>
              <span class="text-muted-foreground">描述：</span>
              <p class="mt-xs leading-normal">{{ store.current.description || '无描述' }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">触发器：</span>
              <div class="flex flex-wrap gap-xs mt-xs">
                <span
                  v-for="trigger in store.current.triggerTypes"
                  :key="trigger"
                  class="text-xs px-sm py-xs rounded-full bg-accent text-accent-foreground"
                >
                  {{ trigger }}
                </span>
              </div>
            </div>
            <div v-if="store.current.steps.length > 0">
              <span class="text-muted-foreground">步骤（{{ store.current.steps.length }}）：</span>
              <div class="mt-sm space-y-xs">
                <div
                  v-for="(step, i) in store.current.steps"
                  :key="i"
                  class="p-sm rounded-lg border border-border bg-card"
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
              </div>
            </div>
          </div>

          <!-- 执行历史 Tab -->
          <div v-if="activeTab === 'executions'" class="space-y-sm">
            <EmptyState
              v-if="store.executions.length === 0"
              icon="📋"
              title="暂无执行记录"
              description="手动触发或等待定时触发后，执行记录将在此展示。"
            />
            <template v-else>
              <div
                v-for="exec in pagedExecutions"
                :key="exec.id"
                class="border border-border rounded-lg bg-card hover:border-primary/50 hover:shadow-sm transition-all duration-200"
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
                    <span
                      class="text-xs px-sm py-xs rounded-full shrink-0"
                      :class="stateLabel[exec.state]?.class ?? 'bg-gray-100 text-gray-800'"
                    >
                      {{ stateLabel[exec.state]?.label ?? exec.state }}
                    </span>
                    <span class="text-sm text-muted-foreground">
                      步骤: {{ exec.currentStepIndex + 1 }} / {{ store.current?.steps.length || '?' }}
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
                  />
                </div>
              </div>

              <!-- 分页 -->
              <Pagination
                v-if="showExecPagination"
                :page="execPage"
                :page-count="execPageCount"
                size="sm"
                @change="execPage = $event"
              />
            </template>
          </div>
        </template>
      </div>
    </div>

    <WorkflowForm
      v-if="showForm"
      :workflow="selectedWorkflow"
      :mode="formMode"
      @close="showForm = false"
      @saved="onWorkflowSaved"
    />
  </div>
</template>
