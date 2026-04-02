import { readonly, ref } from 'vue'

// --- 单例模块级状态 ---
const status = ref<'idle' | 'downloading' | 'complete' | 'error'>('idle')
const progress = ref(0)
const stage = ref('')
const downloaded = ref(0)
const total = ref(0)
const speedBps = ref(0)
const errorMessage = ref('')
const available = ref(false)
const visible = ref(false)

let listenersRegistered = false

/** 检测当前是否运行在 Tauri 桌面端环境 */
function isTauriEnv(): boolean {
  return '__TAURI_INTERNALS__' in window
}

/** 注册 Tauri 事件监听（仅注册一次） */
async function ensureListeners() {
  if (listenersRegistered) return
  if (!isTauriEnv()) return

  listenersRegistered = true

  const { listen } = await import('@tauri-apps/api/event')

  // 下载进度
  await listen<{
    stage: string
    progress: number
    downloaded: number
    total: number
    speed_bps: number
  }>('whisper-download-progress', (event) => {
    status.value = 'downloading'
    visible.value = true
    stage.value = event.payload.stage
    progress.value = event.payload.progress
    downloaded.value = event.payload.downloaded
    total.value = event.payload.total
    speedBps.value = event.payload.speed_bps
  })

  // 下载完成
  await listen<{
    cli_path: string
    model_path: string
  }>('whisper-download-complete', () => {
    status.value = 'complete'
    progress.value = 1
    available.value = true
    // 3 秒后自动隐藏
    setTimeout(() => {
      visible.value = false
      status.value = 'idle'
    }, 3000)
  })

  // 下载错误
  await listen<{
    message: string
    recoverable: boolean
  }>('whisper-download-error', (event) => {
    status.value = 'error'
    errorMessage.value = event.payload.message
  })

  // 下载取消
  await listen('whisper-download-cancelled', () => {
    status.value = 'idle'
    visible.value = false
  })
}

/**
 * Whisper 语音引擎自动下载管理
 *
 * 单例模式：所有调用方共享同一份状态。
 */
export function useWhisperDownload() {
  // 确保事件监听已注册
  ensureListeners()

  /** 检查 Whisper 引擎可用性 */
  async function checkAvailability(): Promise<boolean> {
    if (!isTauriEnv()) {
      // 非 Tauri 环境（浏览器开发模式），假定可用
      available.value = true
      return true
    }

    try {
      const { invoke } = await import('@tauri-apps/api/core')
      const result = await invoke<{
        available: boolean
        cli_path: string | null
        model_path: string | null
        downloading: boolean
      }>('check_whisper_status')

      available.value = result.available

      if (result.downloading) {
        status.value = 'downloading'
        visible.value = true
      }

      return result.available
    } catch (e) {
      console.warn('检查 Whisper 状态失败:', e)
      return false
    }
  }

  /** 触发后台下载 */
  async function triggerDownload(): Promise<void> {
    if (!isTauriEnv()) return

    try {
      status.value = 'downloading'
      visible.value = true
      stage.value = '正在准备下载'
      progress.value = 0

      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('start_whisper_download')
    } catch (e) {
      status.value = 'error'
      errorMessage.value = String(e)
    }
  }

  /** 取消下载 */
  async function cancelDownload(): Promise<void> {
    if (!isTauriEnv()) return

    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('cancel_whisper_download')
      // 立即更新 UI 状态
      status.value = 'idle'
      visible.value = false
    } catch (e) {
      console.warn('取消下载失败:', e)
    }
  }

  /** 手动关闭卡片 */
  function dismiss() {
    visible.value = false
    if (status.value === 'error') {
      status.value = 'idle'
    }
  }

  /** 格式化下载速度 */
  function formatSpeed(bps: number): string {
    if (bps < 1024) return `${bps} B/s`
    if (bps < 1024 * 1024) return `${(bps / 1024).toFixed(1)} KB/s`
    return `${(bps / 1024 / 1024).toFixed(1)} MB/s`
  }

  /** 格式化文件大小 */
  function formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  }

  return {
    // 只读状态
    status: readonly(status),
    progress: readonly(progress),
    stage: readonly(stage),
    downloaded: readonly(downloaded),
    total: readonly(total),
    speedBps: readonly(speedBps),
    errorMessage: readonly(errorMessage),
    available: readonly(available),
    visible: readonly(visible),

    // 方法
    checkAvailability,
    triggerDownload,
    cancelDownload,
    dismiss,
    formatSpeed,
    formatSize,
  }
}
