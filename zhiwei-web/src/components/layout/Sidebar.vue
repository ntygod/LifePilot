<script setup lang="ts">
import { Sheet, SheetContent } from '@/components/ui/sheet'
import SidebarContent from './SidebarContent.vue'

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
      class="w-[var(--sidebar-width)] border-none bg-transparent p-0 shadow-none [&>button]:hidden"
    >
      <SidebarContent is-mobile @close="emit('close')" />
    </SheetContent>
  </Sheet>

  <aside v-else class="h-full w-[var(--sidebar-width)] shrink-0">
    <SidebarContent />
  </aside>
</template>
