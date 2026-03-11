<!--
  上下文组装预览组件。

  输入测试消息后调用后端 ContextAssembler 预览 API，
  分段展示 System Prompt / 对话历史 / 记忆检索 / 工具结果，
  每段标注 token 数和占比百分比，支持折叠/展开。
  集成 TokenBudgetChart 展示 token 预算分配环形图。

  @author zsg
  @since 2026-03-16
-->
<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { agentApi } from '@/api/client'
import type { ContextPreviewResponse } from '@/types'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Search, ChevronDown, ChevronRight, AlertCircle, Eye } from 'lucide-vue-next'

const TokenBudgetChart = defineAsyncComponent(() => import('@/components/agent/TokenBudgetChart.vue'))

const props = defineProps<{
  agentId: string
}>()

// ─── 状态 ───
const inputMessage = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const previewData = ref<ContextPreviewResponse | null>(null)

// 各段落折叠状态
const expandedSegments = ref<Set<string>>(new Set())

// ─── 段落配置 ───
const SEGMENTS = [
  { key: 'systemPrompt', label: 'System Prompt', color: '#3b82f6' },
  { key: 'conversationHistory', label: '对话历史', color: '#10b981' },
  { key: 'memoryRetrieval', label: '记忆检索', color: '#f59e0b' },
  { key: 'toolResults', label: '工具结果', color: '#ef4444' },
] as const

// ─── 计算属性 ───
const canPreview = computed(() => inputMessage.value.trim().length > 0 && !loading.value)

// ─── 折叠/展开 ───
function toggleSegment(key: string) {
  if (expandedSegments.value.has(key)) {
    expandedSegments.value.delete(key)
  } else {
    expandedSegments.value.add(key)
  }
}

// ─── 计算百分比 ───
function tokenPercent(tokens: number): string {
  if (!previewData.value || previewData.value.totalBudget <= 0) return '0.0'
  return ((tokens / previewData.value.totalBudget) * 100).toFixed(1)
}

// ─── 调用预览 API ───
async function fetchPreview() {
  const message = inputMessage.value.trim()
  if (!message || loading.value) return

  loading.value = true
  error.value = null

  try {
    previewData.value = await agentApi.contextPreview(props.agentId, message)
    // 默认展开有内容的段落
    expandedSegments.value.clear()
  } catch (e: unknown) {
    const msg = (e as any)?.message ?? '发送内容加载失败'
    error.value = msg
    // 保留用户输入，不清空 inputMessage
  } finally {
    loading.value = false
  }
}

// ─── 键盘事件 ───
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    fetchPreview()
  }
}
</script>

<template>
  <div class="space-y-4">
    <!-- 输入区域 -->
    <Card>
      <CardHeader class="pb-3">
        <CardTitle class="text-base flex items-center gap-2">
          <Search :size="16" />
          发送内容
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div class="flex items-end gap-2">
          <textarea
            v-model="inputMessage"
            :disabled="loading"
            class="flex-1 resize-none rounded-lg border border-border bg-muted/30 px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 disabled:opacity-50"
            :rows="2"
            placeholder="输入测试消息，查看发送给模型的内容..."
            @keydown="onKeydown"
          />
          <Button
            :disabled="!canPreview"
            size="sm"
            class="shrink-0"
            @click="fetchPreview"
          >
            <Eye :size="14" class="mr-1" />
            预览
          </Button>
        </div>
      </CardContent>
    </Card>

    <!-- 错误提示 -->
    <div
      v-if="error"
      class="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3"
    >
      <AlertCircle :size="16" class="text-destructive shrink-0 mt-0.5" />
      <p class="text-sm text-destructive">{{ error }}</p>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading" class="space-y-3">
      <Skeleton class="h-20 w-full" />
      <Skeleton class="h-20 w-full" />
      <Skeleton class="h-20 w-full" />
    </div>

    <!-- 预览结果 -->
    <template v-if="previewData && !loading">
      <!-- 总览统计 -->
      <Card>
        <CardContent class="pt-4">
          <div class="flex items-center gap-4 text-sm">
            <span class="text-muted-foreground">
              总 Token：<strong class="text-foreground">{{ previewData.totalTokens.toLocaleString() }}</strong>
            </span>
            <span class="text-muted-foreground">
              总预算：<strong class="text-foreground">{{ previewData.totalBudget.toLocaleString() }}</strong>
            </span>
            <span class="text-muted-foreground">
              使用率：<strong class="text-foreground">
                {{ previewData.totalBudget > 0 ? ((previewData.totalTokens / previewData.totalBudget) * 100).toFixed(1) : '0.0' }}%
              </strong>
            </span>
            <Badge v-if="previewData.degraded" variant="destructive" class="text-xs">
              已降级
            </Badge>
          </div>
        </CardContent>
      </Card>

      <!-- 分段展示 -->
      <div class="space-y-2">
        <Card
          v-for="seg in SEGMENTS"
          :key="seg.key"
          class="overflow-hidden"
        >
          <!-- 段落头部（可点击折叠/展开） -->
          <button
            type="button"
            class="w-full flex items-center justify-between px-4 py-3 hover:bg-muted/30 transition-colors text-left"
            @click="toggleSegment(seg.key)"
          >
            <div class="flex items-center gap-2">
              <span
                class="inline-block w-3 h-3 rounded-sm shrink-0"
                :style="{ backgroundColor: seg.color }"
              />
              <component
                :is="expandedSegments.has(seg.key) ? ChevronDown : ChevronRight"
                :size="14"
                class="text-muted-foreground"
              />
              <span class="text-sm font-medium text-foreground">{{ seg.label }}</span>
            </div>
            <div class="flex items-center gap-2">
              <Badge variant="secondary" class="text-xs tabular-nums">
                {{ previewData.segments[seg.key].tokens.toLocaleString() }} tokens
              </Badge>
              <Badge variant="outline" class="text-xs tabular-nums">
                {{ tokenPercent(previewData.segments[seg.key].tokens) }}%
              </Badge>
            </div>
          </button>

          <!-- 段落详细内容（折叠区域） -->
          <div
            v-if="expandedSegments.has(seg.key)"
            class="border-t border-border px-4 py-3"
          >
            <pre
              v-if="previewData.segments[seg.key].content"
              class="text-xs text-muted-foreground whitespace-pre-wrap break-words font-mono bg-muted/30 rounded-md p-3 max-h-64 overflow-y-auto"
            >{{ previewData.segments[seg.key].content }}</pre>
            <p
              v-else
              class="text-xs text-muted-foreground italic"
            >
              （无内容）
            </p>
          </div>
        </Card>
      </div>

      <!-- Token 预算分配图表 -->
      <Card>
        <CardHeader class="pb-3">
          <CardTitle class="text-base">Token 预算分配</CardTitle>
        </CardHeader>
        <CardContent>
          <TokenBudgetChart :budget="previewData.tokenBudget" />
        </CardContent>
      </Card>
    </template>
  </div>
</template>
