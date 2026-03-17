<script setup lang="ts">
/**
 * 步骤 DAG 依赖可视化组件。
 * 使用 flattenNestedSteps 扁平化嵌套步骤 + Kahn 拓扑排序分层 + CSS Grid 布局 + SVG 连线。
 * 支持条件分支、循环、并行等嵌套步骤的完整渲染。
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
  Repeat,
  GitFork,
} from 'lucide-vue-next'
import { flattenNestedSteps, expandCompletedStepIds, type CanvasNode } from '@/composables/useNestedSteps'
import { useDagLayout } from '@/composables/useDagLayout'
import type { StepModel } from '@/composables/useWorkflowModel'

type StepStatus = 'completed' | 'running' | 'waiting' | 'approval'

const props = defineProps<{
  steps: unknown[]
  completedStepIds: string[]
  pendingApprovalStepId?: string
}>()

// 容器引用，用于 SVG 连线坐标计算
const containerRef = ref<HTMLElement | null>(null)
const lines = ref<Array<{ x1: number; y1: number; x2: number; y2: number; color: string }>>([])

const { computeCanvasLayers } = useDagLayout()

// 扁平化所有嵌套步骤为画布节点
const canvasNodes = computed<CanvasNode[]>(() =>
  flattenNestedSteps(props.steps as StepModel[])
)

// canvasId → CanvasNode 映射
const nodeMap = computed(() => {
  const map = new Map<string, CanvasNode>()
  for (const n of canvasNodes.value) map.set(n.canvasId, n)
  return map
})

const completedSet = computed(() => expandCompletedStepIds(canvasNodes.value, props.completedStepIds))

/**
 * 计算步骤状态。
 * 使用原始 stepId 匹配 completedStepIds 和 pendingApprovalStepId。
 */
function computeStepStatus(node: CanvasNode): StepStatus {
  if (completedSet.value.has(node.stepId)) return 'completed'
  if (node.stepId === props.pendingApprovalStepId) return 'approval'
  // 检查所有依赖的原始 stepId 是否已完成
  const allDepsCompleted = node.dependsOn.every(depCanvasId => {
    const depNode = nodeMap.value.get(depCanvasId)
    return depNode ? completedSet.value.has(depNode.stepId) : true
  })
  if (allDepsCompleted) return 'running'
  return 'waiting'
}

// 使用 computeCanvasLayers 进行拓扑分层
const layers = computed(() => computeCanvasLayers(canvasNodes.value))

// 步骤状态映射（canvasId → status）
const stepStatuses = computed(() => {
  const map = new Map<string, StepStatus>()
  for (const n of canvasNodes.value) {
    map.set(n.canvasId, computeStepStatus(n))
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
function getStepTypeIcon(node: CanvasNode) {
  const type = node.type ?? ''
  if (type === 'approval') return Shield
  if (type === 'llm') return Bot
  if (type === 'condition') return GitBranch
  if (type === 'loop') return Repeat
  if (type === 'parallel') return GitFork
  return Wrench
}

// 分支标签
function getBranchLabel(node: CanvasNode): string {
  switch (node.branch) {
    case 'root': return ''
    case 'then': return 'Then'
    case 'else': return 'Else'
    case 'loop': return 'Loop'
    default: return (node.branch as string).replace('branch-', 'Branch ')
  }
}

// 分支标签样式
function getBranchClass(node: CanvasNode): string {
  switch (node.branch) {
    case 'then': return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'else': return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300'
    case 'loop': return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
    default: return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'
  }
}

// 计算 SVG 连线坐标
function updateLines() {
  if (!containerRef.value) return
  const newLines: typeof lines.value = []
  const container = containerRef.value
  const containerRect = container.getBoundingClientRect()

  for (const node of canvasNodes.value) {
    for (const depId of node.dependsOn) {
      const fromEl = container.querySelector(`[data-step-id="${depId}"]`) as HTMLElement | null
      const toEl = container.querySelector(`[data-step-id="${node.canvasId}"]`) as HTMLElement | null
      if (!fromEl || !toEl) continue

      const fromRect = fromEl.getBoundingClientRect()
      const toRect = toEl.getBoundingClientRect()

      const x1 = fromRect.left + fromRect.width / 2 - containerRect.left
      const y1 = fromRect.bottom - containerRect.top
      const x2 = toRect.left + toRect.width / 2 - containerRect.left
      const y2 = toRect.top - containerRect.top

      // 连线颜色：分支连线用分支色，已完成用绿色，否则灰色
      let color = '#d1d5db'
      if (node.branch === 'then' && depId === node.parentStepId) {
        color = '#22c55e' // 绿色 Then
      } else if (node.branch === 'else' && depId === node.parentStepId) {
        color = '#ef4444' // 红色 Else
      } else {
        const depNode = nodeMap.value.get(depId)
        if (depNode && completedSet.value.has(depNode.stepId)) {
          color = '#22c55e'
        }
      }
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
    canvasNodes,
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
        <TooltipProvider v-for="canvasId in layer" :key="canvasId">
          <Tooltip>
            <TooltipTrigger as-child>
              <div
                :data-step-id="canvasId"
                class="flex items-center gap-2 px-3 py-2 rounded-lg border-2 bg-background min-w-[120px] max-w-[200px] cursor-default transition-colors"
                :class="[
                  statusStyles[stepStatuses.get(canvasId) ?? 'waiting'].bg,
                  statusStyles[stepStatuses.get(canvasId) ?? 'waiting'].border,
                ]"
              >
                <!-- 类型图标 -->
                <component
                  :is="getStepTypeIcon(nodeMap.get(canvasId)!)"
                  class="w-4 h-4 flex-shrink-0"
                  :class="{
                    'text-green-600': stepStatuses.get(canvasId) === 'completed',
                    'text-blue-600': stepStatuses.get(canvasId) === 'running',
                    'text-amber-600': stepStatuses.get(canvasId) === 'approval',
                    'text-muted-foreground': stepStatuses.get(canvasId) === 'waiting',
                  }"
                />
                <!-- 分支标签 -->
                <span
                  v-if="getBranchLabel(nodeMap.get(canvasId)!)"
                  class="inline-flex items-center rounded-full px-1 py-0.5 text-[9px] font-medium flex-shrink-0"
                  :class="getBranchClass(nodeMap.get(canvasId)!)"
                >
                  {{ getBranchLabel(nodeMap.get(canvasId)!) }}
                </span>
                <!-- 步骤名称 -->
                <span class="text-xs font-medium truncate">
                  {{ nodeMap.get(canvasId)?.name ?? canvasId }}
                </span>
                <!-- 状态图标 -->
                <component
                  :is="statusStyles[stepStatuses.get(canvasId) ?? 'waiting'].icon"
                  class="w-3.5 h-3.5 flex-shrink-0 ml-auto"
                  :class="{
                    'text-green-600': stepStatuses.get(canvasId) === 'completed',
                    'text-blue-600': stepStatuses.get(canvasId) === 'running',
                    'text-amber-600': stepStatuses.get(canvasId) === 'approval',
                    'text-muted-foreground': stepStatuses.get(canvasId) === 'waiting',
                  }"
                />
              </div>
            </TooltipTrigger>
            <TooltipContent>
              <div class="text-xs space-y-1">
                <div>{{ nodeMap.get(canvasId)?.name ?? canvasId }}</div>
                <div class="text-muted-foreground">类型: {{ nodeMap.get(canvasId)?.type ?? '未知' }}</div>
                <div v-if="getBranchLabel(nodeMap.get(canvasId)!)" class="text-muted-foreground">
                  分支: {{ getBranchLabel(nodeMap.get(canvasId)!) }}
                </div>
                <div class="text-muted-foreground">状态: {{ stepStatuses.get(canvasId) }}</div>
                <div v-if="(nodeMap.get(canvasId)?.dependsOn ?? []).length > 0" class="text-muted-foreground">
                  依赖: {{ (nodeMap.get(canvasId)?.dependsOn ?? []).join(', ') }}
                </div>
              </div>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      </div>
    </div>
  </div>
</template>
