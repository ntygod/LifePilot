<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { CheckCircle, CheckCircle2, Clock, ShieldAlert, ShieldCheck, ShieldX, XCircle } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import type { PermissionApprovalRequest } from '@/types'
import { Button } from '@/components/ui/button'
import {
  formatPermissionActionLabel,
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
const subjectTypeOptions = computed(() => props.request.availableSubjectTypes ?? [])
const currentSubjectType = computed(() =>
  selectedSubjectType.value || props.request.recommendedSubjectType || subjectTypeOptions.value[0] || 'SESSION',
)
const subjectTypeUiMap: Record<string, { label: string; description: string; confirmLabel: string }> = {
  SESSION: {
    label: '本会话',
    description: '这一轮里同类操作不再重复确认。',
    confirmLabel: '允许本会话',
  },
  WORKSPACE: {
    label: '当前目录',
    description: '适合当前目录下的改文件和构建。',
    confirmLabel: '允许当前目录',
  },
  TASK: {
    label: '当前任务',
    description: '这次任务后续步骤都可继续复用。',
    confirmLabel: '允许当前任务',
  },
  USER: {
    label: '长期',
    description: '后续会话也能继续使用。',
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
const actionLabel = computed(() =>
  formatPermissionActionLabel(props.request.actionType, props.request.toolName),
)
const coverageHint = computed(() =>
  props.request.actionType === 'CREATE_SCHEDULE'
    ? '只影响这次任务后续自动运行。'
    : `只放开“${actionLabel.value}”这一类操作。`,
)
const primaryMessage = computed(() => {
  const text = props.request.message?.trim()
  return text || `本次需要${toolLabel.value}。请选择授权范围。`
})
const secondaryMeta = computed(() =>
  actionLabel.value !== toolLabel.value ? actionLabel.value : null,
)
const riskLabel = computed(() => isCritical.value ? '关键操作' : '高风险')
const countdownProgress = computed(() => Math.max(0, Math.min(100, (countdown.value / 60) * 100)))

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

function isSelectedSubjectType(subjectType: string) {
  return currentSubjectType.value === subjectType
}

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
    class="permission-bubble w-full max-w-[34rem] rounded-[1.15rem] border px-3.5 py-3 text-left shadow-[0_12px_24px_-18px_hsl(var(--shadow-color)/0.14)]"
    :class="[
      isCritical ? 'permission-bubble-critical' : 'permission-bubble-high',
      resolved ? 'opacity-75' : '',
    ]"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="flex min-w-0 items-start gap-3">
        <span
          class="permission-icon-shell mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-[0.95rem]"
          :class="isCritical ? 'permission-icon-shell-critical' : 'permission-icon-shell-high'"
        >
          <component
            :is="isCritical ? ShieldAlert : ShieldCheck"
            class="size-4"
            :class="resolved ? 'text-muted-foreground' : (isCritical ? 'text-red-500' : 'text-amber-600')"
          />
        </span>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-1.5">
            <span class="text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/82">权限确认</span>
            <span class="permission-meta-chip">{{ riskLabel }}</span>
            <span v-if="secondaryMeta" class="permission-meta-chip">{{ secondaryMeta }}</span>
          </div>
          <div class="mt-1 text-sm font-medium text-foreground">{{ toolLabel }}</div>
          <p class="mt-1 text-[11px] leading-5 text-muted-foreground/88">
            {{ primaryMessage }}
          </p>
        </div>
      </div>

      <div class="flex shrink-0 items-center gap-2">
        <div v-if="resolved" class="permission-meta-chip" :class="resolutionColor">
          <component :is="resolutionIcon" class="size-3" />
          <span>{{ resolutionLabel }}</span>
        </div>
        <div v-else class="permission-meta-chip">
            <Clock class="size-3" />
            <span>{{ countdown }}s</span>
        </div>
      </div>
    </div>

    <div v-if="!resolved" class="mt-3 h-1.5 overflow-hidden rounded-full bg-border/55">
      <div
        class="h-full rounded-full transition-[width] duration-700 ease-out"
        :class="isCritical ? 'bg-red-400/90' : 'bg-amber-400/90'"
        :style="{ width: `${countdownProgress}%` }"
      />
    </div>

    <div class="mt-2 text-[11px] leading-4 text-muted-foreground/82">
      {{ coverageHint }}
    </div>

    <div
      v-if="errorMessage"
      class="permission-error mt-3 rounded-[0.95rem] border border-destructive/16 bg-destructive/5 px-3 py-2 text-xs text-destructive"
    >
      {{ errorMessage }}
    </div>

    <div
      v-if="!resolved && subjectTypeOptions.length > 0"
      class="mt-3.5 space-y-2.5"
    >
      <div class="text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/82">
        授权范围
      </div>
      <div class="grid gap-2 sm:grid-cols-2">
        <button
          v-for="subjectType in subjectTypeOptions"
          :key="subjectType"
          type="button"
          class="permission-scope-card text-left"
          :class="isSelectedSubjectType(subjectType) ? 'permission-scope-card-active' : 'permission-scope-card-idle'"
          :disabled="submitting"
          @click="selectedSubjectType = subjectType"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0">
              <div class="text-[11px] font-medium text-foreground/92">
                {{ subjectTypeUiMap[subjectType]?.label ?? subjectType }}
              </div>
              <p class="mt-1 text-[10px] leading-relaxed text-muted-foreground/82">
                {{ subjectTypeUiMap[subjectType]?.description ?? '按当前所选范围保存。' }}
              </p>
            </div>
            <CheckCircle2
              v-if="isSelectedSubjectType(subjectType)"
              class="mt-0.5 size-4 shrink-0 text-primary"
            />
          </div>
        </button>
      </div>
    </div>

    <div v-if="!resolved" class="mt-3.5 flex items-center justify-end gap-2">
      <Button
        variant="outline"
        size="sm"
        class="h-9 rounded-[0.95rem] border-border/58 bg-background/80 px-3 text-xs text-muted-foreground shadow-none hover:bg-background hover:text-foreground"
        :disabled="submitting"
        @click="handleReject"
      >
        拒绝
      </Button>
      <Button
        :variant="isCritical ? 'destructive' : 'default'"
        size="sm"
        class="permission-confirm-btn h-9 rounded-[0.95rem] px-3 text-xs shadow-[0_12px_20px_-14px_hsl(var(--shadow-color)/0.18)]"
        :disabled="submitting"
        @click="handleConfirm"
      >
        {{ currentSubjectOption.confirmLabel }}
      </Button>
    </div>
  </div>
</template>

<style scoped>
.permission-bubble {
  position: relative;
  overflow: hidden;
  background: hsl(from var(--card) h s l / 0.96);
  box-shadow:
    0 16px 28px -34px hsl(var(--shadow-color) / 0.14),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.48);
}

.permission-bubble-high {
  border-color: hsl(39 68% 72% / 0.8);
  background: hsl(39 90% 97% / 0.96);
}

.permission-bubble-critical {
  border-color: hsl(from var(--destructive) h s l / 0.2);
  background: hsl(from var(--destructive) h s l / 0.05);
}

.permission-icon-shell {
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.84);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.36);
}

.permission-icon-shell-high {
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.36);
}

.permission-icon-shell-critical {
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.36);
}

.permission-meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0.22rem 0.52rem;
  font-size: 10px;
  line-height: 1.1;
  color: hsl(from var(--muted-foreground) h s l / 0.88);
}

.permission-scope-card {
  border-radius: 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.58);
  padding: 0.8rem 0.9rem;
  transition:
    transform 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid);
}

.permission-scope-card:hover {
  transform: translateY(-1px);
}

.permission-scope-card-idle:hover {
  border-color: hsl(from var(--border) h s l / 0.58);
  background: hsl(from var(--card) h s l / 0.82);
  box-shadow: 0 12px 20px -24px hsl(var(--shadow-color) / 0.12);
}

.permission-scope-card-active {
  border-color: hsl(from var(--primary) h s l / 0.24);
  background: hsl(from var(--primary) h s l / 0.08);
  box-shadow:
    0 12px 20px -24px hsl(var(--shadow-color) / 0.12),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.32);
}

.permission-confirm-btn {
  min-width: 7.5rem;
}

.permission-error {
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.26);
}
</style>
