<!-- SecurityReportDialog — 安全扫描报告对话框，使用 shadcn-vue AlertDialog + Badge -->
<script setup lang="ts">
import type { SecurityReport } from '@/types'
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
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'

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

// 风险级别 Badge variant 映射
const riskVariant: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  LOW: 'secondary',
  MEDIUM: 'outline',
  HIGH: 'destructive'
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
  <AlertDialog :open="show" @update:open="$emit('update:show', $event)">
    <AlertDialogContent class="sm:max-w-[512px]">
      <AlertDialogHeader>
        <AlertDialogTitle>安全扫描报告</AlertDialogTitle>
        <AlertDialogDescription>
          安装「{{ skillName }}」前发现以下安全风险，请确认是否继续。
        </AlertDialogDescription>
      </AlertDialogHeader>

      <!-- 整体风险级别 -->
      <div class="flex items-center gap-2 mb-2">
        <span class="text-sm text-muted-foreground">整体风险：</span>
        <Badge :variant="riskVariant[report.overallRisk] ?? 'outline'">
          {{ riskLabels[report.overallRisk] ?? report.overallRisk }}
        </Badge>
      </div>

      <!-- Findings 列表 -->
      <ScrollArea class="max-h-[40vh]">
        <div class="space-y-2 pr-3">
          <div
            v-for="(finding, idx) in report.findings"
            :key="idx"
            class="flex items-start gap-2 p-3 rounded-md border border-border bg-muted/30"
          >
            <Badge
              :variant="riskVariant[finding.level] ?? 'outline'"
              class="shrink-0 mt-0.5"
            >
              {{ riskLabels[finding.level] ?? finding.level }}
            </Badge>
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
      </ScrollArea>

      <AlertDialogFooter>
        <AlertDialogCancel @click="handleCancel">取消</AlertDialogCancel>
        <AlertDialogAction
          class="bg-destructive text-destructive-foreground hover:bg-destructive/90"
          @click="handleConfirm"
        >
          确认安装
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
