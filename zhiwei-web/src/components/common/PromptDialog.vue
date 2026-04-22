<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'

// 对应 window.prompt 的替代：文本输入 + 确认/取消。
// 关闭（X / ESC / 遮罩）视作取消，与 ConfirmDialog 行为一致。
interface Props {
  title: string
  message?: string
  placeholder?: string
  defaultValue?: string
  confirmLabel?: string
  cancelLabel?: string
  show?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  message: '',
  placeholder: '',
  defaultValue: '',
  confirmLabel: '确认',
  cancelLabel: '取消',
  show: true,
})

const emit = defineEmits<{
  confirm: [value: string]
  cancel: []
  'update:show': [value: boolean]
}>()

const value = ref(props.defaultValue)
const closingReason = ref<'confirm' | 'cancel' | null>(null)

// 每次打开时重置输入框到 defaultValue，避免上次残留
watch(() => props.show, open => {
  if (open) {
    value.value = props.defaultValue
  }
})

function handleConfirm() {
  closingReason.value = 'confirm'
  emit('confirm', value.value)
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

function handleOpenChange(open: boolean) {
  if (open) {
    emit('update:show', true)
    return
  }
  if (closingReason.value) return
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
        <AlertDialogDescription v-if="message" class="whitespace-pre-wrap text-left leading-6">
          {{ message }}
        </AlertDialogDescription>
      </AlertDialogHeader>
      <input
        v-model="value"
        type="text"
        :placeholder="placeholder"
        class="w-full rounded-md border border-input bg-background px-md py-xs text-sm outline-none focus:ring-2 focus:ring-ring"
        @keydown.enter="handleConfirm"
      />
      <AlertDialogFooter class="gap-2">
        <Button type="button" variant="outline" data-test="prompt-cancel" @click="handleCancel">
          {{ cancelLabel }}
        </Button>
        <Button type="button" data-test="prompt-confirm" @click="handleConfirm">
          {{ confirmLabel }}
        </Button>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
