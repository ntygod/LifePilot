<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <aside class="w-[var(--sidebar-width)] border-r border-border bg-card flex flex-col h-full">
    <!-- 顶部标题 -->
    <div class="p-4 border-b border-border">
      <h1 class="text-lg font-semibold text-foreground">LifePilot</h1>
    </div>

    <!-- 会话列表占位 -->
    <div class="flex-1 overflow-y-auto p-2">
      <p class="text-sm text-muted-foreground p-2">暂无会话</p>
    </div>

    <!-- 底部导航 -->
    <div class="p-2 border-t border-border">
      <button
        class="w-full text-left px-3 py-2 rounded-md text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
        @click="router.push('/settings')"
      >
        设置
      </button>
    </div>
  </aside>
</template>
