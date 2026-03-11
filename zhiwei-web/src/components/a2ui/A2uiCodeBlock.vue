<script setup lang="ts">
import { computed, ref } from 'vue'
import { highlightCode, highlightPlainText } from '@/lib/highlight'

const props = defineProps<{
  code: string
  language?: string
}>()

const copied = ref(false)

const highlighted = computed(() => {
  if (props.language) {
    return highlightCode(props.code, props.language)
  }

  // 无语言时，纯文本渲染，避免误判导致的过度高亮。
  return highlightPlainText(props.code)
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
  <div class="relative overflow-hidden rounded-[calc(var(--radius)+4px)] border border-border/70 bg-background/85">
    <div class="flex items-center justify-between border-b border-border/70 bg-muted/40 px-3 py-2">
      <span class="text-xs text-muted-foreground">{{ language || 'text' }}</span>
      <button
        class="text-xs text-muted-foreground transition-colors hover:text-foreground"
        @click="copyCode"
      >{{ copied ? '已复制' : '复制' }}</button>
    </div>
    <pre class="overflow-x-auto p-3 text-sm leading-6"><code v-html="highlighted" /></pre>
  </div>
</template>
