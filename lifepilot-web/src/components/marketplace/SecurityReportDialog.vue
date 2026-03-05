<script setup lang="ts">
import type { SecurityReport } from '@/types'

defineProps<{
  show: boolean
  report: SecurityReport
  skillName: string
}>()

const emit = defineEmits<{
  confirm: []
  cancel: []
  'update:show': [value: boolean]
}>()

// 风险级别颜色映射
const riskColors: Record<string, string> = {
  LOW: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  MEDIUM: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
  HIGH: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
}

const riskLabels: Record<string, string> = {
  LOW: '低风险',
  MEDIUM: '中风险',
  HIGH: '高风险'
}

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
  <div
    v-if="show"
    class="fixed inset-0 bg-black/50 flex items-center justify-center z-[60]"
    @click.self="handleCancel"
  >
    <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[512px] shadow-lg max-h-[80vh] flex flex-col">
      <h3 class="text-lg font-semibold text-foreground mb-1">安全扫描报告</h3>
      <p class="text-sm text-muted-foreground mb-4">
        安装「{{ skillName }}」前发现以下安全风险，请确认是否继续。
      </p>

      <!-- 整体风险级别 -->
      <div class="flex items-center gap-2 mb-4">
        <span class="text-sm text-muted-foreground">整体风险：</span>
        <span
          class="text-xs px-2 py-0.5 rounded-full font-medium"
          :class="riskColors[report.overallRisk]"
        >
          {{ riskLabels[report.overallRisk] }}
        </span>
      </div>

      <!-- Findings 列表 -->
      <div class="flex-1 overflow-y-auto space-y-2 mb-4">
        <div
          v-for="(finding, idx) in report.findings"
          :key="idx"
          class="flex items-start gap-2 p-3 rounded-md border border-border bg-muted/30"
        >
          <span
            class="text-xs px-2 py-0.5 rounded-full font-medium shrink-0 mt-0.5"
            :class="riskColors[finding.level]"
          >
            {{ riskLabels[finding.level] }}
          </span>
          <div class="flex-1 min-w-0">
            <div class="text-xs font-medium text-foreground">{{ finding.category }}</div>
            <div class="text-xs text-muted-foreground mt-0.5">{{ finding.description }}</div>
          </div>
        </div>
        <div
          v-if="report.findings.length === 0"
          class="text-sm text-muted-foreground text-center py-4"
        >
          未发现安全风险
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="flex justify-end gap-2">
        <button
          class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
          @click="handleCancel"
        >
          取消
        </button>
        <button
          class="h-9 px-4 rounded-md text-sm bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
          @click="handleConfirm"
        >
          确认安装
        </button>
      </div>
    </div>
  </div>
</template>
