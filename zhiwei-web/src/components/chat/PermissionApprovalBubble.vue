<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { CheckCircle, Clock, ShieldCheck, ShieldX, XCircle } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import type { PermissionApprovalRequest } from '@/types'
import { Button } from '@/components/ui/button'
import {
  buildPermissionCoverageHint,
  formatPermissionToolLabel,
} from '@/utils/permissionApproval'

const props = defineProps<{
  request: PermissionApprovalRequest
  resolved: boolean
  resolution?: 'approved' | 'rejected' | 'expired'
}>()

const emit = defineEmits<{
  resolve: [resolution: 'approved' | 'rejected' | 'expired', subjectType?: string]
}>()

const countdown = ref(60)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const selectedSubjectType = ref<string>('')

let timer: ReturnType<typeof setInterval> | null = null

const isCritical = computed(() => props.request.riskLevel === 'CRITICAL')
const accentColor = computed(() =>
  isCritical.value ? 'border-red-200/70 bg-red-50/50' : 'border-amber-200/70 bg-amber-50/45',
)
const subjectTypeOptions = computed(() => props.request.availableSubjectTypes ?? [])
const currentSubjectType = computed(() =>
  selectedSubjectType.value || props.request.recommendedSubjectType || subjectTypeOptions.value[0] || 'SESSION',
)
const subjectTypeUiMap: Record<string, { label: string; description: string; confirmLabel: string }> = {
  SESSION: {
    label: '本会话',
    description: '本会话里的同类操作不再重复询问。',
    confirmLabel: '允许本会话',
  },
  WORKSPACE: {
    label: '当前工作目录',
    description: '适合当前目录下的改文件、构建和自动化操作。',
    confirmLabel: '允许当前工作目录',
  },
  TASK: {
    label: '当前任务',
    description: '后续 Cron、心跳和工作流会复用这条任务授权。',
    confirmLabel: '允许当前任务',
  },
  USER: {
    label: '长期',
    description: '后续会话也复用，适合稳定的高频操作。',
    confirmLabel: '长期允许',
  },
}
const currentSubjectOption = computed(() =>
  subjectTypeUiMap[currentSubjectType.value] ?? {
    label: currentSubjectType.value,
    description: '按当前所选授权范围保存。',
    confirmLabel: '允许并记住',
  },
)
const toolLabel = computed(() =>
  formatPermissionToolLabel(props.request.toolId, props.request.toolName),
)
const coverageHint = computed(() =>
  buildPermissionCoverageHint(props.request.actionType, props.request.toolName),
)
const primaryMessage = computed(() => `本次需要${toolLabel.value}。请选择授权范围。`)

const resolutionLabel = computed(() => {
  switch (props.resolution) {
    case 'approved':
      return '已授权'
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
    emit('resolve', 'approved', currentSubjectType.value)
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
    emit('resolve', 'rejected', currentSubjectType.value)
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
    class="w-fit max-w-full rounded-2xl border px-3 py-2 shadow-[0_12px_24px_-24px_hsl(var(--shadow-color)/0.28)] backdrop-blur-sm"
    :class="[accentColor, resolved ? 'opacity-70' : '']"
  >
    <div class="flex items-start gap-2">
      <component
        :is="isCritical ? ShieldX : ShieldCheck"
        :class="['mt-0.5 size-4 shrink-0', resolved ? 'text-muted-foreground' : (isCritical ? 'text-red-500' : 'text-amber-500')]"
      />
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-1.5 text-sm">
          <span class="font-medium text-foreground">{{ toolLabel }}</span>
          <span class="rounded-full border border-border/60 bg-background/85 px-2 py-0.5 text-[10px] text-muted-foreground">
            需授权
          </span>
          <div v-if="resolved" class="ml-auto flex items-center gap-1 text-xs" :class="resolutionColor">
            <component :is="resolutionIcon" class="size-3" />
            <span>{{ resolutionLabel }}</span>
          </div>
          <div v-else class="ml-auto flex items-center gap-1 text-[11px] text-muted-foreground">
            <Clock class="size-3" />
            <span>{{ countdown }}s</span>
          </div>
        </div>

        <div class="mt-1 text-xs leading-5 text-muted-foreground">
          {{ primaryMessage }}
        </div>
        <div class="mt-0.5 text-[11px] leading-4 text-muted-foreground/85">
          {{ coverageHint }}
        </div>
      </div>
    </div>

    <div
      v-if="errorMessage"
      class="mt-2 text-xs text-destructive"
    >
      {{ errorMessage }}
    </div>

    <div
      v-if="!resolved && subjectTypeOptions.length > 0"
      class="mt-2.5 space-y-1.5"
    >
      <div class="flex flex-wrap gap-1.5">
        <Button
          v-for="subjectType in subjectTypeOptions"
          :key="subjectType"
          type="button"
          size="sm"
          :variant="currentSubjectType === subjectType ? 'default' : 'outline'"
          class="h-7 rounded-full px-2.5 text-xs shadow-none"
          :disabled="submitting"
          @click="selectedSubjectType = subjectType"
        >
          {{ subjectTypeUiMap[subjectType]?.label ?? subjectType }}
        </Button>
      </div>

      <div class="text-[11px] text-muted-foreground">
        {{ currentSubjectOption.description }}
      </div>
    </div>

    <div v-if="!resolved" class="mt-2.5 flex items-center justify-end gap-2">
      <Button
        variant="ghost"
        size="sm"
        class="h-7 rounded-full px-2.5 text-xs text-muted-foreground hover:text-foreground"
        :disabled="submitting"
        @click="handleReject"
      >
        拒绝
      </Button>
      <Button
        :variant="isCritical ? 'destructive' : 'default'"
        size="sm"
        class="h-7 rounded-full px-2.5 text-xs shadow-none"
        :disabled="submitting"
        @click="handleConfirm"
      >
        {{ currentSubjectOption.confirmLabel }}
      </Button>
    </div>
  </div>
</template>
