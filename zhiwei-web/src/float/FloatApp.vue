<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'

/**
 * 知微桌面浮窗 — 主动提醒投递终端
 *
 * 五种状态：idle / soft / bubble / panel / chat
 * 设计语言：毛玻璃 Acrylic + 弹簧动效 + 变形展开
 */

// ─── 类型定义 ────────────────────────────────────────────
type FloatState = 'idle' | 'soft' | 'bubble' | 'panel' | 'chat'
type FeedbackType = 'ACTED' | 'SNOOZED' | 'NOT_RELEVANT'

interface ReminderData {
  notificationId: string
  title: string
  content: string
  pushLevel: string
}

interface QuickPanelData {
  todayReminders: number
  trackingCount: number
  weather: string | null
  backendStatus: 'running' | 'stopped' | 'unknown'
}

// ─── 状态 ────────────────────────────────────────────────
const state = ref<FloatState>('idle')
const reminder = ref<ReminderData | null>(null)
const panelData = ref<QuickPanelData>({
  todayReminders: 0, trackingCount: 0, weather: null, backendStatus: 'unknown'
})
const chatMessages = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])
const chatInput = ref('')
const autoDismissTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const isHovering = ref(false)
const hasPendingReminder = ref(false)

// ─── 计算属性 ────────────────────────────────────────────
const isExpanded = computed(() => state.value === 'bubble' || state.value === 'panel' || state.value === 'chat')
const statusColor = computed(() => {
  if (hasPendingReminder.value) return 'var(--status-blue)'
  if (panelData.value.backendStatus === 'running') return 'var(--status-green)'
  return 'var(--status-gray)'
})

// ─── Tauri IPC ───────────────────────────────────────────
async function invokeCommand(cmd: string, args?: Record<string, unknown>) {
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    return await invoke(cmd, args)
  } catch {
    console.warn(`Tauri invoke 不可用: ${cmd}`)
  }
}

async function listenEvent(event: string, handler: (payload: unknown) => void) {
  try {
    const { listen } = await import('@tauri-apps/api/event')
    return await listen(event, (e) => handler(e.payload))
  } catch {
    console.warn(`Tauri listen 不可用: ${event}`)
  }
}

// ─── 后端通信 ────────────────────────────────────────────
async function getBackendPort(): Promise<number> {
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    return await invoke('get_backend_port') as number
  } catch {
    return 8080
  }
}

async function submitFeedback(notificationId: string, feedbackType: FeedbackType) {
  try {
    const port = await getBackendPort()
    await fetch(`http://localhost:${port}/api/notifications/${notificationId}/reminder-feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feedbackType }),
    })
  } catch (err) {
    console.error('提交反馈失败:', err)
  }
}

// ─── 状态切换 ────────────────────────────────────────────
async function switchMode(mode: FloatState) {
  state.value = mode
  // idle 和 soft 共用小窗口尺寸
  const tauriMode = mode === 'soft' ? 'idle' : mode === 'panel' ? 'bubble' : mode
  await invokeCommand('resize_float_window', { mode: tauriMode })
}

// ─── 单击处理 ────────────────────────────────────────────
function handleClick() {
  if (state.value === 'idle' || state.value === 'soft') {
    if (hasPendingReminder.value && reminder.value) {
      showBubble(reminder.value)
    } else {
      switchMode('panel')
    }
  }
}

// ─── 双击处理 ────────────────────────────────────────────
function handleDoubleClick() {
  if (state.value === 'chat') {
    switchMode('idle')
  } else {
    switchMode('chat')
  }
}

// ─── 气泡展示 ────────────────────────────────────────────
function showBubble(data: ReminderData) {
  reminder.value = data
  hasPendingReminder.value = false
  switchMode('bubble')
  clearAutoDismiss()
  // NORMAL_PUSH: 10 秒后自动收起（hover 时暂停）
  startAutoDismiss(10000)
}

function startAutoDismiss(ms: number) {
  autoDismissTimer.value = setTimeout(() => {
    if (!isHovering.value) {
      dismissToIdle()
    } else {
      // hover 中，延迟重试
      startAutoDismiss(3000)
    }
  }, ms)
}

function dismissToIdle() {
  clearAutoDismiss()
  reminder.value = null
  switchMode('idle')
}

function clearAutoDismiss() {
  if (autoDismissTimer.value) {
    clearTimeout(autoDismissTimer.value)
    autoDismissTimer.value = null
  }
}

// ─── 反馈处理 ────────────────────────────────────────────
async function handleFeedback(type: FeedbackType) {
  if (!reminder.value) return
  await submitFeedback(reminder.value.notificationId, type)
  dismissToIdle()
}

// ─── 拖拽 ────────────────────────────────────────────────
async function startDrag(e: MouseEvent) {
  // 只响应左键
  if (e.button !== 0) return
  try {
    const { getCurrentWindow } = await import('@tauri-apps/api/window')
    await getCurrentWindow().startDragging()
  } catch { /* 非 Tauri 环境 */ }
}

// ─── 快捷面板 ────────────────────────────────────────────
function openChat() {
  switchMode('chat')
}

function openSettings() {
  // 打开主窗口设置页面
  invokeCommand('show_reminder_bubble', {
    notificationId: '', title: '', content: '', pushLevel: ''
  })
}

// ─── 对话 ────────────────────────────────────────────────
async function sendChatMessage() {
  const text = chatInput.value.trim()
  if (!text) return
  chatMessages.value.push({ role: 'user', text })
  chatInput.value = ''
  try {
    const port = await getBackendPort()
    const resp = await fetch(`http://localhost:${port}/api/chat/quick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text }),
    })
    const data = await resp.json()
    chatMessages.value.push({ role: 'assistant', text: data.data?.reply ?? '抱歉，暂时无法回复' })
  } catch {
    chatMessages.value.push({ role: 'assistant', text: '网络异常，请稍后重试' })
  }
}

// ─── 生命周期 ────────────────────────────────────────────
let unlistenBubble: (() => void) | undefined
let unlistenDismiss: (() => void) | undefined

onMounted(async () => {
  const unlisten1 = await listenEvent('reminder-bubble', (payload) => {
    const data = payload as ReminderData
    if (data.pushLevel === 'SOFT_PUSH') {
      // 轻提醒：不展开，只标记状态
      reminder.value = data
      hasPendingReminder.value = true
      if (state.value === 'idle') state.value = 'soft'
    } else {
      showBubble(data)
    }
  })
  if (unlisten1) unlistenBubble = unlisten1 as () => void

  const unlisten2 = await listenEvent('reminder-dismiss', () => dismissToIdle())
  if (unlisten2) unlistenDismiss = unlisten2 as () => void

  // 定期检查后端状态
  setInterval(async () => {
    try {
      const running = await invokeCommand('is_backend_running') as boolean
      panelData.value.backendStatus = running ? 'running' : 'stopped'
    } catch {
      panelData.value.backendStatus = 'unknown'
    }
  }, 10000)
})

onUnmounted(() => {
  clearAutoDismiss()
  unlistenBubble?.()
  unlistenDismiss?.()
})
</script>

<template>
  <div class="float-root">
    <!-- ═══ idle / soft：小圆点 ═══ -->
    <div
      v-if="!isExpanded"
      class="float-orb"
      :class="{ 'float-orb--soft': state === 'soft' }"
      @mousedown="startDrag"
      @click.prevent="handleClick"
      @dblclick.prevent="handleDoubleClick"
      @mouseenter="isHovering = true"
      @mouseleave="isHovering = false"
    >
      <!-- 知微 logo -->
      <svg class="float-orb__icon" viewBox="0 0 32 32" fill="none">
        <circle cx="16" cy="16" r="11" stroke="var(--orb-icon)" stroke-width="1.5" fill="none" />
        <circle cx="13" cy="14.5" r="1.2" fill="var(--orb-icon)" />
        <circle cx="19" cy="14.5" r="1.2" fill="var(--orb-icon)" />
        <path d="M12.5 19.5C13.5 21 15 21.5 16 21.5C17 21.5 18.5 21 19.5 19.5"
              stroke="var(--orb-icon)" stroke-width="1.2" stroke-linecap="round" fill="none" />
      </svg>
      <!-- 状态指示点 -->
      <div class="float-orb__status" :style="{ background: statusColor }" />
    </div>

    <!-- ═══ bubble：提醒气泡 ═══ -->
    <div
      v-if="state === 'bubble' && reminder"
      class="float-card float-card--bubble"
      @mouseenter="isHovering = true"
      @mouseleave="isHovering = false"
    >
      <div class="float-card__header" @mousedown="startDrag">
        <span class="float-card__badge">主动提醒</span>
        <button class="float-card__close" @click="dismissToIdle">&times;</button>
      </div>
      <div class="float-card__body">
        {{ reminder.content }}
      </div>
      <div class="float-card__actions">
        <button class="float-pill float-pill--primary" @click="handleFeedback('ACTED')">
          有用
        </button>
        <button class="float-pill" @click="handleFeedback('SNOOZED')">
          知道了
        </button>
        <button class="float-pill float-pill--danger" @click="handleFeedback('NOT_RELEVANT')">
          不需要
        </button>
      </div>
    </div>

    <!-- ═══ panel：快捷面板 ═══ -->
    <div
      v-if="state === 'panel'"
      class="float-card float-card--panel"
    >
      <div class="float-card__header" @mousedown="startDrag">
        <span class="float-card__title">知微 · {{ panelData.backendStatus === 'running' ? '运行中' : '已断开' }}</span>
        <button class="float-card__close" @click="switchMode('idle')">&times;</button>
      </div>
      <div class="float-panel__list">
        <div class="float-panel__item">
          <span>📋</span><span>今日提醒 {{ panelData.todayReminders }} 条</span>
        </div>
        <div v-if="panelData.trackingCount > 0" class="float-panel__item">
          <span>📦</span><span>追踪中 {{ panelData.trackingCount }} 项</span>
        </div>
        <div v-if="panelData.weather" class="float-panel__item">
          <span>🌤️</span><span>{{ panelData.weather }}</span>
        </div>
      </div>
      <div class="float-panel__footer">
        <button class="float-panel__action" @click="openChat">💬 打开对话</button>
      </div>
    </div>

    <!-- ═══ chat：迷你对话 ═══ -->
    <div v-if="state === 'chat'" class="float-card float-card--chat">
      <div class="float-card__header float-card__header--drag" @mousedown="startDrag">
        <span class="float-card__title">知微助手</span>
        <button class="float-card__close" @click="switchMode('idle')">&times;</button>
      </div>
      <div class="float-chat__messages">
        <div v-if="chatMessages.length === 0" class="float-chat__empty">
          有什么可以帮你的吗？
        </div>
        <div
          v-for="(msg, i) in chatMessages"
          :key="i"
          class="float-chat__msg"
          :class="`float-chat__msg--${msg.role}`"
        >
          {{ msg.text }}
        </div>
      </div>
      <div class="float-chat__input-wrap">
        <input
          v-model="chatInput"
          class="float-chat__input"
          placeholder="输入消息..."
          @keydown.enter="sendChatMessage"
        />
        <button class="float-chat__send" @click="sendChatMessage">
          <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
            <path d="M2.94 5.34l13.69 4.56a.5.5 0 010 .95L2.94 15.41a.5.5 0 01-.68-.56l1.3-4.48a.5.5 0 01.4-.37l5.6-.75a.25.25 0 000-.5l-5.6-.75a.5.5 0 01-.4-.37l-1.3-4.48a.5.5 0 01.68-.56z" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style>
/* ─── CSS 变量 ─────────────────────────────────────────── */
:root {
  --orb-bg: #ffffff;
  --card-bg: #ffffff;
  --shadow-idle: 0 2px 8px rgba(0, 0, 0, 0.15), 0 0 0 1px rgba(0, 0, 0, 0.04);
  --shadow-hover: 0 4px 16px rgba(0, 0, 0, 0.2), 0 0 0 1px rgba(0, 0, 0, 0.06);
  --shadow-card: 0 8px 28px rgba(0, 0, 0, 0.14), 0 0 0 1px rgba(0, 0, 0, 0.04);
  --primary: hsl(224 78% 56%);
  --primary-fg: #ffffff;
  --danger: hsl(2 72% 58%);
  --fg: hsl(221 28% 11%);
  --fg-muted: hsl(220 10% 46%);
  --border: hsl(220 16% 90%);
  --bg-hover: hsl(220 20% 96%);
  --orb-icon: hsl(224 60% 48%);
  --status-green: hsl(142 60% 48%);
  --status-blue: hsl(224 78% 56%);
  --status-gray: hsl(220 10% 70%);
  --spring: cubic-bezier(0.34, 1.56, 0.64, 1);
  --ease: cubic-bezier(0.22, 1, 0.36, 1);
  --radius: 14px;
  --font: 'Segoe UI Variable', 'SF Pro Display', 'Noto Sans SC', system-ui, sans-serif;
}

@media (prefers-color-scheme: dark) {
  :root {
    --orb-bg: hsl(220 16% 18%);
    --card-bg: hsl(220 16% 16%);
    --shadow-idle: 0 2px 8px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255, 255, 255, 0.05);
    --shadow-hover: 0 4px 16px rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.08);
    --shadow-card: 0 8px 28px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255, 255, 255, 0.06);
    --fg: hsl(220 16% 90%);
    --fg-muted: hsl(220 10% 58%);
    --border: hsl(220 12% 24%);
    --bg-hover: hsl(220 14% 22%);
    --orb-icon: hsl(224 68% 72%);
  }
}

/* ─── 全局 ─────────────────────────────────────────────── */
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { background: transparent; overflow: hidden; font-family: var(--font); }

.float-root {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
  -webkit-user-select: none;
}

/* ─── 小圆点（idle / soft） ────────────────────────────── */
.float-orb {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  background: var(--orb-bg);
  box-shadow: var(--shadow-idle);
  transition: transform 0.2s var(--ease), box-shadow 0.2s var(--ease);
}

.float-orb:hover {
  transform: scale(1.08);
  box-shadow: var(--shadow-hover);
}

.float-orb:active {
  cursor: grabbing;
  transform: scale(1.02);
}

.float-orb__icon {
  width: 28px;
  height: 28px;
}

.float-orb__status {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid var(--orb-bg);
  transition: background 0.3s var(--ease);
}

/* 轻提醒光晕 */
.float-orb--soft {
  animation: soft-glow 2.5s ease-in-out infinite;
}

@keyframes soft-glow {
  0%, 100% {
    box-shadow: var(--shadow-idle), 0 0 0 0 transparent;
  }
  50% {
    box-shadow: var(--shadow-idle), 0 0 16px 3px color-mix(in srgb, var(--primary) 30%, transparent);
  }
}

/* ─── 卡片通用 ─────────────────────────────────────────── */
.float-card {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
  border-radius: var(--radius);
  box-shadow: var(--shadow-card);
  overflow: hidden;
  animation: card-enter 0.3s var(--spring);
  color: var(--fg);
  font-size: 13px;
  line-height: 1.5;
}

@keyframes card-enter {
  from { opacity: 0; transform: scale(0.92) translateY(6px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.float-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  cursor: default;
}

.float-card__header--drag {
  cursor: grab;
}
.float-card__header--drag:active {
  cursor: grabbing;
}

.float-card__badge {
  font-size: 11px;
  font-weight: 600;
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  padding: 2px 8px;
  border-radius: 10px;
}

.float-card__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg);
}

.float-card__close {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--fg-muted);
  cursor: pointer;
  border-radius: 6px;
  font-size: 15px;
  line-height: 1;
  transition: background 0.15s, color 0.15s;
}

.float-card__close:hover {
  background: var(--bg-hover);
  color: var(--fg);
}

/* ─── 气泡 ─────────────────────────────────────────────── */
.float-card__body {
  flex: 1;
  padding: 10px 14px;
  color: var(--fg);
  overflow-y: auto;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.float-card__actions {
  display: flex;
  gap: 6px;
  padding: 8px 14px 10px;
  border-top: 1px solid var(--border);
}

.float-pill {
  flex: 1;
  padding: 6px 0;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: transparent;
  color: var(--fg);
  font-size: 12px;
  font-family: var(--font);
  cursor: pointer;
  transition: all 0.15s var(--ease);
}

.float-pill:hover {
  background: var(--bg-hover);
}

.float-pill--primary:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 6%, transparent);
}

.float-pill--danger:hover {
  border-color: var(--danger);
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 6%, transparent);
}

/* ─── 快捷面板 ─────────────────────────────────────────── */
.float-panel__list {
  flex: 1;
  padding: 6px 14px;
}

.float-panel__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  color: var(--fg);
  font-size: 12.5px;
}

.float-panel__footer {
  padding: 6px 14px 10px;
  border-top: 1px solid var(--border);
}

.float-panel__action {
  width: 100%;
  padding: 7px 0;
  border: none;
  border-radius: 8px;
  background: var(--bg-hover);
  color: var(--fg);
  font-size: 12.5px;
  font-family: var(--font);
  cursor: pointer;
  transition: background 0.15s;
}

.float-panel__action:hover {
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
}

/* ─── 迷你对话 ─────────────────────────────────────────── */
.float-chat__messages {
  flex: 1;
  overflow-y: auto;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.float-chat__empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fg-muted);
  font-size: 13px;
}

.float-chat__msg {
  max-width: 82%;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.float-chat__msg--user {
  align-self: flex-end;
  background: var(--primary);
  color: var(--primary-fg);
  border-bottom-right-radius: 4px;
}

.float-chat__msg--assistant {
  align-self: flex-start;
  background: var(--bg-hover);
  color: var(--fg);
  border-bottom-left-radius: 4px;
}

.float-chat__input-wrap {
  display: flex;
  gap: 8px;
  padding: 8px 14px 10px;
  border-top: 1px solid var(--border);
}

.float-chat__input {
  flex: 1;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: transparent;
  color: var(--fg);
  font-size: 13px;
  font-family: var(--font);
  outline: none;
  transition: border-color 0.15s;
}

.float-chat__input:focus {
  border-color: var(--primary);
}

.float-chat__input::placeholder {
  color: var(--fg-muted);
}

.float-chat__send {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 10px;
  background: var(--primary);
  color: var(--primary-fg);
  cursor: pointer;
  transition: opacity 0.15s;
  flex-shrink: 0;
}

.float-chat__send:hover {
  opacity: 0.88;
}
</style>
