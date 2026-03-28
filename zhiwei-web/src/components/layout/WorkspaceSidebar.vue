<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import ConversationListPane from '@/components/chat/ConversationListPane.vue'
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

const currentSection = computed(() => resolvePrimaryNavigation(route.path))
</script>

<template>
  <div class="sidebar-panel h-full w-[var(--sidebar-width)]">
    <ConversationListPane v-if="currentSection.mode === 'conversation-list'" @close="emit('close')" />

    <template v-else>
      <div class="border-b border-sidebar-border/45 px-4 py-3">
        <div class="workspace-section-header">
          <h2 class="text-lg font-semibold tracking-tight text-foreground">{{ currentSection.label }}</h2>
          <div class="workspace-section-icon">
            <component :is="currentSection.icon" class="size-5" />
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

<style scoped>
.workspace-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.1rem 0;
}

.workspace-section-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.7rem;
  height: 2.7rem;
  border-radius: 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.9), hsl(from var(--background) h s l / 0.84));
  color: hsl(from var(--primary) h s l / 0.92);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.66),
    0 10px 18px -24px hsl(var(--shadow-color) / 0.08);
}
</style>
