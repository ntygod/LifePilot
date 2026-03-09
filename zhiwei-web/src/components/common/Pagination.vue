<script setup lang="ts">
import { computed } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

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

const btnSize = computed(() => (props.size === 'sm' ? 'icon-sm' : 'icon'))

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
    <Button
      variant="outline"
      :size="btnSize"
      :disabled="!canPrev"
      @click="goPrev"
    >
      <ChevronLeft class="size-4" />
      <span class="sr-only">上一页</span>
    </Button>
    <span class="text-xs text-muted-foreground">
      第 {{ currentDisplay }} / {{ pageCount }} 页
    </span>
    <Button
      variant="outline"
      :size="btnSize"
      :disabled="!canNext"
      @click="goNext"
    >
      <ChevronRight class="size-4" />
      <span class="sr-only">下一页</span>
    </Button>
  </div>
</template>
