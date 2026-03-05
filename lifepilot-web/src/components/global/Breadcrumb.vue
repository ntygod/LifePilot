<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ChevronRight } from 'lucide-vue-next'

/** 面包屑条目接口 */
export interface BreadcrumbItem {
  label: string
  to?: string | { name: string; params?: Record<string, string> }
}

defineProps<{
  items: BreadcrumbItem[]
}>()
</script>

<template>
  <!-- 面包屑导航：水平路径，各级以 > 分隔 -->
  <nav class="flex items-center gap-1 text-sm">
    <template v-for="(item, index) in items" :key="index">
      <!-- 分隔符（非首项前显示） -->
      <ChevronRight v-if="index > 0" :size="14" class="text-muted-foreground shrink-0" />

      <!-- 非末尾条目：可点击链接 -->
      <RouterLink
        v-if="index < items.length - 1 && item.to"
        :to="item.to"
        class="text-muted-foreground hover:text-foreground transition-colors"
      >
        {{ item.label }}
      </RouterLink>

      <!-- 末尾条目：纯文本（当前页面） -->
      <span v-else class="text-foreground font-medium">
        {{ item.label }}
      </span>
    </template>
  </nav>
</template>
