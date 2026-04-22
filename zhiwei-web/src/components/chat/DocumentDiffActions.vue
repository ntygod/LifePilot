<script setup lang="ts">
/**
 * 共享卡片底部三按钮 —— 应用到原路径 / 另存为 / 丢弃。
 * 按钮事件 emit 给父组件，真正的提交 / 二次确认由父组件处理。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { Upload, Save, Trash2 } from 'lucide-vue-next'

interface Props {
  canOverwrite: boolean
}

defineProps<Props>()
defineEmits<{
  (e: 'overwrite'): void
  (e: 'save-as'): void
  (e: 'discard'): void
}>()
</script>

<template>
  <div class="flex items-center gap-sm">
    <button
      v-if="canOverwrite"
      type="button"
      class="inline-flex items-center gap-xs rounded-md bg-primary px-md py-xs text-sm text-primary-foreground"
      @click="$emit('overwrite')"
    >
      <Upload class="size-md" />
      <span>应用到原路径</span>
    </button>
    <button
      type="button"
      class="inline-flex items-center gap-xs rounded-md border border-border bg-transparent px-md py-xs text-sm"
      @click="$emit('save-as')"
    >
      <Save class="size-md" />
      <span>另存为…</span>
    </button>
    <button
      type="button"
      class="inline-flex items-center gap-xs rounded-md border border-border bg-transparent px-md py-xs text-sm text-destructive"
      @click="$emit('discard')"
    >
      <Trash2 class="size-md" />
      <span>丢弃</span>
    </button>
  </div>
</template>
