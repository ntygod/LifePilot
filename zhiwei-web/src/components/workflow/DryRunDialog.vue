<!--
  试运行对话框组件。
  在不产生实际副作用的情况下模拟执行工作流，显示解析后的参数、条件分支结果和表达式警告。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { workflowApi } from '@/api/client'
import type { DryRunResult, WorkflowInputParam } from '@/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Loader2, Play, AlertTriangle, CheckCircle, Info } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  workflowId: string
  inputs?: Record<string, WorkflowInputParam>
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const loading = ref(false)
const result = ref<DryRunResult | null>(null)
const error = ref<string | null>(null)
const inputValues = ref<Record<string, string>>({})

// 初始化输入值
watch(() => props.inputs, (newInputs) => {
  if (newInputs) {
    const values: Record<string, string> = {}
    for (const [key, param] of Object.entries(newInputs)) {
      values[key] = param.defaultValue?.toString() ?? ''
    }
    inputValues.value = values
  }
}, { immediate: true, deep: true })

const requiredInputs = computed(() => {
  if (!props.inputs) return []
  return Object.entries(props.inputs)
    .filter(([, param]) => param.required)
    .map(([key]) => key)
})

async function runDryRun() {
  loading.value = true
  error.value = null
  result.value = null

  try {
    // 将输入值转换为正确的类型
    const inputs: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(inputValues.value)) {
      const param = props.inputs?.[key]
      if (param) {
        if (param.type === 'number') {
          inputs[key] = Number(value) || 0
        } else if (param.type === 'boolean') {
          inputs[key] = value === 'true' || value === '1'
        } else if (param.type === 'list') {
          try {
            inputs[key] = JSON.parse(value)
          } catch {
            inputs[key] = value.split(',').map(s => s.trim())
          }
        } else if (param.type === 'map') {
          try {
            inputs[key] = JSON.parse(value)
          } catch {
            inputs[key] = {}
          }
        } else {
          inputs[key] = value
        }
      } else {
        inputs[key] = value
      }
    }

    result.value = await workflowApi.dryRun(props.workflowId, inputs)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '试运行失败'
  } finally {
    loading.value = false
  }
}

function close() {
  emit('update:open', false)
  // 重置状态
  setTimeout(() => {
    result.value = null
    error.value = null
  }, 300)
}

function updateInputValue(key: string, value: string) {
  inputValues.value[key] = value
}

function getStepTypeLabel(type: string) {
  const typeMap: Record<string, string> = {
    skill: 'Skill',
    tool: 'Tool',
    llm: 'LLM',
    condition: '条件',
    loop: '循环',
    parallel: '并行',
    'sub-workflow': '子工作流',
    noop: '空操作',
    wait: '等待',
    approval: '审批',
    notify: '通知'
  }
  return typeMap[type] || type
}
</script>

<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent class="max-w-4xl max-h-[85vh] overflow-auto">
      <DialogHeader>
        <DialogTitle>试运行工作流</DialogTitle>
        <DialogDescription>
          在不产生实际副作用的情况下模拟执行工作流，验证步骤编排、表达式解析和 DAG 依赖是否正确。
        </DialogDescription>
      </DialogHeader>

      <!-- 输入参数表单 -->
      <div v-if="inputs && Object.keys(inputs).length > 0" class="space-y-4 rounded-lg border p-4">
        <h4 class="text-sm font-medium">输入参数</h4>
        <div class="grid gap-4 md:grid-cols-2">
          <div v-for="(param, key) in inputs" :key="key" class="space-y-1">
            <Label :for="`dryrun-input-${key}`">
              {{ param.name || key }}
              <span v-if="param.required" class="text-destructive">*</span>
            </Label>
            <Input
              :id="`dryrun-input-${key}`"
              :model-value="inputValues[key as string] ?? ''"
              :placeholder="param.description || `请输入${param.name || key}`"
              :type="param.type === 'number' ? 'number' : 'text'"
              @update:model-value="updateInputValue(key as string, $event as string)"
            />
            <p v-if="param.description" class="text-xs text-muted-foreground">{{ param.description }}</p>
          </div>
        </div>
      </div>

      <!-- 运行按钮 -->
      <div class="flex justify-end">
        <Button :disabled="loading" @click="runDryRun">
          <Loader2 v-if="loading" class="mr-2 h-4 w-4 animate-spin" />
          <Play v-else class="mr-2 h-4 w-4" />
          {{ loading ? '运行中...' : '开始试运行' }}
        </Button>
      </div>

      <!-- 错误展示 -->
      <Alert v-if="error" variant="destructive">
        <AlertTriangle class="h-4 w-4" />
        <AlertTitle>试运行失败</AlertTitle>
        <AlertDescription>{{ error }}</AlertDescription>
      </Alert>

      <!-- 结果展示 -->
      <div v-if="result" class="space-y-4">
        <!-- 执行顺序 -->
        <div class="rounded-lg border p-4">
          <h4 class="mb-2 text-sm font-medium">执行顺序</h4>
          <div class="flex flex-wrap gap-1">
            <Badge v-for="(stepId, idx) in result.dagOrder" :key="stepId" variant="outline">
              {{ idx + 1 }}. {{ stepId }}
            </Badge>
          </div>
        </div>

        <!-- 步骤详情 -->
        <div class="rounded-lg border p-4">
          <h4 class="mb-2 text-sm font-medium">步骤轨迹</h4>
          <div class="space-y-2">
            <div
              v-for="step in result.steps"
              :key="step.stepId"
              class="rounded border p-3 text-sm"
            >
              <div class="flex items-center gap-2">
                <Badge>{{ getStepTypeLabel(step.stepType) }}</Badge>
                <span class="font-medium">{{ step.stepName || step.stepId }}</span>
                <span class="text-muted-foreground">({{ step.stepId }})</span>
              </div>
              <!-- 解析后的参数 -->
              <div v-if="step.resolvedParams && Object.keys(step.resolvedParams).length > 0" class="mt-2">
                <p class="text-xs text-muted-foreground">解析后的参数:</p>
                <pre class="mt-1 rounded bg-muted p-2 text-xs">{{ JSON.stringify(step.resolvedParams, null, 2) }}</pre>
              </div>
              <!-- 条件结果 -->
              <div v-if="step.conditionResult !== undefined" class="mt-2">
                <Badge :variant="step.conditionResult ? 'default' : 'secondary'">
                  {{ step.conditionResult ? '条件为真' : '条件为假' }}
                </Badge>
                <span v-if="step.branch" class="ml-2 text-xs text-muted-foreground">分支: {{ step.branch }}</span>
              </div>
              <!-- 循环迭代次数 -->
              <div v-if="step.loopIterations !== undefined" class="mt-2">
                <span class="text-xs text-muted-foreground">将执行 {{ step.loopIterations }} 次迭代</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 警告 -->
        <Alert v-if="result.warnings.length > 0" variant="warning">
          <AlertTriangle class="h-4 w-4" />
          <AlertTitle>表达式警告</AlertTitle>
          <AlertDescription>
            <ul class="list-inside list-disc">
              <li v-for="(warning, idx) in result.warnings" :key="idx">{{ warning }}</li>
            </ul>
          </AlertDescription>
        </Alert>

        <!-- 成功提示 -->
        <Alert v-if="!error && result.warnings.length === 0" variant="success">
          <CheckCircle class="h-4 w-4" />
          <AlertTitle>试运行成功</AlertTitle>
          <AlertDescription>工作流配置正确，可以正式执行。</AlertDescription>
        </Alert>
      </div>

      <DialogFooter>
        <Button variant="outline" @click="close">关闭</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
