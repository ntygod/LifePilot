<script setup lang="ts">
import { useTheme } from '@/composables/useTheme'
import { Toggle } from '@/components/ui/toggle'
import { Sun, Moon, Monitor } from 'lucide-vue-next'
import { computed } from 'vue'

const { mode, cycleTheme } = useTheme()

/** 根据当前主题模式返回对应图标组件 */
const iconComponent = computed(() => {
  switch (mode.value) {
    case 'light': return Sun
    case 'dark': return Moon
    case 'system': return Monitor
  }
})
</script>

<template>
  <!-- 主题切换按钮：点击循环切换 light → dark → system -->
  <Toggle
    size="sm"
    :pressed="mode === 'dark'"
    aria-label="切换主题"
    title="切换主题"
    @click="cycleTheme()"
  >
    <component :is="iconComponent" :size="18" />
  </Toggle>
</template>
