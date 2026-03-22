<script setup lang="ts">
import { computed, ref, onUpdated, onMounted } from 'vue'
import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import { highlightCode } from '@/lib/highlight'
import { injectCopyButtons } from '@/utils/codeBlockCopy'

const props = defineProps<{
  content: string
  streaming?: boolean
}>()

// 容器引用，用于注入代码块复制按钮
const proseRef = ref<HTMLElement | null>(null)

function tryInjectCopyButtons() {
  if (proseRef.value) {
    injectCopyButtons(proseRef.value)
  }
}

// 首次挂载 + 每次 DOM 更新后注入（幂等）
onMounted(tryInjectCopyButtons)
onUpdated(tryInjectCopyButtons)

// 使用 marked-highlight 扩展集成 highlight.js
const markedInstance = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code: string, lang: string) {
      return highlightCode(code, lang)
    }
  })
)

const html = computed(() => {
  if (!props.content) return ''
  try {
    // 流式传输时，Markdown 可能不完整（如代码块未闭合）
    // 尝试解析，如果失败则返回原始文本（避免显示错误）
    const parsed = markedInstance.parse(props.content) as string
    return parsed
  } catch (e) {
    // 解析失败时，如果是流式传输，尝试修复常见的未闭合标记
    if (props.streaming) {
      // 尝试修复未闭合的代码块
      let fixedContent = props.content
      const codeBlockMatches = fixedContent.match(/```[\s\S]*?```/g)
      const openCodeBlocks = (fixedContent.match(/```/g) || []).length
      // 如果代码块标记数量是奇数，说明有未闭合的代码块
      if (openCodeBlocks % 2 !== 0) {
        // 在末尾添加闭合标记（假设是最后一个代码块未闭合）
        fixedContent += '\n```'
      }
      try {
        return markedInstance.parse(fixedContent) as string
      } catch {
        // 修复后仍然失败，返回原始文本（转义 HTML 以避免 XSS）
        return props.content
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;')
          .replace(/\n/g, '<br>')
      }
    }
    // 非流式传输时解析失败，返回转义的原始文本
    return props.content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
      .replace(/\n/g, '<br>')
  }
})
</script>

<template>
  <div ref="proseRef" class="prose prose-sm max-w-none dark:prose-invert" v-html="html" />
  <span v-if="streaming" class="inline-block w-0.5 h-4 bg-foreground/40 ml-0.5" />
</template>
