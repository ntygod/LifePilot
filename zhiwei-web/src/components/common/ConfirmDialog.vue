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
        <AlertDialogCancel as-child>
          <Button type="button" variant="outline" @click="handleCancel">
            {{ cancelLabel }}
          </Button>
        </AlertDialogCancel>
        <AlertDialogAction as-child>
          <Button
            type="button"
            :variant="confirmVariant === 'destructive' ? 'destructive' : 'default'"
            @click="handleConfirm"
          >
            {{ confirmLabel }}
          </Button>
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
