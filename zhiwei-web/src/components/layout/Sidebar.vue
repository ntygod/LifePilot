<script setup lang="ts">
import { Sheet, SheetContent } from '@/components/ui/sheet'
import AppRail from './AppRail.vue'
import WorkspaceSidebar from './WorkspaceSidebar.vue'

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
      class="w-[calc(var(--app-rail-width)+var(--sidebar-width))] max-w-[92vw] border-none bg-transparent p-0 shadow-none [&>button]:hidden"
    >
      <div class="app-nav-shell h-full">
        <AppRail is-mobile @navigate="emit('close')" />
        <WorkspaceSidebar is-mobile @close="emit('close')" />
      </div>
    </SheetContent>
  </Sheet>

  <aside v-else class="app-nav-shell h-full w-[calc(var(--app-rail-width)+var(--sidebar-width))] shrink-0">
    <AppRail />
    <WorkspaceSidebar />
  </aside>
</template>
