<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import NotificationBell from '@/components/notification/NotificationBell.vue'
import ThemeToggle from '@/components/global/ThemeToggle.vue'
import { useChatStore } from '@/stores/chat'
import { primaryNavigationItems, resolvePrimaryNavigation } from './appNavigation'

interface Props {
  isMobile?: boolean
}

withDefaults(defineProps<Props>(), {
  isMobile: false,
})

const emit = defineEmits<{
  navigate: []
}>()

const route = useRoute()
const chatStore = useChatStore()

function emitNavigate() {
  emit('navigate')
}

function resolvePrimaryTarget(path: string, id: string) {
  if (id === 'conversations' && chatStore.activeSessionId) {
    return { name: 'conversationDetail', params: { sessionId: chatStore.activeSessionId } }
  }
  return path
}

function isActive(id: string) {
  return resolvePrimaryNavigation(route.path).id === id
}
</script>

<template>
  <aside class="app-rail">
    <div class="flex h-full flex-col items-center gap-4 px-3 py-4">
      <RouterLink
        :to="resolvePrimaryTarget('/conversations', 'conversations')"
        class="app-rail-brand"
        title="知微"
        @click="emitNavigate"
      >
        <span class="text-sm font-semibold tracking-[0.12em]">ZW</span>
      </RouterLink>

      <div class="soft-divider opacity-70" />

      <nav class="flex flex-1 flex-col items-center gap-2.5">
        <RouterLink
          v-for="item in primaryNavigationItems"
          :key="item.id"
          :to="resolvePrimaryTarget(item.path, item.id)"
          class="app-rail-link"
          :class="{ 'app-rail-link-active': isActive(item.id) }"
          :title="item.label"
          @click="emitNavigate"
        >
          <component :is="item.icon" class="size-[18px]" />
        </RouterLink>
      </nav>

      <div class="flex flex-col items-center gap-2.5">
        <div class="app-rail-utility">
          <NotificationBell />
        </div>
        <div class="app-rail-utility">
          <ThemeToggle />
        </div>
      </div>
    </div>
  </aside>
</template>
