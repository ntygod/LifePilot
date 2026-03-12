<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Bot, Cpu, Database, Wrench } from 'lucide-vue-next'
import { agentApi } from '@/api/client'
import type { Message } from '@/types'
import StreamingText from '@/components/chat/StreamingText.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Slider } from '@/components/ui/slider'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { useAgentStore } from '@/stores/agent'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useSettingsStore } from '@/stores/settings'
import { useToolStore } from '@/stores/tool'
import { useUiStore } from '@/stores/ui'

const ContextPreview = defineAsyncComponent(() => import('@/components/agent/ContextPreview.vue'))
const MarkdownEditor = defineAsyncComponent(() => import('@/components/editor/MarkdownEditor.vue'))

const route = useRoute()
const router = useRouter()
const agentStore = useAgentStore()
const kbStore = useKnowledgeBaseStore()
const toolStore = useToolStore()
const settingsStore = useSettingsStore()
const uiStore = useUiStore()

const agentId = computed(() => route.params.id as string)
const agent = computed(() => agentStore.currentAgent)

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '智能体', to: { name: 'agents' } },
  { label: agent.value?.name ?? '详情' },
])

const pageLoading = ref(true)
const pageError = ref<string | null>(null)
const editingBasic = ref(false)
const basicInfo = ref({ name: '', description: '', tags: [] as string[] })
const systemPrompt = ref('')
const systemPromptDirty = ref(false)
const systemPromptSaving = ref(false)
const llmConfig = ref({ preferredProviderId: '', temperature: 0.7, maxTokens: 2000, topP: 1.0 })
const selectedKbs = ref<Array<{ id: string; name: string; topK?: number; maxContextTokens?: number }>>([])
const enabledTools = ref<string[]>([])
const showKbDialog = ref(false)
const showToolsDialog = ref(false)
const testMessages = ref<Message[]>([])
const testInput = ref('')
const testLoading = ref(false)
const agentMarkdownContent = ref('')
const agentMarkdownLoading = ref(false)
const activeTab = ref<'config' | 'context-preview'>('config')

const agentMarkdownAvailable = computed(() => (
  agent.value?.source === 'MarkdownDefined' || agent.value?.source === 'Builtin'
))

const availableProviders = computed(() => (
  settingsStore.providers.map(provider => ({
    id: provider.id,
    name: provider.displayName || provider.id,
  }))
))

const linkedTools = computed(() => enabledTools.value.map(toolId => {
  const found = toolStore.tools.find(tool => tool.id === toolId)
  return found || {
    id: toolId,
    name: toolId,
    displayName: toolId,
    description: '',
    type: 'PLUGIN',
    riskLevel: 'LOW',
  }
}))

const displaySourceLabel = computed(() => {
  if (agent.value?.source === 'MarkdownDefined') return 'Markdown 定义'
  if (agent.value?.source === 'Builtin') return '内置'
  if (agent.value?.source === 'Custom') return '自定义'
  return agent.value?.source || '自定义'
})

const displayTypeLabel = computed(() => {
  const map: Record<string, string> = {
    default: '默认',
    custom: '自定义',
    workflow: '工作流',
  }
  return map[agent.value?.type ?? ''] ?? agent.value?.type ?? '未分类'
})

const displayStatusLabel = computed(() => (agent.value?.enabled ? '已启用' : '已禁用'))
const statusClass = computed(() => (agent.value?.enabled ? 'status-btn-active' : 'status-btn-inactive'))

onMounted(async () => {
  await loadData()
})

watch(() => route.params.id, async () => {
  editingBasic.value = false
  showKbDialog.value = false
  showToolsDialog.value = false
  activeTab.value = 'config'
  testMessages.value = []
  await loadData()
})

async function loadData() {
  pageLoading.value = true
  pageError.value = null
  agentMarkdownLoading.value = false

  try {
    await agentStore.fetchAgentDetail(agentId.value)
    await Promise.allSettled([
      kbStore.fetchKnowledgeBases(),
      toolStore.fetchTools(),
      settingsStore.fetchProviders(),
    ])

    if (agent.value) {
      initFormData()
      await loadAgentMarkdown()
    }
  } catch (event: any) {
    pageError.value = event?.message || agentStore.error || '加载智能体详情失败。'
  } finally {
    pageLoading.value = false
  }
}

function initFormData() {
  if (!agent.value) return
  basicInfo.value = {
    name: agent.value.name,
    description: agent.value.description || '',
    tags: [...(agent.value.tags || [])],
  }
  systemPrompt.value = agent.value.systemPrompt || ''
  systemPromptDirty.value = false
  llmConfig.value = {
    preferredProviderId: agent.value.preferredProviderId || agent.value.llmConfig?.preferredProviderId || '',
    temperature: agent.value.llmConfig?.temperature ?? 0.7,
    maxTokens: agent.value.llmConfig?.maxTokens ?? 2000,
    topP: agent.value.llmConfig?.topP ?? 1.0,
  }
  selectedKbs.value = (agent.value.knowledgeBases || []).map(item => ({ ...item }))
  enabledTools.value = [...(agent.value.enabledTools || [])]
}

async function loadAgentMarkdown() {
  if (!agentMarkdownAvailable.value) {
    agentMarkdownContent.value = ''
    return
  }
  agentMarkdownLoading.value = true
  try {
    agentMarkdownContent.value = await agentApi.getAgentMarkdown(agentId.value)
  } catch {
    agentMarkdownContent.value = ''
  } finally {
    agentMarkdownLoading.value = false
  }
}

async function handleSaveMarkdown(content: string) {
  await agentApi.updateAgentMarkdown(agentId.value, content)
  await agentStore.fetchAgentDetail(agentId.value)
  await loadAgentMarkdown()
  initFormData()
  uiStore.showToast('success', '智能体定义已保存')
}

async function saveBasicInfo() {
  if (!agent.value) return
  try {
    await agentStore.updateAgent(agent.value.id, {
      name: basicInfo.value.name,
      description: basicInfo.value.description,
      tags: basicInfo.value.tags,
    })
    editingBasic.value = false
    initFormData()
    uiStore.showToast('success', '基础信息已保存')
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '保存失败')
  }
}

async function saveSystemPrompt() {
  if (!agent.value || !systemPromptDirty.value) return
  systemPromptSaving.value = true
  try {
    await agentStore.updateAgent(agent.value.id, { systemPrompt: systemPrompt.value })
    systemPromptDirty.value = false
    initFormData()
    uiStore.showToast('success', '系统提示词已保存')
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '保存失败')
  } finally {
    systemPromptSaving.value = false
  }
}

async function saveLlmConfig() {
  if (!agent.value) return
  try {
    const payload = {
      preferredProviderId: llmConfig.value.preferredProviderId || undefined,
      temperature: llmConfig.value.temperature,
      maxTokens: llmConfig.value.maxTokens,
      topP: llmConfig.value.topP,
    }
    await agentStore.updateAgent(agent.value.id, payload)
    initFormData()
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '保存失败')
  }
}

async function saveKnowledgeBases() {
  if (!agent.value) return
  try {
    const metadata: Record<string, any> = {}
    for (const knowledgeBase of selectedKbs.value) {
      const prefix = `kb.${knowledgeBase.id}.`
      if (knowledgeBase.topK != null) metadata[`${prefix}topK`] = knowledgeBase.topK
      if (knowledgeBase.maxContextTokens != null) metadata[`${prefix}maxContextTokens`] = knowledgeBase.maxContextTokens
    }
    await agentStore.updateAgent(agent.value.id, {
      knowledgeBaseIds: selectedKbs.value.map(knowledgeBase => knowledgeBase.id),
      metadata,
    })
    showKbDialog.value = false
    initFormData()
    uiStore.showToast('success', '知识库关联已保存')
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '保存失败')
  }
}

async function saveTools() {
  if (!agent.value) return
  try {
    await agentStore.updateAgent(agent.value.id, { toolIds: enabledTools.value })
    showToolsDialog.value = false
    initFormData()
    uiStore.showToast('success', '工具配置已保存')
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '保存失败')
  }
}

async function toggleAgent() {
  if (!agent.value) return
  try {
    if (agent.value.enabled) {
      await agentStore.disableAgent(agent.value.id)
      uiStore.showToast('success', '智能体已禁用')
    } else {
      await agentStore.enableAgent(agent.value.id)
      uiStore.showToast('success', '智能体已启用')
    }
    initFormData()
  } catch (event: any) {
    uiStore.showToast('error', event?.message || '操作失败')
  }
}

async function sendTestMessage() {
  if (!agent.value || !testInput.value.trim() || testLoading.value) return
  const userMessage: Message = {
    id: Date.now().toString(),
    role: 'user',
    content: testInput.value,
    timestamp: Date.now(),
  }
  testMessages.value.push(userMessage)
  const content = testInput.value
  testInput.value = ''
  testLoading.value = true

  try {
    const response = await agentApi.testChat(agent.value.id, content)
    testMessages.value.push({
      id: response.messageId,
      role: 'assistant',
      content: response.content,
      timestamp: Date.now(),
    })
  } catch (event: any) {
    testMessages.value.push({
      id: Date.now().toString(),
      role: 'assistant',
      content: `错误：${event?.message || '请求失败'}`,
      timestamp: Date.now(),
      status: 'error',
    })
  } finally {
    testLoading.value = false
  }
}

function toggleKb(kbId: string, kbName: string) {
  const index = selectedKbs.value.findIndex(item => item.id === kbId)
  if (index >= 0) {
    selectedKbs.value.splice(index, 1)
    return
  }
  selectedKbs.value.push({ id: kbId, name: kbName, topK: 5, maxContextTokens: 2000 })
}

function toggleTool(toolId: string) {
  const index = enabledTools.value.indexOf(toolId)
  if (index >= 0) {
    enabledTools.value.splice(index, 1)
    return
  }
  enabledTools.value.push(toolId)
}

function updateTemperature(value: number[] | undefined) {
  if (!value) return
  llmConfig.value.temperature = value[0]
  void saveLlmConfig()
}

function updateTopP(value: number[] | undefined) {
  if (!value) return
  llmConfig.value.topP = value[0]
  void saveLlmConfig()
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('zh-CN')
}

function goBack() {
  router.push('/agents')
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <Button type="button" variant="ghost" class="w-fit" @click="goBack">
            <ArrowLeft class="size-4" />
            返回智能体列表
          </Button>
        </div>

        <template v-if="pageLoading">
          <div class="space-y-5">
            <div class="space-y-3 border-b border-border/70 pb-6">
              <Skeleton class="h-5 w-20" />
              <Skeleton class="h-10 w-72" />
              <Skeleton class="h-5 w-full max-w-[42rem]" />
            </div>
            <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
            </div>
            <Skeleton class="h-[320px] rounded-[calc(var(--radius)+6px)]" />
            <Skeleton class="h-[320px] rounded-[calc(var(--radius)+6px)]" />
          </div>
        </template>

        <StatePanel
          v-else-if="pageError || !agent"
          title="智能体详情暂时不可用"
          :description="pageError || agentStore.error || '没有找到对应的智能体信息。'"
          tone="danger"
        >
          <template #icon>
            <Bot class="size-5" />
          </template>
          <template #actions>
            <Button type="button" variant="outline" @click="goBack">
              返回列表
            </Button>
            <Button type="button" @click="loadData">
              重试
            </Button>
          </template>
        </StatePanel>

        <template v-else>
          <PageHeader
            eyebrow="智能体详情"
            :title="agent.name"
            :description="agent.description || '继续调整模型、知识库、工具和系统提示词，并在同一页里直接测试这个智能体的真实回答。'"
          >
            <template #actions>
              <Button variant="outline" @click="activeTab = 'context-preview'">
                查看发送内容
              </Button>
              <Button :variant="agent.enabled ? 'destructive' : 'outline'" @click="toggleAgent">
                {{ agent.enabled ? '禁用智能体' : '启用智能体' }}
              </Button>
            </template>

            <template #meta>
              <MetricCard label="状态" :value="displayStatusLabel" hint="当前是否可以直接被调用。">
                <template #icon>
                  <Bot class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="默认模型配置" :value="llmConfig.preferredProviderId || '未设置'" hint="当前推理请求默认使用的模型配置入口。">
                <template #icon>
                  <Cpu class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="知识库" :value="selectedKbs.length" hint="当前已经挂载到这个智能体的知识库数量。">
                <template #icon>
                  <Database class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="工具" :value="enabledTools.length" hint="当前已启用、可在回答中调用的工具数量。">
                <template #icon>
                  <Wrench class="size-5" />
                </template>
              </MetricCard>
            </template>
          </PageHeader>

          <Tabs v-model="activeTab" class="space-y-5">
            <TabsList class="inline-flex h-auto flex-wrap rounded-full border border-border/70 bg-muted/55 p-1">
              <TabsTrigger value="config">配置与测试</TabsTrigger>
              <TabsTrigger value="context-preview">发送内容</TabsTrigger>
            </TabsList>

            <TabsContent value="config" class="space-y-5">
              <PageSection eyebrow="基本信息" title="名称、描述与标签" description="先看档案信息，再继续改配置。">
                <template #actions>
                  <Button v-if="!editingBasic" variant="outline" @click="editingBasic = true">
                    编辑
                  </Button>
                </template>

                <div v-if="editingBasic" class="grid gap-4">
                  <div class="space-y-2">
                    <Label>名称</Label>
                    <Input v-model="basicInfo.name" />
                  </div>
                  <div class="space-y-2">
                    <Label>描述</Label>
                    <Textarea v-model="basicInfo.description" :rows="4" class="resize-none" />
                  </div>
                  <div class="space-y-2">
                    <Label>标签</Label>
                    <Input
                      :model-value="basicInfo.tags.join(', ')"
                      placeholder="多个标签用英文逗号分隔"
                      @update:model-value="basicInfo.tags = ($event as string).split(',').map((tag: string) => tag.trim()).filter((tag: string) => tag)"
                    />
                  </div>
                  <div class="flex justify-end gap-3">
                    <Button variant="outline" @click="editingBasic = false; initFormData()">取消</Button>
                    <Button @click="saveBasicInfo">保存</Button>
                  </div>
                </div>

                <div v-else class="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(240px,0.8fr)]">
                  <div class="detail-card p-4 sm:p-5">
                    <div class="space-y-4">
                      <div>
                        <div class="surface-label mb-2 text-[0.68rem]">描述</div>
                        <p class="text-sm leading-7 text-foreground">{{ agent.description || '这个智能体还没有填写描述。' }}</p>
                      </div>
                      <div v-if="agent.tags?.length" class="border-t border-border/60 pt-4">
                        <div class="surface-label mb-3 text-[0.68rem]">标签</div>
                        <div class="flex flex-wrap gap-2">
                          <Badge v-for="tag in agent.tags" :key="tag" variant="secondary">
                            {{ tag }}
                          </Badge>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 p-4">
                    <div class="space-y-4">
                      <div>
                        <div class="surface-label text-[0.68rem]">当前档案</div>
                        <p class="mt-2 text-sm leading-6 text-muted-foreground">
                          这里先确认类型、来源和更新时间，再继续调整模型、知识库与工具。
                        </p>
                      </div>
                      <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                        <span class="surface-chip">类型：{{ displayTypeLabel }}</span>
                        <span class="surface-chip">来源：{{ displaySourceLabel }}</span>
                        <span class="surface-chip">{{ displayStatusLabel }}</span>
                        <span class="surface-chip">更新于 {{ formatDate(agent.updatedAt) }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </PageSection>

              <PageSection
                eyebrow="定义"
                :title="agentMarkdownAvailable ? '智能体定义文件' : '系统提示词'"
                :description="agentMarkdownAvailable ? 'Markdown 定义的智能体可直接编辑源文件；内置智能体仅支持查看。' : '查看或编辑当前智能体的系统提示词。'"
              >
                <template #actions>
                  <div v-if="!agentMarkdownAvailable && systemPromptDirty" class="flex items-center gap-3">
                    <span class="text-xs text-muted-foreground">有未保存改动</span>
                    <Button :disabled="systemPromptSaving" @click="saveSystemPrompt">
                      {{ systemPromptSaving ? '保存中...' : '保存' }}
                    </Button>
                  </div>
                </template>

                <div v-if="agentMarkdownAvailable">
                  <Skeleton v-if="agentMarkdownLoading" class="h-[520px] w-full rounded-[calc(var(--radius)+6px)]" />
                  <MarkdownEditor
                    v-else
                    v-model="agentMarkdownContent"
                    :readonly="agent.source === 'Builtin'"
                    title="智能体定义"
                    :on-save="handleSaveMarkdown"
                  />
                </div>

                <Textarea
                  v-else
                  v-model="systemPrompt"
                  :rows="12"
                  class="font-mono text-sm"
                  placeholder="输入系统提示词..."
                  @update:model-value="systemPromptDirty = true"
                />
              </PageSection>

              <div class="grid gap-5 2xl:grid-cols-2">
                <PageSection eyebrow="模型" title="推理参数" description="调整模型、最大词元数和采样参数。">
                  <div class="grid gap-4">
                    <div class="space-y-2">
                      <Label>模型</Label>
                      <Select v-model="llmConfig.preferredProviderId" @update:model-value="saveLlmConfig">
                        <SelectTrigger>
                          <SelectValue placeholder="选择模型配置" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem v-for="provider in availableProviders" :key="provider.id" :value="provider.id">
                            {{ provider.name }}
                          </SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                    <div class="space-y-2">
                      <Label>最大词元数</Label>
                      <Input v-model.number="llmConfig.maxTokens" type="number" :min="1" @change="saveLlmConfig" />
                    </div>
                    <div class="space-y-3">
                      <Label>温度：{{ llmConfig.temperature }}</Label>
                      <Slider :model-value="[llmConfig.temperature]" :min="0" :max="2" :step="0.1" @update:model-value="updateTemperature" />
                    </div>
                    <div class="space-y-3">
                      <Label>Top-P：{{ llmConfig.topP }}</Label>
                      <Slider :model-value="[llmConfig.topP]" :min="0" :max="1" :step="0.01" @update:model-value="updateTopP" />
                    </div>
                  </div>
                </PageSection>

                <PageSection eyebrow="知识库" :title="`已关联知识库（${selectedKbs.length}）`" description="查看和管理当前智能体已关联的知识库。">
                  <template #actions>
                    <Button variant="outline" @click="showKbDialog = true">管理知识库</Button>
                  </template>
                  <StatePanel v-if="selectedKbs.length === 0" title="还没有关联知识库" description="关联后，智能体会在回答前补充检索结果。">
                    <template #icon><Database class="size-5" /></template>
                  </StatePanel>
                  <div v-else class="space-y-3">
                    <article v-for="knowledgeBase in selectedKbs" :key="knowledgeBase.id" class="list-card p-4">
                      <div class="flex items-center justify-between gap-4">
                        <div>
                          <div class="text-sm font-medium text-foreground">{{ knowledgeBase.name }}</div>
                          <div class="mt-2 flex flex-wrap gap-2 text-xs text-muted-foreground">
                            <span class="surface-chip">Top-K {{ knowledgeBase.topK || 5 }}</span>
                            <span class="surface-chip">最大上下文 {{ knowledgeBase.maxContextTokens || 2000 }} 词元</span>
                          </div>
                        </div>
                        <Badge variant="outline">已关联</Badge>
                      </div>
                    </article>
                  </div>
                </PageSection>

                <PageSection eyebrow="工具" :title="`已启用工具（${enabledTools.length}）`" description="查看和管理当前智能体可调用的工具。">
                  <template #actions>
                    <Button variant="outline" @click="showToolsDialog = true">管理工具</Button>
                  </template>
                  <StatePanel v-if="enabledTools.length === 0" title="还没有启用工具" description="需要调用外部能力时，可先启用工具。">
                    <template #icon><Wrench class="size-5" /></template>
                  </StatePanel>
                  <div v-else class="space-y-3">
                    <article v-for="tool in linkedTools" :key="tool.id" class="list-card p-4">
                      <div class="flex items-center justify-between gap-4">
                        <div>
                          <div class="text-sm font-medium text-foreground">{{ tool.displayName || tool.name }}</div>
                          <div class="text-sm text-muted-foreground">{{ tool.description || '这个工具还没有描述。' }}</div>
                        </div>
                        <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                          <span class="surface-chip">类型：{{ tool.type }}</span>
                          <span class="surface-chip">风险：{{ tool.riskLevel }}</span>
                        </div>
                      </div>
                    </article>
                  </div>
                </PageSection>
              </div>

              <PageSection eyebrow="测试" title="即时对话测试" description="不用离开详情页，直接验证当前配置下的回复表现。">
                <div class="space-y-4">
                  <div class="min-h-[320px] rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/25 p-4">
                    <div v-if="testMessages.length === 0" class="flex h-full min-h-[288px] items-center justify-center text-sm text-muted-foreground">
                      发一条测试消息，检查这个智能体现在会怎么回答。
                    </div>
                    <div v-else class="space-y-4">
                      <div v-for="message in testMessages" :key="message.id" class="flex" :class="message.role === 'user' ? 'justify-end' : 'justify-start'">
                        <div
                          class="max-w-[85%] rounded-[calc(var(--radius)+6px)] px-4 py-3 text-sm shadow-sm"
                          :class="message.role === 'user'
                            ? 'bg-primary text-primary-foreground'
                            : message.status === 'error'
                              ? 'border border-destructive/20 bg-destructive/6 text-foreground'
                              : 'border border-border/70 bg-background text-foreground'"
                        >
                          <StreamingText :content="message.content" />
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="flex flex-col gap-3 sm:flex-row">
                    <Input v-model="testInput" class="flex-1" placeholder="输入测试消息" @keydown.enter="sendTestMessage" />
                    <Button :disabled="!testInput.trim() || testLoading" @click="sendTestMessage">
                      {{ testLoading ? '发送中...' : '发送消息' }}
                    </Button>
                  </div>
                </div>
              </PageSection>
            </TabsContent>

            <TabsContent value="context-preview">
              <PageSection eyebrow="发送内容" title="发送内容" description="查看发送给模型的内容。">
                <ContextPreview :agent-id="agentId" />
              </PageSection>
            </TabsContent>
          </Tabs>
        </template>
      </div>
    </PageContainer>

    <Dialog v-model:open="showKbDialog">
      <DialogContent class="shell-card max-h-[82vh] overflow-y-auto border-border/70 sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>管理知识库</DialogTitle>
          <DialogDescription>选择要挂载到当前智能体的知识库，并继续使用现有的 Top-K 与最大上下文配置。</DialogDescription>
        </DialogHeader>
        <div class="my-4 space-y-3">
          <div v-for="knowledgeBase in kbStore.list" :key="knowledgeBase.id" class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-4">
            <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div class="flex items-start gap-3">
                <Checkbox :model-value="selectedKbs.some(item => item.id === knowledgeBase.id)" @update:model-value="() => toggleKb(knowledgeBase.id, knowledgeBase.name)" />
                <div class="space-y-1">
                  <div class="text-sm font-medium text-foreground">{{ knowledgeBase.name }}</div>
                  <div class="text-sm text-muted-foreground">{{ knowledgeBase.description || '这个知识库还没有描述。' }}</div>
                </div>
              </div>
              <div v-if="selectedKbs.some(item => item.id === knowledgeBase.id)" class="grid gap-3 sm:grid-cols-2">
                <Input
                  :model-value="selectedKbs.find(item => item.id === knowledgeBase.id)?.topK ?? 5"
                  type="number"
                  :min="1"
                  :max="20"
                  placeholder="Top-K"
                  class="w-full sm:w-24"
                  @update:model-value="(value: string | number) => { const found = selectedKbs.find(item => item.id === knowledgeBase.id); if (found) found.topK = Number(value) }"
                />
                <Input
                  :model-value="selectedKbs.find(item => item.id === knowledgeBase.id)?.maxContextTokens ?? 2000"
                  type="number"
                  :min="100"
                  :max="10000"
                  placeholder="最大词元数"
                  class="w-full sm:w-32"
                  @update:model-value="(value: string | number) => { const found = selectedKbs.find(item => item.id === knowledgeBase.id); if (found) found.maxContextTokens = Number(value) }"
                />
              </div>
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="showKbDialog = false">取消</Button>
          <Button @click="saveKnowledgeBases">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="showToolsDialog">
      <DialogContent class="shell-card max-h-[82vh] overflow-y-auto border-border/70 sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>管理工具</DialogTitle>
          <DialogDescription>选择要启用的工具能力并保存到当前智能体。</DialogDescription>
        </DialogHeader>
        <div class="my-4 space-y-3">
          <div v-for="tool in toolStore.tools" :key="tool.id" class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-4">
            <div class="flex items-start justify-between gap-4">
              <div class="space-y-1">
                <div class="text-sm font-medium text-foreground">{{ tool.displayName || tool.name }}</div>
                <div class="text-sm text-muted-foreground">{{ tool.description || '这个工具还没有描述。' }}</div>
                <div class="flex flex-wrap gap-2 pt-1">
                  <Badge variant="outline">{{ tool.type }}</Badge>
                  <Badge variant="secondary">{{ tool.riskLevel }}</Badge>
                </div>
              </div>
              <Checkbox :model-value="enabledTools.includes(tool.id)" @update:model-value="() => toggleTool(tool.id)" />
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="showToolsDialog = false">取消</Button>
          <Button @click="saveTools">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
