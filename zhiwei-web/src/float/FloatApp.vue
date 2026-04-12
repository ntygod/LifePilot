<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'

/**
 * 知微桌面浮窗 — 手绘小猫助手
 *
 * 状态：idle / bubble / chat
 * 小猫微动画：眨眼、呼吸、鼠标跟随、表情切换
 * 通知：对话气泡风格，毛玻璃 + slide-up 动画
 */

type FloatState = 'idle' | 'bubble' | 'chat'
type CatMood = 'normal' | 'happy' | 'surprised' | 'alert'
type FeedbackType = 'ACTED' | 'SNOOZED' | 'NOT_RELEVANT'

interface ReminderData {
  notificationId: string
  title: string
  content: string
  pushLevel: string
}

// ─── 状态 ────────────────────────────────────────────────
const state = ref<FloatState>('idle')
const reminder = ref<ReminderData | null>(null)
const chatMessages = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])
const chatInput = ref('')
const hasPending = ref(false)
const isHovering = ref(false)
const backendOk = ref(false)
let dismissTimer: ReturnType<typeof setTimeout> | null = null

// ─── 小猫状态 ────────────────────────────────────────────
const catMood = ref<CatMood>('normal')
const isBlinking = ref(false)
const pupilOffsetX = ref(0)
const pupilOffsetY = ref(0)
let blinkInterval: ReturnType<typeof setInterval> | null = null

const isExpanded = computed(() => state.value === 'bubble' || state.value === 'chat')
const statusDot = computed(() =>
  hasPending.value ? '#4B83F0' : backendOk.value ? '#34C759' : '#aaa'
)

// ─── Tauri IPC ───────────────────────────────────────────
async function invoke(cmd: string, args?: Record<string, unknown>) {
  try {
    const { invoke: tauriInvoke } = await import('@tauri-apps/api/core')
    return await tauriInvoke(cmd, args)
  } catch { /* 非 Tauri 环境 */ }
}

async function listen(event: string, handler: (payload: unknown) => void) {
  try {
    const { listen: tauriListen } = await import('@tauri-apps/api/event')
    return await tauriListen(event, (e) => handler(e.payload))
  } catch { /* 非 Tauri 环境 */ }
}

async function getPort(): Promise<number> {
  try {
    const { invoke: tauriInvoke } = await import('@tauri-apps/api/core')
    return await tauriInvoke('get_backend_port') as number
  } catch { return 8080 }
}

// ─── 拖拽 + 点击 ────────────────────────────────────────
const DRAG_THRESHOLD = 3
let pointerStart = { x: 0, y: 0 }
let dragging = false

function onBallPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  pointerStart = { x: e.clientX, y: e.clientY }
  dragging = false
  const onMove = (ev: PointerEvent) => {
    if (!dragging) {
      const dx = Math.abs(ev.clientX - pointerStart.x)
      const dy = Math.abs(ev.clientY - pointerStart.y)
      if (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD) {
        dragging = true
        setCatMood('surprised')
        import('@tauri-apps/api/webviewWindow').then(m =>
          m.getCurrentWebviewWindow().startDragging()
        ).catch(() => {})
      }
    }
  }
  const onUp = () => {
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
    if (dragging) {
      setTimeout(() => setCatMood('normal'), 500)
    } else {
      handleClick()
    }
  }
  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}

let lastClick = 0
function handleClick() {
  const now = Date.now()
  if (now - lastClick < 300) {
    // 双击 → 打开对话
    switchToChat()
    lastClick = 0
    return
  }
  lastClick = now
  setTimeout(() => {
    if (lastClick === 0) return
    // 单击
    if (hasPending.value && reminder.value) {
      showBubble(reminder.value)
    } else {
      // 无通知时单击只做表情反馈，不开对话
      setCatMood('happy')
      setTimeout(() => { if (catMood.value === 'happy') setCatMood('normal') }, 600)
    }
  }, 300)
}

// ─── 小猫动画 ────────────────────────────────────────────
function setCatMood(mood: CatMood) {
  catMood.value = mood
}

function startBlinking() {
  const blink = () => {
    isBlinking.value = true
    setTimeout(() => { isBlinking.value = false }, 150)
  }
  blinkInterval = setInterval(() => {
    if (catMood.value === 'normal' && !isHovering.value) blink()
  }, 4000 + Math.random() * 2000)
}

function onBallMouseMove(e: MouseEvent) {
  if (catMood.value !== 'normal' && catMood.value !== 'alert') return
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const dx = (e.clientX - cx) / rect.width
  const dy = (e.clientY - cy) / rect.height
  pupilOffsetX.value = dx * 1.8
  pupilOffsetY.value = dy * 1.5
}

function onBallMouseLeave() {
  isHovering.value = false
  pupilOffsetX.value = 0
  pupilOffsetY.value = 0
}

// ─── 状态切换 ────────────────────────────────────────────
async function switchToChat() {
  // 先 resize 窗口到对话尺寸，再切状态（避免对话面板被裁剪）
  await invoke('resize_float_window', { mode: 'chat' })
  state.value = 'chat'
}

function onBubbleAfterLeave() {
  if (state.value === 'bubble') {
    state.value = 'idle'
    invoke('resize_float_window', { mode: 'idle' })
    setCatMood('normal')
  }
}

function onChatAfterLeave() {
  invoke('resize_float_window', { mode: 'idle' })
}

// ─── 气泡 ────────────────────────────────────────────────
function showBubble(data: ReminderData) {
  reminder.value = data
  hasPending.value = false
  state.value = 'bubble'
  setCatMood('alert')
  clearDismiss()
  scheduleDismiss(10000)
}

function scheduleDismiss(ms: number) {
  dismissTimer = setTimeout(() => {
    if (isHovering.value) { scheduleDismiss(3000); return }
    dismissBubble()
  }, ms)
}

function dismissBubble() {
  clearDismiss()
  reminder.value = null // 触发 Transition leave
}

function clearDismiss() {
  if (dismissTimer) { clearTimeout(dismissTimer); dismissTimer = null }
}

// ─── 反馈 ────────────────────────────────────────────────
async function feedback(type: FeedbackType) {
  if (!reminder.value) return
  const port = await getPort()
  try {
    await fetch(`http://localhost:${port}/api/notifications/${reminder.value.notificationId}/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feedbackType: type }),
    })
  } catch { /* 静默 */ }
  setCatMood('happy')
  setTimeout(() => setCatMood('normal'), 600)
  dismissBubble()
}

// ─── 对话 ────────────────────────────────────────────────
const chatSessionId = ref<string | null>(null)

function closeChat() {
  // 只改状态触发退出动画，resize 在 onChatAfterLeave 中处理
  state.value = 'idle'
}

async function sendMsg() {
  const text = chatInput.value.trim()
  if (!text) return
  chatMessages.value.push({ role: 'user', text })
  chatInput.value = ''
  chatMessages.value.push({ role: 'assistant', text: '思考中...' })
  const thinkingIdx = chatMessages.value.length - 1

  try {
    const port = await getPort()
    const resp = await fetch(`http://localhost:${port}/api/chat/messages/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({
        content: text,
        sessionId: chatSessionId.value,
        action: 'SEND',
      }),
    })
    if (!resp.ok || !resp.body) {
      const errText = await resp.text().catch(() => '')
      chatMessages.value[thinkingIdx] = { role: 'assistant', text: `请求失败(${resp.status}): ${errText}`.slice(0, 200) }
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let fullText = ''
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const json = line.slice(5).trim()
        if (!json || json === '[DONE]') continue
        try {
          const event = JSON.parse(json)
          if (event.sessionId) chatSessionId.value = event.sessionId
          if (event.type === 'content' && event.content) {
            fullText += event.content
            chatMessages.value[thinkingIdx] = { role: 'assistant', text: fullText }
          } else if (event.type === 'done' && event.content) {
            fullText = event.content
            chatMessages.value[thinkingIdx] = { role: 'assistant', text: fullText }
          }
        } catch { /* 非 JSON 行 */ }
      }
    }
    if (!fullText) {
      chatMessages.value[thinkingIdx] = { role: 'assistant', text: '没有收到回复' }
    }
  } catch {
    chatMessages.value[thinkingIdx] = { role: 'assistant', text: '网络异常，请稍后重试' }
  }
}

// ─── SSE 通知直连 ───────────────────────────────────────
let notificationSource: EventSource | null = null

async function connectNotificationStream() {
  // 等待后端就绪（端口 > 0）
  let port = 0
  for (let i = 0; i < 30; i++) {
    port = await getPort()
    if (port > 0) break
    await new Promise(r => setTimeout(r, 2000))
  }
  if (port <= 0) return

  const url = `http://localhost:${port}/api/notifications/stream?userId=default`
  console.log('[浮窗] SSE 通知流连接:', url)
  notificationSource = new EventSource(url)

  notificationSource.addEventListener('notification', (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data)
      if (data.type === 'unread-count-snapshot') return

      const typeId = data.typeId as string | undefined
      if (typeId === 'proactive_reminder' || typeId === 'clipboard_intent') {
        const contentJson = data.contentJson as string
        const content = parseContentSummary(contentJson)
        const metadata = data.metadataJson ? JSON.parse(data.metadataJson) : {}
        const title = resolveTitle(typeId, metadata)
        console.log('[浮窗] 收到通知:', typeId, title)
        // 先 resize 到气泡尺寸
        invoke('resize_float_window', { mode: 'bubble' }).then(() => {
          showBubble({ notificationId: data.id, title, content, pushLevel: 'NORMAL_PUSH' })
        })
      }
    } catch (e) { console.error('[浮窗] 通知处理异常:', e) }
  })

  notificationSource.addEventListener('open', () => {
    backendOk.value = true
    console.log('[浮窗] SSE 通知流已连接')
  })
  notificationSource.onerror = () => {
    backendOk.value = false
    notificationSource?.close()
    notificationSource = null
    setTimeout(connectNotificationStream, 5000)
  }
}

/** 从 contentJson 提取摘要文本 */
function parseContentSummary(contentJson: string): string {
  try {
    const parsed = JSON.parse(contentJson)
    if (parsed.type === 'TEXT') return parsed.text ?? contentJson
    if (parsed.type === 'CARD') return [parsed.title, parsed.body].filter(Boolean).join(' — ')
    if (parsed.type === 'MARKDOWN') return (parsed.markdown ?? '').replace(/[#*_`>\[\]()]/g, '').slice(0, 100)
    return contentJson
  } catch { return contentJson }
}

/** 根据通知类型生成标题 */
function resolveTitle(typeId: string, metadata: Record<string, string>): string {
  if (typeId === 'clipboard_intent') {
    const labels: Record<string, string> = {
      TRACKING_NUMBER: '快递查询', FLIGHT_NUMBER: '航班查询', TRAIN_NUMBER: '车次查询',
    }
    return labels[metadata.intentType ?? ''] ?? '剪贴板识别'
  }
  return metadata.topicKey ?? '主动提醒'
}

// ─── 生命周期 ────────────────────────────────────────────
let cleanups: Array<() => void> = []

onMounted(async () => {
  // 仍保留 Tauri 事件监听（向后兼容主窗口 invoke 链路）
  const u1 = await listen('reminder-bubble', (p) => {
    const d = p as ReminderData
    showBubble(d)
  })
  if (u1) cleanups.push(u1 as () => void)

  const u2 = await listen('reminder-dismiss', () => {
    dismissBubble()
  })
  if (u2) cleanups.push(u2 as () => void)

  await invoke('resize_float_window', { mode: 'idle' })
  try {
    const { getCurrentWebviewWindow } = await import('@tauri-apps/api/webviewWindow')
    await getCurrentWebviewWindow().show()
  } catch {}

  startBlinking()

  // 浮窗直连 SSE 通知流（不依赖主窗口 invoke 转发）
  connectNotificationStream()
})

onUnmounted(() => {
  clearDismiss()
  if (blinkInterval) clearInterval(blinkInterval)
  notificationSource?.close()
  cleanups.forEach(f => f())
})
</script>

<template>
  <div class="float-root">
    <!-- ═══ 小猫浮球 ═══ -->
    <div
      v-show="state !== 'chat'"
      class="ball"
      :class="{ 'ball--glow': hasPending && state === 'idle' }"
      @pointerdown="onBallPointerDown"
      @mouseenter="isHovering = true"
      @mousemove="onBallMouseMove"
      @mouseleave="onBallMouseLeave"
    >
      <!-- 手绘小猫 SVG -->
      <svg
        class="cat"
        :class="[`cat--${catMood}`, { 'cat--blink': isBlinking, 'cat--alert': state === 'bubble' }]"
        viewBox="0 0 40 40"
        fill="none"
      >
        <!-- 身体/头部 — 手绘风圆形（微微不规则） -->
        <path
          class="cat__head"
          d="M20 34 C11 34 5 28 5 20 C5 12 11 6 20 6 C29 6 35 12 35 20 C35 28 29 34 20 34Z"
        />

        <!-- 左耳 -->
        <path class="cat__ear" d="M9.5 12 C8 5.5 12 4 14.5 9"/>
        <!-- 右耳 -->
        <path class="cat__ear" d="M30.5 12 C32 5.5 28 4 25.5 9"/>
        <!-- 左耳内 -->
        <path class="cat__ear-inner" d="M10.5 10.5 C10 7 12.5 6 13.8 9"/>
        <!-- 右耳内 -->
        <path class="cat__ear-inner" d="M29.5 10.5 C30 7 27.5 6 26.2 9"/>

        <!-- 左眼白 -->
        <ellipse class="cat__eye-white" cx="14.5" cy="18.5" rx="3.8" ry="4"/>
        <!-- 右眼白 -->
        <ellipse class="cat__eye-white" cx="25.5" cy="18.5" rx="3.8" ry="4"/>

        <!-- 左瞳孔（跟随鼠标） -->
        <circle
          class="cat__pupil"
          :cx="14.5 + pupilOffsetX"
          :cy="18.5 + pupilOffsetY"
          r="2"
        />
        <!-- 右瞳孔 -->
        <circle
          class="cat__pupil"
          :cx="25.5 + pupilOffsetX"
          :cy="18.5 + pupilOffsetY"
          r="2"
        />

        <!-- 左眼高光 -->
        <circle class="cat__highlight" :cx="15.3 + pupilOffsetX * 0.5" :cy="17.5 + pupilOffsetY * 0.5" r="0.9"/>
        <!-- 右眼高光 -->
        <circle class="cat__highlight" :cx="26.3 + pupilOffsetX * 0.5" :cy="17.5 + pupilOffsetY * 0.5" r="0.9"/>

        <!-- 眨眼覆盖层（闭眼时显示） -->
        <ellipse v-if="isBlinking" class="cat__eye-closed" cx="14.5" cy="18.5" rx="3.8" ry="4"/>
        <ellipse v-if="isBlinking" class="cat__eye-closed" cx="25.5" cy="18.5" rx="3.8" ry="4"/>
        <path v-if="isBlinking" class="cat__eye-line" d="M10.7 18.5 Q14.5 20.5 18.3 18.5"/>
        <path v-if="isBlinking" class="cat__eye-line" d="M21.7 18.5 Q25.5 20.5 29.3 18.5"/>

        <!-- 鼻子 -->
        <path class="cat__nose" d="M19 23 L20 24.5 L21 23Z"/>

        <!-- 嘴巴 — 根据表情切换 -->
        <path v-if="catMood === 'normal' || catMood === 'alert'" class="cat__mouth" d="M17 25.5 Q20 27.5 23 25.5"/>
        <path v-if="catMood === 'happy'" class="cat__mouth" d="M16 25 Q20 29 24 25"/>
        <ellipse v-if="catMood === 'surprised'" class="cat__mouth-o" cx="20" cy="26.5" rx="2" ry="2.5"/>

        <!-- 胡须 -->
        <line class="cat__whisker" x1="3" y1="20" x2="10" y2="21.5"/>
        <line class="cat__whisker" x1="3" y1="23" x2="10" y2="23.5"/>
        <line class="cat__whisker" x1="30" y1="21.5" x2="37" y2="20"/>
        <line class="cat__whisker" x1="30" y1="23.5" x2="37" y2="23"/>
      </svg>

      <!-- 状态点 -->
      <i class="ball__dot" :style="{ background: statusDot }"/>
    </div>

    <!-- ═══ 通知气泡 ═══ -->
    <Transition name="bubble" @after-leave="onBubbleAfterLeave">
      <div
        v-if="state === 'bubble' && reminder"
        class="bubble"
        @mouseenter="isHovering = true"
        @mouseleave="isHovering = false"
      >
        <div class="bubble__head">
          <span class="bubble__badge">{{ reminder.title || '主动提醒' }}</span>
          <button class="bubble__close" @click="dismissBubble">&times;</button>
        </div>
        <p class="bubble__text">{{ reminder.content }}</p>
        <div class="bubble__actions">
          <button class="bubble__btn bubble__btn--ok" @click="feedback('ACTED')">有用</button>
          <button class="bubble__btn" @click="feedback('SNOOZED')">知道了</button>
          <button class="bubble__btn bubble__btn--no" @click="feedback('NOT_RELEVANT')">不需要</button>
        </div>
      </div>
    </Transition>

    <!-- ═══ 对话 ═══ -->
    <Transition name="chat" @after-leave="onChatAfterLeave">
      <div v-if="state === 'chat'" class="chat">
        <header class="chat__head" @pointerdown="onBallPointerDown">
          <span class="chat__title">知微</span>
          <button class="chat__close" @click="closeChat">&times;</button>
        </header>
        <div class="chat__msgs">
          <p v-if="!chatMessages.length" class="chat__empty">有什么可以帮你的？</p>
          <div
            v-for="(m, i) in chatMessages" :key="i"
            class="chat__msg" :class="`chat__msg--${m.role}`"
          >{{ m.text }}</div>
        </div>
        <div class="chat__bar">
          <input
            v-model="chatInput" class="chat__input" placeholder="输入消息..."
            @keydown.enter="sendMsg"
          />
          <button class="chat__send" @click="sendMsg">
            <svg viewBox="0 0 20 20" fill="currentColor" width="15" height="15">
              <path d="M2.94 5.34l13.69 4.56a.5.5 0 010 .95L2.94 15.41a.5.5 0 01-.68-.56l1.3-4.48a.5.5 0 01.4-.37l5.6-.75a.25.25 0 000-.5l-5.6-.75a.5.5 0 01-.4-.37l-1.3-4.48a.5.5 0 01.68-.56z"/>
            </svg>
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style>
/* ─── 变量 ─────────────────────────────────────────────── */
:root {
  --bg: #fff;
  --fg: #1a1a2e;
  --fg2: #888;
  --bdr: #eaeaef;
  --hover: #f5f5f8;
  --blue: #5B8DEF;
  --blue-bg: #EDF2FE;
  --red: #E5484D;
  --glass: rgba(255,255,255,0.88);
  --glass-bdr: rgba(0,0,0,0.06);
  --shadow: 0 2px 10px rgba(0,0,0,0.1), 0 0 0 1px rgba(0,0,0,0.04);
  --shadow-lg: 0 8px 32px rgba(0,0,0,0.12), 0 0 0 1px rgba(0,0,0,0.04);
  --spring: cubic-bezier(.34,1.56,.64,1);
  --ease: cubic-bezier(.22,1,.36,1);
  --font: 'Segoe UI Variable','SF Pro','Noto Sans SC',system-ui,sans-serif;
  --cat-stroke: #5a5a6e;
  --cat-fill: #faf5f0;
  --cat-ear: #f0ccc0;
  --cat-nose: #e8a0a0;
  --cat-pupil: #2d2d3a;
}
@media(prefers-color-scheme:dark){:root{
  --bg:#1e1e24; --fg:#e8e8ec; --fg2:#777; --bdr:#2e2e36; --hover:#282830;
  --glass:rgba(30,30,36,0.88); --glass-bdr:rgba(255,255,255,0.08);
  --shadow:0 2px 10px rgba(0,0,0,0.3),0 0 0 1px rgba(255,255,255,0.06);
  --shadow-lg:0 8px 32px rgba(0,0,0,0.4),0 0 0 1px rgba(255,255,255,0.06);
  --cat-stroke:#b0b0c0; --cat-fill:#2a2a35; --cat-ear:#4a3a35; --cat-nose:#c08080; --cat-pupil:#e0e0e8;
}}

*{margin:0;padding:0;box-sizing:border-box}
html,body{background:transparent;overflow:hidden;font-family:var(--font);color:var(--fg);font-size:13px;height:100%}

.float-root{
  width:100%;height:100%;position:relative;
  display:flex;flex-direction:column;justify-content:flex-end;align-items:flex-end;
  user-select:none;-webkit-user-select:none;
}

/* ─── 浮球 ─────────────────────────────────────────────── */
.ball{
  width:52px;height:52px;border-radius:50%;cursor:pointer;
  display:flex;align-items:center;justify-content:center;position:relative;
  background:transparent;
  filter:drop-shadow(0 2px 6px rgba(0,0,0,.15));
  transition:transform .2s var(--ease),filter .2s var(--ease);
  z-index:10;
}
.ball:hover{transform:scale(1.08);filter:drop-shadow(0 4px 12px rgba(0,0,0,.2))}
.ball:active{transform:scale(.96)}
.ball__dot{
  position:absolute;bottom:1px;right:1px;width:9px;height:9px;
  border-radius:50%;border:2px solid var(--cat-fill);pointer-events:none;
  transition:background .3s;
}
.ball--glow{animation:ball-glow 2.5s ease-in-out infinite}
@keyframes ball-glow{
  0%,100%{filter:drop-shadow(0 2px 6px rgba(0,0,0,.15))}
  50%{filter:drop-shadow(0 2px 6px rgba(0,0,0,.15)) drop-shadow(0 0 10px rgba(91,141,239,.35))}
}

/* ─── 小猫 SVG ─────────────────────────────────────────── */
.cat{width:38px;height:38px;pointer-events:none;animation:cat-breathe 3.5s ease-in-out infinite}
@keyframes cat-breathe{
  0%,100%{transform:translateY(0)}
  50%{transform:translateY(-1px)}
}

.cat__head{fill:var(--cat-fill);stroke:var(--cat-stroke);stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round}
.cat__ear{fill:none;stroke:var(--cat-stroke);stroke-width:1.3;stroke-linecap:round}
.cat__ear-inner{fill:var(--cat-ear);stroke:none}
.cat__eye-white{fill:#fff;stroke:var(--cat-stroke);stroke-width:.8}
.cat__pupil{fill:var(--cat-pupil);transition:cx .15s ease,cy .15s ease}
.cat__highlight{fill:#fff}
.cat__eye-closed{fill:var(--cat-fill);stroke:none}
.cat__eye-line{fill:none;stroke:var(--cat-stroke);stroke-width:1;stroke-linecap:round}
.cat__nose{fill:var(--cat-nose);stroke:none}
.cat__mouth{fill:none;stroke:var(--cat-stroke);stroke-width:.9;stroke-linecap:round}
.cat__mouth-o{fill:none;stroke:var(--cat-stroke);stroke-width:.9}
.cat__whisker{stroke:var(--cat-stroke);stroke-width:.6;stroke-linecap:round;opacity:.5}

/* 猫表情状态 */
.cat--alert{animation:cat-breathe 3.5s ease-in-out infinite,cat-alert .3s var(--spring)}
@keyframes cat-alert{from{transform:rotate(0)}50%{transform:rotate(-5deg)}to{transform:rotate(0)}}

.cat--happy .cat__eye-white,.cat--happy .cat__pupil,.cat--happy .cat__highlight{opacity:0}
.cat--happy::after{content:'';/* 闭眼弧线由 blink 覆盖 */}

.cat--surprised .cat__pupil{r:1.4}

/* ─── 通知气泡 ─────────────────────────────────────────── */
.bubble{
  position:absolute;bottom:64px;right:0;width:300px;
  background:var(--bg);
  border:1px solid var(--bdr);
  border-radius:16px;box-shadow:var(--shadow-lg);
  padding:14px 16px;display:flex;flex-direction:column;gap:10px;
  z-index:5;
}
/* 气泡尖角 */
.bubble::after{
  content:'';position:absolute;bottom:-6px;right:20px;
  width:12px;height:12px;background:var(--bg);
  border-right:1px solid var(--bdr);border-bottom:1px solid var(--bdr);
  transform:rotate(45deg);border-radius:0 0 3px 0;
  box-shadow:3px 3px 6px rgba(0,0,0,.04);
}
.bubble__head{display:flex;align-items:center;justify-content:space-between}
.bubble__badge{
  font-size:11px;font-weight:600;color:var(--blue);
  background:var(--blue-bg);padding:2px 10px;border-radius:10px;
}
.bubble__close{
  width:20px;height:20px;display:flex;align-items:center;justify-content:center;
  border:0;background:0;color:var(--fg2);cursor:pointer;border-radius:6px;font-size:14px;
}
.bubble__close:hover{background:var(--hover);color:var(--fg)}
.bubble__text{font-size:13.5px;line-height:1.65;overflow-y:auto;max-height:80px}
.bubble__actions{display:flex;gap:6px}
.bubble__btn{
  flex:1;padding:6px 0;border:1px solid var(--bdr);border-radius:20px;
  background:0;color:var(--fg);font-size:12px;font-family:var(--font);cursor:pointer;transition:all .15s;
}
.bubble__btn:hover{background:var(--hover)}
.bubble__btn--ok:hover{border-color:var(--blue);color:var(--blue);background:var(--blue-bg)}
.bubble__btn--no:hover{border-color:var(--red);color:var(--red)}

/* 气泡入场/退场动画 */
.bubble-enter-active{animation:bubble-in .3s var(--spring) forwards}
.bubble-leave-active{animation:bubble-out .2s var(--ease) forwards}
@keyframes bubble-in{from{opacity:0;transform:translateY(12px) scale(.95)}to{opacity:1;transform:none}}
@keyframes bubble-out{from{opacity:1;transform:none}to{opacity:0;transform:translateY(8px) scale(.97)}}

/* ─── 对话面板 ─────────────────────────────────────────── */
.chat{
  position:absolute;inset:0;display:flex;flex-direction:column;
  background:var(--bg);
  border:1px solid var(--bdr);
  border-radius:16px;box-shadow:var(--shadow-lg);overflow:hidden;z-index:20;
}
.chat__head{
  display:flex;align-items:center;justify-content:space-between;
  padding:10px 14px;border-bottom:1px solid var(--bdr);cursor:grab;
}
.chat__head:active{cursor:grabbing}
.chat__title{font-size:14px;font-weight:600}
.chat__close{
  width:22px;height:22px;display:flex;align-items:center;justify-content:center;
  border:0;background:0;color:var(--fg2);cursor:pointer;border-radius:6px;font-size:16px;
}
.chat__close:hover{background:var(--hover);color:var(--fg)}
.chat__msgs{flex:1;overflow-y:auto;padding:12px 14px;display:flex;flex-direction:column;gap:10px}
.chat__empty{flex:1;display:flex;align-items:center;justify-content:center;color:var(--fg2);font-size:13px}
.chat__msg{max-width:82%;padding:10px 14px;line-height:1.55;word-break:break-word;font-size:13px}
.chat__msg--user{
  align-self:flex-end;background:var(--blue);color:#fff;
  border-radius:16px 16px 4px 16px;
}
.chat__msg--assistant{
  align-self:flex-start;background:var(--hover);
  border-radius:16px 16px 16px 4px;
}
.chat__bar{display:flex;gap:8px;padding:10px 14px;border-top:1px solid var(--bdr)}
.chat__input{
  flex:1;padding:8px 12px;border:1px solid var(--bdr);border-radius:12px;
  background:0;color:var(--fg);font-size:13px;font-family:var(--font);outline:0;
  transition:border .15s;
}
.chat__input:focus{border-color:var(--blue)}
.chat__input::placeholder{color:var(--fg2)}
.chat__send{
  width:32px;height:32px;display:flex;align-items:center;justify-content:center;
  border:0;border-radius:10px;background:var(--blue);color:#fff;cursor:pointer;flex-shrink:0;
  transition:opacity .15s;
}
.chat__send:hover{opacity:.85}

/* 对话入场/退场动画 */
.chat-enter-active{animation:chat-in .35s var(--spring) forwards}
.chat-leave-active{animation:chat-out .2s var(--ease) forwards}
@keyframes chat-in{from{opacity:0;transform:translateY(20px) scale(.92);transform-origin:bottom right}to{opacity:1;transform:none}}
@keyframes chat-out{from{opacity:1;transform:none}to{opacity:0;transform:translateY(12px) scale(.95)}}
</style>
