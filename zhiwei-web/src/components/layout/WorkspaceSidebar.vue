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
      <div class="border-b border-sidebar-border/45 px-5 py-5">
        <div class="space-y-1.5">
          <div class="surface-label">{{ currentSection.label }}</div>
          <h2 class="text-base font-semibold text-foreground">{{ currentSection.label }}</h2>
          <p class="text-sm leading-6 text-muted-foreground">
            {{ currentSection.description }}
          </p>
        </div>
      </div>

      <div class="flex-1 overflow-y-auto px-3 py-4 scrollbar-thin">
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
