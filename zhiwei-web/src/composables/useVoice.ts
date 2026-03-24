import { ref, onUnmounted } from 'vue'
import type { Ref } from 'vue'

/**
 * 语音交互 composable，封装录音生命周期与 TTS 播放逻辑。
 *
 * 录音使用 MediaRecorder API（audio/webm;codecs=opus），
 * TTS 播放使用 Web Audio API（AudioContext + AudioBuffer），
 * 波形可视化通过 AnalyserNode 提供频域数据。
 *
 * @param maxRecordingSeconds 最大录音时长（秒），默认 120
 */
export function useVoice(maxRecordingSeconds = 120) {
  // ── 录音状态 ──────────────────────────────────────────
  const isRecording: Ref<boolean> = ref(false)
  const recordingDuration: Ref<number> = ref(0)
  const audioBlob: Ref<Blob | null> = ref(null)
  const analyserNode: Ref<AnalyserNode | null> = ref(null)

  // ── TTS 播放状态 ──────────────────────────────────────
  const isPlaying: Ref<boolean> = ref(false)
  const playbackProgress: Ref<number> = ref(0)
  const isLoadingTts: Ref<boolean> = ref(false)

  // ── 浏览器能力检测 ────────────────────────────────────
  const isSupported: Ref<boolean> = ref(
    typeof navigator !== 'undefined' &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof MediaRecorder !== 'undefined'
  )

  // ── 内部引用 ──────────────────────────────────────────
  let mediaRecorder: MediaRecorder | null = null
  let audioContext: AudioContext | null = null
  let mediaStream: MediaStream | null = null
  let chunks: Blob[] = []
  let durationTimer: ReturnType<typeof setInterval> | null = null

  // TTS 播放内部引用
  let ttsAudioContext: AudioContext | null = null
  let ttsSourceNode: AudioBufferSourceNode | null = null
  let ttsStartTime = 0
  let ttsDuration = 0
  let ttsAnimFrame: number | null = null
  const ttsCache = new Map<string, AudioBuffer>()

  // ── 录音控制 ──────────────────────────────────────────

  /**
   * 开始录音。请求麦克风权限，创建 MediaRecorder 和 AnalyserNode。
   */
  async function startRecording(): Promise<void> {
    if (!isSupported.value || isRecording.value) return

    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })

      // 创建 AudioContext + AnalyserNode（供波形组件消费）
      audioContext = new AudioContext()
      const source = audioContext.createMediaStreamSource(mediaStream)
      const analyser = audioContext.createAnalyser()
      analyser.fftSize = 64
      source.connect(analyser)
      analyserNode.value = analyser

      // 创建 MediaRecorder
      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : 'audio/webm'
      mediaRecorder = new MediaRecorder(mediaStream, { mimeType })
      chunks = []

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data)
      }

      mediaRecorder.onstop = () => {
        audioBlob.value = new Blob(chunks, { type: mediaRecorder?.mimeType || 'audio/webm' })
        chunks = []
      }

      mediaRecorder.start(250) // 每 250ms 收集一次数据
      isRecording.value = true
      recordingDuration.value = 0
      audioBlob.value = null

      // 时长计时器，达到上限自动停止
      durationTimer = setInterval(() => {
        recordingDuration.value++
        if (recordingDuration.value >= maxRecordingSeconds) {
          stopRecording()
        }
      }, 1000)
    } catch {
      // 用户拒绝权限或浏览器不支持
      isSupported.value = false
      releaseRecordingResources()
    }
  }

  /**
   * 停止录音。停止 MediaRecorder 并释放 MediaStream。
   */
  async function stopRecording(): Promise<void> {
    if (!isRecording.value || !mediaRecorder) return

    isRecording.value = false
    if (durationTimer) {
      clearInterval(durationTimer)
      durationTimer = null
    }

    // 等待 onstop 回调完成
    await new Promise<void>((resolve) => {
      if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        const originalOnStop = mediaRecorder.onstop
        mediaRecorder.onstop = (e) => {
          if (originalOnStop) (originalOnStop as (ev: Event) => void)(e)
          resolve()
        }
        mediaRecorder.stop()
      } else {
        resolve()
      }
    })

    releaseRecordingResources()
  }

  /** 释放录音相关资源（MediaStream、AudioContext）。 */
  function releaseRecordingResources() {
    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop())
      mediaStream = null
    }
    if (audioContext) {
      audioContext.close().catch(() => {})
      audioContext = null
    }
    analyserNode.value = null
    mediaRecorder = null
  }

  // ── TTS 播放控制 ──────────────────────────────────────

  /**
   * 播放指定消息的 TTS 音频。首次请求后缓存 AudioBuffer。
   */
  async function playTts(entryId: string): Promise<void> {
    if (isPlaying.value) stopTts()

    isLoadingTts.value = true
    try {
      let buffer = ttsCache.get(entryId)
      if (!buffer) {
        const resp = await fetch(`/api/chat/entries/${entryId}/tts`, { method: 'POST' })
        if (!resp.ok) throw new Error(`TTS 请求失败: ${resp.status}`)
        const arrayBuffer = await resp.arrayBuffer()
        if (!ttsAudioContext) ttsAudioContext = new AudioContext()
        buffer = await ttsAudioContext.decodeAudioData(arrayBuffer)
        ttsCache.set(entryId, buffer)
      }

      if (!ttsAudioContext) ttsAudioContext = new AudioContext()
      ttsSourceNode = ttsAudioContext.createBufferSource()
      ttsSourceNode.buffer = buffer
      ttsSourceNode.connect(ttsAudioContext.destination)

      ttsDuration = buffer.duration
      ttsStartTime = ttsAudioContext.currentTime
      playbackProgress.value = 0
      isPlaying.value = true

      ttsSourceNode.onended = () => {
        isPlaying.value = false
        playbackProgress.value = 1
        cancelTtsProgressTracking()
      }

      ttsSourceNode.start()
      trackTtsProgress()
    } catch {
      isPlaying.value = false
      playbackProgress.value = 0
    } finally {
      isLoadingTts.value = false
    }
  }

  /** 停止 TTS 播放。 */
  function stopTts() {
    if (ttsSourceNode) {
      try { ttsSourceNode.stop() } catch { /* 已停止 */ }
      ttsSourceNode = null
    }
    isPlaying.value = false
    playbackProgress.value = 0
    cancelTtsProgressTracking()
  }

  /** 通过 requestAnimationFrame 跟踪播放进度。 */
  function trackTtsProgress() {
    if (!ttsAudioContext || !isPlaying.value) return
    const elapsed = ttsAudioContext.currentTime - ttsStartTime
    playbackProgress.value = Math.min(elapsed / ttsDuration, 1)
    if (isPlaying.value) {
      ttsAnimFrame = requestAnimationFrame(trackTtsProgress)
    }
  }

  /** 取消进度跟踪动画帧。 */
  function cancelTtsProgressTracking() {
    if (ttsAnimFrame !== null) {
      cancelAnimationFrame(ttsAnimFrame)
      ttsAnimFrame = null
    }
  }

  // ── 资源清理 ──────────────────────────────────────────

  function cleanup() {
    if (isRecording.value) {
      isRecording.value = false
      if (durationTimer) { clearInterval(durationTimer); durationTimer = null }
    }
    releaseRecordingResources()
    stopTts()
    if (ttsAudioContext) {
      ttsAudioContext.close().catch(() => {})
      ttsAudioContext = null
    }
    ttsCache.clear()
  }

  onUnmounted(cleanup)

  return {
    // 录音状态
    isRecording,
    recordingDuration,
    audioBlob,
    isSupported,
    analyserNode,
    // TTS 播放状态
    isPlaying,
    playbackProgress,
    isLoadingTts,
    // 录音控制
    startRecording,
    stopRecording,
    // TTS 播放控制
    playTts,
    stopTts,
    // 资源清理
    cleanup,
  }
}
