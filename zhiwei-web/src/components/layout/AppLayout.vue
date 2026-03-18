<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Menu } from 'lucide-vue-next'
import OnboardingDialog from '@/components/onboarding/OnboardingDialog.vue'
import ToastContainer from '@/components/global/ToastContainer.vue'
import GlobalLoadingBar from '@/components/global/GlobalLoadingBar.vue'
import { Button } from '@/components/ui/button'
import { useKeyboardShortcuts } from '@/composables/useKeyboardShortcuts'
import Sidebar from './Sidebar.vue'

const showOnboarding = ref(false)
const sidebarOpen = ref(false)
const isMobile = ref(false)

const { install: installShortcuts, uninstall: uninstallShortcuts } = useKeyboardShortcuts()

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

  const completed = localStorage.getItem('zhiwei_onboarding_completed')
  if (!completed) {
    showOnboarding.value = true
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  uninstallShortcuts()
})

function handleOnboardingComplete() {
  showOnboarding.value = false
}

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
      class="fixed top-4 left-4 z-40 border border-border/60 bg-background/88 shadow-[0_18px_32px_-24px_hsl(var(--shadow-color)/0.5)] backdrop-blur-md md:hidden"
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
    <OnboardingDialog v-if="showOnboarding" @complete="handleOnboardingComplete" />
  </div>
</template>
