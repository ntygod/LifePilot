<script setup lang="ts">
interface Props {
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  confirmVariant?: 'default' | 'destructive'
  show?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  confirmLabel: '确认',
  cancelLabel: '取消',
  confirmVariant: 'default',
  show: true
})

const emit = defineEmits<{
  confirm: []
  cancel: []
  'update:show': [value: boolean]
}>()

function handleConfirm() {
  emit('confirm')
  emit('update:show', false)
}

function handleCancel() {
  emit('cancel')
  emit('update:show', false)
}

function handleBackdropClick() {
  emit('update:show', false)
  emit('cancel')
}
</script>

<template>
  <div
    v-if="show"
    class="fixed inset-0 bg-black/50 flex items-center justify-center z-[60]"
    @click.self="handleBackdropClick"
  >
    <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[384px] shadow-lg">
      <h3 class="text-lg font-semibold text-foreground mb-2">{{ title }}</h3>
      <p class="text-sm text-muted-foreground mb-4 whitespace-pre-wrap">{{ message }}</p>
      <div class="flex justify-end gap-2">
        <button
          class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
          @click="handleCancel"
        >
          {{ cancelLabel }}
        </button>
        <button
          :class="[
            'h-9 px-4 rounded-md text-sm transition-colors',
            confirmVariant === 'destructive'
              ? 'bg-destructive text-destructive-foreground hover:bg-destructive/90'
              : 'bg-primary text-primary-foreground hover:bg-primary/90'
          ]"
          @click="handleConfirm"
        >
          {{ confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
