<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Badge } from '@/components/ui/badge'
import TracePanel from './TracePanel.vue'
import ProcessTaskList from '@/components/process/ProcessTaskList.vue'
import { useProcessTaskStore } from '@/stores/processTask'
import type { ReasoningEvent, ReactStepDto } from '@/types'

/**
 * 聊天视图右侧面板 — 整合"执行轨迹"和"后台任务"两个 tab。
 *
 * 激活逻辑：
 * - 用户点击消息的"查看轨迹" → 默认激活 trace tab
 * - 没有轨迹但有后台任务 → 默认激活 tasks tab
 * - 用户手动切换 tab 后不自动切回
 */

interface TraceData {
  reasoningEvents: ReasoningEvent[]
  reactSteps: ReactStepDto[]
  streaming: boolean
  traceId: string | undefined
}

const props = defineProps<{
  /** 当前选中消息的轨迹数据；无轨迹时传 null */
  traceData: TraceData | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const store = useProcessTaskStore()
const runningCount = computed(() => store.runningCount)
const hasTrace = computed(() => !!props.traceData)

const activeTab = ref<'trace' | 'tasks'>(hasTrace.value ? 'trace' : 'tasks')

// 当轨迹数据到达（用户点"查看轨迹"）时，自动切到 trace tab
watch(hasTrace, (now, prev) => {
  if (now && !prev) {
    activeTab.value = 'trace'
  }
})
</script>

<template>
  <Tabs v-model="activeTab" class="flex h-full flex-col gap-0 overflow-hidden">
    <div class="right-panel-header">
      <TabsList class="bg-transparent p-0">
        <TabsTrigger
          value="trace"
          :disabled="!hasTrace"
          class="right-panel-tab"
        >
          执行轨迹
        </TabsTrigger>
        <TabsTrigger value="tasks" class="right-panel-tab">
          <span class="flex items-center gap-xs">
            后台任务
            <Badge
              v-if="runningCount > 0"
              variant="secondary"
              class="h-4 min-w-[1rem] px-xs text-[10px] font-semibold"
            >
              {{ runningCount }}
            </Badge>
          </span>
        </TabsTrigger>
      </TabsList>
      <button
        type="button"
        class="right-panel-close"
        aria-label="关闭面板"
        @click="emit('close')"
      >
        <X class="size-3.5" />
      </button>
    </div>

    <TabsContent value="trace" class="m-0 flex-1 overflow-y-auto scrollbar-thin">
      <TracePanel
        v-if="traceData"
        :reasoning-events="traceData.reasoningEvents"
        :react-steps="traceData.reactSteps"
        :streaming="traceData.streaming"
        :trace-id="traceData.traceId"
        hide-header
        @close="emit('close')"
      />
    </TabsContent>

    <TabsContent value="tasks" class="m-0 flex-1 overflow-y-auto scrollbar-thin">
      <ProcessTaskList />
    </TabsContent>
  </Tabs>
</template>


<style scoped>
.right-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.3);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.5), transparent);
}

.right-panel-tab {
  font-size: 13px;
  font-weight: 500;
  border-radius: 0.5rem;
  padding: 0.35rem 0.625rem;
  transition: all 160ms ease;
}

.right-panel-tab[data-state="active"] {
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 0.92);
}

.right-panel-tab[data-disabled] {
  opacity: 0.35;
}

.right-panel-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.625rem;
  height: 1.625rem;
  border-radius: 0.5rem;
  color: hsl(from var(--muted-foreground) h s l / 0.5);
  transition: all 140ms ease;
}

.right-panel-close:hover {
  background: hsl(from var(--muted) h s l / 0.5);
  color: var(--foreground);
}
</style>
