<script setup lang="ts">
import { ref } from 'vue'
import { Upload } from 'lucide-vue-next'
import { classifyFiles } from '@/utils/fileUtils'

interface DropZoneProps {
  /** 支持的文件 MIME 类型列表 */
  acceptTypes: string[]
  /** 是否禁用拖拽 */
  disabled?: boolean
}

const props = withDefaults(defineProps<DropZoneProps>(), {
  disabled: false
})

const emit = defineEmits<{
  /** 用户释放有效文件时触发 */
  (e: 'drop', files: File[]): void
  /** 拖入了不支持格式的文件时触发 */
  (e: 'reject', fileNames: string[]): void
}>()

// 拖拽悬停状态
const isDragOver = ref(false)

function handleDragOver(e: DragEvent) {
  if (props.disabled) return
  e.preventDefault()
  isDragOver.value = true
}

function handleDragLeave(e: DragEvent) {
  if (props.disabled) return
  e.preventDefault()
  isDragOver.value = false
}

function handleDrop(e: DragEvent) {
  if (props.disabled) return
  e.preventDefault()
  isDragOver.value = false

  const files = Array.from(e.dataTransfer?.files ?? [])
  if (files.length === 0) return

  const { accepted, rejected } = classifyFiles(files)

  if (accepted.length > 0) {
    emit('drop', accepted)
  }
  if (rejected.length > 0) {
    emit('reject', rejected)
  }
}
</script>

<template>
  <div
    class="relative rounded-lg border-2 border-dashed transition-all duration-200"
    :class="[
      disabled
        ? 'border-muted bg-muted/20 cursor-not-allowed opacity-50'
        : isDragOver
          ? 'border-primary bg-primary/5 shadow-sm'
          : 'border-border hover:border-muted-foreground/40'
    ]"
    @dragover="handleDragOver"
    @dragleave="handleDragLeave"
    @drop="handleDrop"
  >
    <!-- 拖拽高亮覆盖层 -->
    <div
      v-if="isDragOver && !disabled"
      class="absolute inset-0 flex flex-col items-center justify-center bg-primary/5 rounded-lg z-10"
    >
      <Upload :size="32" class="text-primary mb-2" />
      <p class="text-sm font-medium text-primary">释放以上传</p>
    </div>

    <!-- 默认插槽内容 -->
    <slot />
  </div>
</template>
