<script setup lang="ts">
import { Sheet, SheetContent } from '@/components/ui/sheet'
import UnifiedSidebar from './UnifiedSidebar.vue'

interface Props {
  isMobile?: boolean
  isOpen?: boolean
}

withDefaults(defineProps<Props>(), {
  isMobile: false,
  isOpen: false,
})

const emit = defineEmits<{
  close: []
}>()
</script>

<template>
  <Sheet v-if="isMobile" :open="isOpen" @update:open="(value: boolean) => { if (!value) emit('close') }">
    <SheetContent
      side="left"
      class="w-[var(--sidebar-width)] max-w-[92vw] border-none bg-transparent p-0 shadow-none [&>button]:hidden"
    >
      <div class="app-nav-shell h-full">
        <UnifiedSidebar @close="emit('close')" />
      </div>
    </SheetContent>
  </Sheet>

  <aside v-else class="app-nav-shell h-full w-[var(--sidebar-width)] shrink-0">
    <UnifiedSidebar @close="emit('close')" />
  </aside>
</template>
