<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'

/**
 * 浮窗主组件 — 知微桌面端主动提醒投递终端
 *
 * 四种状态：
 * - idle: 64x64 小圆点图标，可拖拽
 * - glow: 圆点加呼吸光效动画（有新提醒待展示）
 * - bubble: 展开为卡片，显示提醒文字 + 反馈按钮
 * - chat: 展开为 320x480 迷你对话窗口
 */

// ─── 类型定义 ────────────────────────────────────────────
type FloatState = 'idle' | 'glow' | 'bubble' | 'chat'
type PushLevel = 'SOFT_PUSH' | 'NORMAL_PUSH' | 'URGENT_PUSH'
type FeedbackType = 'ACTED' | 'SNOOZED' | 'NOT_RELEVANT'

interface ReminderData {
  notificationId: string
  title: string
  content: string
  pushLevel: PushLevel
}

// ─── 状态 ────────────────────────────────────────────────
const state = ref<FloatState>('idle')
const reminder = ref<ReminderData | null>(null)
const chatMessages = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])
const chatInput = ref('')
const autoDismissTimer = ref<ReturnType<typeof setTimeout> | null>(null)

// ─── 计算属性 ────────────────────────────────────────────
const isExpanded = computed(() => state.value === 'bubble' || state.value === 'chat')

// ─── Tauri IPC ───────────────────────────────────────────
/** 动态导入 Tauri API（仅在 Tauri 环境下可用） */
async function invokeCommand(cmd: string, args?: Record<string, unknown>) {
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    return await invoke(cmd, args)
  } catch {
    console.warn(`Tauri invoke 不可用: ${cmd}`)
  }
}

/** 监听 Tauri 事件 */
async function listenEvent(event: string, handler: (payload: unknown) => void) {
  try {
    const { listen } = await import('@tauri-apps/api/event')
    return await listen(event, (e) => handler(e.payload))
  } catch {
    console.warn(`Tauri listen 不可用: ${event}`)
  }
}

// ─── 后端 API ────────────────────────────────────────────
/** 获取后端端口（通过 Tauri command） */
async function getBackendPort(): Promise<number> {
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    return await invoke('get_backend_port') as number
  } catch {
    return 8080
  }
}

/** 提交提醒反馈 */
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
/** 调用 Rust 端调整窗口尺寸 */
async function switchMode(mode: FloatState) {
  state.value = mode
  await invokeCommand('resize_float_window', { mode })
}

/** 展示气泡 */
function showBubble(data: ReminderData) {
  reminder.value = data
  switchMode('bubble')

  // 自动收起逻辑
  clearAutoDismiss()
  if (data.pushLevel === 'SOFT_PUSH') {
    // SOFT_PUSH: 3 秒后自动回到 idle
    autoDismissTimer.value = setTimeout(() => {
      dismissBubble()
    }, 3000)
  }
  // NORMAL_PUSH: 保持到用户交互
  // URGENT_PUSH: 保持到用户交互
}

/** 收起气泡 */
async function dismissBubble() {
  clearAutoDismiss()
  reminder.value = null
  await switchMode('idle')
}

/** 清除自动收起定时器 */
function clearAutoDismiss() {
  if (autoDismissTimer.value) {
    clearTimeout(autoDismissTimer.value)
    autoDismissTimer.value = null
  }
}

// ─── 反馈按钮处理 ────────────────────────────────────────
async function handleFeedback(type: FeedbackType) {
  if (!reminder.value) return
  const id = reminder.value.notificationId
  await submitFeedback(id, type)
  dismissBubble()
}

// ─── 双击打开对话 ──────────────────────────────────────
function handleDoubleClick() {
  if (state.value === 'chat') {
    switchMode('idle')
  } else {
    switchMode('chat')
  }
}

// ─── 拖拽支持 ────────────────────────────────────────────
async function startDrag() {
  try {
    const { getCurrentWindow } = await import('@tauri-apps/api/window')
    await getCurrentWindow().startDragging()
  } catch {
    // 非 Tauri 环境忽略
  }
}

// ─── 迷你对话（简化版） ──────────────────────────────────
async function sendChatMessage() {
  const text = chatInput.value.trim()
  if (!text) return

  chatMessages.value.push({ role: 'user', text })
  chatInput.value = ''

  // 调用后端对话 API
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

function closeChat() {
  chatMessages.value = []
  switchMode('idle')
}

// ─── 生命周期 ────────────────────────────────────────────
let unlistenBubble: (() => void) | undefined
let unlistenDismiss: (() => void) | undefined

onMounted(async () => {
  // 监听 Rust 端发来的气泡事件
  const unlisten1 = await listenEvent('reminder-bubble', (payload) => {
    const data = payload as ReminderData
    showBubble(data)
  })
  if (unlisten1) unlistenBubble = unlisten1 as () => void

  const unlisten2 = await listenEvent('reminder-dismiss', () => {
    dismissBubble()
  })
  if (unlisten2) unlistenDismiss = unlisten2 as () => void
})

onUnmounted(() => {
  clearAutoDismiss()
  unlistenBubble?.()
  unlistenDismiss?.()
})

// 状态变化时输出日志
watch(state, (newState) => {
  console.log(`浮窗状态切换: ${newState}`)
})
</script>

<template>
  <div class="float-root">
    <!-- idle / glow 状态：小圆点 -->
    <div
      v-if="!isExpanded"
      class="float-dot"
      :class="{ 'float-dot--glow': state === 'glow' }"
      @mousedown="startDrag"
      @dblclick="handleDoubleClick"
    >
      <div class="float-dot__icon">
        <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
          <circle cx="16" cy="16" r="14" fill="var(--primary, hsl(224 78% 56%))" />
          <path
            d="M10 16.5C10 13.5 12.5 11 16 11C19.5 11 22 13.5 22 16.5C22 18.5 20.5 20 19 21L17 22.5V23H15V22L13 20.5C11.5 19.5 10 18 10 16.5Z"
            fill="white"
            opacity="0.9"
          />
          <circle cx="14" cy="15.5" r="1.2" fill="var(--primary, hsl(224 78% 56%))" />
          <circle cx="18" cy="15.5" r="1.2" fill="var(--primary, hsl(224 78% 56%))" />
        </svg>
      </div>
    </div>

    <!-- bubble 状态：提醒气泡卡片 -->
    <div v-if="state === 'bubble' && reminder" class="float-bubble">
      <div class="float-bubble__header">
        <span class="float-bubble__title">{{ reminder.title }}</span>
        <button class="float-bubble__close" @click="dismissBubble">&times;</button>
      </div>
      <div class="float-bubble__content">
        {{ reminder.content }}
      </div>
      <div class="float-bubble__actions">
        <button
          class="float-bubble__btn float-bubble__btn--acted"
          @click="handleFeedback('ACTED')"
        >
          <span class="float-bubble__btn-icon">&#x1F44D;</span>
          <span>有用</span>
        </button>
        <button
          class="float-bubble__btn float-bubble__btn--snoozed"
          @click="handleFeedback('SNOOZED')"
        >
          <span class="float-bubble__btn-icon">&#x1F44B;</span>
          <span>知道了</span>
        </button>
        <button
          class="float-bubble__btn float-bubble__btn--dismiss"
          @click="handleFeedback('NOT_RELEVANT')"
        >
          <span class="float-bubble__btn-icon">&#x2715;</span>
          <span>不需要</span>
        </button>
      </div>
    </div>

    <!-- chat 状态：迷你对话窗口 -->
    <div v-if="state === 'chat'" class="float-chat">
      <div class="float-chat__header">
        <span class="float-chat__title">知微助手</span>
        <button class="float-chat__close" @click="closeChat">&times;</button>
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
      <div class="float-chat__input-area">
        <input
          v-model="chatInput"
          class="float-chat__input"
          placeholder="输入消息..."
          @keydown.enter="sendChatMessage"
        />
        <button class="float-chat__send" @click="sendChatMessage">
          <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
            <path d="M2.94 5.34l13.69 4.56a.5.5 0 010 .95L2.94 15.41a.5.5 0 01-.68-.56l1.3-4.48a.5.5 0 01.4-.37l5.6-.75a.25.25 0 000-.5l-5.6-.75a.5.5 0 01-.4-.37l-1.3-4.48a.5.5 0 01.68-.56z" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ─── 根容器 ─────────────────────────────────────────── */
.float-root {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
  -webkit-user-select: none;
}

/* ─── 小圆点（idle / glow） ─────────────────────────── */
.float-dot {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  cursor: grab;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--card, hsl(210 22% 99%));
  box-shadow:
    0 2px 8px rgba(0, 0, 0, 0.12),
    0 0 0 1px rgba(0, 0, 0, 0.04);
  transition: box-shadow 0.3s var(--ease-fluid, cubic-bezier(0.22, 1, 0.36, 1));
}

.float-dot:hover {
  box-shadow:
    0 4px 16px rgba(0, 0, 0, 0.16),
    0 0 0 1px rgba(0, 0, 0, 0.06);
}

.float-dot:active {
  cursor: grabbing;
}

.float-dot__icon {
  width: 32px;
  height: 32px;
}

.float-dot__icon svg {
  width: 100%;
  height: 100%;
}

/* 呼吸光效动画 */
.float-dot--glow {
  animation: glow-pulse 2s ease-in-out infinite;
}

@keyframes glow-pulse {
  0%, 100% {
    box-shadow:
      0 2px 8px rgba(0, 0, 0, 0.12),
      0 0 0 1px rgba(0, 0, 0, 0.04),
      0 0 0 0 var(--primary, hsl(224 78% 56%));
  }
  50% {
    box-shadow:
      0 2px 8px rgba(0, 0, 0, 0.12),
      0 0 0 1px rgba(0, 0, 0, 0.04),
      0 0 16px 4px color-mix(in srgb, var(--primary, hsl(224 78% 56%)) 40%, transparent);
  }
}

/* ─── 气泡卡片（bubble） ────────────────────────────── */
.float-bubble {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--card, hsl(210 22% 99%));
  border-radius: var(--radius, 0.9rem);
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.14),
    0 0 0 1px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  animation: bubble-enter 0.3s var(--ease-fluid, cubic-bezier(0.22, 1, 0.36, 1));
}

@keyframes bubble-enter {
  from {
    opacity: 0;
    transform: scale(0.9) translateY(8px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.float-bubble__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 0.875rem;
  border-bottom: 1px solid var(--border, hsl(220 16% 84%));
}

.float-bubble__title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--foreground, hsl(221 28% 11%));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.float-bubble__close {
  width: 1.5rem;
  height: 1.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--muted-foreground, hsl(220 10% 41%));
  cursor: pointer;
  border-radius: 0.25rem;
  font-size: 1.1rem;
  line-height: 1;
  flex-shrink: 0;
}

.float-bubble__close:hover {
  background: var(--accent, hsl(221 40% 93%));
  color: var(--foreground, hsl(221 28% 11%));
}

.float-bubble__content {
  flex: 1;
  padding: 0.5rem 0.875rem;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: var(--foreground, hsl(221 28% 11%));
  overflow-y: auto;
}

.float-bubble__actions {
  display: flex;
  gap: 0.25rem;
  padding: 0.5rem 0.875rem;
  border-top: 1px solid var(--border, hsl(220 16% 84%));
}

.float-bubble__btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  padding: 0.375rem 0;
  border: 1px solid var(--border, hsl(220 16% 84%));
  border-radius: 0.5rem;
  background: transparent;
  color: var(--foreground, hsl(221 28% 11%));
  font-size: 0.75rem;
  cursor: pointer;
  transition: all 0.15s ease;
}

.float-bubble__btn:hover {
  background: var(--accent, hsl(221 40% 93%));
}

.float-bubble__btn--acted:hover {
  border-color: var(--primary, hsl(224 78% 56%));
  color: var(--primary, hsl(224 78% 56%));
}

.float-bubble__btn--dismiss:hover {
  border-color: var(--destructive, hsl(2 78% 58%));
  color: var(--destructive, hsl(2 78% 58%));
}

.float-bubble__btn-icon {
  font-size: 0.875rem;
}

/* ─── 迷你对话（chat） ──────────────────────────────── */
.float-chat {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--card, hsl(210 22% 99%));
  border-radius: var(--radius, 0.9rem);
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.14),
    0 0 0 1px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  animation: bubble-enter 0.3s var(--ease-fluid, cubic-bezier(0.22, 1, 0.36, 1));
}

.float-chat__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 0.875rem;
  border-bottom: 1px solid var(--border, hsl(220 16% 84%));
  cursor: grab;
}

.float-chat__title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--foreground, hsl(221 28% 11%));
}

.float-chat__close {
  width: 1.5rem;
  height: 1.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--muted-foreground, hsl(220 10% 41%));
  cursor: pointer;
  border-radius: 0.25rem;
  font-size: 1.1rem;
  line-height: 1;
}

.float-chat__close:hover {
  background: var(--accent, hsl(221 40% 93%));
  color: var(--foreground, hsl(221 28% 11%));
}

.float-chat__messages {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem 0.875rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.float-chat__empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--muted-foreground, hsl(220 10% 41%));
  font-size: 0.875rem;
}

.float-chat__msg {
  max-width: 85%;
  padding: 0.5rem 0.875rem;
  border-radius: 0.75rem;
  font-size: 0.8125rem;
  line-height: 1.5;
  word-break: break-word;
}

.float-chat__msg--user {
  align-self: flex-end;
  background: var(--primary, hsl(224 78% 56%));
  color: var(--primary-foreground, hsl(0 0% 100%));
  border-bottom-right-radius: 0.25rem;
}

.float-chat__msg--assistant {
  align-self: flex-start;
  background: var(--secondary, hsl(220 20% 95%));
  color: var(--secondary-foreground, hsl(220 21% 18%));
  border-bottom-left-radius: 0.25rem;
}

.float-chat__input-area {
  display: flex;
  gap: 0.5rem;
  padding: 0.5rem 0.875rem;
  border-top: 1px solid var(--border, hsl(220 16% 84%));
}

.float-chat__input {
  flex: 1;
  padding: 0.375rem 0.5rem;
  border: 1px solid var(--input, hsl(220 16% 88%));
  border-radius: 0.5rem;
  background: transparent;
  color: var(--foreground, hsl(221 28% 11%));
  font-size: 0.8125rem;
  outline: none;
  transition: border-color 0.15s ease;
}

.float-chat__input:focus {
  border-color: var(--ring, hsl(224 78% 56%));
}

.float-chat__input::placeholder {
  color: var(--muted-foreground, hsl(220 10% 41%));
}

.float-chat__send {
  width: 2rem;
  height: 2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 0.5rem;
  background: var(--primary, hsl(224 78% 56%));
  color: var(--primary-foreground, hsl(0 0% 100%));
  cursor: pointer;
  transition: opacity 0.15s ease;
  flex-shrink: 0;
}

.float-chat__send:hover {
  opacity: 0.9;
}
</style>
