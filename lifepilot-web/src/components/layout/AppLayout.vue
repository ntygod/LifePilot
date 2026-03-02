<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import Sidebar from './Sidebar.vue'
import OnboardingDialog from '@/components/onboarding/OnboardingDialog.vue'
import { Menu } from 'lucide-vue-next'

const showOnboarding = ref(false)
const sidebarOpen = ref(false)
const isMobile = ref(false)

function checkMobile() {
  isMobile.value = window.innerWidth < 768 // md breakpoint
  if (!isMobile.value) {
    sidebarOpen.value = false
  }
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
  
  // 检查是否已完成 onboarding
  const completed = localStorage.getItem('lifepilot_onboarding_completed')
  if (!completed) {
    showOnboarding.value = true
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
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
  <div class="flex h-screen overflow-hidden">
    <!-- 移动端菜单按钮 -->
    <button
      v-if="isMobile"
      class="fixed top-4 left-4 z-30 p-2 rounded-md bg-card border border-border text-foreground hover:bg-accent transition-colors md:hidden"
      @click="toggleSidebar"
    >
      <Menu :size="20" />
    </button>
    
    <!-- 侧边栏 -->
    <Sidebar :is-mobile="isMobile" :is-open="sidebarOpen" @close="closeSidebar" />
    
    <!-- 主内容区 -->
    <main class="flex-1 overflow-hidden md:ml-0" :class="{ 'ml-0': isMobile }">
      <router-view />
    </main>
    
    <!-- Onboarding 对话框 -->
    <OnboardingDialog v-if="showOnboarding" @complete="handleOnboardingComplete" />
  </div>
</template>
