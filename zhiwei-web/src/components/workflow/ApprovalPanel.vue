<script setup lang="ts">
/**
 * 审批操作面板组件。
 * 当工作流实例处于 PAUSED 状态时渲染，提供批准/拒绝操作。
 */
import { ref, computed } from 'vue'
import type { WorkflowExecution } from '@/types'
import { useWorkflowStore } from '@/stores/workflow'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Badge } from '@/components/ui/badge'
import { ShieldCheck, ShieldX, Loader2, AlertCircle } from 'lucide-vue-next'

const props = defineProps<{
  execution: WorkflowExecution
  steps: unknown[]
}>()

const emit = defineEmits<{
  approved: []
}>()

const store = useWorkflowStore()

// 内部状态
const action = ref<'approve' | 'reject' | null>(null)
const reason = ref('')
const submitting = ref(false)
const submitError = ref<string | null>(null)

// 查找当前等待审批的步骤信息
const pendingStep = computed(() => {
  if (!props.execution.pendingApprovalStepId) return null
  return props.steps.find(
    (s: any) => s.id === props.execution.pendingApprovalStepId
  ) as any | null
})

// 审批消息
const approvalMessage = computed(() => pendingStep.value?.message ?? '需要人工审批')

// 审批人列表
const approvers = computed<string[]>(() => pendingStep.value?.approvers ?? [])

function selectAction(a: 'approve' | 'reject') {
  action.value = a
  reason.value = ''
  submitError.value = null
}

function cancel() {
  action.value = null
  reason.value = ''
  submitError.value = null
}

async function submit() {
  if (!props.execution.pendingApprovalStepId) return
  // 拒绝时原因必填
  if (action.value === 'reject' && !reason.value.trim()) {
    submitError.value = '拒绝时必须填写原因'
    return
  }

  submitting.value = true
  submitError.value = null
  try {
    await store.approve(
      props.execution.id,
      props.execution.pendingApprovalStepId,
      {
        decision: action.value === 'approve' ? 'APPROVED' : 'REJECTED',
        decidedBy: 'web-user',
        reason: reason.value.trim() || undefined
      }
    )
    action.value = null
    reason.value = ''
    emit('approved')
  } catch (e: any) {
    submitError.value = e.message ?? '审批操作失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Card class="border-amber-300 bg-amber-50/50">
    <CardHeader class="pb-2">
      <div class="flex items-center gap-2">
        <CardTitle class="text-sm">人工审批</CardTitle>
        <Badge variant="outline" class="border-amber-400 text-amber-700">等待审批</Badge>
      </div>
    </CardHeader>
    <CardContent class="space-y-3">
      <!-- 审批消息 -->
      <p class="text-sm text-foreground">{{ approvalMessage }}</p>

      <!-- 审批人列表 -->
      <div v-if="approvers.length > 0" class="flex items-center gap-1 flex-wrap">
        <span class="text-xs text-muted-foreground">审批人:</span>
        <Badge v-for="a in approvers" :key="a" variant="secondary" class="text-xs">{{ a }}</Badge>
      </div>

      <!-- 操作按钮（未选择操作时） -->
      <div v-if="!action" class="flex items-center gap-2">
        <Button size="sm" class="bg-green-600 hover:bg-green-700" @click="selectAction('approve')">
          <ShieldCheck class="w-4 h-4 mr-1" />
          批准
        </Button>
        <Button size="sm" variant="destructive" @click="selectAction('reject')">
          <ShieldX class="w-4 h-4 mr-1" />
          拒绝
        </Button>
      </div>

      <!-- 批准确认 -->
      <div v-if="action === 'approve'" class="space-y-2">
        <Textarea
          v-model="reason"
          placeholder="备注（可选）"
          :rows="2"
          class="text-sm"
        />
        <div class="flex items-center gap-2">
          <Button size="sm" class="bg-green-600 hover:bg-green-700" :disabled="submitting" @click="submit">
            <Loader2 v-if="submitting" class="w-4 h-4 mr-1 animate-spin" />
            确认批准
          </Button>
          <Button size="sm" variant="ghost" :disabled="submitting" @click="cancel">取消</Button>
        </div>
      </div>

      <!-- 拒绝确认 -->
      <div v-if="action === 'reject'" class="space-y-2">
        <Textarea
          v-model="reason"
          placeholder="拒绝原因（必填）"
          :rows="2"
          class="text-sm"
        />
        <div class="flex items-center gap-2">
          <Button size="sm" variant="destructive" :disabled="submitting" @click="submit">
            <Loader2 v-if="submitting" class="w-4 h-4 mr-1 animate-spin" />
            确认拒绝
          </Button>
          <Button size="sm" variant="ghost" :disabled="submitting" @click="cancel">取消</Button>
        </div>
      </div>

      <!-- 错误提示 -->
      <div v-if="submitError" class="flex items-center gap-1 text-sm text-destructive">
        <AlertCircle class="w-4 h-4 flex-shrink-0" />
        <span>{{ submitError }}</span>
      </div>
    </CardContent>
  </Card>
</template>
