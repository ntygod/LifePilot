<script setup lang="ts">
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import type { StepType } from '@/composables/useWorkflowModel'

interface Props {
  step: any
  index: number
}

const props = defineProps<Props>()

const typeMeta = computed(() => {
  const type = props.step?.type as StepType | undefined
  return type && STEP_TYPE_META[type] ? STEP_TYPE_META[type] : null
})

const errorStrategyLabel = computed(() => {
  const strategy = props.step?.errorStrategy
  if (!strategy?.type) return null
  switch (strategy.type) {
    case 'retry':
      return `重试 ${strategy.maxAttempts ?? '?'} 次`
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

const dependsOnList = computed(() => {
  const dependsOn = props.step?.dependsOn
  return Array.isArray(dependsOn) && dependsOn.length > 0 ? dependsOn : null
})

const PROMPT_TRUNCATE_LENGTH = 120

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return minutes > 0 ? `${hours} 小时 ${minutes} 分钟` : `${hours} 小时`
}

function getParams(step: any): [string, string][] {
  const params = step?.params
  if (!params || typeof params !== 'object') return []
  return Object.entries(params) as [string, string][]
}

function getPrompt(step: any): string {
  return String(step?.prompt ?? step?.promptTemplate ?? '')
}
</script>

<template>
  <div class="rounded-lg border border-border bg-muted/30 p-sm">
    <div class="flex items-start gap-sm">
      <div class="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-primary text-xs font-medium text-primary-foreground">
        {{ index + 1 }}
      </div>

      <div class="min-w-0 flex-1">
        <div class="mb-xs flex flex-wrap items-center gap-xs">
          <component
            v-if="typeMeta"
            :is="typeMeta.icon"
            class="h-4 w-4 flex-shrink-0 text-muted-foreground"
          />
          <span class="font-medium text-foreground">
            {{ step.name || `步骤 ${index + 1}` }}
          </span>
          <Badge v-if="typeMeta" variant="secondary" class="px-1.5 py-0 text-[10px]">
            {{ typeMeta.label }}
          </Badge>
          <Badge v-if="errorStrategyLabel" variant="outline" class="px-1.5 py-0 text-[10px]">
            {{ errorStrategyLabel }}
          </Badge>
        </div>

        <div v-if="step.id" class="mb-xs text-xs text-muted-foreground">
          ID: {{ step.id }}
        </div>

        <div v-if="dependsOnList" class="mb-xs text-xs text-muted-foreground">
          依赖: {{ dependsOnList.join(', ') }}
        </div>

        <template v-if="step.type === 'skill'">
          <div class="mb-xs text-xs text-muted-foreground">
            技能: <span class="font-mono text-foreground">{{ step.skillId }}</span>
          </div>
          <table v-if="getParams(step).length > 0" class="w-full text-xs">
            <tr v-for="[key, value] in getParams(step)" :key="key" class="border-b border-border/50 last:border-0">
              <td class="whitespace-nowrap py-0.5 pr-sm font-mono text-muted-foreground">{{ key }}</td>
              <td class="break-all py-0.5 text-foreground">{{ value }}</td>
            </tr>
          </table>
        </template>

        <template v-else-if="step.type === 'tool'">
          <div class="mb-xs text-xs text-muted-foreground">
            工具: <span class="font-mono text-foreground">{{ step.toolId }}</span>
          </div>
          <table v-if="getParams(step).length > 0" class="w-full text-xs">
            <tr v-for="[key, value] in getParams(step)" :key="key" class="border-b border-border/50 last:border-0">
              <td class="whitespace-nowrap py-0.5 pr-sm font-mono text-muted-foreground">{{ key }}</td>
              <td class="break-all py-0.5 text-foreground">{{ value }}</td>
            </tr>
          </table>
        </template>

        <template v-else-if="step.type === 'llm'">
          <div class="mb-xs flex flex-wrap items-center gap-xs text-xs text-muted-foreground">
            <span>场景: <span class="text-foreground">{{ step.scene }}</span></span>
            <Badge v-if="step.capability" variant="outline" class="px-1.5 py-0 text-[10px]">
              {{ step.capability }}
            </Badge>
            <Badge v-if="step.modelName" variant="outline" class="px-1.5 py-0 text-[10px]">
              模型 {{ step.modelName }}
            </Badge>
            <Badge v-if="step.preferredProviderId" variant="outline" class="px-1.5 py-0 text-[10px]">
              Provider {{ step.preferredProviderId }}
            </Badge>
          </div>

          <div v-if="getPrompt(step)" class="mb-xs text-xs">
            <span class="text-muted-foreground">提示词：</span>
            <details class="inline">
              <summary class="cursor-pointer text-foreground hover:text-primary">
                {{ getPrompt(step).slice(0, PROMPT_TRUNCATE_LENGTH) }}{{ getPrompt(step).length > PROMPT_TRUNCATE_LENGTH ? '...' : '' }}
              </summary>
              <pre class="mt-xs overflow-x-auto whitespace-pre-wrap break-all rounded bg-muted p-sm text-xs">{{ getPrompt(step) }}</pre>
            </details>
          </div>

          <div v-if="step.media?.length" class="mb-xs space-y-1">
            <div class="text-xs text-muted-foreground">媒体输入：</div>
            <div class="space-y-1">
              <div
                v-for="(media, mediaIndex) in step.media"
                :key="mediaIndex"
                class="rounded-md border border-border/60 bg-background/70 p-2 text-xs"
              >
                <div class="break-all text-foreground">{{ media.source }}</div>
                <div class="mt-1 flex flex-wrap gap-xs text-muted-foreground">
                  <Badge v-if="media.mimeType" variant="secondary" class="px-1.5 py-0 text-[10px]">
                    {{ media.mimeType }}
                  </Badge>
                  <Badge v-if="media.fileName" variant="secondary" class="px-1.5 py-0 text-[10px]">
                    {{ media.fileName }}
                  </Badge>
                </div>
              </div>
            </div>
          </div>

          <div v-if="step.outputSchema" class="text-xs text-muted-foreground">
            输出 Schema：<span class="font-mono text-foreground">{{ step.outputSchema }}</span>
          </div>
        </template>

        <template v-else-if="step.type === 'condition'">
          <div class="mb-xs text-xs text-muted-foreground">
            条件: <code class="rounded bg-muted px-1 text-foreground">{{ step.condition }}</code>
          </div>

          <div v-if="step.thenSteps?.length || step.then?.length" class="mt-xs">
            <div class="mb-xs text-xs font-medium text-muted-foreground">满足条件分支</div>
            <div class="ml-md space-y-xs">
              <StepDetailCard
                v-for="(nested, nestedIndex) in (step.thenSteps || step.then)"
                :key="nested.id || nestedIndex"
                :step="nested"
                :index="nestedIndex"
              />
            </div>
          </div>

          <div v-if="step.elseSteps?.length || step.else?.length" class="mt-xs">
            <div class="mb-xs text-xs font-medium text-muted-foreground">否则分支</div>
            <div class="ml-md space-y-xs">
              <StepDetailCard
                v-for="(nested, nestedIndex) in (step.elseSteps || step.else)"
                :key="nested.id || nestedIndex"
                :step="nested"
                :index="nestedIndex"
              />
            </div>
          </div>
        </template>

        <template v-else-if="step.type === 'loop'">
          <div class="mb-xs text-xs text-muted-foreground">
            遍历: <code class="rounded bg-muted px-1 text-foreground">{{ step.items }}</code>
            &nbsp;变量: <code class="rounded bg-muted px-1 text-foreground">{{ step.loopVar }}</code>
          </div>
          <div v-if="step.body?.length" class="mt-xs">
            <div class="mb-xs text-xs font-medium text-muted-foreground">循环体</div>
            <div class="ml-md space-y-xs">
              <StepDetailCard
                v-for="(nested, nestedIndex) in step.body"
                :key="nested.id || nestedIndex"
                :step="nested"
                :index="nestedIndex"
              />
            </div>
          </div>
        </template>

        <template v-else-if="step.type === 'parallel'">
          <div v-if="step.branches?.length" class="mt-xs space-y-xs">
            <div v-for="(branch, branchIndex) in step.branches" :key="branchIndex">
              <div class="mb-xs text-xs font-medium text-muted-foreground">
                分支 {{ branchIndex + 1 }}
              </div>
              <div class="ml-md space-y-xs">
                <StepDetailCard
                  v-for="(nested, nestedIndex) in branch"
                  :key="nested.id || nestedIndex"
                  :step="nested"
                  :index="nestedIndex"
                />
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="step.type === 'sub-workflow'">
          <div class="mb-xs text-xs text-muted-foreground">
            工作流: <span class="font-mono text-foreground">{{ step.workflowId }}</span>
          </div>
          <table v-if="getParams(step).length > 0" class="w-full text-xs">
            <tr v-for="[key, value] in getParams(step)" :key="key" class="border-b border-border/50 last:border-0">
              <td class="whitespace-nowrap py-0.5 pr-sm font-mono text-muted-foreground">{{ key }}</td>
              <td class="break-all py-0.5 text-foreground">{{ value }}</td>
            </tr>
          </table>
        </template>

        <template v-else-if="step.type === 'wait'">
          <div class="text-xs text-muted-foreground">
            等待时长: <span class="text-foreground">{{ formatDuration(step.durationSeconds) }}</span>
          </div>
        </template>

        <template v-else-if="step.type === 'approval'">
          <div class="space-y-xs text-xs">
            <div v-if="step.message" class="text-muted-foreground">
              消息: <span class="text-foreground">{{ step.message }}</span>
            </div>
            <div v-if="step.approvers?.length" class="text-muted-foreground">
              审批人:
              <Badge
                v-for="approver in step.approvers"
                :key="approver"
                variant="outline"
                class="mr-xs px-1.5 py-0 text-[10px]"
              >
                {{ approver }}
              </Badge>
            </div>
            <div class="text-muted-foreground">
              超时: <span class="text-foreground">{{ formatDuration(step.timeoutSeconds) }}</span>
              <span v-if="step.autoApproveOnTimeout" class="ml-sm text-amber-600">（超时自动通过）</span>
              <span v-else class="ml-sm">（超时不自动通过）</span>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
