<script setup lang="ts">
import { computed, ref } from 'vue'
import hljs from 'highlight.js'

const props = defineProps<{
  code: string
  language?: string
}>()

const copied = ref(false)

const highlighted = computed(() => {
  if (props.language && hljs.getLanguage(props.language)) {
    return hljs.highlight(props.code, { language: props.language }).value
  }
  // 无语言或不支持时，纯文本渲染（转义 HTML）
  return hljs.highlight(props.code, { language: 'plaintext' }).value
})

async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.code)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // 剪贴板 API 不可用时静默失败
  }
}
</script>

<template>
  <div class="relative rounded-md bg-muted border border-border">
    <div class="flex items-center justify-between px-3 py-1.5 border-b border-border">
      <span class="text-xs text-muted-foreground">{{ language || 'text' }}</span>
      <button
        class="text-xs text-muted-foreground hover:text-foreground transition-colors"
        @click="copyCode"
      >{{ copied ? '已复制' : '复制' }}</button>
    </div>
    <pre class="p-3 overflow-x-auto text-sm"><code v-html="highlighted" /></pre>
  </div>
</template>
