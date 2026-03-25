<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { CheckCircle, Clock, ShieldCheck, ShieldX, XCircle } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import type { PermissionApprovalRequest } from '@/types'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  request: PermissionApprovalRequest
  resolved: boolean
  resolution?: 'approved' | 'rejected' | 'expired'
}>()

const emit = defineEmits<{
  resolve: [resolution: 'approved' | 'rejected' | 'expired']
}>()

const countdown = ref(60)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const selectedSubjectType = ref<string>('')

let timer: ReturnType<typeof setInterval> | null = null

const isCritical = computed(() => props.request.riskLevel === 'CRITICAL')
const accentColor = computed(() =>
  isCritical.value ? 'border-l-red-500' : 'border-l-orange-400',
)
const subjectTypeOptions = computed(() => props.request.availableSubjectTypes ?? [])
const currentSubjectType = computed(() =>
  selectedSubjectType.value || props.request.recommendedSubjectType || subjectTypeOptions.value[0] || 'SESSION',
)
const subjectTypeLabelMap: Record<string, string> = {
  SESSION: '本会话',
  WORKSPACE: '当前工作区',
  TASK: '当前任务',
  USER: '当前账号',
}

const resolutionLabel = computed(() => {
  switch (props.resolution) {
    case 'approved':
      return '已允许'
    case 'rejected':
      return '已拒绝'
    case 'expired':
      return '已超时'
    default:
      return ''
  }
})

const resolutionIcon = computed(() => {
  switch (props.resolution) {
    case 'approved':
      return CheckCircle
    case 'rejected':
    case 'expired':
      return XCircle
    default:
      return null
  }
})

const resolutionColor = computed(() => {
  switch (props.resolution) {
    case 'approved':
      return 'text-green-600 dark:text-green-400'
    case 'rejected':
    case 'expired':
      return 'text-muted-foreground'
    default:
      return ''
  }
})

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startCountdown() {
  stopCountdown()
  countdown.value = 60
  selectedSubjectType.value = props.request.recommendedSubjectType || props.request.availableSubjectTypes?.[0] || 'SESSION'
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      stopCountdown()
      emit('resolve', 'expired')
    }
  }, 1000)
}

watch(
  () => props.resolved,
  resolved => {
    if (resolved) {
      stopCountdown()
      return
    }
    startCountdown()
  },
  { immediate: true },
)

onUnmounted(stopCountdown)

async function handleConfirm() {
  submitting.value = true
  errorMessage.value = null

  try {
    await chatApi.respondPermissionApproval(
      props.request.requestId,
      true,
      currentSubjectType.value,
    )
    stopCountdown()
    emit('resolve', 'approved')
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error && (error as { code: number }).code === 404) {
      errorMessage.value = '授权请求已失效'
      setTimeout(() => emit('resolve', 'expired'), 1500)
    } else {
      errorMessage.value = '网络异常，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}

async function handleReject() {
  submitting.value = true
  errorMessage.value = null

  try {
    await chatApi.respondPermissionApproval(
      props.request.requestId,
      false,
      currentSubjectType.value,
    )
    stopCountdown()
    emit('resolve', 'rejected')
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error && (error as { code: number }).code === 404) {
      errorMessage.value = '授权请求已失效'
      setTimeout(() => emit('resolve', 'expired'), 1500)
    } else {
      errorMessage.value = '网络异常，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div
    class="rounded-md border-l-[3px] bg-muted/40 px-3 py-2.5"
    :class="[accentColor, resolved ? 'opacity-70' : '']"
  >
    <div class="flex items-center gap-2 text-sm">
      <component
        :is="isCritical ? ShieldX : ShieldCheck"
        :class="['size-4 shrink-0', resolved ? 'text-muted-foreground' : (isCritical ? 'text-red-500' : 'text-orange-500')]"
      />
      <span class="font-medium">{{ request.toolName }}</span>
      <span class="text-[11px] text-muted-foreground">需要授权</span>

      <div v-if="resolved" class="ml-auto flex items-center gap-1 text-xs" :class="resolutionColor">
        <component :is="resolutionIcon" class="size-3" />
        <span>{{ resolutionLabel }}</span>
      </div>
      <div v-else class="ml-auto flex items-center gap-1 text-[11px] text-muted-foreground">
        <Clock class="size-3" />
        <span>{{ countdown }}s</span>
      </div>
    </div>

    <div class="mt-2 text-xs text-muted-foreground">
      {{ request.message }}
    </div>

    <div
      v-if="errorMessage"
      class="mt-2 text-xs text-destructive"
    >
      {{ errorMessage }}
    </div>

    <div
      v-if="!resolved && subjectTypeOptions.length > 0"
      class="mt-3 flex flex-wrap gap-2"
    >
      <Button
        v-for="subjectType in subjectTypeOptions"
        :key="subjectType"
        type="button"
        size="sm"
        :variant="currentSubjectType === subjectType ? 'default' : 'outline'"
        class="h-7 px-2.5 text-xs"
        :disabled="submitting"
        @click="selectedSubjectType = subjectType"
      >
        {{ subjectTypeLabelMap[subjectType] ?? subjectType }}
      </Button>
    </div>

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
        允许执行
      </Button>
    </div>
  </div>
</template>
