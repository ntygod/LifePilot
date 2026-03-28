<script setup lang="ts">
import { ArrowRight, LibraryBig, MessageSquare, Puzzle, Workflow } from 'lucide-vue-next'

const emit = defineEmits<{
  (e: 'send', content: string): void
}>()

const examples = [
  {
    label: '判断方向',
    title: '评估项目可行性',
    description: '先判断方向。',
    prompt: '帮我快速判断这个项目现在适合做什么、不适合做什么',
  },
  {
    label: '搭知识库',
    title: '整理知识库',
    description: '先列步骤。',
    prompt: '我想把一批内部文档接成知识库，先帮我列出实施步骤',
  },
  {
    label: '同步进展',
    title: '整理摘要',
    description: '快速出一版摘要。',
    prompt: '结合最近几条对话，整理一份能直接发给同事的进展摘要',
  },
]

const signals = [
  {
    icon: LibraryBig,
    title: '可加资料',
    description: '需要时再加。',
  },
  {
    icon: Puzzle,
    title: '可用技能',
    description: '常用能力都在。',
  },
  {
    icon: Workflow,
    title: '可继续聊',
    description: '上下文会接上。',
  },
] as const
</script>

<template>
  <div class="h-full px-md md:px-lg">
    <div class="mx-auto grid max-w-[1040px] gap-5 lg:grid-cols-[minmax(0,0.92fr)_minmax(0,1.08fr)] lg:items-center">
      <div class="space-y-6 text-left">
        <div class="flex size-16 items-center justify-center rounded-[1.15rem] border border-border/56 bg-background/86 text-primary shadow-[0_12px_20px_-18px_hsl(var(--shadow-color)/0.14)]">
          <MessageSquare :size="30" />
        </div>

        <div class="space-y-3">
          <div class="surface-label">新对话</div>
          <p class="max-w-[16ch] text-3xl font-semibold leading-tight tracking-tight text-foreground">
            想聊什么？
          </p>
          <p class="max-w-[32rem] text-sm leading-7 text-muted-foreground">
            直接输入问题，或贴一段资料。
          </p>
        </div>

        <div class="grid gap-3 sm:grid-cols-3">
          <article
            v-for="signal in signals"
            :key="signal.title"
            class="section-panel px-4 py-4"
          >
            <component :is="signal.icon" class="size-4 text-primary" />
            <div class="mt-4 text-sm font-semibold text-foreground">{{ signal.title }}</div>
            <p class="mt-2 text-xs leading-6 text-muted-foreground">
              {{ signal.description }}
            </p>
          </article>
        </div>
      </div>

      <div class="space-y-3 text-left">
        <div class="flex items-center justify-between gap-3">
          <div class="surface-label">常见起手</div>
          <span class="text-xs text-muted-foreground">点一下发送</span>
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
              <div class="text-sm font-semibold text-foreground sm:text-[0.98rem]">
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
    </div>
  </div>
</template>
