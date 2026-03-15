<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Search, Trash2, RotateCcw } from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import type {
  ConversationSummary,
  ConversationDetail,
  ConversationListParams,
} from '@/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import { DatePicker } from '@/components/ui/date-picker'
import Pagination from '@/components/common/Pagination.vue'

// ── 筛选状态 ──
const filterQ = ref('')
const filterTimeFrom = ref('')
const filterTimeTo = ref('')

// ── 列表状态 ──
const PAGE_SIZE = 20
const currentPage = ref(0)
const items = ref<ConversationSummary[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// ── 详情面板状态 ──
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailConversation = ref<ConversationDetail | null>(null)

// ── 删除确认对话框 ──
const deleteOpen = ref(false)
const deleting = ref(false)
const deleteConversationId = ref('')
const deleteConversationGoal = ref('')

// ── 数据加载 ──
async function loadConversations() {
  loading.value = true
  error.value = null
  try {
    const params: ConversationListParams = {
      page: currentPage.value,
      size: PAGE_SIZE,
    }
    if (filterQ.value.trim()) params.q = filterQ.value.trim()
    if (filterTimeFrom.value) params.timeFrom = filterTimeFrom.value
    if (filterTimeTo.value) params.timeTo = filterTimeTo.value

    const result = await memoryApi.listConversations(params)
    items.value = result.items
    total.value = result.total
  } catch (e: any) {
    console.error('加载对话列表失败:', e)
    error.value = e?.message || '加载对话列表失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 0
  loadConversations()
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadConversations()
}

// 筛选条件变化时重新加载
watch([filterTimeFrom, filterTimeTo], () => {
  currentPage.value = 0
  loadConversations()
})

onMounted(() => loadConversations())

// ── 详情面板 ──
async function openDetail(conversation: ConversationSummary) {
  detailOpen.value = true
  detailLoading.value = true
  detailConversation.value = null

  try {
    detailConversation.value = await memoryApi.getConversation(conversation.id)
  } catch (e: any) {
    console.error('加载对话详情失败:', e)
  } finally {
    detailLoading.value = false
  }
}

// ── 删除对话 ──
function openDelete(id: string, goal: string) {
  deleteConversationId.value = id
  deleteConversationGoal.value = goal
  deleteOpen.value = true
}

async function handleDelete() {
  deleting.value = true
  try {
    await memoryApi.deleteConversation(deleteConversationId.value)
    deleteOpen.value = false
    detailOpen.value = false
    loadConversations()
  } catch (e: any) {
    console.error('删除对话失败:', e)
    alert(e?.message || '删除对话失败')
  } finally {
    deleting.value = false
  }
}

// ── 辅助函数 ──
function formatDate(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function roleLabel(role: string) {
  switch (role) {
    case 'user': return '用户'
    case 'assistant': return '助手'
    case 'system': return '系统'
    default: return role
  }
}
</script>

<template>
  <div class="space-y-4">
    <!-- 筛选栏 -->
    <div class="detail-card p-4">
      <div class="flex flex-wrap items-end gap-3">
        <!-- 关键词搜索 -->
        <div class="flex-1 min-w-[200px]">
          <label class="text-xs text-muted-foreground mb-1 block">关键词搜索</label>
          <div class="relative">
            <Search class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              v-model="filterQ"
              placeholder="按目标或摘要搜索..."
              class="pl-9"
              @keydown.enter="handleSearch"
            />
          </div>
        </div>

        <!-- 时间范围 -->
        <div class="w-36">
          <label class="text-xs text-muted-foreground mb-1 block">开始时间</label>
          <DatePicker v-model="filterTimeFrom" placeholder="开始日期" class="w-full" />
        </div>
        <div class="w-36">
          <label class="text-xs text-muted-foreground mb-1 block">结束时间</label>
          <DatePicker v-model="filterTimeTo" placeholder="结束日期" class="w-full" />
        </div>

        <!-- 搜索按钮 -->
        <div class="flex items-end">
          <Button @click="handleSearch">搜索</Button>
        </div>
      </div>
    </div>

    <!-- 加载中骨架屏 -->
    <div v-if="loading" class="detail-card p-4 space-y-3">
      <Skeleton v-for="i in 6" :key="i" class="h-10 w-full" />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ error }}</p>
      <Button variant="outline" class="mt-4" @click="loadConversations">
        <RotateCcw class="mr-1.5 size-4" />
        重试
      </Button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="items.length === 0" class="detail-card px-6 py-12 text-center">
      <p class="text-sm text-muted-foreground">暂无对话数据</p>
      <p class="mt-1 text-xs text-muted-foreground">对话记录会在与 Agent 交互过程中自动保存。</p>
    </div>

    <!-- 对话表格 -->
    <div v-else class="detail-card overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60">
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">目标</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">摘要</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">消息数</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
            <th class="px-4 py-3 text-center font-medium text-muted-foreground">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="conv in items"
            :key="conv.id"
            class="border-b border-border/40 cursor-pointer transition-colors hover:bg-muted/50"
            @click="openDetail(conv)"
          >
            <td class="px-4 py-3 font-medium text-foreground max-w-[200px] truncate">
              {{ conv.goal || '-' }}
            </td>
            <td class="px-4 py-3 text-muted-foreground max-w-[300px] truncate">
              {{ conv.summary || '-' }}
            </td>
            <td class="px-4 py-3 text-right tabular-nums">{{ conv.messageCount }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(conv.createdAt) }}</td>
            <td class="px-4 py-3 text-center" @click.stop>
              <Button
                size="sm"
                variant="ghost"
                class="text-destructive hover:text-destructive"
                @click="openDelete(conv.id, conv.goal)"
              >
                <Trash2 class="size-3.5" />
              </Button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 分页控件 -->
      <div class="flex items-center justify-between border-t border-border/60 px-4 py-3">
        <span class="text-xs text-muted-foreground">共 {{ total }} 条</span>
        <Pagination
          v-if="pageCount > 1"
          :page="currentPage"
          :page-count="pageCount"
          size="sm"
          @change="handlePageChange"
        />
      </div>
    </div>

    <!-- 详情 Sheet -->
    <Sheet v-model:open="detailOpen">
      <SheetContent class="w-full sm:max-w-xl overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{{ detailConversation?.goal || '对话详情' }}</SheetTitle>
          <SheetDescription>
            {{ detailConversation?.sessionId ? `会话 ${detailConversation.sessionId}` : '' }}
          </SheetDescription>
        </SheetHeader>

        <!-- 详情加载中 -->
        <div v-if="detailLoading" class="mt-6 space-y-3">
          <Skeleton class="h-5 w-40" />
          <Skeleton class="h-4 w-full" />
          <Skeleton class="h-4 w-3/4" />
          <Skeleton class="h-20 w-full" />
        </div>

        <!-- 详情内容 -->
        <div v-else-if="detailConversation" class="mt-6 space-y-5">
          <!-- 操作按钮 -->
          <div class="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              class="text-destructive hover:text-destructive"
              @click="openDelete(detailConversation!.id, detailConversation!.goal)"
            >
              <Trash2 class="mr-1.5 size-3.5" />
              删除
            </Button>
          </div>

          <!-- 基本信息 -->
          <div class="grid grid-cols-2 gap-3 text-sm">
            <div>
              <span class="text-muted-foreground">目标</span>
              <p class="font-medium">{{ detailConversation.goal || '-' }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">消息数</span>
              <p class="font-medium">{{ detailConversation.messages?.length || 0 }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">创建时间</span>
              <p class="font-medium">{{ formatDate(detailConversation.createdAt) }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">更新时间</span>
              <p class="font-medium">{{ formatDate(detailConversation.updatedAt) }}</p>
            </div>
          </div>

          <!-- 摘要 -->
          <div v-if="detailConversation.summary" class="text-sm">
            <span class="text-muted-foreground">摘要</span>
            <p class="mt-1">{{ detailConversation.summary }}</p>
          </div>

          <!-- 消息列表 -->
          <div class="text-sm">
            <span class="text-muted-foreground">消息列表</span>
            <div class="mt-2 space-y-3">
              <div
                v-for="(msg, idx) in detailConversation.messages"
                :key="msg.id || idx"
                class="rounded-md border border-border/60 p-3"
              >
                <div class="flex items-center justify-between mb-1.5">
                  <span
                    class="rounded-md px-1.5 py-0.5 text-xs font-medium"
                    :class="msg.role === 'user'
                      ? 'bg-primary/10 text-primary'
                      : msg.role === 'assistant'
                        ? 'bg-muted text-muted-foreground'
                        : 'bg-muted text-muted-foreground'"
                  >
                    {{ roleLabel(msg.role) }}
                  </span>
                  <span class="text-xs text-muted-foreground">
                    {{ formatDate(msg.createdAt) }}
                  </span>
                </div>
                <p class="whitespace-pre-wrap text-sm leading-relaxed">{{ msg.content }}</p>
                <div v-if="msg.tokenCount" class="mt-1.5 text-xs text-muted-foreground">
                  Token: {{ msg.tokenCount }}
                  <span v-if="msg.isPinned" class="ml-2">📌 已固定</span>
                  <span v-if="msg.compressionLevel !== 'ORIGINAL'" class="ml-2">
                    压缩: {{ msg.compressionLevel }}
                  </span>
                </div>
              </div>

              <div
                v-if="!detailConversation.messages || detailConversation.messages.length === 0"
                class="py-4 text-center text-muted-foreground"
              >
                暂无消息记录
              </div>
            </div>
          </div>
        </div>
      </SheetContent>
    </Sheet>

    <!-- 删除确认对话框 -->
    <Dialog v-model:open="deleteOpen">
      <DialogContent class="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>确认删除</DialogTitle>
          <DialogDescription>
            确定要删除对话「{{ deleteConversationGoal }}」吗？删除后数据将无法恢复。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" @click="deleteOpen = false">取消</Button>
          <Button variant="destructive" :disabled="deleting" @click="handleDelete">
            {{ deleting ? '删除中...' : '确认删除' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
