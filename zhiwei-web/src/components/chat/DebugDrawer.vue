<script setup lang="ts">
import { ref } from 'vue'
import type { TokenUsage, ReasoningEvent, ToolCallSummary, SourceSummary } from '@/types'
import { ChevronDown, ChevronRight, X } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'

defineProps<{
  tokenUsage: TokenUsage | null
  modelId: string | null
  prompt: string | null
  reasoningEvents: ReasoningEvent[]
  toolsSummary: ToolCallSummary[]
  kbSources: SourceSummary[]
  traceId?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

// 各 section 折叠状态
const sections = ref({
  token: true,
  prompt: false,
  reasoning: false,
  tools: false,
  kb: false,
})

function toggle(key: keyof typeof sections.value) {
  sections.value[key] = !sections.value[key]
}
</script>

<template>
  <div class="hidden lg:flex w-80 border-l border-border bg-background/60 text-xs flex-col">
    <!-- 头部 -->
    <div class="px-3 py-2 border-b border-border flex items-center justify-between">
      <span class="font-medium text-foreground/80 text-[11px]">最近一轮调试概要</span>
      <button
        type="button"
        class="text-muted-foreground hover:text-foreground transition-colors"
        @click="emit('close')"
      >
        <X :size="14" />
      </button>
    </div>

    <div class="flex-1 overflow-y-auto px-3 py-2 space-y-1 text-[11px] text-muted-foreground">
      <!-- 模型与 Token -->
      <button
        type="button"
        class="w-full flex items-center gap-1 py-1.5 text-left font-medium text-foreground/80 hover:text-foreground"
        @click="toggle('token')"
      >
        <component :is="sections.token ? ChevronDown : ChevronRight" :size="12" />
        <span>模型与 Token</span>
      </button>
      <div v-if="sections.token" class="pl-4 pb-2">
        <template v-if="tokenUsage">
          <p>模型：{{ modelId || '未知模型' }}</p>
          <p>Tokens：{{ tokenUsage.totalTokens }}（提示 {{ tokenUsage.promptTokens }} / 回答 {{ tokenUsage.completionTokens }}）</p>
        </template>
        <p v-else>暂无统计信息，发送一条消息后将在此展示。</p>
      </div>

      <!-- Prompt 摘要 -->
      <button
        type="button"
        class="w-full flex items-center gap-1 py-1.5 text-left font-medium text-foreground/80 hover:text-foreground"
        @click="toggle('prompt')"
      >
        <component :is="sections.prompt ? ChevronDown : ChevronRight" :size="12" />
        <span>Prompt 摘要</span>
      </button>
      <div v-if="sections.prompt" class="pl-4 pb-2">
        <p v-if="prompt">{{ prompt }}</p>
        <p v-else>暂未记录本轮 Prompt 摘要。</p>
      </div>

      <!-- 推理过程 -->
      <button
        type="button"
        class="w-full flex items-center gap-1 py-1.5 text-left font-medium text-foreground/80 hover:text-foreground"
        @click="toggle('reasoning')"
      >
        <component :is="sections.reasoning ? ChevronDown : ChevronRight" :size="12" />
        <span>推理过程</span>
      </button>
      <div v-if="sections.reasoning" class="pl-4 pb-2">
        <div v-if="reasoningEvents.length > 0" class="space-y-1">
          <div
            v-for="event in reasoningEvents"
            :key="event.id"
            class="flex items-start gap-2"
          >
            <div
              class="mt-[3px] w-1.5 h-1.5 rounded-full shrink-0"
              :class="event.type === 'ERROR' ? 'bg-destructive' : event.type.startsWith('TOOL_CALL') ? 'bg-primary' : 'bg-muted-foreground/60'"
            />
            <div>
              <span class="text-foreground/90 font-medium">{{ event.title }}</span>
              <span v-if="event.toolName" class="ml-1 px-1 py-0.5 rounded-full bg-muted text-[10px]">
                工具：{{ event.toolName }}
              </span>
              <p v-if="event.description" class="text-[10px] text-muted-foreground/90">{{ event.description }}</p>
            </div>
          </div>
        </div>
        <p v-else>暂无推理事件。</p>
      </div>

      <!-- 工具统计 -->
      <button
        type="button"
        class="w-full flex items-center gap-1 py-1.5 text-left font-medium text-foreground/80 hover:text-foreground"
        @click="toggle('tools')"
      >
        <component :is="sections.tools ? ChevronDown : ChevronRight" :size="12" />
        <span>工具统计</span>
      </button>
      <div v-if="sections.tools" class="pl-4 pb-2">
        <div v-if="toolsSummary.length > 0" class="space-y-0.5">
          <p>共调用 {{ toolsSummary.length }} 次工具</p>
          <div
            v-for="(tool, i) in toolsSummary.slice(0, 5)"
            :key="tool.toolId + ':' + i"
            class="flex items-center justify-between gap-2"
          >
            <span class="truncate text-foreground/80">{{ tool.toolId }}</span>
            <span class="shrink-0 text-[10px]">
              <span :class="tool.success === false ? 'text-destructive' : 'text-emerald-500'">
                {{ tool.success === false ? '失败' : '成功' }}
              </span>
              <span class="mx-1 text-muted-foreground/60">•</span>
              <span>{{ tool.latencyMs }}ms</span>
            </span>
          </div>
        </div>
        <p v-else>暂无工具调用统计。</p>
      </div>

      <!-- 知识库来源 -->
      <button
        type="button"
        class="w-full flex items-center gap-1 py-1.5 text-left font-medium text-foreground/80 hover:text-foreground"
        @click="toggle('kb')"
      >
        <component :is="sections.kb ? ChevronDown : ChevronRight" :size="12" />
        <span>知识库来源</span>
      </button>
      <div v-if="sections.kb" class="pl-4 pb-2">
        <div v-if="kbSources.length > 0" class="space-y-0.5">
          <p>本轮命中 {{ kbSources.length }} 个知识库：</p>
          <div v-for="kb in kbSources.slice(0, 4)" :key="kb.id" class="truncate text-foreground/80">
            {{ kb.name }} <span class="text-muted-foreground/70">({{ kb.id }})</span>
          </div>
        </div>
        <p v-else>暂无知识库命中记录。</p>
      </div>
    </div>

    <!-- 底部链接 -->
    <div class="px-3 py-2 border-t border-border">
      <RouterLink
        v-if="traceId"
        :to="{ name: 'traces', query: { id: traceId } }"
        class="text-[11px] text-primary hover:underline underline-offset-2"
      >
        查看完整轨迹
      </RouterLink>
      <RouterLink
        v-else
        :to="{ name: 'traces' }"
        class="text-[11px] text-muted-foreground hover:underline underline-offset-2"
      >
        查看轨迹列表
      </RouterLink>
    </div>
  </div>
</template>
