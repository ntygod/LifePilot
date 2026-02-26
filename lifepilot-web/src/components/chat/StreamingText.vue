<script setup lang="ts">
import { computed } from 'vue'
import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'

const props = defineProps<{
  content: string
  streaming?: boolean
}>()

// 使用 marked-highlight 扩展集成 highlight.js
const markedInstance = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code: string, lang: string) {
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(code, { language: lang }).value
      }
      return hljs.highlightAuto(code).value
    }
  })
)

const html = computed(() => {
  if (!props.content) return ''
  try {
    return markedInstance.parse(props.content) as string
  } catch {
    return props.content
  }
})
</script>

<template>
  <div class="prose prose-sm max-w-none dark:prose-invert" v-html="html" />
  <span v-if="streaming" class="inline-block w-2 h-4 bg-foreground/60 animate-pulse ml-0.5" />
</template>
