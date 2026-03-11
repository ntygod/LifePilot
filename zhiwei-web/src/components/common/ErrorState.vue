<script setup lang="ts">
import { AlertTriangle } from 'lucide-vue-next'
import StatePanel from '@/components/common/StatePanel.vue'
import { Button } from '@/components/ui/button'

interface Props {
  title?: string
  description?: string
  actionLabel?: string
  showAction?: boolean
}

withDefaults(defineProps<Props>(), {
  title: '出了点问题',
  description: '发生了未预期的错误，请稍后再试。',
  showAction: false,
})

const emit = defineEmits<{
  action: []
}>()

function handleAction() {
  emit('action')
}
</script>

<template>
  <StatePanel
    :title="title"
    :description="description"
    tone="danger"
    class="mx-auto max-w-3xl"
  >
    <template #icon>
      <AlertTriangle class="size-5" />
    </template>

    <template v-if="showAction && actionLabel" #actions>
      <Button @click="handleAction">
        {{ actionLabel }}
      </Button>
    </template>
  </StatePanel>
</template>
