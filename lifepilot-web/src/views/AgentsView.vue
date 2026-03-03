<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import type { AgentSummary } from '@/types'

const router = useRouter()
const agentStore = useAgentStore()
const kbStore = useKnowledgeBaseStore()

const searchQuery = ref('')
const typeFilter = ref<string>('')
const statusFilter = ref<string>('')
const tagsFilter = ref<string[]>([])
const showCreateDialog = ref(false)
const deleteTarget = ref<AgentSummary | null>(null)

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

  if (typeFilter.value) {
    result = result.filter(a => a.type === typeFilter.value)
  }

  if (statusFilter.value) {
    if (statusFilter.value === 'enabled') {
      result = result.filter(a => a.enabled)
    } else if (statusFilter.value === 'disabled') {
      result = result.filter(a => !a.enabled)
    }
  }

  if (tagsFilter.value.length > 0) {
    result = result.filter(a => 
      a.tags?.some(tag => tagsFilter.value.includes(tag))
    )
  }

  return result
})

const allTags = computed(() => {
  const tagSet = new Set<string>()
  agentStore.agents.forEach(a => {
    a.tags?.forEach(tag => tagSet.add(tag))
  })
  return Array.from(tagSet).sort()
})

async function handleCreate() {
  if (!newAgent.value.name.trim()) {
    alert('请输入 Agent 名称')
    return
  }
  try {
    const agent = await agentStore.createAgent(newAgent.value)
    showCreateDialog.value = false
    router.push(`/agents/${agent.id}`)
  } catch (e: any) {
    alert(e.message || '创建失败')
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    await agentStore.deleteAgent(deleteTarget.value.id)
    deleteTarget.value = null
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
          <button
            class="px-md py-sm rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 hover:shadow-md transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            @click="showCreateDialog = true"
          >
            新建 Agent
          </button>
        </div>

        <!-- 搜索和过滤 -->
        <div class="flex flex-wrap gap-sm">
          <div class="flex-1 min-w-[220px]">
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索 Agent 名称或描述..."
              class="w-full px-3 py-2 rounded-2xl border border-input bg-background text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
            />
          </div>
          <select
            v-model="typeFilter"
            class="px-md py-sm rounded-lg border border-input bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部类型</option>
            <option value="default">默认</option>
            <option value="custom">自定义</option>
            <option value="workflow">工作流</option>
          </select>
          <select
            v-model="statusFilter"
            class="px-md py-sm rounded-lg border border-input bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部状态</option>
            <option value="enabled">已启用</option>
            <option value="disabled">已禁用</option>
          </select>
        </div>
      </div>
    </div>

    <!-- Agent 列表 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <div v-if="agentStore.loading" class="text-sm text-muted-foreground">加载中...</div>
        <div v-else-if="filteredAgents.length === 0" class="text-sm text-muted-foreground">
          {{ searchQuery || typeFilter || statusFilter ? '未找到匹配的 Agent' : '暂无 Agent，点击"新建 Agent"创建' }}
        </div>
        <div
          v-else
          class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md"
        >
          <div
            v-for="agent in filteredAgents"
            :key="agent.id"
            class="border border-border rounded-lg p-md hover:border-primary/50 hover:shadow-sm transition-all duration-200 cursor-pointer bg-card"
            @click="router.push(`/agents/${agent.id}`)"
          >
            <div class="flex items-start justify-between mb-xs gap-sm">
              <div class="flex items-center gap-xs">
                <h3 class="font-medium text-foreground text-sm leading-snug">
                  {{ agent.name }}
                </h3>
                <span
                  class="text-xs px-sm py-xs rounded-full"
                  :class="agent.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
                >
                  {{ agent.enabled ? '已启用' : '已禁用' }}
                </span>
              </div>
              <span class="text-xs px-sm py-xs rounded-full bg-accent text-accent-foreground shrink-0">
                {{ typeLabel[agent.type] ?? agent.type }}
              </span>
            </div>
            <p class="text-sm text-muted-foreground mb-sm line-clamp-2 leading-normal">
              {{ agent.description || '无描述' }}
            </p>
            <div class="flex items-center gap-md text-xs text-muted-foreground">
              <span>模型: {{ agent.modelId || '未设置' }}</span>
              <span>知识库: {{ agent.knowledgeBaseCount }}</span>
            </div>
            <div class="flex items-center gap-xs mt-sm">
              <span
                v-for="tag in agent.tags?.slice(0, 3)"
                :key="tag"
                class="text-xs px-sm py-xs rounded-full bg-muted text-muted-foreground"
              >
                {{ tag }}
              </span>
            </div>
            <div class="text-xs text-muted-foreground mt-xs">
              更新于: {{ formatDate(agent.updatedAt) }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 创建对话框 -->
    <div
      v-if="showCreateDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showCreateDialog = false"
    >
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[448px] shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-4">新建 Agent</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">名称 *</label>
            <input
              v-model="newAgent.name"
              type="text"
              placeholder="输入 Agent 名称"
              class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">描述</label>
            <textarea
              v-model="newAgent.description"
              placeholder="输入 Agent 描述"
              rows="3"
              class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">类型</label>
            <select
              v-model="newAgent.type"
              class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <option value="custom">自定义</option>
              <option value="workflow">工作流</option>
            </select>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-6">
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="showCreateDialog = false"
          >
            取消
          </button>
          <button
            class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            @click="handleCreate"
          >
            创建
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认对话框 -->
    <div
      v-if="deleteTarget"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="deleteTarget = null"
    >
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[384px] shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-2">确认删除</h3>
        <p class="text-sm text-muted-foreground mb-4">
          确定要删除 Agent「{{ deleteTarget.name }}」吗？此操作不可撤销。
        </p>
        <div class="flex justify-end gap-2">
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="deleteTarget = null"
          >
            取消
          </button>
          <button
            class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
            @click="handleDelete"
          >
            删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
