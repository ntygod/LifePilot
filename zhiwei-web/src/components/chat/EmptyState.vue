<script setup lang="ts">
import { ArrowRight, LibraryBig, MessageSquare, Puzzle, Workflow } from 'lucide-vue-next'

const emit = defineEmits<{
  (e: 'send', content: string): void
}>()

const examples = [
  {
    label: '判断方向',
    title: '评估项目可行性',
    description: '评估项目边界、风险和优先级。',
    prompt: '帮我快速判断这个项目现在适合做什么、不适合做什么',
  },
  {
    label: '搭知识库',
    title: '整理知识库',
    description: '整理实施步骤和分工。',
    prompt: '我想把一批内部文档接成知识库，先帮我列出实施步骤',
  },
  {
    label: '同步进展',
    title: '整理摘要',
    description: '整理阶段结论和待办事项。',
    prompt: '结合最近几条对话，整理一份能直接发给同事的进展摘要',
  },
]
</script>

<template>
  <div class="h-full flex items-center justify-center px-md md:px-lg">
    <div class="text-center max-w-[560px] mx-auto space-y-8">
      <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-gradient-to-br from-primary/10 to-primary/5">
        <MessageSquare :size="32" class="text-primary" />
      </div>

      <div class="space-y-3">
        <div class="surface-label">从这里开始</div>
        <p class="text-xl font-semibold leading-tight text-foreground">先把问题、片段或待办放进来</p>
        <p class="text-sm leading-7 text-muted-foreground">
          直接贴需求、文档摘录、排障记录都可以。
          后面就在这段会话里继续追问、整理、回看，不用反复重讲背景。
        </p>
      </div>

      <div class="space-y-3 text-left">
        <div class="flex items-center justify-between gap-3">
          <div class="surface-label">常见起手</div>
          <span class="text-xs text-muted-foreground">点一下就能继续写</span>
        </div>

        <button
          v-for="example in examples"
          :key="example.title"
          type="button"
          class="list-card group w-full px-4 py-4 text-left"
          @click="emit('send', example.prompt)"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0 space-y-2">
              <span class="surface-chip">{{ example.label }}</span>
              <div class="text-sm font-semibold text-foreground sm:text-[0.96rem]">
                {{ example.title }}
              </div>
              <p class="text-sm leading-6 text-muted-foreground">
                {{ example.description }}
              </p>
            </div>

            <div class="hidden items-center gap-1 pt-1 text-xs font-medium text-primary sm:flex sm:translate-x-1 sm:opacity-0 sm:transition-all sm:duration-150 sm:group-hover:translate-x-0 sm:group-hover:opacity-100">
              <span>直接发送</span>
              <ArrowRight class="size-3.5" />
            </div>
          </div>
        </button>
      </div>

      <div class="flex items-center justify-center gap-5 text-xs text-muted-foreground">
        <div class="flex items-center gap-1.5">
          <LibraryBig :size="14" />
          <span>知识库可接入</span>
        </div>
        <div class="flex items-center gap-1.5">
          <Puzzle :size="14" />
          <span>技能可复用</span>
        </div>
        <div class="flex items-center gap-1.5">
          <Workflow :size="14" />
          <span>流程可沉淀</span>
        </div>
      </div>
    </div>
  </div>
</template>
