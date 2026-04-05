<script setup lang="ts">
import { computed, onMounted } from 'vue'
import {
  Clock3,
  Sparkles,
} from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'
import { useChatStore } from '@/stores/chat'
import { useSkillStore } from '@/stores/skill'

const emit = defineEmits<{
  (e: 'send', content: string): void
}>()

const router = useRouter()

const chatStore = useChatStore()
const skillStore = useSkillStore()

onMounted(async () => {
  if (skillStore.skills.length === 0) {
    await skillStore.fetchSkills()
  }
})

/** 取前 4 个内置技能作为快捷入口 */
const quickSkills = computed(() =>
  skillStore.skills
    .filter(s => s.source.type === 'Builtin')
    .slice(0, 4),
)

/** 最近 3 条对话 */
const recentSessions = computed(() =>
  [...chatStore.sessions]
    .filter(s => !s.archived && s.lastMessagePreview)
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
    .slice(0, 3),
)

function formatRelativeTime(isoString?: string) {
  if (!isoString) return ''
  const date = new Date(isoString)
  const diffMs = Date.now() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  const diffHour = Math.floor(diffMs / 3600000)
  const diffDay = Math.floor(diffMs / 86400000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  if (diffHour < 24) return `${diffHour} 小时前`
  if (diffDay === 1) return '昨天'
  if (diffDay < 7) return `${diffDay} 天前`
  return `${date.getMonth() + 1}月${date.getDate()}日`
}
</script>

<template>
  <div class="w-full max-w-[480px] animate-in fade-in slide-in-from-bottom-4 duration-500">
      <!-- 问候区 -->
      <div class="mb-xl text-center">
        <div class="mb-lg inline-flex items-center justify-center rounded-2xl bg-primary/8 p-md animate-in zoom-in-75 duration-400 delay-100">
          <ZhiweiMark class="size-8 text-primary" />
        </div>
        <h1 class="text-2xl font-semibold tracking-tight text-foreground animate-in fade-in slide-in-from-bottom-2 duration-400 delay-150">
          你好，有什么需要帮忙的？
        </h1>
        <p class="mt-sm text-sm text-muted-foreground animate-in fade-in duration-400 delay-250">
          直接输入问题，或选择一个技能开始
        </p>
      </div>

      <!-- 快捷技能 -->
      <div v-if="quickSkills.length > 0" class="mb-xl animate-in fade-in slide-in-from-bottom-2 duration-400 delay-300">
        <div class="grid grid-cols-2 gap-sm sm:grid-cols-4">
          <button
            v-for="(skill, idx) in quickSkills"
            :key="skill.id"
            type="button"
            class="flex items-center gap-sm rounded-2xl border border-border/40 bg-card/60 px-md py-sm text-left text-xs text-foreground transition-all hover:-translate-y-px hover:border-primary/30 hover:bg-card/90 hover:shadow-[0_6px_16px_-8px_hsl(var(--shadow-color)/0.1)] animate-in fade-in zoom-in-95 duration-300"
            :style="{ animationDelay: `${350 + idx * 60}ms` }"
            @click="emit('send', `使用技能「${skill.name}」`)"
          >
            <Sparkles class="size-3.5 shrink-0 text-primary/70" />
            <span class="truncate">{{ skill.name }}</span>
          </button>
        </div>
      </div>

      <!-- 最近对话 -->
      <div v-if="recentSessions.length > 0">
        <div class="mb-sm text-xs font-medium text-muted-foreground">最近对话</div>
        <div class="space-y-xs">
          <button
            v-for="session in recentSessions"
            :key="session.id"
            type="button"
            class="flex w-full items-center justify-between rounded-xl px-md py-sm text-left opacity-60 transition-colors hover:bg-accent/50 hover:opacity-100"
            @click="router.push({ name: 'conversationDetail', params: { sessionId: session.id } })"
          >
            <div class="truncate text-sm text-foreground">{{ session.title || '新对话' }}</div>
            <div class="ml-md flex shrink-0 items-center gap-xs text-[11px] text-muted-foreground">
              <Clock3 class="size-3" />
              {{ formatRelativeTime(session.updatedAt) }}
            </div>
          </button>
        </div>
      </div>
  </div>
</template>
