<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { ThemeMode } from '@/composables/useTheme'
import AppLayout from '@/components/layout/AppLayout.vue'
import { useSettingsStore } from '@/stores/settings'

const DISPLAY_PREFERENCES_KEY = 'zhiwei_display_preferences'

const route = useRoute()
const settingsStore = useSettingsStore()

const showAppLayout = computed(() =>
  route.path !== '/' && route.path !== '/splash' && route.path !== '/setup',
)

settingsStore.hydrate()

function resolveDarkTheme(mode: ThemeMode) {
  return mode === 'dark'
    || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
}

function applyTheme(mode: ThemeMode) {
  if (resolveDarkTheme(mode)) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }

  localStorage.setItem('zhiwei_theme', mode)
}

function applyDisplayPreferences() {
  const root = document.documentElement
  root.dataset.uiDensity = settingsStore.layoutDensity
  root.dataset.fontSize = settingsStore.fontSize
  root.dataset.showTokenUsage = String(settingsStore.showTokenUsage)

  localStorage.setItem(DISPLAY_PREFERENCES_KEY, JSON.stringify({
    layoutDensity: settingsStore.layoutDensity,
    fontSize: settingsStore.fontSize,
    showTokenUsage: settingsStore.showTokenUsage,
  }))
}

watch(() => settingsStore.theme, mode => {
  applyTheme(mode)
}, { immediate: true })

watch(
  () => [settingsStore.layoutDensity, settingsStore.fontSize, settingsStore.showTokenUsage],
  () => {
    applyDisplayPreferences()
  },
  { immediate: true },
)
</script>

<template>
  <AppLayout v-if="showAppLayout" />
  <router-view v-else />
</template>
