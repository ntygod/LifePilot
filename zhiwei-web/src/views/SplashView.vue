<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Loader2 } from 'lucide-vue-next'
import { API_ORIGIN } from '@/api/config'
import { modelServiceApi } from '@/api/client'

const router = useRouter()
const status = ref('正在启动后端服务...')
const hasError = ref(false)
let unlisten: (() => void) | null = null

/** 后端就绪后，检查是否已配置模型服务，决定跳转目标 */
async function navigateAfterReady() {
  status.value = '正在检查配置...'
  try {
    const services = await modelServiceApi.listEnabledServices('GENERATION')
    if (services.length === 0) {
      router.replace('/setup')
    } else {
      router.replace('/conversations')
    }
  } catch {
    // API 调用失败时直接进入主界面，用户可在设置中配置
    router.replace('/conversations')
  }
}

async function waitForBackend() {
  if (!window.__TAURI_INTERNALS__) {
    // 非 Tauri 环境，直接跳转
    router.replace('/conversations')
    return
  }

  try {
    const { listen } = await import('@tauri-apps/api/event')
    const { invoke } = await import('@tauri-apps/api/core')

    // 先检查端口
    const port = await invoke<number>('get_backend_port')
    window.__ZHIWEI_BACKEND_PORT__ = port
    status.value = `正在启动后端服务（端口 ${port}）...`

    // 监听后端就绪事件
    unlisten = await listen<number>('backend-ready', () => {
      status.value = '后端已就绪，正在加载...'
      navigateAfterReady()
    })

    // 也监听错误事件
    await listen<string>('backend-error', (event) => {
      status.value = `启动失败: ${event.payload}`
      hasError.value = true
    })

    // 可能后端已经启动好了（事件在监听前已发送），主动检查一次
    const result = await invoke<{ running: boolean; port: number }>('get_backend_status')
    if (result.running) {
      try {
        const resp = await fetch(`http://localhost:${port}/actuator/health`)
        if (resp.ok) {
          status.value = '后端已就绪，正在加载...'
          navigateAfterReady()
        }
      } catch {
        // 进程在但还没 ready，等事件
      }
    }
  } catch (e) {
    status.value = `初始化失败: ${e}`
    hasError.value = true
  }
}

async function retry() {
  hasError.value = false
  status.value = '正在重启后端服务...'

  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('restart_backend')
  } catch (e) {
    status.value = `重启失败: ${e}`
    hasError.value = true
  }
}

onMounted(() => {
  waitForBackend()
})

onUnmounted(() => {
  if (unlisten) unlisten()
})
</script>

<template>
  <div class="flex h-screen w-screen items-center justify-center bg-background">
    <div class="flex flex-col items-center gap-lg text-center">
      <!-- Logo -->
      <div class="text-5xl font-bold text-foreground">知微</div>
      <div class="text-sm text-muted-foreground">ZhiWei AI Assistant</div>

      <!-- 加载状态 -->
      <div class="mt-xl flex flex-col items-center gap-md">
        <Loader2
          v-if="!hasError"
          class="h-8 w-8 animate-spin text-primary"
        />
        <p class="text-sm text-muted-foreground">{{ status }}</p>

        <!-- 重试按钮 -->
        <button
          v-if="hasError"
          class="mt-md rounded-lg bg-primary px-lg py-sm text-sm text-primary-foreground hover:bg-primary/90"
          @click="retry"
        >
          重试
        </button>
      </div>
    </div>
  </div>
</template>
