<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import ConversationListPane from '@/components/chat/ConversationListPane.vue'
import { useChatStore } from '@/stores/chat'
import { isPathActive, resolvePrimaryNavigation } from './appNavigation'

interface Props {
  isMobile?: boolean
}

withDefaults(defineProps<Props>(), {
  isMobile: false,
})

const emit = defineEmits<{
  close: []
}>()

const route = useRoute()
const chatStore = useChatStore()

const currentSection = computed(() => resolvePrimaryNavigation(route.path))
const childCountLabel = computed(() => `${currentSection.value.children?.length ?? 0} 项`)
const sessionCountLabel = computed(() => `${chatStore.sessions.length} 段`)
</script>

<template>
  <div class="sidebar-panel h-full w-[var(--sidebar-width)]">
    <ConversationListPane v-if="currentSection.mode === 'conversation-list'" @close="emit('close')" />

    <template v-else>
      <div class="border-b border-sidebar-border/45 px-4 py-4">
        <div class="shell-card px-4 py-4">
          <div class="space-y-3.5">
            <div class="flex items-start justify-between gap-3">
              <div class="space-y-1">
                <div class="surface-label">当前分类</div>
                <h2 class="text-lg font-semibold tracking-tight text-foreground">{{ currentSection.label }}</h2>
                <p class="text-sm leading-5 text-muted-foreground">
                  {{ currentSection.description }}
                </p>
              </div>
              <div class="flex size-10 shrink-0 items-center justify-center rounded-[1rem] border border-border/50 bg-background/84 text-primary shadow-[0_10px_18px_-20px_hsl(var(--shadow-color)/0.12)]">
                <component :is="currentSection.icon" class="size-5" />
              </div>
            </div>

            <div class="grid grid-cols-2 gap-2">
              <div class="stat-block px-3 py-3">
                <div class="text-[11px] text-muted-foreground">功能</div>
                <div class="mt-1 text-sm font-semibold text-foreground">{{ childCountLabel }}</div>
              </div>
              <div class="stat-block px-3 py-3">
                <div class="text-[11px] text-muted-foreground">对话</div>
                <div class="mt-1 text-sm font-semibold text-foreground">{{ sessionCountLabel }}</div>
              </div>
            </div>

            <div class="flex flex-wrap gap-1.5 text-[11px]">
              <span class="surface-chip surface-chip-strong">个人助手</span>
              <span class="surface-chip">会记住</span>
              <span class="surface-chip">会用工具</span>
            </div>
          </div>
        </div>
      </div>

      <div class="flex-1 overflow-y-auto px-3 py-4 scrollbar-thin">
        <div class="mb-3 px-2">
          <div class="nav-section-title">常用功能</div>
          <div class="mt-1 text-[11px] text-muted-foreground">需要时再打开。</div>
        </div>

        <div class="space-y-1.5">
          <RouterLink
            v-for="item in currentSection.children"
            :key="item.path"
            :to="item.path"
            class="nav-link w-full"
            :class="{ 'nav-link-active': isPathActive(route.path, item.path) }"
            @click="emit('close')"
          >
            <component :is="item.icon" class="size-[18px] shrink-0 text-muted-foreground" />
            <span class="truncate">{{ item.label }}</span>
          </RouterLink>
        </div>
      </div>
    </template>
  </div>
</template>
