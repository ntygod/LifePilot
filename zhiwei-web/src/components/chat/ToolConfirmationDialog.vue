<script setup lang="ts">
/**
 * 工具确认对话框。
 *
 * 当 GuardrailEngine 判定工具风险等级为 HIGH 或 CRITICAL 时，
 * 通过 SSE 推送确认请求到前端，本组件弹出对话框等待用户确认或拒绝。
 *
 * - HIGH 风险：橙色标识，单次确认
 * - CRITICAL 风险：红色标识，需二次确认
 * - 倒计时归零自动关闭（视为拒绝）
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, ShieldAlert, Timer } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import type { ToolConfirmationRequest } from '@/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'

const props = defineProps<{
  /** 当前待处理的确认请求，null 时对话框关闭 */
  request: ToolConfirmationRequest | null
}>()

const emit = defineEmits<{
  /** 确认/拒绝完成后通知父组件清除请求 */
  resolved: []
}>()

// 倒计时（秒）
const countdown = ref(60)
// CRITICAL 二次确认状态
const awaitingSecondConfirm = ref(false)
// 提交中状态
const submitting = ref(false)
// 错误提示
const errorMessage = ref<string | null>(null)

let timer: ReturnType<typeof setInterval> | null = null

const isOpen = computed(() => props.request !== null)
const isCritical = computed(() => props.request?.riskLevel === 'CRITICAL')

const riskColor = computed(() =>
  isCritical.value ? 'text-red-500' : 'text-orange-500',
)
const riskBg = computed(() =>
  isCritical.value ? 'bg-red-500/10 border-red-500/30' : 'bg-orange-500/10 border-orange-500/30',
)
const riskLabel = computed(() =>
  isCritical.value ? '极高风险' : '高风险',
)

function startCountdown() {
  stopCountdown()
  countdown.value = 60
  awaitingSecondConfirm.value = false
  errorMessage.value = null
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      // 超时自动拒绝
      stopCountdown()
      emit('resolved')
    }
  }, 1000)
}

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

watch(isOpen, (open) => {
  if (open) {
    startCountdown()
  } else {
    stopCountdown()
  }
})

onUnmounted(stopCountdown)

async function handleConfirm() {
  if (!props.request) return

  // CRITICAL 风险需要二次确认
  if (isCritical.value && !awaitingSecondConfirm.value) {
    awaitingSecondConfirm.value = true
    return
  }

  submitting.value = true
  errorMessage.value = null
  try {
    await chatApi.respondToolConfirmation(props.request.requestId, true)
    stopCountdown()
    emit('resolved')
  } catch (e) {
    if (e && typeof e === 'object' && 'code' in e && (e as { code: number }).code === 404) {
      errorMessage.value = '确认请求已过期'
      setTimeout(() => emit('resolved'), 1500)
    } else {
      errorMessage.value = '网络异常，请重试'
    }
  } finally {
    submitting.value = false
  }
}

async function handleReject() {
  if (!props.request) return

  submitting.value = true
  errorMessage.value = null
  try {
    await chatApi.respondToolConfirmation(props.request.requestId, false)
    stopCountdown()
    emit('resolved')
  } catch (e) {
    if (e && typeof e === 'object' && 'code' in e && (e as { code: number }).code === 404) {
      errorMessage.value = '确认请求已过期'
      setTimeout(() => emit('resolved'), 1500)
    } else {
      errorMessage.value = '网络异常，请重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AlertDialog :open="isOpen">
    <AlertDialogContent class="max-w-md">
      <AlertDialogHeader>
        <div class="flex items-center gap-2">
          <component
            :is="isCritical ? ShieldAlert : AlertTriangle"
            :class="['size-5', riskColor]"
          />
          <AlertDialogTitle>工具执行确认</AlertDialogTitle>
        </div>
        <AlertDialogDescription class="space-y-3">
          <!-- 工具信息 -->
          <div class="flex items-center gap-2 pt-1">
            <span class="text-sm text-muted-foreground">工具：</span>
            <span class="text-sm font-medium text-foreground">{{ request?.toolName }}</span>
            <Badge
              :class="['text-xs', riskBg, riskColor, 'border']"
              variant="outline"
            >
              {{ riskLabel }}
            </Badge>
          </div>

          <!-- 确认消息 -->
          <div class="rounded-lg border border-border/70 bg-muted/30 px-3 py-2 text-sm text-foreground">
            {{ request?.message || '该工具需要您的确认才能执行。' }}
          </div>

          <!-- CRITICAL 二次确认提示 -->
          <div
            v-if="awaitingSecondConfirm"
            class="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-600 dark:text-red-400"
          >
            此操作风险极高，确认后将不可撤销。请再次点击「确认执行」以继续。
          </div>

          <!-- 错误提示 -->
          <div
            v-if="errorMessage"
            class="rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {{ errorMessage }}
          </div>

          <!-- 倒计时 -->
          <div class="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Timer class="size-3.5" />
            <span>{{ countdown }} 秒后自动拒绝</span>
          </div>
        </AlertDialogDescription>
      </AlertDialogHeader>

      <AlertDialogFooter>
        <Button
          variant="outline"
          :disabled="submitting"
          @click="handleReject"
        >
          拒绝
        </Button>
        <Button
          :variant="isCritical ? 'destructive' : 'default'"
          :disabled="submitting"
          @click="handleConfirm"
        >
          {{ awaitingSecondConfirm ? '确认执行（二次确认）' : '确认执行' }}
        </Button>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
