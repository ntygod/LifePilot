<script setup lang="ts">
import StatePanel from '@/components/common/StatePanel.vue'
import { Button } from '@/components/ui/button'

interface Props {
  icon?: string
  title?: string
  description?: string
  actionLabel?: string
  showAction?: boolean
  examples?: string[]
}

withDefaults(defineProps<Props>(), {
  icon: '🫥',
  title: '暂无数据',
  description: '',
  showAction: false,
  examples: () => [],
})

const emit = defineEmits<{
  action: []
  example: [content: string]
}>()

function handleAction() {
  emit('action')
}

function handleExample(content: string) {
  emit('example', content)
}
</script>

<template>
  <StatePanel :title="title" :description="description" class="mx-auto max-w-[768px]">
    <template v-if="icon" #icon>
      <span class="text-3xl">{{ icon }}</span>
    </template>

    <template v-if="showAction && actionLabel" #actions>
      <Button @click="handleAction">
        {{ actionLabel }}
      </Button>
    </template>

    <div v-if="examples.length > 0" class="grid gap-2 sm:grid-cols-2">
      <button
        v-for="(example, index) in examples"
        :key="index"
        type="button"
        class="rounded-2xl border border-dashed border-border/80 bg-background/80 px-4 py-3 text-left text-sm text-foreground shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] transition-all duration-200 hover:-translate-y-px hover:border-primary/35 hover:bg-accent/70"
        @click="handleExample(example)"
      >
        {{ example }}
      </button>
    </div>
  </StatePanel>
</template>
