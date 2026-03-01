<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  page: number
  pageCount: number
  size?: 'sm' | 'md'
}>()

const emit = defineEmits<{
  (e: 'change', value: number): void
}>()

const currentDisplay = computed(() => props.page + 1)

const canPrev = computed(() => props.page > 0)
const canNext = computed(() => props.page < props.pageCount - 1)

function goPrev() {
  if (!canPrev.value) return
  emit('change', props.page - 1)
}

function goNext() {
  if (!canNext.value) return
  emit('change', props.page + 1)
}
</script>

<template>
  <div class="flex items-center justify-center gap-2 mt-4">
    <button
      type="button"
      class="rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
      :class="size === 'sm' ? 'text-xs px-3 py-1' : 'text-sm px-3 py-1.5'"
      :disabled="!canPrev"
      @click="goPrev"
    >
      上一页
    </button>
    <span class="text-xs text-muted-foreground">
      第 {{ currentDisplay }} / {{ pageCount }} 页
    </span>
    <button
      type="button"
      class="rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
      :class="size === 'sm' ? 'text-xs px-3 py-1' : 'text-sm px-3 py-1.5'"
      :disabled="!canNext"
      @click="goNext"
    >
      下一页
    </button>
  </div>
</template>

