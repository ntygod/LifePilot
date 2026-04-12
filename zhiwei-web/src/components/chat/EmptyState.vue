<script setup lang="ts">
import { computed } from 'vue'
import {
  BarChart3,
  Code,
  PenLine,
  Search,
} from 'lucide-vue-next'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'

const emit = defineEmits<{
  (e: 'fill', content: string): void
}>()

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了，还有什么需要帮忙的？'
  if (hour < 12) return '早上好，今天有什么需要帮忙的？'
  if (hour < 18) return '下午好，有什么需要帮忙的？'
  return '晚上好，有什么需要帮忙的？'
})

const prompts = [
  { icon: Search, label: '帮我搜索最新资讯', text: '帮我搜索最新资讯' },
  { icon: PenLine, label: '帮我写一篇文章', text: '帮我写一篇文章' },
  { icon: Code, label: '帮我分析代码', text: '帮我分析代码' },
  { icon: BarChart3, label: '帮我处理数据', text: '帮我处理数据' },
]

const capabilities = ['联网搜索', '文件解析', '知识库', '长期记忆', '代码执行', '工作流']
</script>

<template>
  <div class="w-full max-w-[540px] animate-in fade-in slide-in-from-bottom-4 duration-500">
    <!-- 问候区 -->
    <div class="mb-xl text-center">
      <div class="mb-lg inline-flex items-center justify-center rounded-2xl bg-primary/8 p-md animate-in zoom-in-75 duration-400 delay-100">
        <ZhiweiMark class="size-8 text-primary" />
      </div>
      <h1 class="text-2xl font-semibold tracking-tight text-foreground animate-in fade-in slide-in-from-bottom-2 duration-400 delay-150">
        {{ greeting }}
      </h1>
      <p class="mt-sm text-sm text-muted-foreground animate-in fade-in duration-400 delay-250">
        我可以帮你搜索资讯、写作、编程、数据分析等
      </p>
    </div>

    <!-- 推荐 Prompt 卡片 -->
    <div class="mb-lg animate-in fade-in slide-in-from-bottom-2 duration-400 delay-300">
      <div class="grid grid-cols-2 gap-sm">
        <button
          v-for="(prompt, idx) in prompts"
          :key="idx"
          type="button"
          class="prompt-card flex items-center gap-sm rounded-2xl border border-border/40 bg-card/60 px-md py-sm text-left text-xs text-foreground transition-all hover:-translate-y-px hover:border-primary/30 hover:bg-card/90 hover:shadow-[0_6px_16px_-8px_hsl(var(--shadow-color)/0.1)] animate-in fade-in zoom-in-95 duration-300"
          :style="{
            animationDelay: `${350 + idx * 60}ms`,
            '--float-delay': `${idx * -0.7}s`,
          }"
          @click="emit('fill', prompt.text)"
        >
          <component :is="prompt.icon" class="size-3.5 shrink-0 text-primary/70" />
          <span class="truncate">{{ prompt.label }}</span>
        </button>
      </div>
    </div>

    <!-- 能力标签 -->
    <div class="flex flex-wrap items-center justify-center gap-xs animate-in fade-in duration-400 delay-500">
      <button
        v-for="tag in capabilities"
        :key="tag"
        type="button"
        class="cursor-pointer rounded-full border border-border/30 bg-muted/50 px-sm py-0.5 text-[11px] text-muted-foreground/80 transition-all duration-200 hover:scale-105 hover:border-primary/40 hover:text-primary/90"
        @click="emit('fill', tag)"
      >
        {{ tag }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.prompt-card {
  animation: prompt-float 3s ease-in-out infinite;
  animation-delay: var(--float-delay, 0s);
}

@keyframes prompt-float {
  0%,
  100% {
    transform: translateY(0);
  }

  50% {
    transform: translateY(-2px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .prompt-card {
    animation: none;
  }
}
</style>
