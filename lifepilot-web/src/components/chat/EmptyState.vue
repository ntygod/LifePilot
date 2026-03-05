<script setup lang="ts">
import { MessageSquare, LibraryBig, Puzzle, Workflow } from 'lucide-vue-next'

const emit = defineEmits<{
  (e: 'send', content: string): void
}>()

const examples = [
  '帮我快速了解这个项目目前的能力和限制',
  '我想用自己的文档搭一个知识库助手，应该怎么开始？',
  '结合最近几条对话，帮我整理一份可以发给同事的总结',
]
</script>

<template>
  <div class="h-full flex items-center justify-center px-md md:px-lg">
    <div class="text-center max-w-[560px] mx-auto space-y-6">
      <!-- 图标 -->
      <div class="w-16 h-16 rounded-full bg-primary/10 flex items-center justify-center mx-auto">
        <MessageSquare :size="32" class="text-primary" />
      </div>

      <!-- 标题与描述 -->
      <div>
        <p class="text-xl font-semibold text-foreground mb-2 leading-tight">开始新对话</p>
        <p class="text-sm text-muted-foreground leading-normal">
          向 AI 提问，或粘贴一段内容让它帮你总结、改写或分析。
        </p>
      </div>

      <!-- 可点击示例问题 -->
      <div class="flex flex-col gap-2">
        <button
          v-for="(example, i) in examples"
          :key="i"
          type="button"
          class="w-full rounded-lg border border-dashed border-border px-md py-sm text-left text-sm
                 hover:bg-accent hover:text-accent-foreground transition-all duration-200"
          @click="emit('send', example)"
        >
          {{ example }}
        </button>
      </div>

      <!-- 能力概览 -->
      <div class="flex items-center justify-center gap-4 text-xs text-muted-foreground">
        <div class="flex items-center gap-1">
          <LibraryBig :size="14" />
          <span>知识库</span>
        </div>
        <div class="flex items-center gap-1">
          <Puzzle :size="14" />
          <span>技能</span>
        </div>
        <div class="flex items-center gap-1">
          <Workflow :size="14" />
          <span>工作流</span>
        </div>
      </div>
    </div>
  </div>
</template>
