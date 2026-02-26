<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowItem } from '@/types'

const store = useWorkflowStore()
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)

onMounted(() => store.fetchList())

async function selectWorkflow(wf: WorkflowItem) {
  await store.fetchDetail(wf.id)
  activeTab.value = 'detail'
}

function backToList() {
  store.current = null
  store.executions = []
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
  await store.fetchExecutions(id)
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
  <div class="flex flex-col h-full p-6 overflow-y-auto">
    <!-- 错误提示 -->
    <div v-if="store.error" class="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
      {{ store.error }}
    </div>

    <!-- 工作流列表 -->
    <template v-if="!store.current">
      <h2 class="text-xl font-semibold text-foreground mb-6">工作流管理</h2>

      <div v-if="store.loading" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="store.list.length === 0" class="text-sm text-muted-foreground">暂无工作流</div>
      <div v-else class="space-y-3">
        <div
          v-for="wf in store.list"
          :key="wf.id"
          class="border border-border rounded-lg p-4 cursor-pointer hover:border-primary/50 transition-colors"
          @click="selectWorkflow(wf)"
        >
          <div class="flex items-center gap-3">
            <h3 class="font-medium text-foreground">{{ wf.name }}</h3>
            <span
              class="text-xs px-2 py-0.5 rounded-full"
              :class="wf.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
            >{{ wf.enabled ? '已启用' : '已禁用' }}</span>
            <span class="text-xs text-muted-foreground">v{{ wf.version }}</span>
          </div>
          <p class="text-sm text-muted-foreground mt-1">{{ wf.description || '无描述' }}</p>
          <div class="flex items-center gap-2 mt-2">
            <span
              v-for="trigger in wf.triggerTypes"
              :key="trigger"
              class="text-xs px-2 py-0.5 rounded-full bg-accent text-accent-foreground"
            >{{ trigger }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 工作流详情 -->
    <template v-else>
      <div class="flex items-center gap-3 mb-4">
        <button
          class="text-sm text-muted-foreground hover:text-foreground transition-colors"
          @click="backToList"
        >← 返回</button>
        <h2 class="text-xl font-semibold text-foreground">{{ store.current.name }}</h2>
        <span
          class="text-xs px-2 py-0.5 rounded-full"
          :class="store.current.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
        >{{ store.current.enabled ? '已启用' : '已禁用' }}</span>
      </div>

      <!-- 操作按钮 -->
      <div class="flex items-center gap-2 mb-6">
        <button
          class="text-sm px-4 py-1.5 rounded-md border border-input hover:bg-accent transition-colors"
          @click="handleToggle(store.current!.id, store.current!.enabled)"
        >{{ store.current.enabled ? '禁用' : '启用' }}</button>
        <button
          class="text-sm px-4 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          :disabled="!store.current.enabled || triggerLoading"
          @click="handleTrigger(store.current!.id)"
        >{{ triggerLoading ? '触发中...' : '手动触发' }}</button>
      </div>

      <!-- Tab 切换 -->
      <div class="flex gap-1 mb-4 border-b border-border">
        <button
          class="px-4 py-2 text-sm transition-colors"
          :class="activeTab === 'detail' ? 'border-b-2 border-primary text-foreground' : 'text-muted-foreground hover:text-foreground'"
          @click="activeTab = 'detail'"
        >详情</button>
        <button
          class="px-4 py-2 text-sm transition-colors"
          :class="activeTab === 'executions' ? 'border-b-2 border-primary text-foreground' : 'text-muted-foreground hover:text-foreground'"
          @click="showExecutions(store.current!.id)"
        >执行历史</button>
      </div>

      <!-- 详情 Tab -->
      <div v-if="activeTab === 'detail'" class="space-y-4 text-sm">
        <div>
          <span class="text-muted-foreground">描述：</span>
          <p>{{ store.current.description || '无描述' }}</p>
        </div>
        <div>
          <span class="text-muted-foreground">触发器：</span>
          <div class="flex flex-wrap gap-1 mt-1">
            <span
              v-for="trigger in store.current.triggerTypes"
              :key="trigger"
              class="text-xs px-2 py-0.5 rounded-full bg-accent text-accent-foreground"
            >{{ trigger }}</span>
          </div>
        </div>
        <div v-if="store.current.steps.length > 0">
          <span class="text-muted-foreground">步骤（{{ store.current.steps.length }}）：</span>
          <div class="mt-1 space-y-1">
            <div
              v-for="(step, i) in store.current.steps"
              :key="i"
              class="p-2 rounded-md bg-muted text-xs"
            >
              <pre class="whitespace-pre-wrap break-words">{{ JSON.stringify(step, null, 2) }}</pre>
            </div>
          </div>
        </div>
      </div>

      <!-- 执行历史 Tab -->
      <div v-if="activeTab === 'executions'" class="space-y-2">
        <div v-if="store.executions.length === 0" class="text-sm text-muted-foreground">暂无执行记录</div>
        <div
          v-for="exec in store.executions"
          :key="exec.id"
          class="border border-border rounded-md p-3"
        >
          <div class="flex items-center gap-3 text-sm">
            <span
              class="text-xs px-2 py-0.5 rounded-full shrink-0"
              :class="stateLabel[exec.state]?.class ?? 'bg-gray-100 text-gray-800'"
            >{{ stateLabel[exec.state]?.label ?? exec.state }}</span>
            <span class="text-muted-foreground">步骤 {{ exec.currentStepIndex }}</span>
            <span class="text-muted-foreground">{{ formatDuration(exec.startedAt, exec.completedAt) }}</span>
            <span class="text-xs text-muted-foreground ml-auto">{{ new Date(exec.createdAt).toLocaleString() }}</span>
          </div>
          <div v-if="exec.failureReason" class="text-xs text-destructive mt-1">{{ exec.failureReason }}</div>
        </div>
      </div>
    </template>
  </div>
</template>
