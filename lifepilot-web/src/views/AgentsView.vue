<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import type { AgentSummary } from '@/types'
import SearchBar from '@/components/common/SearchBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'

const router = useRouter()
const agentStore = useAgentStore()
const kbStore = useKnowledgeBaseStore()

const searchQuery = ref('')
const typeFilter = ref<string>('all')
const statusFilter = ref<string>('all')
const showCreateDialog = ref(false)
const deleteTarget = ref<AgentSummary | null>(null)
const showDeleteConfirm = ref(false)

const newAgent = ref({
  name: '',
  description: '',
  type: 'custom' as 'default' | 'custom' | 'workflow',
  tags: [] as string[]
})

onMounted(async () => {
  await agentStore.fetchAgents()
  await kbStore.fetchKnowledgeBases()
})

const filteredAgents = computed(() => {
  let result = agentStore.agents

  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    result = result.filter(a =>
      a.name.toLowerCase().includes(q) ||
      a.description?.toLowerCase().includes(q)
    )
  }

  if (typeFilter.value && typeFilter.value !== 'all') {
    result = result.filter(a => a.type === typeFilter.value)
  }

  if (statusFilter.value && statusFilter.value !== 'all') {
    if (statusFilter.value === 'enabled') {
      result = result.filter(a => a.enabled)
    } else if (statusFilter.value === 'disabled') {
      result = result.filter(a => !a.enabled)
    }
  }

  return result
})

async function handleCreate() {
  if (!newAgent.value.name.trim()) {
    alert('请输入 Agent 名称')
    return
  }
  try {
    const agent = await agentStore.createAgent(newAgent.value)
    showCreateDialog.value = false
    newAgent.value = { name: '', description: '', type: 'custom', tags: [] }
    router.push(`/agents/${agent.id}`)
  } catch (e: any) {
    alert(e.message || '创建失败')
  }
}

function confirmDelete(agent: AgentSummary) {
  deleteTarget.value = agent
  showDeleteConfirm.value = true
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    await agentStore.deleteAgent(deleteTarget.value.id)
    deleteTarget.value = null
    showDeleteConfirm.value = false
  } catch (e: any) {
    alert(e.message || '删除失败')
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('zh-CN')
}

const typeLabel: Record<string, string> = {
  default: '默认',
  custom: '自定义',
  workflow: '工作流'
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部操作栏 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <div class="flex items-center justify-between mb-md gap-sm">
          <h2 class="text-2xl font-semibold text-foreground leading-tight">
            Agent 管理
          </h2>
          <Button @click="showCreateDialog = true">
            新建 Agent
          </Button>
        </div>

        <!-- 搜索和过滤 -->
        <div class="flex flex-wrap gap-sm">
          <SearchBar
            v-model="searchQuery"
            placeholder="搜索 Agent 名称或描述..."
            class="flex-1 min-w-[220px]"
          />
          <Select v-model="typeFilter">
            <SelectTrigger class="w-[130px]">
              <SelectValue placeholder="全部类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部类型</SelectItem>
              <SelectItem value="default">默认</SelectItem>
              <SelectItem value="custom">自定义</SelectItem>
              <SelectItem value="workflow">工作流</SelectItem>
            </SelectContent>
          </Select>
          <Select v-model="statusFilter">
            <SelectTrigger class="w-[130px]">
              <SelectValue placeholder="全部状态" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="enabled">已启用</SelectItem>
              <SelectItem value="disabled">已禁用</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>

    <!-- Agent 列表 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- Skeleton 加载占位符 -->
        <div v-if="agentStore.loading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md">
          <Card v-for="i in 6" :key="i">
            <CardHeader class="pb-2">
              <div class="flex items-center justify-between">
                <div class="flex items-center gap-xs">
                  <Skeleton class="h-5 w-24" />
                  <Skeleton class="h-5 w-14 rounded-full" />
                </div>
                <Skeleton class="h-5 w-14 rounded-full" />
              </div>
            </CardHeader>
            <CardContent class="pb-3">
              <Skeleton class="h-4 w-full mb-2" />
              <Skeleton class="h-4 w-2/3 mb-3" />
              <div class="flex gap-md">
                <Skeleton class="h-3 w-20" />
                <Skeleton class="h-3 w-16" />
              </div>
              <div class="flex gap-xs mt-2">
                <Skeleton class="h-5 w-12 rounded-full" />
                <Skeleton class="h-5 w-12 rounded-full" />
              </div>
            </CardContent>
          </Card>
        </div>

        <!-- 空状态 -->
        <EmptyState
          v-else-if="filteredAgents.length === 0 && !searchQuery && typeFilter === 'all' && statusFilter === 'all'"
          icon="🤖"
          title="暂无 Agent"
          description="点击「新建 Agent」创建你的第一个 Agent"
        />

        <!-- 搜索/过滤无结果 -->
        <EmptyState
          v-else-if="filteredAgents.length === 0"
          icon="🔍"
          title="未找到匹配的 Agent"
          description="尝试调整搜索关键词或筛选条件"
        />

        <!-- Agent 卡片网格 -->
        <div
          v-else
          class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md"
        >
          <Card
            v-for="agent in filteredAgents"
            :key="agent.id"
            class="list-card cursor-pointer group"
            @click="router.push(`/agents/${agent.id}`)"
          >
            <CardHeader class="pb-2">
              <div class="flex items-start justify-between gap-sm">
                <div class="flex items-center gap-xs">
                  <CardTitle class="text-sm leading-snug">
                    {{ agent.name }}
                  </CardTitle>
                  <Badge
                    :variant="agent.enabled ? 'default' : 'secondary'"
                    class="text-xs"
                  >
                    {{ agent.enabled ? '已启用' : '已禁用' }}
                  </Badge>
                </div>
                <Badge variant="outline" class="shrink-0 text-xs">
                  {{ typeLabel[agent.type] ?? agent.type }}
                </Badge>
              </div>
            </CardHeader>
            <CardContent class="pb-3">
              <p class="text-sm text-muted-foreground mb-sm line-clamp-2 leading-normal">
                {{ agent.description || '无描述' }}
              </p>
              <div class="flex items-center gap-md text-xs text-muted-foreground">
                <span>模型: {{ agent.modelId || '未设置' }}</span>
                <span>知识库: {{ agent.knowledgeBaseCount }}</span>
              </div>
              <div v-if="agent.tags && agent.tags.length > 0" class="flex items-center gap-xs mt-sm">
                <Badge
                  v-for="tag in agent.tags.slice(0, 3)"
                  :key="tag"
                  variant="secondary"
                  class="text-xs"
                >
                  {{ tag }}
                </Badge>
              </div>
              <div class="text-xs text-muted-foreground mt-xs">
                更新于: {{ formatDate(agent.updatedAt) }}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>

    <!-- 创建对话框 -->
    <Dialog v-model:open="showCreateDialog">
      <DialogContent class="sm:max-w-[448px]">
        <DialogHeader>
          <DialogTitle>新建 Agent</DialogTitle>
          <DialogDescription>创建一个新的 Agent 实例</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-2">
          <div class="space-y-2">
            <Label for="agent-name">名称 *</Label>
            <Input
              id="agent-name"
              v-model="newAgent.name"
              placeholder="输入 Agent 名称"
            />
          </div>
          <div class="space-y-2">
            <Label for="agent-desc">描述</Label>
            <Textarea
              id="agent-desc"
              v-model="newAgent.description"
              placeholder="输入 Agent 描述"
              :rows="3"
              class="resize-none"
            />
          </div>
          <div class="space-y-2">
            <Label>类型</Label>
            <Select v-model="newAgent.type">
              <SelectTrigger class="w-full">
                <SelectValue placeholder="选择类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="custom">自定义</SelectItem>
                <SelectItem value="workflow">工作流</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="showCreateDialog = false">
            取消
          </Button>
          <Button @click="handleCreate">
            创建
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 删除确认对话框 -->
    <ConfirmDialog
      v-if="deleteTarget"
      :show="showDeleteConfirm"
      title="确认删除"
      :message="`确定要删除 Agent「${deleteTarget.name}」吗？此操作不可撤销。`"
      confirm-label="删除"
      cancel-label="取消"
      confirm-variant="destructive"
      @confirm="handleDelete"
      @cancel="deleteTarget = null; showDeleteConfirm = false"
      @update:show="showDeleteConfirm = $event"
    />
  </div>
</template>
