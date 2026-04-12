<script setup lang="ts">
import { ref, onBeforeUnmount, onMounted, onUpdated, watch } from 'vue'
import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import { highlightCode } from '@/lib/highlight'
import { injectCopyButtons } from '@/utils/codeBlockCopy'

const props = defineProps<{
  content: string
  streaming?: boolean
}>()

const proseRef = ref<HTMLElement | null>(null)

const markedInstance = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code: string, lang: string) {
      return highlightCode(code, lang)
    }
  })
)

/* ---- Markdown 节流渲染 ----
 * 流式输出时，token 以 ~24ms 间隔到达并触发 content 变更。
 * 如果每次变更都做全量 markdown 解析 + v-html 替换 DOM，
 * 60fps 的布局重排是抖动的主要来源。
 *
 * 方案：leading + trailing 节流，首次变更立即解析，
 * 后续变更在 PARSE_INTERVAL_MS 内合并为一次 DOM 更新。
 */
const html = ref('')
let parseTimer: ReturnType<typeof setTimeout> | null = null
let lastParseTs = 0
const PARSE_INTERVAL_MS = 100

function safeParseMarkdown(text: string): string {
  if (!text) return ''
  try {
    return markedInstance.parse(text) as string
  } catch {
    // 流式输出时尝试修复未闭合的代码块
    let fixed = text
    if ((fixed.match(/```/g) || []).length % 2 !== 0) {
      fixed += '\n```'
    }
    try {
      return markedInstance.parse(fixed) as string
    } catch {
      return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
        .replace(/\n/g, '<br>')
    }
  }
}

function clearParseTimer() {
  if (parseTimer !== null) {
    clearTimeout(parseTimer)
    parseTimer = null
  }
}

/** 立即解析并渲染 markdown */
function flushMarkdown() {
  clearParseTimer()
  lastParseTs = Date.now()
  html.value = safeParseMarkdown(props.content)
}

/** leading + trailing 节流：首次立即触发，后续按间隔合并 */
function scheduleMarkdownParse() {
  const now = Date.now()
  const elapsed = now - lastParseTs

  // leading edge：距上次解析已超过间隔，立即执行
  if (elapsed >= PARSE_INTERVAL_MS) {
    lastParseTs = now
    html.value = safeParseMarkdown(props.content)
    return
  }

  // trailing edge：在剩余时间后执行
  if (parseTimer !== null) return
  parseTimer = setTimeout(() => {
    parseTimer = null
    lastParseTs = Date.now()
    html.value = safeParseMarkdown(props.content)
  }, PARSE_INTERVAL_MS - elapsed)
}

/* ---- 生命周期 ---- */
function tryInjectCopyButtons() {
  if (proseRef.value) {
    injectCopyButtons(proseRef.value)
  }
}

onMounted(tryInjectCopyButtons)
onUpdated(tryInjectCopyButtons)
onBeforeUnmount(clearParseTimer)

/* ---- 响应式监听 ---- */
watch(() => props.content, () => {
  if (props.streaming) {
    scheduleMarkdownParse()
  } else {
    flushMarkdown()
  }
}, { immediate: true })

watch(() => props.streaming, (streaming) => {
  if (!streaming) {
    // 流结束时立即做最终渲染
    flushMarkdown()
  }
})
</script>

<template>
  <div class="streaming-shell">
    <div
      ref="proseRef"
      class="message-prose prose max-w-none dark:prose-invert"
      :class="streaming && 'streaming-prose'"
      :aria-live="streaming ? 'polite' : undefined"
      v-html="html"
    />
    <Transition name="caret-fade">
      <span v-if="streaming" class="streaming-caret" />
    </Transition>
  </div>
</template>

<style scoped>
.streaming-shell {
  position: relative;
}

.message-prose {
  color: hsl(from var(--foreground) h s l / 0.92);
}

.message-prose :deep(> :first-child) {
  margin-top: 0;
}

.message-prose :deep(> :last-child) {
  margin-bottom: 0;
}

.message-prose :deep(h1),
.message-prose :deep(h2),
.message-prose :deep(h3),
.message-prose :deep(h4) {
  margin-top: 1.2rem;
  margin-bottom: 0.65rem;
  color: var(--foreground);
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.025em;
}

.message-prose :deep(h1) {
  font-size: 1.24rem;
  font-family: var(--font-sans);
}

.message-prose :deep(h2) {
  padding-bottom: 0.3rem;
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.44);
  font-size: 1.08rem;
}

.message-prose :deep(h3) {
  font-size: 0.98rem;
}

.message-prose :deep(h4) {
  font-size: 0.9rem;
  color: hsl(from var(--foreground) h s l / 0.8);
}

.message-prose :deep(p) {
  margin-top: 0.58rem;
  margin-bottom: 0.58rem;
  line-height: 1.82;
  color: hsl(from var(--foreground) h s l / 0.88);
}

.message-prose :deep(strong) {
  color: var(--foreground);
  font-weight: 700;
}

.message-prose :deep(a) {
  color: hsl(from var(--primary) h s l / 0.94);
  text-decoration: underline;
  text-decoration-color: hsl(from var(--primary) h s l / 0.32);
  text-underline-offset: 0.18rem;
}

.message-prose :deep(a:hover) {
  text-decoration-color: hsl(from var(--primary) h s l / 0.56);
}

.message-prose :deep(ul),
.message-prose :deep(ol) {
  margin: 0.7rem 0;
  padding-left: 1.15rem;
  color: hsl(from var(--foreground) h s l / 0.88);
}

.message-prose :deep(li) {
  margin: 0.34rem 0;
  padding-left: 0.2rem;
  line-height: 1.76;
}

.message-prose :deep(ul > li::marker),
.message-prose :deep(ol > li::marker) {
  color: hsl(from var(--primary) h s l / 0.72);
  font-weight: 700;
}

.message-prose :deep(blockquote) {
  margin: 0.9rem 0;
  border-left: 3px solid hsl(from var(--primary) h s l / 0.28);
  border-radius: 0 0.9rem 0.9rem 0;
  background: hsl(from var(--accent) h s l / 0.42);
  padding: 0.8rem 1rem;
  color: hsl(from var(--foreground) h s l / 0.76);
}

.message-prose :deep(hr) {
  margin: 1.1rem 0;
  border: none;
  height: 1px;
  background: hsl(from var(--border) h s l / 0.72);
}

.message-prose :deep(code:not(.hljs)) {
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  border-radius: 0.45rem;
  background: hsl(from var(--accent) h s l / 0.44);
  padding: 0.16rem 0.42rem;
  font-size: 0.84em;
  color: hsl(from var(--primary) h s l / 0.88);
}

.message-prose :deep(pre) {
  position: relative;
  overflow-x: auto;
  margin: 0.95rem 0;
  border: 1px solid hsl(219 21% 25% / 0.82);
  border-radius: 0.95rem;
  background: linear-gradient(180deg, hsl(222 23% 15%), hsl(223 21% 12%));
  padding: 2rem 1rem 0.95rem;
  box-shadow: 0 18px 28px -30px hsl(var(--shadow-color) / 0.2);
}

.message-prose :deep(pre code.hljs) {
  display: block;
  overflow-x: auto;
  background: transparent;
  padding: 0;
  font-size: 0.82rem;
  line-height: 1.72;
  color: hsl(210 34% 92%);
}

.message-prose :deep(.code-block-toolbar) {
  position: absolute;
  top: 0.55rem;
  right: 0.75rem;
  left: 0.75rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  pointer-events: none;
}

.message-prose :deep(.code-lang-label) {
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1;
  color: hsl(210 18% 56%);
  letter-spacing: 0.02em;
  user-select: none;
}

.message-prose :deep(.code-copy-btn) {
  border: 1px solid hsl(216 14% 34% / 0.9);
  border-radius: 999px;
  background: hsl(220 17% 19% / 0.94);
  padding: 0.26rem 0.56rem;
  color: hsl(210 18% 86%);
  font-size: 10px;
  line-height: 1.1;
  opacity: 0;
  pointer-events: auto;
  cursor: pointer;
  box-shadow: inset 0 1px 0 hsl(0 0% 100% / 0.04);
  transition: opacity 150ms ease;
}

.message-prose :deep(pre:hover .code-copy-btn) {
  opacity: 1;
}

.message-prose :deep(table) {
  width: 100%;
  margin: 0.95rem 0;
  border-collapse: separate;
  border-spacing: 0;
  overflow: hidden;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  border-radius: 1rem;
  background: hsl(from var(--card) h s l / 0.74);
}

.message-prose :deep(th),
.message-prose :deep(td) {
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.34);
  padding: 0.7rem 0.8rem;
  text-align: left;
  vertical-align: top;
}

.message-prose :deep(th) {
  background: hsl(from var(--background) h s l / 0.82);
  font-size: 0.76rem;
  font-weight: 700;
  color: hsl(from var(--foreground) h s l / 0.84);
}

.message-prose :deep(tr:last-child td) {
  border-bottom: none;
}

.message-prose :deep(img) {
  margin: 0.9rem 0;
  border-radius: 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  box-shadow: 0 18px 30px -34px hsl(var(--shadow-color) / 0.18);
}

.message-prose :deep(.hljs-comment),
.message-prose :deep(.hljs-quote) {
  color: hsl(215 16% 62%);
}

.message-prose :deep(.hljs-keyword),
.message-prose :deep(.hljs-selector-tag),
.message-prose :deep(.hljs-literal),
.message-prose :deep(.hljs-section),
.message-prose :deep(.hljs-link) {
  color: hsl(190 72% 69%);
}

.message-prose :deep(.hljs-string),
.message-prose :deep(.hljs-title),
.message-prose :deep(.hljs-name),
.message-prose :deep(.hljs-attribute) {
  color: hsl(152 60% 66%);
}

.message-prose :deep(.hljs-number),
.message-prose :deep(.hljs-symbol),
.message-prose :deep(.hljs-bullet),
.message-prose :deep(.hljs-variable) {
  color: hsl(37 82% 70%);
}

.message-prose :deep(.hljs-built_in),
.message-prose :deep(.hljs-type),
.message-prose :deep(.hljs-class .hljs-title),
.message-prose :deep(.hljs-function .hljs-title) {
  color: hsl(214 86% 74%);
}

.message-prose :deep(.hljs-meta),
.message-prose :deep(.hljs-tag) {
  color: hsl(280 60% 74%);
}

.streaming-prose {
  position: relative;
  contain: layout;
}

.streaming-prose::after {
  content: "";
  position: absolute;
  right: -0.5rem;
  bottom: -0.35rem;
  width: 5rem;
  height: 1.2rem;
  pointer-events: none;
  background: radial-gradient(circle, hsl(from var(--primary) h s l / 0.18), transparent 72%);
  filter: blur(14px);
  animation: streaming-glow-drift 2.1s var(--ease-fluid) infinite;
}

.streaming-caret {
  position: relative;
  display: inline-flex;
  width: 0.55rem;
  height: 1.15rem;
  margin-left: 0.35rem;
  vertical-align: -0.18rem;
}

.streaming-caret::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.92);
  box-shadow:
    0 0 0 1px hsl(from var(--primary) h s l / 0.16),
    0 0 1rem hsl(from var(--primary) h s l / 0.34);
  animation:
    streaming-caret-blink 1.05s steps(1, end) infinite,
    streaming-caret-breathe 1.4s var(--ease-fluid) infinite;
}

@keyframes streaming-caret-blink {
  0%,
  48% {
    opacity: 1;
  }

  52%,
  100% {
    opacity: 0;
  }
}

@keyframes streaming-caret-breathe {
  0%,
  100% {
    transform: translateY(0) scaleY(0.92);
  }

  50% {
    transform: translateY(-1px) scaleY(1.04);
  }
}

@keyframes streaming-glow-drift {
  0%,
  100% {
    opacity: 0.55;
    transform: translateX(0);
  }

  50% {
    opacity: 0.9;
    transform: translateX(0.18rem);
  }
}

/* 光标退出动画 */
.caret-fade-leave-active {
  transition:
    opacity 250ms ease-out,
    transform 250ms ease-out;
}

.caret-fade-leave-to {
  opacity: 0;
  transform: scale(0.3);
}

/* glow 同步渐隐：流式结束后 prose 的 ::after 辉光淡出 */
.streaming-shell:not(:has(.streaming-caret)) .streaming-prose::after {
  transition: opacity 300ms ease-out;
  opacity: 0;
}
</style>
