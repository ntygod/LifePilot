<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Menu } from 'lucide-vue-next'
import ToastContainer from '@/components/global/ToastContainer.vue'
import GlobalLoadingBar from '@/components/global/GlobalLoadingBar.vue'
import { Button } from '@/components/ui/button'
import { useKeyboardShortcuts } from '@/composables/useKeyboardShortcuts'
import { useNotificationStream } from '@/composables/useNotificationStream'
import Sidebar from './Sidebar.vue'

const sidebarOpen = ref(false)
const isMobile = ref(false)

const { install: installShortcuts, uninstall: uninstallShortcuts } = useKeyboardShortcuts()

// 建立通知 SSE 连接，接收标题生成、通知推送等实时事件
useNotificationStream()

function checkMobile() {
  isMobile.value = window.innerWidth < 768
  if (!isMobile.value) {
    sidebarOpen.value = false
  }
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
  installShortcuts()

})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  uninstallShortcuts()
})

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

function closeSidebar() {
  sidebarOpen.value = false
}
</script>

<template>
  <div class="app-shell">
    <GlobalLoadingBar />

    <Button
      v-if="isMobile"
      type="button"
      variant="outline"
      size="icon"
      class="fixed left-4 top-4 z-40 rounded-[1rem] border border-border/56 bg-background/88 shadow-[0_12px_22px_-18px_hsl(var(--shadow-color)/0.16)] backdrop-blur-xl md:hidden"
      aria-label="切换侧边栏"
      @click="toggleSidebar"
    >
      <Menu class="size-5" />
    </Button>

    <Sidebar :is-mobile="isMobile" :is-open="sidebarOpen" @close="closeSidebar" />

    <div class="app-main-surface">
      <main class="relative min-h-0 flex-1 overflow-hidden">
        <router-view v-slot="{ Component }">
          <Transition name="page-slide" mode="out-in">
            <component :is="Component" />
          </Transition>
        </router-view>
      </main>
    </div>

    <ToastContainer />
  </div>
</template>
