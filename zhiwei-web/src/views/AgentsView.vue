<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpRight,
  Bot,
  Cpu,
  Database,
  Plus,
  Search,
  SlidersHorizontal,
  Workflow,
} from 'lucide-vue-next'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { useAgentStore } from '@/stores/agent'
import { useUiStore } from '@/stores/ui'

const router = useRouter()
const agentStore = useAgentStore()
const uiStore = useUiStore()

const searchQuery = ref('')
const typeFilter = ref<string>('all')
const statusFilter = ref<string>('all')
const showCreateDialog = ref(false)
const showFilters = ref(false)

const newAgent = ref({
  name: '',
  description: '',
  tags: [] as string[],
})

const typeLabel: Record<string, string> = {
  default: '默认',
  custom: '自定义',
  workflow: '工作流',
}

onMounted(async () => {
  await agentStore.fetchAgents()
})

const filteredAgents = computed(() => {
  let result = agentStore.agents

  if (searchQuery.value.trim()) {
    const query = searchQuery.value.trim().toLowerCase()
    result = result.filter(agent =>
      agent.name.toLowerCase().includes(query)
      || agent.description?.toLowerCase().includes(query),
    )
  }

  if (typeFilter.value !== 'all') {
    result = result.filter(agent => agent.type === typeFilter.value)
  }

  if (statusFilter.value !== 'all') {
    if (statusFilter.value === 'enabled') {
      result = result.filter(agent => agent.enabled)
    } else if (statusFilter.value === 'disabled') {
      result = result.filter(agent => !agent.enabled)
    }
  }

  return result
})

const totalAgents = computed(() => agentStore.agents.length)
const enabledAgents = computed(() => agentStore.agents.filter(agent => agent.enabled).length)
const workflowAgents = computed(() => agentStore.agents.filter(agent => agent.type === 'workflow').length)
const linkedKnowledgeBases = computed(() => agentStore.agents.reduce((count, agent) => count + agent.knowledgeBaseCount, 0))

const hasFilters = computed(() => (
  Boolean(searchQuery.value.trim()) || typeFilter.value !== 'all' || statusFilter.value !== 'all'
))
const typeFilterLabel = computed(() => {
  if (typeFilter.value === 'all') return '全部类型'
  return typeLabel[typeFilter.value] ?? typeFilter.value
})
const statusFilterLabel = computed(() => {
  if (statusFilter.value === 'enabled') return '已启用'
  if (statusFilter.value === 'disabled') return '已禁用'
  return '全部状态'
})

function resetForm() {
  newAgent.value = {
    name: '',
    description: '',
    tags: [],
  }
}

function openAgent(agentId: string) {
  router.push(`/agents/${agentId}`)
}

async function reloadAgents() {
  await agentStore.fetchAgents()
}

async function handleCreate() {
  if (!newAgent.value.name.trim()) {
    uiStore.showToast('error', '请输入智能体名称')
    return
  }

  try {
    const agent = await agentStore.createAgent({
      name: newAgent.value.name,
      description: newAgent.value.description,
      tags: newAgent.value.tags,
    })
    showCreateDialog.value = false
    resetForm()
    uiStore.showToast('success', '智能体创建成功')
    router.push(`/agents/${agent.id}`)
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '创建失败')
  }
}

function clearFilters() {
  searchQuery.value = ''
  typeFilter.value = 'all'
  statusFilter.value = 'all'
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="智能体"
          title="智能体目录"
          :description="`${enabledAgents} 个启用中，共 ${totalAgents} 个`"
        >
          <template #actions>
            <Button type="button" @click="showCreateDialog = true">
              <Plus class="size-4" />
              新建智能体
            </Button>
          </template>
        </PageHeader>

        <section class="toolbar-strip">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div class="flex flex-1 items-center gap-3">
              <div class="relative min-w-[240px] flex-1 xl:max-w-[28rem]">
                <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  v-model="searchQuery"
                  type="search"
                  placeholder="搜索智能体名称或描述"
                  class="pl-9"
                />
              </div>

              <Button variant="outline" size="sm" @click="showFilters = !showFilters">
                <SlidersHorizontal class="size-4" />
                筛选
              </Button>
            </div>

            <div class="flex items-center gap-3">
              <div class="toolbar-counter">
                <div class="surface-label text-[0.68rem]">结果</div>
                <div class="toolbar-counter-value">{{ filteredAgents.length }}</div>
              </div>
              <Button v-if="hasFilters" type="button" variant="ghost" @click="clearFilters">
                清空筛选
              </Button>
            </div>
          </div>

          <div v-if="showFilters" class="mt-sm rounded-xl border border-border/40 bg-card/60 p-md">
            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:flex">
              <Select v-model="typeFilter">
                <SelectTrigger class="w-full lg:w-[150px]">
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
                <SelectTrigger class="w-full lg:w-[150px]">
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

          <div v-if="hasFilters" class="toolbar-meta text-xs">
            <span v-if="typeFilter !== 'all'" class="surface-chip">类型 {{ typeFilterLabel }}</span>
            <span v-if="statusFilter !== 'all'" class="surface-chip">状态 {{ statusFilterLabel }}</span>
            <span v-if="searchQuery.trim()" class="surface-chip">关键词 {{ searchQuery.trim() }}</span>
          </div>
        </section>

        <section class="space-y-4">
          <h2 class="text-lg font-semibold text-foreground">全部智能体</h2>

          <div v-if="agentStore.loading" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <div
              v-for="index in 6"
              :key="index"
              class="rounded-[calc(var(--radius)+1px)] border border-border/64 bg-card/90 p-4"
            >
              <div class="flex items-center justify-between gap-3">
                <div class="space-y-2">
                  <Skeleton class="h-4 w-28" />
                  <Skeleton class="h-4 w-16 rounded-full" />
                </div>
                <Skeleton class="h-5 w-12 rounded-full" />
              </div>
              <div class="mt-4 space-y-2">
                <Skeleton class="h-4 w-full" />
                <Skeleton class="h-4 w-3/4" />
              </div>
              <div class="mt-5 grid gap-2 sm:grid-cols-2">
                <Skeleton class="h-14 rounded-xl" />
                <Skeleton class="h-14 rounded-xl" />
              </div>
            </div>
          </div>

          <StatePanel
            v-else-if="agentStore.error"
            title="智能体列表加载失败"
            :description="agentStore.error"
            tone="danger"
          >
            <template #actions>
              <Button type="button" variant="outline" @click="reloadAgents">
                重新加载
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="filteredAgents.length === 0"
            :title="hasFilters ? '没有匹配的智能体' : '还没有任何智能体'"
              :description="hasFilters
                ? '可以放宽名称、类型或状态条件后再试。'
                : '创建后可查看模型、知识库和启用状态。'"
          >
            <template #actions>
              <Button v-if="hasFilters" type="button" variant="outline" @click="clearFilters">
                清空筛选
              </Button>
              <Button v-else type="button" @click="showCreateDialog = true">
                <Plus class="size-4" />
                新建智能体
              </Button>
            </template>
          </StatePanel>

          <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <article
              v-for="agent in filteredAgents"
              :key="agent.id"
              class="list-card group cursor-pointer p-4"
              @click="openAgent(agent.id)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0 space-y-2">
                  <div class="flex flex-wrap items-center gap-2">
                    <h3 class="truncate text-base font-semibold text-foreground">
                      {{ agent.name }}
                    </h3>
                    <Badge
                      :variant="agent.enabled ? 'default' : 'secondary'"
                      class="text-xs"
                      :class="agent.enabled ? 'status-btn-active' : 'status-btn-inactive'"
                    >
                      {{ agent.enabled ? '已启用' : '已禁用' }}
                    </Badge>
                  </div>
                  <div class="flex flex-wrap items-center gap-2">
                    <Badge variant="outline" class="text-xs">
                      {{ typeLabel[agent.type] ?? agent.type }}
                    </Badge>
                    <Badge variant="outline" class="text-xs">
                      {{ agent.source || '内置' }}
                    </Badge>
                    <span class="surface-chip">{{ formatDate(agent.updatedAt) }}</span>
                  </div>
                </div>

                <div class="flex size-11 shrink-0 items-center justify-center rounded-[1rem] border border-border/62 bg-background/84 text-primary transition-colors duration-200 group-hover:bg-background">
                  <Bot class="size-4" />
                </div>
              </div>

              <p class="mt-4 line-clamp-3 text-sm leading-6 text-muted-foreground">
                {{ agent.description || '这个智能体还没有描述信息。' }}
              </p>

              <div class="mt-4 flex flex-wrap gap-2 text-xs">
                <span class="surface-chip">
                  <Cpu class="size-3.5 text-primary" />
                  {{ agent.preferredProviderId || '未设置模型' }}
                </span>
                <span class="surface-chip">
                  <Database class="size-3.5 text-primary" />
                  知识库 {{ agent.knowledgeBaseCount }}
                </span>
              </div>

              <div v-if="agent.tags && agent.tags.length > 0" class="mt-4 flex flex-wrap gap-2">
                <Badge
                  v-for="tag in agent.tags.slice(0, 4)"
                  :key="tag"
                  variant="secondary"
                  class="text-xs"
                >
                  {{ tag }}
                </Badge>
              </div>

              <div class="mt-5 flex justify-end">
                <span class="inline-flex items-center gap-1 text-sm font-medium text-primary transition-colors group-hover:text-primary/80">
                  查看详情
                  <ArrowUpRight class="size-4" />
                </span>
              </div>
            </article>
          </div>
        </section>
      </div>
    </PageContainer>

    <FormSheetShell
      :open="showCreateDialog"
      title="新建智能体"
      description="填写名称、描述和类型。"
      @update:open="value => showCreateDialog = value"
      @close="showCreateDialog = false"
    >
      <div class="grid gap-4">
        <div class="space-y-2">
          <Label for="agent-name">名称 *</Label>
          <Input
            id="agent-name"
            v-model="newAgent.name"
            placeholder="输入智能体名称"
          />
        </div>

        <div class="space-y-2">
          <Label for="agent-desc">描述</Label>
          <Textarea
            id="agent-desc"
            v-model="newAgent.description"
            placeholder="简要描述它负责的工作"
            :rows="4"
            class="resize-none"
          />
        </div>
      </div>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <Button variant="outline" @click="showCreateDialog = false">
            取消
          </Button>
          <Button @click="handleCreate">
            创建
          </Button>
        </div>
      </template>
    </FormSheetShell>
  </div>
</template>
