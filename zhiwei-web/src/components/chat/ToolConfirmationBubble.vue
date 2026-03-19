<script setup lang="ts">
/**
 * 工具确认卡片（嵌入在 assistant 消息气泡内部）。
 *
 * 当 GuardrailEngine 判定工具风险等级为 HIGH 或 CRITICAL 时，
 * 通过 SSE 推送确认请求到前端，本组件作为 MessageBubble 内部内容展示。
 *
 * - HIGH 风险：橙色标识，单次确认
 * - CRITICAL 风险：红色标识，需二次确认
 * - 倒计时归零自动视为拒绝
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { CheckCircle, Clock, ShieldCheck, ShieldX, XCircle } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import type { ToolConfirmationRequest } from '@/types'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  request: ToolConfirmationRequest
  resolved: boolean
  resolution?: 'approved' | 'rejected' | 'expired'
}>()

const emit = defineEmits<{
  resolve: [resolution: 'approved' | 'rejected' | 'expired']
}>()

const countdown = ref(60)
const awaitingSecondConfirm = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

const isCritical = computed(() => props.request.riskLevel === 'CRITICAL')
const accentColor = computed(() =>
  isCritical.value ? 'border-l-red-500' : 'border-l-orange-400',
)

const resolutionLabel = computed(() => {
  switch (props.resolution) {
    case 'approved': return '已批准'
    case 'rejected': return '已拒绝'
    case 'expired': return '已超时'
    default: return ''
  }
})
const resolutionIcon = computed(() => {
  switch (props.resolution) {
    case 'approved': return CheckCircle
    case 'rejected':
    case 'expired': return XCircle
    default: return null
  }
})
const resolutionColor = computed(() => {
  switch (props.resolution) {
    case 'approved': return 'text-green-600 dark:text-green-400'
    case 'rejected':
    case 'expired': return 'text-muted-foreground'
    default: return ''
  }
})

function startCountdown() {
  stopCountdown()
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      stopCountdown()
      emit('resolve', 'expired')
    }
  }, 1000)
}
function stopCountdown() {
  if (timer) { clearInterval(timer); timer = null }
}
onMounted(() => { if (!props.resolved) startCountdown() })
onUnmounted(stopCountdown)

async function handleConfirm() {
  if (isCritical.value && !awaitingSecondConfirm.value) {
    awaitingSecondConfirm.value = true
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    await chatApi.respondToolConfirmation(props.request.requestId, true)
    stopCountdown()
    emit('resolve', 'approved')
  } catch (e) {
    if (e && typeof e === 'object' && 'code' in e && (e as { code: number }).code === 404) {
      errorMessage.value = '确认请求已过期'
      setTimeout(() => emit('resolve', 'expired'), 1500)
    } else {
      errorMessage.value = '网络异常，请重试'
    }
  } finally {
    submitting.value = false
  }
}

async function handleReject() {
  submitting.value = true
  errorMessage.value = null
  try {
    await chatApi.respondToolConfirmation(props.request.requestId, false)
    stopCountdown()
    emit('resolve', 'rejected')
  } catch (e) {
    if (e && typeof e === 'object' && 'code' in e && (e as { code: number }).code === 404) {
      errorMessage.value = '确认请求已过期'
      setTimeout(() => emit('resolve', 'expired'), 1500)
    } else {
      errorMessage.value = '网络异常，请重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <!-- 左侧色条 + 紧凑内容，像 Slack/Discord 的内嵌引用风格 -->
  <div
    class="rounded-md border-l-[3px] bg-muted/40 px-3 py-2.5"
    :class="[accentColor, resolved ? 'opacity-70' : '']"
  >
    <!-- 第一行：图标 + 工具名 + 倒计时/结果 -->
    <div class="flex items-center gap-2 text-sm">
      <component
        :is="isCritical ? ShieldX : ShieldCheck"
        :class="['size-4 shrink-0', resolved ? 'text-muted-foreground' : (isCritical ? 'text-red-500' : 'text-orange-500')]"
      />
      <span class="font-medium">{{ request.toolName }}</span>
      <span class="text-[11px] text-muted-foreground">需要确认</span>

      <!-- 已解决：结果标签 -->
      <div v-if="resolved" class="ml-auto flex items-center gap-1 text-xs" :class="resolutionColor">
        <component :is="resolutionIcon" class="size-3" />
        <span>{{ resolutionLabel }}</span>
      </div>
      <!-- 未解决：倒计时 -->
      <div v-else class="ml-auto flex items-center gap-1 text-[11px] text-muted-foreground">
        <Clock class="size-3" />
        <span>{{ countdown }}s</span>
      </div>
    </div>

    <!-- CRITICAL 二次确认提示 -->
    <div
      v-if="awaitingSecondConfirm && !resolved"
      class="mt-2 text-xs text-red-600 dark:text-red-400"
    >
      极高风险操作，请再次点击确认。
    </div>

    <!-- 错误提示 -->
    <div
      v-if="errorMessage"
      class="mt-2 text-xs text-destructive"
    >
      {{ errorMessage }}
    </div>

    <!-- 未解决：操作按钮（紧凑行内） -->
    <div v-if="!resolved" class="mt-2 flex items-center gap-2">
      <Button
        variant="ghost"
        size="sm"
        class="h-6 px-2.5 text-xs text-muted-foreground hover:text-foreground"
        :disabled="submitting"
        @click="handleReject"
      >
        拒绝
      </Button>
      <Button
        :variant="isCritical ? 'destructive' : 'default'"
        size="sm"
        class="h-6 px-2.5 text-xs"
        :disabled="submitting"
        @click="handleConfirm"
      >
        {{ awaitingSecondConfirm ? '再次确认' : '允许执行' }}
      </Button>
    </div>
  </div>
</template>
