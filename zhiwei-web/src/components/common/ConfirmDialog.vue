<script setup lang="ts">
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'

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

function handleConfirm() {
  emit('confirm')
  emit('update:show', false)
}

function handleCancel() {
  emit('cancel')
  emit('update:show', false)
}
</script>

<template>
  <AlertDialog :open="show" @update:open="$emit('update:show', $event)">
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
        <AlertDialogCancel @click="handleCancel">
          {{ cancelLabel }}
        </AlertDialogCancel>
        <AlertDialogAction
          :class="confirmVariant === 'destructive' ? 'bg-destructive text-white hover:bg-destructive/90' : ''"
          @click="handleConfirm"
        >
          {{ confirmLabel }}
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
