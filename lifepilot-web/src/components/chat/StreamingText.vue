<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js'

const props = defineProps<{
  content: string
  streaming?: boolean
}>()

// 配置 marked 使用 highlight.js 代码高亮
marked.setOptions({
  highlight(code: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  }
})

const html = computed(() => {
  if (!props.content) return ''
  try {
    return marked.parse(props.content) as string
  } catch {
    return props.content
  }
})
</script>

<template>
  <div class="prose prose-sm max-w-none dark:prose-invert" v-html="html" />
  <span v-if="streaming" class="inline-block w-2 h-4 bg-foreground/60 animate-pulse ml-0.5" />
</template>
