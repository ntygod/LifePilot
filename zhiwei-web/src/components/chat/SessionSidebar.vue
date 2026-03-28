<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { ChatSessionDetail, KnowledgeBase } from '@/types'
import { Clock3, Database, Gauge, MessageSquareText, PencilLine, Search, Sparkles, Trash2 } from 'lucide-vue-next'
import InspectorRail from '@/components/layout/InspectorRail.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const props = withDefaults(defineProps<{
  session: ChatSessionDetail | null
  knowledgeBases: KnowledgeBase[]
  messageCount: number
  searchQuery?: string
  statusText?: string
  contextCount?: number
  matchedMessageCount?: number
  showClose?: boolean
}>(), {
  searchQuery: '',
  statusText: '就绪',
  contextCount: 0,
  matchedMessageCount: 0,
  showClose: true,
})

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'updateTitle', title: string): void
  (e: 'update:searchQuery', value: string): void
  (e: 'clear'): void
}>()

const editTitle = ref(props.session?.title ?? '')

const ringRadius = 32
const ringCircumference = 2 * Math.PI * ringRadius

watch(() => props.session?.title, value => {
  editTitle.value = value ?? ''
})

const hasMessageSearch = computed(() => props.searchQuery.trim().length > 0)

const linkedKnowledgeBases = computed(() => {
  const boundIds = props.session?.knowledgeBaseIds ?? []
  if (boundIds.length === 0) return []
  const idSet = new Set(boundIds)
  return props.knowledgeBases.filter(kb => idSet.has(kb.id))
})

const compactionStatus = computed(() => props.session?.compactionStatus ?? null)

const compactionProgress = computed(() => {
  const status = compactionStatus.value
  if (!status?.enabled || status.triggerThresholdTokens <= 0) return 0
  return Math.min(1, status.activeTranscriptTokens / status.triggerThresholdTokens)
})

const compactionProgressPercent = computed(() => Math.round(compactionProgress.value * 100))

const compactionStrokeColor = computed(() => {
  const status = compactionStatus.value
  if (!status?.enabled) return '#94a3b8'
  if (status.readyToCompact) return '#ef4444'
  if (status.thresholdReached) return '#f59e0b'
  if (compactionProgress.value >= 0.72) return '#f59e0b'
  return '#14b8a6'
})

const compactionStrokeOffset = computed(() => ringCircumference * (1 - compactionProgress.value))

const compactionLabel = computed(() => {
  const status = compactionStatus.value
  if (!status) return '--'
  if (!status.enabled) return 'OFF'
  return `${compactionProgressPercent.value}%`
})

const compactionStateTitle = computed(() => {
  const status = compactionStatus.value
  if (!status) return '暂无上下文数据'
  if (!status.enabled) return '压缩已关闭'
  if (status.readyToCompact) return '已满足压缩条件'
  if (status.thresholdReached && !status.minTurnsReached) return '已到阈值，等待轮次'
  if (!status.minTurnsReached) return '轮次积累中'
  return '上下文增长中'
})

const compactionStateDetail = computed(() => {
  const status = compactionStatus.value
  if (!status) return '当前还没有可用于估算的上下文报告。'
  if (!status.enabled) return '当前会话关闭了中途压缩。'
  if (status.readyToCompact) return '下一次循环检查时会优先压缩历史 transcript。'
  if (status.thresholdReached && !status.minTurnsReached) {
    return `已达到 Token 阈值，但完整轮次还差 ${Math.max(0, status.minTurnCount - status.activeTurnCount)} 轮。`
  }
  if (!status.minTurnsReached) {
    return `需要至少 ${status.minTurnCount} 个完整轮次后才会触发压缩。`
  }
  return `距离阈值还差 ${formatNumber(status.remainingTokens)} tokens。`
})

function handleBlur() {
  if (editTitle.value.trim()) {
    emit('updateTitle', editTitle.value.trim())
  }
}

function handleSearchInput(value: string | number) {
  emit('update:searchQuery', String(value))
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function formatNumber(value?: number | null) {
  return new Intl.NumberFormat('zh-CN').format(value ?? 0)
}
</script>

<template>
  <InspectorRail
    title="会话信息"
    :show-close="showClose"
    @close="emit('close')"
  >
    <template #eyebrow>
      会话
    </template>

    <div class="space-y-4">
      <section class="detail-card p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-medium text-foreground">
          <Search class="size-4 text-primary" />
          搜索消息
        </div>
        <div class="relative">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            :model-value="searchQuery"
            type="search"
            placeholder="搜当前对话"
            class="h-9 rounded-full border-border/60 bg-background/70 pl-9 text-sm focus-visible:ring-1"
            @update:model-value="handleSearchInput"
          />
        </div>
      </section>

      <section class="grid grid-cols-2 gap-3">
        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Sparkles class="size-4 text-primary" />
            状态
          </div>
          <p class="text-sm text-muted-foreground">{{ statusText }}</p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <MessageSquareText class="size-4 text-primary" />
            消息
          </div>
          <p class="text-sm text-muted-foreground">共 {{ messageCount }} 条</p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Database class="size-4 text-primary" />
            资料
          </div>
          <p class="text-sm text-muted-foreground">{{ contextCount }} 项</p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Search class="size-4 text-primary" />
            命中
          </div>
          <p class="text-sm text-muted-foreground">
            {{ hasMessageSearch ? `${matchedMessageCount} 条` : '未搜索' }}
          </p>
        </div>
      </section>

      <section class="detail-card p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-medium text-foreground">
          <PencilLine class="size-4 text-primary" />
          会话标题
        </div>
        <div class="space-y-2">
          <Label class="text-xs text-muted-foreground">标题</Label>
          <Input
            v-model="editTitle"
            class="h-9 text-sm"
            @blur="handleBlur"
          />
        </div>
      </section>
      <section class="grid gap-3">
        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Clock3 class="size-4 text-primary" />
            创建时间
          </div>
          <p class="text-sm text-muted-foreground">
            {{ session?.createdAt ? new Date(session.createdAt).toLocaleString() : '-' }}
          </p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Clock3 class="size-4 text-primary" />
            最近更新
          </div>
          <p class="text-sm text-muted-foreground">
            {{ session?.updatedAt ? new Date(session.updatedAt).toLocaleString() : '-' }}
          </p>
        </div>
      </section>

      <section class="detail-card p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-medium text-foreground">
          <Gauge class="size-4 text-primary" />
          上下文压缩
        </div>

        <div class="flex items-center gap-4">
          <div class="relative shrink-0">
            <svg width="92" height="92" viewBox="0 0 92 92" class="-rotate-90">
              <circle
                cx="46"
                cy="46"
                :r="ringRadius"
                fill="none"
                stroke="rgb(226 232 240)"
                stroke-width="8"
              />
              <circle
                cx="46"
                cy="46"
                :r="ringRadius"
                fill="none"
                :stroke="compactionStrokeColor"
                stroke-width="8"
                stroke-linecap="round"
                :stroke-dasharray="ringCircumference"
                :stroke-dashoffset="compactionStrokeOffset"
              />
            </svg>
            <div class="absolute inset-0 flex flex-col items-center justify-center text-center">
              <span class="text-lg font-semibold text-foreground">{{ compactionLabel }}</span>
              <span class="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">compact</span>
            </div>
          </div>

          <div class="min-w-0 space-y-1">
            <p class="text-sm font-medium text-foreground">{{ compactionStateTitle }}</p>
            <p class="text-xs leading-5 text-muted-foreground">{{ compactionStateDetail }}</p>
          </div>
        </div>

        <div class="mt-4 grid grid-cols-2 gap-3">
          <div class="rounded-[calc(var(--radius)+6px)] border border-border/60 bg-muted/20 px-3 py-2.5">
            <p class="text-[11px] uppercase tracking-[0.12em] text-muted-foreground">历史估算</p>
            <p class="mt-1 text-sm font-medium text-foreground">
              {{ formatNumber(compactionStatus?.activeTranscriptTokens) }}
              <span class="text-xs font-normal text-muted-foreground">
                / {{ formatNumber(compactionStatus?.triggerThresholdTokens) }}
              </span>
            </p>
          </div>

          <div class="rounded-[calc(var(--radius)+6px)] border border-border/60 bg-muted/20 px-3 py-2.5">
            <p class="text-[11px] uppercase tracking-[0.12em] text-muted-foreground">完整轮次</p>
            <p class="mt-1 text-sm font-medium text-foreground">
              {{ formatNumber(compactionStatus?.activeTurnCount) }}
              <span class="text-xs font-normal text-muted-foreground">
                / {{ formatNumber(compactionStatus?.minTurnCount) }}
              </span>
            </p>
          </div>

          <div class="rounded-[calc(var(--radius)+6px)] border border-border/60 bg-muted/20 px-3 py-2.5">
            <p class="text-[11px] uppercase tracking-[0.12em] text-muted-foreground">已压缩</p>
            <p class="mt-1 text-sm font-medium text-foreground">
              {{ formatNumber(compactionStatus?.compactionCount) }} 次
            </p>
          </div>

          <div class="rounded-[calc(var(--radius)+6px)] border border-border/60 bg-muted/20 px-3 py-2.5">
            <p class="text-[11px] uppercase tracking-[0.12em] text-muted-foreground">最近一次</p>
            <p class="mt-1 text-sm font-medium text-foreground">
              {{ formatDate(compactionStatus?.lastCompactedAt) }}
            </p>
          </div>
        </div>

        <p class="mt-3 text-xs leading-5 text-muted-foreground">
          进度按 transcript 可见历史的粗估 Token 计算，压缩阈值当前为 {{ compactionStatus?.triggerThresholdPercent ?? 0 }}%。
        </p>
      </section>

      <section class="detail-card p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-medium text-foreground">
          <Database class="size-4 text-primary" />
          已绑定知识库
        </div>

        <div v-if="linkedKnowledgeBases.length > 0" class="space-y-2">
          <div
            v-for="kb in linkedKnowledgeBases"
            :key="kb.id"
            class="list-card flex items-center justify-between gap-3 px-3 py-2 text-sm"
          >
            <span class="min-w-0 truncate text-foreground">{{ kb.name }}</span>
            <RouterLink
              :to="{ name: 'knowledgeBases', query: { id: kb.id } }"
              class="shrink-0 text-xs font-medium text-primary transition-colors hover:text-primary/80"
            >
              查看
            </RouterLink>
          </div>
        </div>

        <p v-else class="text-sm text-muted-foreground">当前没有绑定知识库。</p>
      </section>
    </div>

    <template #footer>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        class="w-full justify-center rounded-full"
        :disabled="messageCount === 0"
        @click="emit('clear')"
      >
        <Trash2 class="size-4" />
        清空对话
      </Button>
    </template>
  </InspectorRail>
</template>
