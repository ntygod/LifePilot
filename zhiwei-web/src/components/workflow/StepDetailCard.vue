<script setup lang="ts">
/**
 * 步骤详情卡片组件。
 *
 * 按步骤类型展示专属图标、类型标签、结构化信息，
 * 支持嵌套步骤递归缩进展示、错误策略标签、dependsOn 依赖展示。
 */
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import type { StepType } from '@/composables/useWorkflowModel'

interface Props {
  /** 后端返回的原始步骤 JSON 对象 */
  step: any
  /** 步骤序号（从 0 开始） */
  index: number
}

const props = defineProps<Props>()

// 步骤类型元数据
const typeMeta = computed(() => {
  const t = props.step?.type as StepType | undefined
  return t && STEP_TYPE_META[t] ? STEP_TYPE_META[t] : null
})

// 错误策略标签文本
const errorStrategyLabel = computed(() => {
  const es = props.step?.errorStrategy
  if (!es || !es.type) return null
  switch (es.type) {
    case 'retry':
      return `重试 ${es.maxAttempts ?? '?'} 次`
    case 'skip':
      return '跳过'
    case 'fail':
      return '失败终止'
    case 'compensate':
      return '补偿回滚'
    default:
      return null
  }
})

// dependsOn 展示
const dependsOnList = computed(() => {
  const deps = props.step?.dependsOn
  return Array.isArray(deps) && deps.length > 0 ? deps : null
})

// 等待时长人类可读格式
function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`
  const hours = Math.floor(seconds / 3600)
  const mins = Math.floor((seconds % 3600) / 60)
  return mins > 0 ? `${hours} 小时 ${mins} 分钟` : `${hours} 小时`
}

// params 表格数据
function getParams(step: any): [string, string][] {
  const p = step?.params
  if (!p || typeof p !== 'object') return []
  return Object.entries(p) as [string, string][]
}

// prompt 截断展示
const PROMPT_TRUNCATE_LENGTH = 120
</script>

<template>
  <div class="p-sm rounded-lg border border-border bg-muted/30">
    <div class="flex items-start gap-sm">
      <!-- 步骤序号圆圈 -->
      <div
        class="flex-shrink-0 w-6 h-6 rounded-full bg-primary text-primary-foreground flex items-center justify-center text-xs font-medium"
      >
        {{ index + 1 }}
      </div>

      <div class="flex-1 min-w-0">
        <!-- 第一行：类型图标 + 名称 + 类型标签 + 错误策略标签 -->
        <div class="flex items-center gap-xs flex-wrap mb-xs">
          <component
            v-if="typeMeta"
            :is="typeMeta.icon"
            class="w-4 h-4 text-muted-foreground flex-shrink-0"
          />
          <span class="font-medium text-foreground">
            {{ step.name || `步骤 ${index + 1}` }}
          </span>
          <Badge v-if="typeMeta" variant="secondary" class="text-[10px] px-1.5 py-0">
            {{ typeMeta.label }}
          </Badge>
          <Badge v-if="errorStrategyLabel" variant="outline" class="text-[10px] px-1.5 py-0">
            {{ errorStrategyLabel }}
          </Badge>
        </div>

        <!-- 步骤 ID -->
        <div v-if="step.id" class="text-xs text-muted-foreground mb-xs">
          ID: {{ step.id }}
        </div>

        <!-- dependsOn 依赖展示 -->
        <div v-if="dependsOnList" class="text-xs text-muted-foreground mb-xs">
          依赖: {{ dependsOnList.join(', ') }}
        </div>

        <!-- ========== 按类型展示结构化信息 ========== -->

        <!-- SkillStep -->
        <template v-if="step.type === 'skill'">
          <div class="text-xs text-muted-foreground mb-xs">
            Skill: <span class="text-foreground font-mono">{{ step.skillId }}</span>
          </div>
          <table v-if="getParams(step).length > 0" class="text-xs w-full">
            <tr v-for="[k, v] in getParams(step)" :key="k" class="border-b border-border/50 last:border-0">
              <td class="py-0.5 pr-sm text-muted-foreground font-mono whitespace-nowrap">{{ k }}</td>
              <td class="py-0.5 text-foreground break-all">{{ v }}</td>
            </tr>
          </table>
        </template>

        <!-- ToolStep -->
        <template v-else-if="step.type === 'tool'">
          <div class="text-xs text-muted-foreground mb-xs">
            工具: <span class="text-foreground font-mono">{{ step.toolId }}</span>
          </div>
          <table v-if="getParams(step).length > 0" class="text-xs w-full">
            <tr v-for="[k, v] in getParams(step)" :key="k" class="border-b border-border/50 last:border-0">
              <td class="py-0.5 pr-sm text-muted-foreground font-mono whitespace-nowrap">{{ k }}</td>
              <td class="py-0.5 text-foreground break-all">{{ v }}</td>
            </tr>
          </table>
        </template>

        <!-- LlmStep -->
        <template v-else-if="step.type === 'llm'">
          <div class="text-xs text-muted-foreground mb-xs">
            场景: <span class="text-foreground">{{ step.scene }}</span>
          </div>
          <div v-if="step.prompt || step.promptTemplate" class="text-xs mb-xs">
            <span class="text-muted-foreground">Prompt: </span>
            <details class="inline">
              <summary class="cursor-pointer text-foreground hover:text-primary">
                {{ ((step.prompt || step.promptTemplate) as string).slice(0, PROMPT_TRUNCATE_LENGTH) }}{{ ((step.prompt || step.promptTemplate) as string).length > PROMPT_TRUNCATE_LENGTH ? '...' : '' }}
              </summary>
              <pre class="mt-xs p-sm rounded bg-muted text-xs whitespace-pre-wrap break-all overflow-x-auto">{{ step.prompt || step.promptTemplate }}</pre>
            </details>
          </div>
          <div v-if="step.outputSchema" class="text-xs text-muted-foreground">
            OutputSchema: <span class="font-mono text-foreground">{{ step.outputSchema }}</span>
          </div>
        </template>

        <!-- ConditionStep -->
        <template v-else-if="step.type === 'condition'">
          <div class="text-xs text-muted-foreground mb-xs">
            条件: <code class="text-foreground bg-muted px-1 rounded">{{ step.condition }}</code>
          </div>
          <!-- then 分支 -->
          <div v-if="step.thenSteps?.length || step.then?.length" class="mt-xs">
            <div class="text-xs text-muted-foreground mb-xs font-medium">✅ Then 分支:</div>
            <div class="ml-md space-y-xs">
              <StepDetailCard
                v-for="(nested, ni) in (step.thenSteps || step.then)"
                :key="nested.id || ni"
                :step="nested"
                :index="ni"
              />
            </div>
          </div>
          <!-- else 分支 -->
          <div v-if="step.elseSteps?.length || step.else?.length" class="mt-xs">
            <div class="text-xs text-muted-foreground mb-xs font-medium">❌ Else 分支:</div>
            <div class="ml-md space-y-xs">
              <StepDetailCard
                v-for="(nested, ni) in (step.elseSteps || step.else)"
                :key="nested.id || ni"
                :step="nested"
                :index="ni"
              />
            </div>
          </div>
        </template>

        <!-- LoopStep -->
        <template v-else-if="step.type === 'loop'">
          <div class="text-xs text-muted-foreground mb-xs">
            遍历: <code class="text-foreground bg-muted px-1 rounded">{{ step.items }}</code>
            &nbsp;变量: <code class="text-foreground bg-muted px-1 rounded">{{ step.loopVar }}</code>
          </div>
          <div v-if="step.body?.length" class="mt-xs">
            <div class="text-xs text-muted-foreground mb-xs font-medium">🔁 循环体:</div>
            <div class="ml-md space-y-xs">
              <StepDetailCard
                v-for="(nested, ni) in step.body"
                :key="nested.id || ni"
                :step="nested"
                :index="ni"
              />
            </div>
          </div>
        </template>

        <!-- ParallelStep -->
        <template v-else-if="step.type === 'parallel'">
          <div v-if="step.branches?.length" class="mt-xs space-y-xs">
            <div
              v-for="(branch, bi) in step.branches"
              :key="bi"
            >
              <div class="text-xs text-muted-foreground mb-xs font-medium">
                ⚡ 分支 {{ bi + 1 }}:
              </div>
              <div class="ml-md space-y-xs">
                <StepDetailCard
                  v-for="(nested, ni) in branch"
                  :key="nested.id || ni"
                  :step="nested"
                  :index="ni"
                />
              </div>
            </div>
          </div>
        </template>

        <!-- SubWorkflowStep -->
        <template v-else-if="step.type === 'sub-workflow'">
          <div class="text-xs text-muted-foreground mb-xs">
            工作流: <span class="text-foreground font-mono">{{ step.workflowId }}</span>
          </div>
          <table v-if="getParams(step).length > 0" class="text-xs w-full">
            <tr v-for="[k, v] in getParams(step)" :key="k" class="border-b border-border/50 last:border-0">
              <td class="py-0.5 pr-sm text-muted-foreground font-mono whitespace-nowrap">{{ k }}</td>
              <td class="py-0.5 text-foreground break-all">{{ v }}</td>
            </tr>
          </table>
        </template>

        <!-- WaitStep -->
        <template v-else-if="step.type === 'wait'">
          <div class="text-xs text-muted-foreground">
            等待时长: <span class="text-foreground">{{ formatDuration(step.durationSeconds) }}</span>
          </div>
        </template>

        <!-- ApprovalStep -->
        <template v-else-if="step.type === 'approval'">
          <div class="text-xs space-y-xs">
            <div v-if="step.message" class="text-muted-foreground">
              消息: <span class="text-foreground">{{ step.message }}</span>
            </div>
            <div v-if="step.approvers?.length" class="text-muted-foreground">
              审批人: <Badge v-for="a in step.approvers" :key="a" variant="outline" class="text-[10px] px-1.5 py-0 mr-xs">{{ a }}</Badge>
            </div>
            <div class="text-muted-foreground">
              超时: <span class="text-foreground">{{ formatDuration(step.timeoutSeconds) }}</span>
              <span v-if="step.autoApproveOnTimeout" class="ml-sm text-amber-600">（超时自动通过）</span>
              <span v-else class="ml-sm text-muted-foreground">（超时不自动通过）</span>
            </div>
          </div>
        </template>

        <!-- NoopStep — 仅展示类型标签，无额外信息 -->
      </div>
    </div>
  </div>
</template>
