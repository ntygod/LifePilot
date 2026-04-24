<script setup lang="ts">
/**
 * 浏览器人工接管弹窗 — Agent 挂起（SuspendReason.BrowserTakeover）时向用户提示
 * 去浏览器窗口完成验证码 / 登录 / 人机验证等操作，完成后点"继续"让 Agent 恢复，
 * 或点"取消任务"放弃本轮。
 *
 * 组件不直接发请求，通过 emit 把动作交给 useChat 的 confirmBrowserTakeover /
 * cancelBrowserTakeover 处理，统一维护状态。
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { AlertCircle } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  reason: string
  timeoutSeconds: number
}>()

const emit = defineEmits<{
  (e: 'continue'): void
  (e: 'cancel'): void
}>()

const remaining = ref(props.timeoutSeconds)
let timer: ReturnType<typeof setInterval> | null = null

function startTimer() {
  stopTimer()
  remaining.value = props.timeoutSeconds
  timer = setInterval(() => {
    remaining.value = Math.max(0, remaining.value - 1)
    if (remaining.value === 0) {
      stopTimer()
      emit('cancel')
    }
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(() => {
  if (props.open) startTimer()
})

onUnmounted(() => {
  stopTimer()
})

// 复用弹窗时 open 从 false 切回 true 需要重启倒计时
watch(
  () => props.open,
  (open) => {
    if (open) startTimer()
    else stopTimer()
  },
)

// timeoutSeconds 动态变化时也应刷新
watch(
  () => props.timeoutSeconds,
  () => {
    if (props.open) startTimer()
  },
)

const remainingLabel = computed(() => {
  const m = Math.floor(remaining.value / 60)
  const s = remaining.value % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

function handleDialogUpdate(value: boolean) {
  if (!value) emit('cancel')
}
</script>

<template>
  <Dialog :open="open" @update:open="handleDialogUpdate">
    <DialogContent class="max-w-md p-lg">
      <div class="flex items-center gap-sm">
        <AlertCircle :size="20" class="text-amber-500" />
        <DialogTitle>需要你接管</DialogTitle>
      </div>
      <DialogDescription class="mt-md text-sm">{{ reason }}</DialogDescription>
      <p class="mt-md text-xs text-muted-foreground">
        请在浏览器窗口完成操作，完成后点"继续"让 Agent 恢复任务。
      </p>
      <div class="mt-md flex items-center justify-between">
        <span class="font-mono text-xs text-muted-foreground">剩余 {{ remainingLabel }}</span>
        <div class="flex gap-sm">
          <Button variant="outline" @click="emit('cancel')">取消任务</Button>
          <Button @click="emit('continue')">已完成，继续</Button>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
