<script setup lang="ts">
import { computed } from 'vue'
import type { UploadFileItem } from '@/types'
import { CheckCircle, XCircle, Loader2, Clock, RefreshCw, X } from 'lucide-vue-next'

interface UploadProgressProps {
  files: UploadFileItem[]
}

const props = defineProps<UploadProgressProps>()

const emit = defineEmits<{
  /** 重试某个失败文件 */
  (e: 'retry', fileId: string): void
  /** 关闭上传进度面板 */
  (e: 'dismiss'): void
}>()

// 是否全部完成（无 waiting 或 uploading 状态）
const allDone = computed(() =>
  props.files.length > 0 &&
  props.files.every(f => f.status === 'success' || f.status === 'error')
)

// 统计信息
const successCount = computed(() => props.files.filter(f => f.status === 'success').length)
const errorCount = computed(() => props.files.filter(f => f.status === 'error').length)

// 状态图标映射
const statusConfig: Record<UploadFileItem['status'], { icon: any; class: string; label: string }> = {
  waiting: { icon: Clock, class: 'text-muted-foreground', label: '等待中' },
  uploading: { icon: Loader2, class: 'text-blue-500 animate-spin', label: '上传中' },
  success: { icon: CheckCircle, class: 'text-green-500', label: '成功' },
  error: { icon: XCircle, class: 'text-destructive', label: '失败' }
}
</script>

<template>
  <div class="rounded-lg border border-border bg-card p-4 space-y-3">
    <!-- 标题栏 -->
    <div class="flex items-center justify-between">
      <h4 class="text-sm font-medium text-foreground">
        上传进度（{{ successCount }}/{{ files.length }}）
      </h4>
      <button
        v-if="allDone"
        class="p-1 text-muted-foreground hover:text-foreground transition-colors rounded-md hover:bg-muted"
        title="关闭"
        @click="emit('dismiss')"
      >
        <X :size="16" />
      </button>
    </div>

    <!-- 文件列表 -->
    <div class="space-y-2 max-h-[240px] overflow-y-auto">
      <div
        v-for="item in files"
        :key="item.id"
        class="flex items-center gap-3 p-2 rounded-md bg-muted/30"
      >
        <!-- 状态图标 -->
        <component
          :is="statusConfig[item.status].icon"
          :size="16"
          :class="statusConfig[item.status].class"
          class="shrink-0"
        />

        <!-- 文件名 + 错误信息 -->
        <div class="flex-1 min-w-0">
          <p class="text-sm text-foreground truncate">{{ item.fileName }}</p>
          <p
            v-if="item.status === 'error' && item.errorMessage"
            class="text-xs text-destructive mt-0.5 truncate"
          >
            {{ item.errorMessage }}
          </p>
        </div>

        <!-- 重试按钮（仅失败条目） -->
        <button
          v-if="item.status === 'error'"
          class="shrink-0 inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs
                 text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
          title="重试"
          @click="emit('retry', item.id)"
        >
          <RefreshCw :size="12" />
          重试
        </button>
      </div>
    </div>

    <!-- 汇总信息 -->
    <div v-if="allDone" class="text-xs text-muted-foreground">
      <span v-if="errorCount === 0">全部上传成功</span>
      <span v-else>{{ successCount }} 个成功，{{ errorCount }} 个失败</span>
    </div>
  </div>
</template>
