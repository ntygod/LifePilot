<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, Loader2, RotateCcw, X } from 'lucide-vue-next'
import { modelServiceApi } from '@/api/client'

const router = useRouter()

type StepStatus = 'pending' | 'active' | 'completed' | 'error'

const steps = ref([
  { label: '初始化运行环境', status: 'pending' as StepStatus },
  { label: '加载记忆与工具', status: 'pending' as StepStatus },
  { label: '启动核心服务', status: 'pending' as StepStatus },
  { label: '准备就绪', status: 'pending' as StepStatus },
])

const hasError = ref(false)
const errorMessage = ref('')
const elapsedSeconds = ref(0)

let unlisteners: (() => void)[] = []
let elapsedTimer: ReturnType<typeof setInterval> | null = null
let healthPoller: ReturnType<typeof setInterval> | null = null
let navigating = false

function handleBackendStage(stage: string) {
  switch (stage) {
    case 'resolving_java':
      if (steps.value[0].status === 'pending') steps.value[0].status = 'active'
      break
    case 'java_found':
      steps.value[0].status = 'completed'
      if (steps.value[1].status === 'pending') steps.value[1].status = 'active'
      break
    case 'process_started':
      steps.value[1].status = 'completed'
      if (steps.value[2].status === 'pending') steps.value[2].status = 'active'
      break
    case 'health_check':
      if (steps.value[2].status === 'pending') steps.value[2].status = 'active'
      break
  }
}

/** 根据后端当前状态补齐已错过的步骤进度 */
function syncStepsFromStatus(running: boolean) {
  if (running) {
    // 进程已在运行，前两步必然完成，第三步进行中
    if (steps.value[0].status !== 'completed') steps.value[0].status = 'completed'
    if (steps.value[1].status !== 'completed') steps.value[1].status = 'completed'
    if (steps.value[2].status === 'pending') steps.value[2].status = 'active'
  }
}

function failAtCurrentStep(error: string) {
  const activeIdx = steps.value.findIndex(s => s.status === 'active')
  if (activeIdx >= 0) steps.value[activeIdx].status = 'error'
  hasError.value = true
  errorMessage.value = error
  stopTimer()
  stopHealthPoller()
}

function startTimer() {
  elapsedSeconds.value = 0
  elapsedTimer = setInterval(() => elapsedSeconds.value++, 1000)
}

function stopTimer() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

function stopHealthPoller() {
  if (healthPoller) {
    clearInterval(healthPoller)
    healthPoller = null
  }
}

async function resolveDestination(): Promise<string> {
  const onboardingDone = localStorage.getItem('zhiwei_onboarding_completed') === 'true'
  try {
    const services = await modelServiceApi.listEnabledServices('GENERATION')
    if (services.length === 0 && !onboardingDone) return '/setup'
  } catch {
    // 查询失败且未完成过引导，仍进入引导流程
    if (!onboardingDone) return '/setup'
  }
  return '/conversations/new'
}

async function navigateAfterReady(quick = false) {
  if (navigating) return
  navigating = true

  steps.value[2].status = 'completed'
  steps.value[3].status = 'completed'
  stopTimer()
  stopHealthPoller()

  if (!quick) await new Promise(r => setTimeout(r, 500))

  router.replace(await resolveDestination())
}

async function waitForBackend() {
  if (!window.__TAURI_INTERNALS__) {
    router.replace('/conversations/new')
    return
  }

  // 第一步立即标记为进行中
  steps.value[0].status = 'active'
  startTimer()

  try {
    const { listen } = await import('@tauri-apps/api/event')
    const { invoke } = await import('@tauri-apps/api/core')

    const port = await invoke<number>('get_backend_port')
    window.__ZHIWEI_BACKEND_PORT__ = port

    // 注册事件监听（用于步骤进度更新）
    unlisteners.push(await listen<string>('backend-stage', e => handleBackendStage(e.payload)))
    unlisteners.push(await listen<number>('backend-ready', () => navigateAfterReady()))
    unlisteners.push(await listen<string>('backend-error', e => failAtCurrentStep(e.payload)))

    // 查询当前状态，补齐在监听注册前已完成的步骤
    const status = await invoke<{ running: boolean; port: number }>('get_backend_status')
    syncStepsFromStatus(status.running)

    // 立即做一次健康检查，后端已就绪时快速跳过动画
    try {
      const resp = await fetch(`http://localhost:${port}/actuator/health`, {
        signal: AbortSignal.timeout(2000),
      })
      if (resp.ok) {
        navigateAfterReady(true)
        return
      }
    } catch {
      // 后端还未就绪，继续走轮询流程
    }

    // 启动健康检查轮询（每 2 秒），作为事件丢失时的可靠兜底
    healthPoller = setInterval(async () => {
      if (navigating || hasError.value) return
      try {
        const resp = await fetch(`http://localhost:${port}/actuator/health`, {
          signal: AbortSignal.timeout(2000),
        })
        if (resp.ok) navigateAfterReady()
      } catch {
        // 后端还未就绪，继续轮询
      }
    }, 2000)
  } catch (e) {
    failAtCurrentStep(`初始化失败: ${e}`)
  }
}

async function retry() {
  navigating = false
  hasError.value = false
  errorMessage.value = ''
  steps.value.forEach(s => { s.status = 'pending' })
  steps.value[0].status = 'active'
  startTimer()

  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('restart_backend')

    // 重启后重新开始健康轮询
    const port = await invoke<number>('get_backend_port')
    stopHealthPoller()
    healthPoller = setInterval(async () => {
      if (navigating || hasError.value) return
      try {
        const resp = await fetch(`http://localhost:${port}/actuator/health`, {
          signal: AbortSignal.timeout(2000),
        })
        if (resp.ok) navigateAfterReady()
      } catch {
        // 继续轮询
      }
    }, 2000)
  } catch (e) {
    failAtCurrentStep(`重启失败: ${e}`)
  }
}

const formattedElapsed = computed(() => {
  const s = elapsedSeconds.value
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m${s % 60}s`
})

onMounted(() => waitForBackend())
onUnmounted(() => {
  unlisteners.forEach(fn => fn())
  stopTimer()
  stopHealthPoller()
})
</script>

<template>
  <div class="flex h-screen w-screen items-center justify-center bg-background">
    <div class="w-[22rem] flex flex-col items-center">
      <!-- Logo -->
      <div class="text-5xl font-bold text-foreground">知微</div>
      <div class="mt-sm text-sm text-muted-foreground">ZhiWei AI Assistant</div>

      <!-- 步骤 -->
      <div class="mt-2xl flex flex-col gap-lg">
        <div
          v-for="(step, i) in steps"
          :key="i"
          class="flex items-center gap-md whitespace-nowrap"
        >
          <div class="flex h-5 w-5 shrink-0 items-center justify-center">
            <Check v-if="step.status === 'completed'" class="h-5 w-5 text-green-500" />
            <Loader2 v-else-if="step.status === 'active'" class="h-5 w-5 animate-spin text-primary" />
            <X v-else-if="step.status === 'error'" class="h-5 w-5 text-destructive" />
            <div v-else class="h-1.5 w-1.5 rounded-full bg-muted-foreground/30" />
          </div>
          <span
            class="text-sm"
            :class="{
              'text-muted-foreground': step.status === 'completed',
              'font-medium text-foreground': step.status === 'active',
              'font-medium text-destructive': step.status === 'error',
              'text-muted-foreground/40': step.status === 'pending',
            }"
          >
            {{ step.label }}
            <span v-if="step.status === 'active' && !hasError" class="ml-sm text-xs text-muted-foreground/50">
              {{ formattedElapsed }}
            </span>
          </span>
        </div>
      </div>

      <!-- 提示 -->
      <p v-if="!hasError && elapsedSeconds > 10" class="mt-xl text-center text-xs text-muted-foreground/50">
        首次启动可能需要较长时间
      </p>

      <!-- 错误 -->
      <div v-if="hasError" class="mt-xl text-center">
        <p class="text-sm text-destructive">{{ errorMessage }}</p>
        <button
          class="mt-md inline-flex items-center gap-sm rounded-lg bg-primary px-lg py-sm text-sm text-primary-foreground hover:bg-primary/90"
          @click="retry"
        >
          <RotateCcw class="h-4 w-4" />
          重试
        </button>
      </div>
    </div>
  </div>
</template>
