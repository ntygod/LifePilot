<script setup lang="ts">
/**
 * 共享卡片头 —— 文件名 + diff 摘要 + 版本号 + 展开箭头。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { FileText, ChevronDown, ChevronUp } from 'lucide-vue-next'

interface Props {
  fileName: string | undefined
  summary?: string | undefined
  fromVersion?: number | undefined
  toVersion?: number | undefined
  expanded: boolean
}

defineProps<Props>()
defineEmits<{ (e: 'toggle'): void }>()
</script>

<template>
  <button
    type="button"
    class="flex w-full items-center justify-between gap-sm text-left"
    @click="$emit('toggle')"
  >
    <span class="flex min-w-0 items-center gap-xs">
      <FileText class="size-md shrink-0 text-muted-foreground" />
      <span class="truncate font-medium">{{ fileName || '加载中…' }}</span>
      <span v-if="summary" class="truncate text-muted-foreground">
        · {{ summary }}
        <template v-if="fromVersion !== undefined && toVersion !== undefined">
          · v{{ fromVersion }} → v{{ toVersion }}
        </template>
      </span>
    </span>
    <component :is="expanded ? ChevronUp : ChevronDown" class="size-md shrink-0" />
  </button>
</template>
