<script setup lang="ts">
import { computed } from 'vue'
import { useProcessTaskStore } from '@/stores/processTask'
import ProcessTaskCard from './ProcessTaskCard.vue'

/**
 * 后台任务列表 — 嵌入右侧面板"后台任务"tab 内。
 *
 * 从 useProcessTaskStore 消费数据，垂直展示所有活跃和最近终止的任务。
 * SSE 订阅由 AppLayout 全局建立，本组件只负责渲染。
 */

const store = useProcessTaskStore()
const tasks = computed(() => store.tasksOrdered)
</script>

<template>
  <div class="flex flex-col gap-sm p-sm">
    <p v-if="tasks.length === 0" class="py-2xl text-center text-sm text-muted-foreground">
      当前没有后台任务
    </p>
    <ProcessTaskCard v-for="task in tasks" :key="task.sessionId" :task="task" />
  </div>
</template>
