<script setup lang="ts">
import { ref } from 'vue'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'

interface Props {
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  confirmVariant?: 'default' | 'destructive'
  show?: boolean
}

withDefaults(defineProps<Props>(), {
  confirmLabel: '确认',
  cancelLabel: '取消',
  confirmVariant: 'default',
  show: true,
})

const emit = defineEmits<{
  confirm: []
  cancel: []
  'update:show': [value: boolean]
}>()

const closingReason = ref<'confirm' | 'cancel' | null>(null)

function handleConfirm() {
  closingReason.value = 'confirm'
  emit('confirm')
  emit('update:show', false)
  queueMicrotask(() => {
    closingReason.value = null
  })
}

function handleCancel() {
  closingReason.value = 'cancel'
  emit('cancel')
  emit('update:show', false)
  queueMicrotask(() => {
    closingReason.value = null
  })
}

function handleOpenChange(value: boolean) {
  if (value) {
    emit('update:show', true)
    return
  }
  if (closingReason.value) {
    return
  }
  emit('cancel')
  emit('update:show', false)
}
</script>

<template>
  <AlertDialog :open="show" @update:open="handleOpenChange">
    <AlertDialogContent class="sm:max-w-[440px]">
      <AlertDialogHeader>
        <AlertDialogTitle class="text-left text-lg font-semibold tracking-tight">
          {{ title }}
        </AlertDialogTitle>
        <AlertDialogDescription class="whitespace-pre-wrap text-left leading-6">
          {{ message }}
        </AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter class="gap-2">
        <Button
          type="button"
          variant="outline"
          data-test="confirm-cancel"
          @click="handleCancel"
        >
          {{ cancelLabel }}
        </Button>
        <Button
          type="button"
          data-test="confirm-action"
          :variant="confirmVariant === 'destructive' ? 'destructive' : 'default'"
          @click="handleConfirm"
        >
          {{ confirmLabel }}
        </Button>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
