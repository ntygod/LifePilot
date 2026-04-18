<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Loader2,
  CheckCircle2,
  XCircle,
  OctagonX,
  Bot,
  Terminal,
  Sparkles,
  ChevronDown,
  ChevronRight,
  Square,
} from 'lucide-vue-next'
import type { ProcessTask } from '@/stores/processTask'
import { useProcessTaskStore } from '@/stores/processTask'
import type { CliKind } from '@/utils/cliStreamParser'

/**
 * 单个后台任务卡片 — 垂直布局，嵌入右侧面板的"后台任务"tab 内。
 *
 * 点击卡片头部展开/折叠详情（命令、实时输出、停止按钮）。
 * 与横向胶囊版本（ProcessTaskCapsule）区别：
 * - 布局：垂直列表 vs 横向胶囊
 * - 详情：inline 展开 vs Popover 弹出
 * - 适用场景：侧边面板（空间充足、持续可见） vs 贴底浮动
 */

const props = defineProps<{ task: ProcessTask }>()

const store = useProcessTaskStore()
const expanded = ref(false)
const stopping = ref(false)
const logContainer = ref<HTMLDivElement | null>(null)

// 相对时间刷新（仅 RUNNING 时）
const nowTick = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
})
onUnmounted(() => { if (tickTimer) clearInterval(tickTimer) })

const cliKind = computed<CliKind>(() => {
  return props.task.parsedSummary?.cliKind ?? detectKindFromCommand(props.task.command)
})
const cliLabel = computed(() => {
  switch (cliKind.value) {
    case 'claude': return 'Claude Code'
    case 'codex': return 'Codex'
    case 'gemini': return 'Gemini'
    default: return firstToken(props.task.command)
  }
})
const summaryText = computed(() => {
  const action = props.task.parsedSummary?.currentAction
  if (props.task.state === 'FAILED') return '已失败'
  if (props.task.state === 'KILLED') return '已停止'
  if (props.task.state === 'COMPLETED') return '已完成'
  return action ?? '执行中'
})
const elapsedText = computed(() => {
  const endAt = props.task.terminalAt ?? nowTick.value
  const seconds = Math.max(0, Math.floor((endAt - props.task.startTime) / 1000))
  const mm = Math.floor(seconds / 60)
  const ss = seconds % 60
  return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
})
const statusClass = computed(() => {
  switch (props.task.state) {
    case 'RUNNING': return 'text-primary'
    case 'COMPLETED': return 'text-primary'
    case 'FAILED': return 'text-destructive'
    case 'KILLED': return 'text-muted-foreground'
    default: return 'text-muted-foreground'
  }
})
const isRunning = computed(() => props.task.state === 'RUNNING')

const mergedLog = computed(() => {
  const out = props.task.stdoutBuffer
  const err = props.task.stderrBuffer
  if (!out && !err) return '（暂无输出）'
  if (!err) return out
  if (!out) return `[stderr]\n${err}`
  return `${out}\n\n[stderr]\n${err}`
})

async function handleStop(e: MouseEvent) {
  e.stopPropagation()
  if (stopping.value || !isRunning.value) return
  stopping.value = true
  try {
    await store.stopTask(props.task.sessionId)
  } finally {
    stopping.value = false
  }
}

// 展开后自动滚到底部（含后续输出增量）
watch(() => props.task.stdoutBuffer + props.task.stderrBuffer, async () => {
  if (!expanded.value) return
  await nextTick()
  const el = logContainer.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  if (nearBottom) el.scrollTop = el.scrollHeight
})

function detectKindFromCommand(command: string): CliKind {
  const cmd = command.toLowerCase().trim()
  if (cmd.startsWith('claude')) return 'claude'
  if (cmd.startsWith('codex')) return 'codex'
  if (cmd.startsWith('gemini')) return 'gemini'
  return 'unknown'
}
function firstToken(command: string): string {
  const trimmed = command.trim()
  const space = trimmed.indexOf(' ')
  return space > 0 ? trimmed.slice(0, space) : trimmed
}
</script>

<template>
  <div class="rounded-md border border-border bg-card transition hover:border-primary/40">
    <!-- 头部：点击切换展开 -->
    <button
      type="button"
      class="flex w-full items-center gap-sm p-sm text-left"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <!-- 展开指示 -->
      <span class="flex shrink-0 text-muted-foreground">
        <ChevronDown v-if="expanded" class="size-4" />
        <ChevronRight v-else class="size-4" />
      </span>

      <!-- 状态图标 -->
      <span :class="statusClass" class="flex shrink-0">
        <Loader2 v-if="task.state === 'RUNNING'" class="size-4 animate-spin" />
        <CheckCircle2 v-else-if="task.state === 'COMPLETED'" class="size-4" />
        <XCircle v-else-if="task.state === 'FAILED'" class="size-4" />
        <OctagonX v-else class="size-4" />
      </span>

      <!-- CLI 品牌图标 -->
      <span class="flex shrink-0 text-muted-foreground">
        <Bot v-if="cliKind === 'claude'" class="size-4" />
        <Sparkles v-else-if="cliKind === 'codex'" class="size-4" />
        <Sparkles v-else-if="cliKind === 'gemini'" class="size-4" />
        <Terminal v-else class="size-4" />
      </span>

      <!-- 文字区 -->
      <div class="flex min-w-0 flex-1 flex-col">
        <div class="flex items-center gap-xs text-sm">
          <span class="font-medium text-foreground">{{ cliLabel }}</span>
          <span class="text-muted-foreground">·</span>
          <span class="truncate text-muted-foreground">{{ summaryText }}</span>
        </div>
      </div>

      <!-- 耗时 -->
      <span class="shrink-0 font-mono text-xs text-muted-foreground">{{ elapsedText }}</span>
    </button>

    <!-- 详情（inline 展开） -->
    <Transition
      enter-active-class="transition-all duration-200 ease-out"
      enter-from-class="max-h-0 opacity-0"
      enter-to-class="max-h-[30rem] opacity-100"
      leave-active-class="transition-all duration-150 ease-in"
      leave-from-class="max-h-[30rem] opacity-100"
      leave-to-class="max-h-0 opacity-0"
    >
      <div v-if="expanded" class="overflow-hidden border-t border-border">
        <div class="flex flex-col gap-sm p-sm">
          <!-- session + 停止 -->
          <div class="flex items-center justify-between gap-sm">
            <span class="font-mono text-xs text-muted-foreground">
              {{ task.sessionId }}
              <span v-if="task.exitCode != null" class="ml-sm">· exitCode={{ task.exitCode }}</span>
            </span>
            <button
              v-if="isRunning"
              type="button"
              :disabled="stopping"
              class="inline-flex items-center gap-xs rounded-md px-sm py-xs text-xs text-destructive hover:bg-destructive/10 disabled:opacity-50"
              @click="handleStop"
            >
              <Square class="size-3" />
              <span>{{ stopping ? '停止中…' : '停止' }}</span>
            </button>
          </div>

          <!-- 命令 -->
          <code class="block max-h-[4rem] overflow-auto rounded-md bg-muted p-sm text-xs leading-relaxed">
            {{ task.command }}
          </code>

          <!-- 实时输出 -->
          <div class="flex flex-col gap-xs">
            <span class="text-xs font-medium text-muted-foreground">实时输出</span>
            <div
              ref="logContainer"
              class="max-h-[14rem] overflow-auto rounded-md border border-border bg-background p-sm font-mono text-xs leading-relaxed whitespace-pre-wrap break-words"
            >{{ mergedLog }}</div>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>
