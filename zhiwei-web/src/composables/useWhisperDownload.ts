import { ref, readonly } from 'vue'
import type { Ref } from 'vue'

/**
 * Whisper 下载状态类型
 */
type WhisperDownloadStatus = 'idle' | 'checking' | 'downloading' | 'complete' | 'error'

/**
 * 下载进度事件负载（与 Rust 侧 DownloadProgress 对应）
 */
interface DownloadProgressPayload {
  stage: string
  progress: number
  downloaded: number
  total: number
  speed_bps: number
}

/**
 * 下载完成事件负载
 */
interface DownloadCompletePayload {
  cli_path: string
  model_path: string
}

/**
 * 下载错误事件负载
 */
interface DownloadErrorPayload {
  message: string
  recoverable: boolean
}

/**
 * Whisper 可用性检查结果（与 Rust 侧 WhisperAvailability 对应）
 */
interface WhisperAvailability {
  available: boolean
  cli_path: string
  model_path: string
  downloading: boolean
}

// ── 模块级单例状态（所有组件共享） ──────────────────────────
const status: Ref<WhisperDownloadStatus> = ref('idle')
const progress = ref(0)
const stage = ref('')
const downloaded = ref(0)
const total = ref(0)
const speedBps = ref(0)
const errorMessage: Ref<string | null> = ref(null)
const available = ref(false)
const visible = ref(false)

let listenersRegistered = false
let dismissTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 检查当前环境是否为 Tauri 桌面端
 */
function isTauri(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

/**
 * 注册 Tauri 事件监听器（仅首次调用生效）
 */
async function ensureListeners() {
  if (listenersRegistered || !isTauri()) return
  listenersRegistered = true

  const { listen } = await import('@tauri-apps/api/event')

  // 事件监听器跟随应用生命周期，无需手动 unlisten
  await listen<DownloadProgressPayload>('whisper-download-progress', (e) => {
    status.value = 'downloading'
    progress.value = e.payload.progress
    stage.value = e.payload.stage
    downloaded.value = e.payload.downloaded
    total.value = e.payload.total
    speedBps.value = e.payload.speed_bps
    visible.value = true
  })

  await listen<DownloadCompletePayload>('whisper-download-complete', () => {
    status.value = 'complete'
    progress.value = 100
    stage.value = '下载完成'
    available.value = true

    dismissTimer = setTimeout(() => {
      visible.value = false
      status.value = 'idle'
    }, 3000)
  })

  await listen<DownloadErrorPayload>('whisper-download-error', (e) => {
    status.value = 'error'
    errorMessage.value = e.payload.message
  })
}

/**
 * 检查 Whisper 是否可用
 */
async function checkAvailability(): Promise<boolean> {
  if (!isTauri()) {
    available.value = true
    return true
  }

  await ensureListeners()
  status.value = 'checking'

  try {
    const { invoke } = await import('@tauri-apps/api/core')
    const result = await invoke<WhisperAvailability>('check_whisper_status')
    available.value = result.available

    if (result.downloading) {
      status.value = 'downloading'
      visible.value = true
    } else {
      status.value = 'idle'
    }

    return result.available
  } catch (e) {
    console.error('检查 Whisper 状态失败:', e)
    status.value = 'error'
    errorMessage.value = e instanceof Error ? e.message : '检查语音引擎状态失败'
    return false
  }
}

/**
 * 触发 Whisper 下载
 */
async function triggerDownload(): Promise<void> {
  if (!isTauri()) return
  if (status.value === 'downloading') return

  await ensureListeners()
  errorMessage.value = null
  status.value = 'downloading'
  progress.value = 0
  stage.value = '准备下载...'
  visible.value = true

  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('start_whisper_download')
  } catch (e) {
    status.value = 'error'
    errorMessage.value = e instanceof Error ? e.message : '启动下载失败'
  }
}

/**
 * 取消下载
 */
async function cancelDownload(): Promise<void> {
  if (!isTauri()) return

  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('cancel_whisper_download')
    status.value = 'idle'
    visible.value = false
    progress.value = 0
  } catch (e) {
    console.error('取消下载失败:', e)
  }
}

/**
 * 手动关闭进度卡片
 */
function dismiss() {
  visible.value = false
  if (dismissTimer) {
    clearTimeout(dismissTimer)
    dismissTimer = null
  }
}

/**
 * 格式化下载速度
 */
function formatSpeed(bps: number): string {
  if (bps < 1024) return `${bps} B/s`
  if (bps < 1024 * 1024) return `${(bps / 1024).toFixed(1)} KB/s`
  return `${(bps / 1024 / 1024).toFixed(1)} MB/s`
}

/**
 * 格式化文件大小
 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * Whisper 下载管理 composable。
 *
 * 使用模块级单例状态，所有消费组件共享同一份进度数据。
 * 非 Tauri 环境下 available 始终为 true。
 */
export function useWhisperDownload() {
  return {
    status: readonly(status),
    progress: readonly(progress),
    stage: readonly(stage),
    downloaded: readonly(downloaded),
    total: readonly(total),
    speedBps: readonly(speedBps),
    errorMessage: readonly(errorMessage),
    available: readonly(available),
    visible: readonly(visible),

    checkAvailability,
    triggerDownload,
    cancelDownload,
    dismiss,

    formatSpeed,
    formatSize,
  }
}
