<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkflowStore } from '@/stores/workflow'

const route = useRoute()
const router = useRouter()
const workflowStore = useWorkflowStore()

const workflowId = computed(() => route.params.id as string)
const workflow = computed(() => workflowStore.current)
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)

onMounted(async () => {
  await workflowStore.fetchDetail(workflowId.value)
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
    <!-- 头部 -->
    <div class="flex-shrink-0 p-6 border-b border-border">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-3">
          <button
            class="text-sm text-muted-foreground hover:text-foreground transition-colors"
            @click="router.push('/workflows')"
          >
            ← 返回列表
          </button>
          <h2 class="text-2xl font-semibold text-foreground">
            {{ workflow?.name || '加载中...' }}
          </h2>
          <span
            v-if="workflow"
            class="text-xs px-2 py-0.5 rounded-full"
            :class="workflow.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
          >
            {{ workflow.enabled ? '已启用' : '已禁用' }}
          </span>
        </div>
        <div class="flex gap-2">
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="handleToggle"
          >
            {{ workflow?.enabled ? '禁用' : '启用' }}
          </button>
          <button
            class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            :disabled="!workflow?.enabled || triggerLoading"
            @click="handleTrigger"
          >
            {{ triggerLoading ? '触发中...' : '手动触发' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto p-6">
      <div v-if="!workflow" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else class="max-w-4xl mx-auto space-y-6">
        <!-- Tab 切换 -->
        <div class="flex gap-1 border-b border-border">
          <button
            class="px-4 py-2 text-sm transition-colors"
            :class="activeTab === 'detail' ? 'border-b-2 border-primary text-foreground' : 'text-muted-foreground hover:text-foreground'"
            @click="activeTab = 'detail'"
          >
            详情
          </button>
          <button
            class="px-4 py-2 text-sm transition-colors"
            :class="activeTab === 'executions' ? 'border-b-2 border-primary text-foreground' : 'text-muted-foreground hover:text-foreground'"
            @click="showExecutions"
          >
            执行历史
          </button>
        </div>

        <!-- 详情 Tab -->
        <div v-if="activeTab === 'detail'" class="space-y-6">
          <!-- 基本信息 -->
          <div class="border border-border rounded-lg p-6">
            <h3 class="text-lg font-semibold text-foreground mb-4">基本信息</h3>
            <div class="space-y-2 text-sm">
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
                  <span
                    v-for="trigger in workflow.triggerTypes"
                    :key="trigger"
                    class="text-xs px-2 py-0.5 rounded-full bg-accent text-accent-foreground"
                  >
                    {{ trigger }}
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- 步骤列表 -->
          <div v-if="workflow.steps.length > 0" class="border border-border rounded-lg p-6">
            <h3 class="text-lg font-semibold text-foreground mb-4">步骤列表（{{ workflow.steps.length }}）</h3>
            <div class="space-y-3">
              <div
                v-for="(step, i) in workflow.steps"
                :key="i"
                class="p-4 rounded-md border border-border bg-card"
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
            </div>
          </div>
        </div>

        <!-- 执行历史 Tab -->
        <div v-if="activeTab === 'executions'" class="space-y-3">
          <div v-if="workflowStore.executions.length === 0" class="text-sm text-muted-foreground">暂无执行记录</div>
          <div
            v-for="exec in workflowStore.executions"
            :key="exec.id"
            class="border border-border rounded-lg p-4 hover:border-primary/50 transition-colors"
          >
            <div class="flex items-start justify-between mb-2">
              <div class="flex items-center gap-3">
                <span
                  class="text-xs px-2 py-0.5 rounded-full shrink-0"
                  :class="stateLabel[exec.state]?.class ?? 'bg-gray-100 text-gray-800'"
                >
                  {{ stateLabel[exec.state]?.label ?? exec.state }}
                </span>
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
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
