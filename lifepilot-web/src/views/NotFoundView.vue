<script setup lang="ts">
import { useRouter } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'

const router = useRouter()

function goHome() {
  router.push('/')
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="flex items-center justify-center min-h-screen p-6">
    <EmptyState
      icon="⚠️"
      title="页面不存在"
      description="抱歉，您访问的页面不存在或已被删除。"
    >
      <template #actions>
        <button
          @click="goBack"
          class="px-4 py-2 rounded-md bg-secondary text-secondary-foreground hover:bg-secondary/80 transition-colors"
        >
          返回上一页
        </button>
        <button
          @click="goHome"
          class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          返回首页
        </button>
      </template>
    </EmptyState>
  </div>
</template>
