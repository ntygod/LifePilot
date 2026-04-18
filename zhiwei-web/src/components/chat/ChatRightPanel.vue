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
  <Tabs v-model="activeTab" class="flex h-full flex-col gap-0">
    <div class="flex items-center justify-between border-b border-border/40 px-sm py-xs">
      <TabsList class="bg-transparent p-0">
        <TabsTrigger
          value="trace"
          :disabled="!hasTrace"
          class="data-[state=active]:bg-muted/60"
        >
          执行轨迹
        </TabsTrigger>
        <TabsTrigger value="tasks" class="data-[state=active]:bg-muted/60">
          <span class="flex items-center gap-xs">
            后台任务
            <Badge
              v-if="runningCount > 0"
              variant="secondary"
              class="h-4 min-w-[1rem] px-xs text-[10px]"
            >
              {{ runningCount }}
            </Badge>
          </span>
        </TabsTrigger>
      </TabsList>
      <button
        type="button"
        class="rounded-md p-xs text-muted-foreground/60 hover:bg-muted/40 hover:text-foreground"
        aria-label="关闭面板"
        @click="emit('close')"
      >
        <X class="size-4" />
      </button>
    </div>

    <TabsContent value="trace" class="m-0 flex-1 overflow-y-auto">
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

    <TabsContent value="tasks" class="m-0 flex-1 overflow-y-auto">
      <ProcessTaskList />
    </TabsContent>
  </Tabs>
</template>
