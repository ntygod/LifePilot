<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'

/**
 * 知微桌面浮窗
 *
 * 状态：idle / soft / bubble / panel / chat
 * 透明方案：transparent + !decorations + !shadow（Tauri v2 三要素）
 * 拖拽方案：pointer 事件 + 3px 位移阈值区分 click 和 drag
 */

type FloatState = 'idle' | 'soft' | 'bubble' | 'panel' | 'chat'
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
const todayReminders = ref(0)
const memoryCount = ref(0)
let dismissTimer: ReturnType<typeof setTimeout> | null = null

const isExpanded = computed(() => ['bubble', 'panel', 'chat'].includes(state.value))
const statusDot = computed(() =>
  hasPending.value ? '#4B83F0' : backendOk.value ? '#34C759' : '#999'
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

// ─── 拖拽 + 点击（3px 阈值区分）────────────────────────
const DRAG_THRESHOLD = 3
let pointerStart = { x: 0, y: 0 }
let dragging = false

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  pointerStart = { x: e.clientX, y: e.clientY }
  dragging = false
  const onMove = (ev: PointerEvent) => {
    if (!dragging) {
      const dx = Math.abs(ev.clientX - pointerStart.x)
      const dy = Math.abs(ev.clientY - pointerStart.y)
      if (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD) {
        dragging = true
        import('@tauri-apps/api/webviewWindow').then(m =>
          m.getCurrentWebviewWindow().startDragging()
        ).catch(() => {})
      }
    }
  }
  const onUp = () => {
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
    if (!dragging) handleClick()
  }
  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}

let lastClick = 0
function handleClick() {
  const now = Date.now()
  if (now - lastClick < 300) {
    // 双击 → 对话
    switchTo('chat')
    lastClick = 0
    return
  }
  lastClick = now
  setTimeout(() => {
    if (lastClick === 0) return // 已被双击消费
    if (state.value === 'idle' || state.value === 'soft') {
      if (hasPending.value && reminder.value) {
        showBubble(reminder.value)
      } else {
        switchTo('panel')
      }
    } else {
      switchTo('idle')
    }
  }, 300)
}

// ─── 状态切换 ────────────────────────────────────────────
async function switchTo(mode: FloatState) {
  state.value = mode
  const tauriMode = mode === 'soft' ? 'idle' : mode === 'panel' ? 'bubble' : mode
  await invoke('resize_float_window', { mode: tauriMode })
  if (mode === 'panel') fetchPanelData()
}

async function fetchPanelData() {
  try {
    const port = await getPort()
    const resp = await fetch(`http://localhost:${port}/api/notifications?userId=default&page=0&size=1`)
    if (resp.ok) {
      const data = await resp.json()
      todayReminders.value = data.total ?? 0
    }
  } catch { /* 静默 */ }
}

// ─── 气泡 ────────────────────────────────────────────────
function showBubble(data: ReminderData) {
  reminder.value = data
  hasPending.value = false
  switchTo('bubble')
  clearDismiss()
  scheduleDismiss(10000)
}

function scheduleDismiss(ms: number) {
  dismissTimer = setTimeout(() => {
    if (isHovering.value) { scheduleDismiss(3000); return }
    switchTo('idle')
    reminder.value = null
  }, ms)
}

function clearDismiss() {
  if (dismissTimer) { clearTimeout(dismissTimer); dismissTimer = null }
}

// ─── 反馈 ────────────────────────────────────────────────
async function feedback(type: FeedbackType) {
  if (!reminder.value) return
  const port = await getPort()
  try {
    await fetch(`http://localhost:${port}/api/notifications/${reminder.value.notificationId}/reminder-feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feedbackType: type }),
    })
  } catch {}
  clearDismiss()
  reminder.value = null
  switchTo('idle')
}

// ─── 对话 ────────────────────────────────────────────────
const chatSessionId = ref<string | null>(null)

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
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        content: text,
        sessionId: chatSessionId.value,
      }),
    })
    if (!resp.ok || !resp.body) {
      const err = await resp.text().catch(() => '未知错误')
      chatMessages.value[thinkingIdx] = { role: 'assistant', text: `请求失败: ${resp.status}` }
      console.error('对话请求失败:', resp.status, err)
      return
    }

    // 读取 SSE 流
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
          // 提取 sessionId
          if (event.sessionId) chatSessionId.value = event.sessionId
          // 提取文本内容
          if (event.type === 'content' && event.content) {
            fullText += event.content
            chatMessages.value[thinkingIdx] = { role: 'assistant', text: fullText }
          } else if (event.type === 'done' && event.content) {
            fullText = event.content
            chatMessages.value[thinkingIdx] = { role: 'assistant', text: fullText }
          }
        } catch { /* 非 JSON 行，跳过 */ }
      }
    }

    if (!fullText) {
      chatMessages.value[thinkingIdx] = { role: 'assistant', text: '没有收到回复' }
    }
  } catch (e) {
    chatMessages.value[thinkingIdx] = { role: 'assistant', text: '网络异常，请稍后重试' }
    console.error('对话异常:', e)
  }
}

// ─── 生命周期 ────────────────────────────────────────────
let cleanups: Array<() => void> = []

onMounted(async () => {
  // 监听提醒事件
  const u1 = await listen('reminder-bubble', (p) => {
    const d = p as ReminderData
    if (d.pushLevel === 'SOFT_PUSH') {
      reminder.value = d; hasPending.value = true
      if (state.value === 'idle') state.value = 'soft'
    } else {
      showBubble(d)
    }
  })
  if (u1) cleanups.push(u1 as () => void)

  const u2 = await listen('reminder-dismiss', () => {
    clearDismiss(); reminder.value = null; switchTo('idle')
  })
  if (u2) cleanups.push(u2 as () => void)

  // 前端就绪，通知 Rust 显示浮窗
  await invoke('resize_float_window', { mode: 'idle' })
  try {
    const { getCurrentWebviewWindow } = await import('@tauri-apps/api/webviewWindow')
    await getCurrentWebviewWindow().show()
  } catch {}

  // 心跳
  setInterval(async () => {
    try { backendOk.value = (await invoke('is_backend_running')) as boolean }
    catch { backendOk.value = false }
  }, 10000)
})

onUnmounted(() => { clearDismiss(); cleanups.forEach(f => f()) })
</script>

<template>
  <div class="root">
    <!-- ═══ 浮球 ═══ -->
    <div
      v-if="!isExpanded"
      class="ball"
      :class="{ 'ball--soft': state === 'soft' }"
      @pointerdown="onPointerDown"
      @mouseenter="isHovering = true"
      @mouseleave="isHovering = false"
    >
      <svg class="ball__face" viewBox="0 0 32 32" fill="none">
        <circle cx="16" cy="16" r="11" stroke="var(--icon)" stroke-width="1.5" fill="none"/>
        <circle cx="13" cy="14.5" r="1.3" fill="var(--icon)"/>
        <circle cx="19" cy="14.5" r="1.3" fill="var(--icon)"/>
        <path d="M12.5 19.5C13.5 21 15 21.5 16 21.5C17 21.5 18.5 21 19.5 19.5"
              stroke="var(--icon)" stroke-width="1.3" stroke-linecap="round" fill="none"/>
      </svg>
      <i class="ball__dot" :style="{ background: statusDot }"/>
    </div>

    <!-- ═══ 气泡 ═══ -->
    <div v-if="state === 'bubble' && reminder" class="card"
         @mouseenter="isHovering = true" @mouseleave="isHovering = false">
      <header class="card__head">
        <span class="card__badge">主动提醒</span>
        <button class="card__x" @click="() => { clearDismiss(); reminder = null; switchTo('idle') }">&times;</button>
      </header>
      <p class="card__body">{{ reminder.content }}</p>
      <footer class="card__foot">
        <button class="pill pill--ok" @click="feedback('ACTED')">有用</button>
        <button class="pill" @click="feedback('SNOOZED')">知道了</button>
        <button class="pill pill--no" @click="feedback('NOT_RELEVANT')">不需要</button>
      </footer>
    </div>

    <!-- ═══ 快捷面板 ═══ -->
    <div v-if="state === 'panel'" class="card">
      <header class="card__head">
        <span class="card__title">知微 · {{ backendOk ? '运行中' : '已断开' }}</span>
        <button class="card__x" @click="switchTo('idle')">&times;</button>
      </header>
      <div class="panel__body">
        <div class="panel__row">
          <span class="panel__icon">🔔</span>
          <span>今日提醒 {{ todayReminders }} 条</span>
        </div>
        <div class="panel__row">
          <span class="panel__icon">📝</span>
          <span>记忆中有 {{ memoryCount }} 条记录</span>
        </div>
        <div class="panel__row panel__row--hint">
          <span class="panel__icon">💡</span>
          <span>双击浮球可打开对话</span>
        </div>
      </div>
      <footer class="card__foot">
        <button class="pill pill--ok" style="flex:1" @click="switchTo('chat')">💬 打开对话</button>
      </footer>
    </div>

    <!-- ═══ 对话 ═══ -->
    <div v-if="state === 'chat'" class="card card--chat">
      <header class="card__head card__head--drag" @pointerdown="onPointerDown">
        <span class="card__title">知微助手</span>
        <button class="card__x" @click="switchTo('idle')">&times;</button>
      </header>
      <div class="chat__msgs">
        <p v-if="!chatMessages.length" class="chat__empty">有什么可以帮你的吗？</p>
        <div v-for="(m, i) in chatMessages" :key="i"
             class="chat__msg" :class="`chat__msg--${m.role}`">{{ m.text }}</div>
      </div>
      <div class="chat__bar">
        <input v-model="chatInput" class="chat__input" placeholder="输入消息..."
               @keydown.enter="sendMsg"/>
        <button class="chat__send" @click="sendMsg">
          <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
            <path d="M2.94 5.34l13.69 4.56a.5.5 0 010 .95L2.94 15.41a.5.5 0 01-.68-.56l1.3-4.48a.5.5 0 01.4-.37l5.6-.75a.25.25 0 000-.5l-5.6-.75a.5.5 0 01-.4-.37l-1.3-4.48a.5.5 0 01.68-.56z"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style>
:root {
  --bg: #fff;
  --fg: #1a1a2e;
  --fg2: #666;
  --bdr: #e8e8ec;
  --hover: #f3f3f6;
  --icon: #4B6BCC;
  --blue: #4B83F0;
  --blue-bg: #EBF0FE;
  --red: #E5484D;
  --shadow: 0 2px 12px rgba(0,0,0,.12);
  --shadow-lg: 0 8px 28px rgba(0,0,0,.14);
  --spring: cubic-bezier(.34,1.56,.64,1);
  --ease: cubic-bezier(.22,1,.36,1);
  --font: 'Segoe UI Variable','SF Pro','Noto Sans SC',system-ui,sans-serif;
}
@media(prefers-color-scheme:dark){:root{
  --bg:#1e1e24;--fg:#e8e8ec;--fg2:#888;--bdr:#2e2e36;--hover:#282830;
  --icon:#8FAEF0;--blue-bg:#252840;--shadow:0 2px 12px rgba(0,0,0,.35);
  --shadow-lg:0 8px 28px rgba(0,0,0,.45);
}}

*{margin:0;padding:0;box-sizing:border-box}
html,body{background:transparent;overflow:hidden;font-family:var(--font);color:var(--fg);font-size:13px}

.root{width:100%;height:100%;display:flex;align-items:center;justify-content:center;user-select:none;-webkit-user-select:none}

/* ─── 浮球 ─────────────────────────────────────────────── */
.ball{
  width:48px;height:48px;border-radius:50%;cursor:pointer;
  display:flex;align-items:center;justify-content:center;position:relative;
  background:var(--bg);box-shadow:var(--shadow);
  transition:transform .2s var(--ease),box-shadow .2s var(--ease);
}
.ball:hover{transform:scale(1.06);box-shadow:0 4px 18px rgba(0,0,0,.16)}
.ball:active{transform:scale(.97)}
.ball__face{width:28px;height:28px;pointer-events:none}
.ball__dot{
  position:absolute;bottom:2px;right:2px;width:8px;height:8px;
  border-radius:50%;border:1.5px solid var(--bg);pointer-events:none;
  transition:background .3s;
}
.ball--soft{animation:glow 2.5s ease-in-out infinite}
@keyframes glow{
  0%,100%{box-shadow:var(--shadow),0 0 0 0 transparent}
  50%{box-shadow:var(--shadow),0 0 14px 3px rgba(75,131,240,.3)}
}

/* ─── 卡片通用 ─────────────────────────────────────────── */
.card{
  width:100%;height:100%;display:flex;flex-direction:column;
  background:var(--bg);border-radius:14px;box-shadow:var(--shadow-lg);overflow:hidden;
  animation:pop .25s var(--spring);
}
.card--chat{border-radius:14px}
@keyframes pop{from{opacity:0;transform:scale(.93) translateY(4px)}to{opacity:1;transform:none}}

.card__head{
  display:flex;align-items:center;justify-content:space-between;
  padding:10px 14px;border-bottom:1px solid var(--bdr);
}
.card__head--drag{cursor:grab}
.card__head--drag:active{cursor:grabbing}
.card__badge{font-size:11px;font-weight:600;color:var(--blue);background:var(--blue-bg);padding:2px 8px;border-radius:10px}
.card__title{font-size:13px;font-weight:600}
.card__x{
  width:22px;height:22px;display:flex;align-items:center;justify-content:center;
  border:0;background:0;color:var(--fg2);cursor:pointer;border-radius:6px;font-size:15px;
}
.card__x:hover{background:var(--hover);color:var(--fg)}
.card__body{flex:1;padding:10px 14px;line-height:1.6;overflow-y:auto}
.card__foot{display:flex;gap:6px;padding:8px 14px 10px;border-top:1px solid var(--bdr)}

.pill{
  flex:1;padding:6px 0;border:1px solid var(--bdr);border-radius:20px;
  background:0;color:var(--fg);font-size:12px;font-family:var(--font);cursor:pointer;transition:all .15s;
}
.pill:hover{background:var(--hover)}
.pill--ok:hover{border-color:var(--blue);color:var(--blue);background:var(--blue-bg)}
.pill--no:hover{border-color:var(--red);color:var(--red)}

/* ─── 面板 ─────────────────────────────────────────────── */
.panel__body{flex:1;padding:8px 14px}
.panel__row{display:flex;align-items:center;gap:8px;padding:6px 0;font-size:12.5px}
.panel__row--hint{color:var(--fg2);font-size:11.5px;opacity:.7}
.panel__icon{width:18px;text-align:center;font-size:13px}

/* ─── 对话 ─────────────────────────────────────────────── */
.chat__msgs{flex:1;overflow-y:auto;padding:10px 14px;display:flex;flex-direction:column;gap:8px}
.chat__empty{flex:1;display:flex;align-items:center;justify-content:center;color:var(--fg2)}
.chat__msg{max-width:82%;padding:8px 12px;border-radius:12px;line-height:1.5;word-break:break-word}
.chat__msg--user{align-self:flex-end;background:var(--blue);color:#fff;border-bottom-right-radius:4px}
.chat__msg--assistant{align-self:flex-start;background:var(--hover);border-bottom-left-radius:4px}
.chat__bar{display:flex;gap:8px;padding:8px 14px 10px;border-top:1px solid var(--bdr)}
.chat__input{
  flex:1;padding:7px 10px;border:1px solid var(--bdr);border-radius:10px;
  background:0;color:var(--fg);font-size:13px;font-family:var(--font);outline:0;transition:border .15s;
}
.chat__input:focus{border-color:var(--blue)}
.chat__input::placeholder{color:var(--fg2)}
.chat__send{
  width:30px;height:30px;display:flex;align-items:center;justify-content:center;
  border:0;border-radius:10px;background:var(--blue);color:#fff;cursor:pointer;flex-shrink:0;
}
.chat__send:hover{opacity:.88}
</style>
