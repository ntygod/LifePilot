<script setup lang="ts">
/**
 * 步骤 DAG 依赖可视化组件。
 * 使用 Kahn 拓扑排序分层 + CSS Grid 布局 + SVG 连线。
 */
import { computed, ref, onBeforeUnmount, onMounted, nextTick, watch } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import {
  Wrench,
  Bot,
  GitBranch,
  Shield,
  CheckCircle2,
  Play,
  Clock,
  ShieldAlert,
} from 'lucide-vue-next'

type StepStatus = 'completed' | 'running' | 'waiting' | 'approval'

interface StepLike {
  id: string
  name?: string
  type?: string
  dependsOn?: string[]
  [key: string]: unknown
}

const props = defineProps<{
  steps: unknown[]
  completedStepIds: string[]
  pendingApprovalStepId?: string
}>()

// 容器引用，用于 SVG 连线坐标计算
const containerRef = ref<HTMLElement | null>(null)
const lines = ref<Array<{ x1: number; y1: number; x2: number; y2: number; color: string }>>([])

// 类型安全的步骤列表
const typedSteps = computed<StepLike[]>(() =>
  (props.steps as StepLike[]).map(s => ({
    ...s,
    id: s.id ?? '',
    dependsOn: s.dependsOn ?? []
  }))
)

const completedSet = computed(() => new Set(props.completedStepIds))

/**
 * Kahn 拓扑排序分层算法。
 * 将步骤按 DAG 依赖分为多个层级，无依赖步骤为第 0 层。
 */
function topoLayers(steps: StepLike[]): string[][] {
  const inDegree = new Map<string, number>()
  const adj = new Map<string, string[]>()

  for (const step of steps) {
    inDegree.set(step.id, (step.dependsOn ?? []).length)
    for (const dep of (step.dependsOn ?? [])) {
      if (!adj.has(dep)) adj.set(dep, [])
      adj.get(dep)!.push(step.id)
    }
  }

  const layers: string[][] = []
  let queue = steps.filter(s => (s.dependsOn ?? []).length === 0).map(s => s.id)

  while (queue.length > 0) {
    layers.push([...queue])
    const nextQueue: string[] = []
    for (const id of queue) {
      for (const next of (adj.get(id) ?? [])) {
        inDegree.set(next, inDegree.get(next)! - 1)
        if (inDegree.get(next) === 0) nextQueue.push(next)
      }
    }
    queue = nextQueue
  }

  return layers
}

/**
 * 计算步骤状态。
 */
function computeStepStatus(
  stepId: string,
  dependsOn: string[],
  completedIds: Set<string>,
  pendingApproval?: string
): StepStatus {
  if (completedIds.has(stepId)) return 'completed'
  if (stepId === pendingApproval) return 'approval'
  if (dependsOn.every(dep => completedIds.has(dep))) return 'running'
  return 'waiting'
}

// 分层结果
const layers = computed(() => topoLayers(typedSteps.value))

// 步骤 ID → 步骤对象映射
const stepMap = computed(() => {
  const map = new Map<string, StepLike>()
  for (const s of typedSteps.value) map.set(s.id, s)
  return map
})

// 步骤状态映射
const stepStatuses = computed(() => {
  const map = new Map<string, StepStatus>()
  for (const s of typedSteps.value) {
    map.set(s.id, computeStepStatus(s.id, s.dependsOn ?? [], completedSet.value, props.pendingApprovalStepId))
  }
  return map
})

// 状态颜色配置
const statusStyles: Record<StepStatus, { bg: string; border: string; icon: typeof CheckCircle2 }> = {
  completed: { bg: 'bg-green-50', border: 'border-green-500', icon: CheckCircle2 },
  running:   { bg: 'bg-blue-50',  border: 'border-blue-500',  icon: Play },
  waiting:   { bg: 'bg-muted',    border: 'border-border',    icon: Clock },
  approval:  { bg: 'bg-amber-50', border: 'border-amber-500', icon: ShieldAlert },
}

// 步骤类型图标
function getStepTypeIcon(step: StepLike) {
  const type = step.type ?? ''
  if (type === 'ApprovalStep' || type.includes('Approval')) return Shield
  if (type === 'AgentStep' || type.includes('Agent')) return Bot
  if (type === 'ConditionStep' || type.includes('Condition')) return GitBranch
  return Wrench
}

// 计算 SVG 连线坐标
function updateLines() {
  if (!containerRef.value) return
  const newLines: typeof lines.value = []
  const container = containerRef.value
  const containerRect = container.getBoundingClientRect()

  for (const step of typedSteps.value) {
    for (const dep of (step.dependsOn ?? [])) {
      const fromEl = container.querySelector(`[data-step-id="${dep}"]`) as HTMLElement | null
      const toEl = container.querySelector(`[data-step-id="${step.id}"]`) as HTMLElement | null
      if (!fromEl || !toEl) continue

      const fromRect = fromEl.getBoundingClientRect()
      const toRect = toEl.getBoundingClientRect()

      // 从源节点底部中心 → 目标节点顶部中心
      const x1 = fromRect.left + fromRect.width / 2 - containerRect.left
      const y1 = fromRect.bottom - containerRect.top
      const x2 = toRect.left + toRect.width / 2 - containerRect.left
      const y2 = toRect.top - containerRect.top

      // 连线颜色：如果源步骤已完成则绿色，否则灰色
      const color = completedSet.value.has(dep) ? '#22c55e' : '#d1d5db'
      newLines.push({ x1, y1, x2, y2, color })
    }
  }
  lines.value = newLines
}

function scheduleUpdateLines() {
  void nextTick(updateLines)
}

onMounted(() => {
  scheduleUpdateLines()
  window.addEventListener('resize', scheduleUpdateLines)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleUpdateLines)
})

watch(
  [
    typedSteps,
    () => props.completedStepIds.join(','),
    () => props.pendingApprovalStepId ?? '',
  ],
  () => {
    scheduleUpdateLines()
  },
  { deep: true, flush: 'post' },
)
</script>

<template>
  <div ref="containerRef" class="relative">
    <!-- SVG 连线层 -->
    <svg
      v-if="lines.length > 0"
      class="absolute inset-0 w-full h-full pointer-events-none"
      style="z-index: 0"
    >
      <line
        v-for="(line, i) in lines"
        :key="i"
        :x1="line.x1"
        :y1="line.y1"
        :x2="line.x2"
        :y2="line.y2"
        :stroke="line.color"
        stroke-width="2"
        stroke-dasharray="4 2"
      />
    </svg>

    <!-- 分层网格 -->
    <div class="relative space-y-4" style="z-index: 1">
      <div
        v-for="(layer, layerIdx) in layers"
        :key="layerIdx"
        class="flex items-start justify-center gap-3 flex-wrap"
      >
        <TooltipProvider v-for="stepId in layer" :key="stepId">
          <Tooltip>
            <TooltipTrigger as-child>
              <div
                :data-step-id="stepId"
                class="flex items-center gap-2 px-3 py-2 rounded-lg border-2 bg-background min-w-[120px] max-w-[200px] cursor-default transition-colors"
                :class="[
                  statusStyles[stepStatuses.get(stepId) ?? 'waiting'].bg,
                  statusStyles[stepStatuses.get(stepId) ?? 'waiting'].border,
                ]"
              >
                <!-- 类型图标 -->
                <component
                  :is="getStepTypeIcon(stepMap.get(stepId)!)"
                  class="w-4 h-4 flex-shrink-0"
                  :class="{
                    'text-green-600': stepStatuses.get(stepId) === 'completed',
                    'text-blue-600': stepStatuses.get(stepId) === 'running',
                    'text-amber-600': stepStatuses.get(stepId) === 'approval',
                    'text-muted-foreground': stepStatuses.get(stepId) === 'waiting',
                  }"
                />
                <!-- 步骤名称 -->
                <span class="text-xs font-medium truncate">
                  {{ stepMap.get(stepId)?.name ?? stepId }}
                </span>
                <!-- 状态图标 -->
                <component
                  :is="statusStyles[stepStatuses.get(stepId) ?? 'waiting'].icon"
                  class="w-3.5 h-3.5 flex-shrink-0 ml-auto"
                  :class="{
                    'text-green-600': stepStatuses.get(stepId) === 'completed',
                    'text-blue-600': stepStatuses.get(stepId) === 'running',
                    'text-amber-600': stepStatuses.get(stepId) === 'approval',
                    'text-muted-foreground': stepStatuses.get(stepId) === 'waiting',
                  }"
                />
              </div>
            </TooltipTrigger>
            <TooltipContent>
              <div class="text-xs space-y-1">
                <div>{{ stepMap.get(stepId)?.name ?? stepId }}</div>
                <div class="text-muted-foreground">类型: {{ stepMap.get(stepId)?.type ?? '未知' }}</div>
                <div class="text-muted-foreground">状态: {{ stepStatuses.get(stepId) }}</div>
                <div v-if="(stepMap.get(stepId)?.dependsOn ?? []).length > 0" class="text-muted-foreground">
                  依赖: {{ (stepMap.get(stepId)?.dependsOn ?? []).join(', ') }}
                </div>
              </div>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      </div>
    </div>
  </div>
</template>
