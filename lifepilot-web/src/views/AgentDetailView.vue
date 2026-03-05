<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useToolStore } from '@/stores/tool'
import { useSettingsStore } from '@/stores/settings'
import { agentApi } from '@/api/client'
import type { AgentDetail, Message } from '@/types'
import StreamingText from '@/components/chat/StreamingText.vue'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'
import { Slider } from '@/components/ui/slider'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'

const route = useRoute()
const router = useRouter()
const agentStore = useAgentStore()
const kbStore = useKnowledgeBaseStore()
const toolStore = useToolStore()
const settingsStore = useSettingsStore()

const agentId = computed(() => route.params.id as string)
const agent = computed(() => agentStore.currentAgent)

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: 'Agents', to: { name: 'agents' } },
  { label: agent.value?.name ?? '...' }
])

const editingBasic = ref(false)
const basicInfo = ref({ name: '', description: '', tags: [] as string[] })
const systemPrompt = ref('')
const systemPromptDirty = ref(false)
const systemPromptSaving = ref(false)
const modelConfig = ref({ modelId: '', temperature: 0.7, maxTokens: 2000, topP: 1.0 })
const selectedKbs = ref<Array<{ id: string; name: string; topK?: number; maxContextTokens?: number }>>([])
const showKbDialog = ref(false)
const enabledTools = ref<string[]>([])
const showToolsDialog = ref(false)
const testMessages = ref<Message[]>([])
const testInput = ref('')
const testLoading = ref(false)

onMounted(async () => {
  await Promise.all([
    agentStore.fetchAgentDetail(agentId.value),
    kbStore.fetchKnowledgeBases(),
    toolStore.fetchTools(),
    settingsStore.fetchProviders()
  ])
  if (agent.value) initFormData()
})

watch(() => agent.value, (newAgent) => { if (newAgent) initFormData() })

function initFormData() {
  if (!agent.value) return
  basicInfo.value = {
    name: agent.value.name,
    description: agent.value.description || '',
    tags: agent.value.tags || []
  }
  systemPrompt.value = agent.value.systemPrompt || ''
  systemPromptDirty.value = false
  if (agent.value.modelConfig) {
    modelConfig.value = {
      modelId: agent.value.modelConfig.modelId,
      temperature: agent.value.modelConfig.temperature ?? 0.7,
      maxTokens: agent.value.modelConfig.maxTokens ?? 2000,
      topP: agent.value.modelConfig.topP ?? 1.0
    }
  }
  selectedKbs.value = agent.value.knowledgeBases || []
  enabledTools.value = agent.value.enabledTools || []
}

const availableModels = computed(() =>
  settingsStore.providers.map(p => ({ id: p.id, name: p.displayName || p.id }))
)

async function saveBasicInfo() {
  if (!agent.value) return
  try {
    await agentStore.updateAgent(agent.value.id, {
      name: basicInfo.value.name,
      description: basicInfo.value.description,
      tags: basicInfo.value.tags
    })
    editingBasic.value = false
  } catch (e: any) { alert(e.message || '保存失败') }
}

async function saveSystemPrompt() {
  if (!agent.value || !systemPromptDirty.value) return
  systemPromptSaving.value = true
  try {
    await agentStore.updateAgent(agent.value.id, { systemPrompt: systemPrompt.value })
    systemPromptDirty.value = false
  } catch (e: any) { alert(e.message || '保存失败') }
  finally { systemPromptSaving.value = false }
}

watch(systemPrompt, () => { systemPromptDirty.value = true })

async function saveModelConfig() {
  if (!agent.value) return
  try {
    const payload: any = {
      modelId: modelConfig.value.modelId,
      temperature: modelConfig.value.temperature,
      maxTokens: modelConfig.value.maxTokens,
      metadata: {}
    }
    if (modelConfig.value.topP != null) payload.metadata.topP = modelConfig.value.topP
    await agentStore.updateAgent(agent.value.id, payload)
  } catch (e: any) { alert(e.message || '保存失败') }
}

async function saveKnowledgeBases() {
  if (!agent.value) return
  try {
    const metadata: Record<string, any> = {}
    for (const kb of selectedKbs.value) {
      const prefix = `kb.${kb.id}.`
      if (kb.topK != null) metadata[`${prefix}topK`] = kb.topK
      if (kb.maxContextTokens != null) metadata[`${prefix}maxContextTokens`] = kb.maxContextTokens
    }
    await agentStore.updateAgent(agent.value.id, { knowledgeBases: selectedKbs.value, metadata })
    showKbDialog.value = false
  } catch (e: any) { alert(e.message || '保存失败') }
}

async function saveTools() {
  if (!agent.value) return
  try {
    await agentStore.updateAgent(agent.value.id, { enabledTools: enabledTools.value })
    showToolsDialog.value = false
  } catch (e: any) { alert(e.message || '保存失败') }
}

async function toggleAgent() {
  if (!agent.value) return
  try {
    if (agent.value.enabled) await agentStore.disableAgent(agent.value.id)
    else await agentStore.enableAgent(agent.value.id)
  } catch (e: any) { alert(e.message || '操作失败') }
}

async function sendTestMessage() {
  if (!agent.value || !testInput.value.trim() || testLoading.value) return
  const userMessage: Message = { id: Date.now().toString(), role: 'user', content: testInput.value, timestamp: Date.now() }
  testMessages.value.push(userMessage)
  const message = testInput.value
  testInput.value = ''
  testLoading.value = true
  try {
    const response = await agentApi.testChat(agent.value.id, message)
    testMessages.value.push({ id: response.messageId, role: 'assistant', content: response.content, timestamp: Date.now() })
  } catch (e: any) {
    testMessages.value.push({ id: Date.now().toString(), role: 'assistant', content: `错误: ${e.message || '请求失败'}`, timestamp: Date.now(), status: 'error' })
  } finally { testLoading.value = false }
}

function formatDate(dateStr: string) { return new Date(dateStr).toLocaleString('zh-CN') }

function toggleKb(kbId: string, kbName: string) {
  const idx = selectedKbs.value.findIndex(k => k.id === kbId)
  if (idx >= 0) selectedKbs.value.splice(idx, 1)
  else selectedKbs.value.push({ id: kbId, name: kbName, topK: 5, maxContextTokens: 2000 })
}

function toggleTool(toolId: string) {
  const idx = enabledTools.value.indexOf(toolId)
  if (idx >= 0) enabledTools.value.splice(idx, 1)
  else enabledTools.value.push(toolId)
}
</script>
<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部 -->
    <div class="flex-shrink-0 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <Breadcrumb :items="breadcrumbItems" class="mb-2" />
        <div class="flex items-center justify-between gap-sm">
          <div class="flex items-center gap-3">
            <h2 class="text-2xl font-semibold text-foreground">{{ agent?.name || '加载中...' }}</h2>
            <Badge :variant="agent?.enabled ? 'default' : 'secondary'">
              {{ agent?.enabled ? '已启用' : '已禁用' }}
            </Badge>
          </div>
          <Button variant="outline" @click="toggleAgent">
            {{ agent?.enabled ? '禁用' : '启用' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <div v-if="!agent" class="max-w-[1200px] mx-auto px-md md:px-lg py-lg text-sm text-muted-foreground">加载中...</div>
      <div v-else class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6">
        <!-- 基本信息 -->
        <Card>
          <CardHeader>
            <div class="flex items-center justify-between">
              <CardTitle>基本信息</CardTitle>
              <Button v-if="!editingBasic" variant="ghost" size="sm" @click="editingBasic = true">编辑</Button>
            </div>
          </CardHeader>
          <CardContent>
            <div v-if="editingBasic" class="space-y-4">
              <div class="space-y-2">
                <Label>名称</Label>
                <Input v-model="basicInfo.name" />
              </div>
              <div class="space-y-2">
                <Label>描述</Label>
                <Textarea v-model="basicInfo.description" :rows="3" />
              </div>
              <div class="flex justify-end gap-2">
                <Button variant="outline" @click="editingBasic = false">取消</Button>
                <Button @click="saveBasicInfo">保存</Button>
              </div>
            </div>
            <div v-else class="space-y-2 text-sm">
              <div>
                <span class="text-muted-foreground">描述：</span>
                <p class="mt-1">{{ agent.description || '无描述' }}</p>
              </div>
              <div v-if="agent.tags && agent.tags.length > 0">
                <span class="text-muted-foreground">标签：</span>
                <div class="flex flex-wrap gap-1 mt-1">
                  <Badge v-for="tag in agent.tags" :key="tag" variant="secondary">{{ tag }}</Badge>
                </div>
              </div>
              <div><span class="text-muted-foreground">创建时间：</span><span>{{ formatDate(agent.createdAt) }}</span></div>
              <div><span class="text-muted-foreground">更新时间：</span><span>{{ formatDate(agent.updatedAt) }}</span></div>
            </div>
          </CardContent>
        </Card>

        <!-- System Prompt -->
        <Card>
          <CardHeader>
            <div class="flex items-center justify-between">
              <CardTitle>System Prompt</CardTitle>
              <div class="flex items-center gap-2">
                <span v-if="systemPromptDirty" class="text-xs text-muted-foreground">未保存</span>
                <Button v-if="systemPromptDirty" size="sm" :disabled="systemPromptSaving" @click="saveSystemPrompt">
                  {{ systemPromptSaving ? '保存中...' : '保存' }}
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            <Textarea v-model="systemPrompt" :rows="10" placeholder="输入 System Prompt..." class="font-mono text-sm" />
          </CardContent>
        </Card>

        <!-- 模型配置 -->
        <Card>
          <CardHeader><CardTitle>模型配置</CardTitle></CardHeader>
          <CardContent>
            <div class="grid grid-cols-2 gap-4">
              <div class="space-y-2">
                <Label>模型</Label>
                <Select v-model="modelConfig.modelId" @update:model-value="saveModelConfig">
                  <SelectTrigger><SelectValue placeholder="选择模型" /></SelectTrigger>
                  <SelectContent>
                    <SelectItem v-for="model in availableModels" :key="model.id" :value="model.id">{{ model.name }}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div class="space-y-2">
                <Label>温度: {{ modelConfig.temperature }}</Label>
                <Slider :model-value="[modelConfig.temperature]" :min="0" :max="2" :step="0.1" @update:model-value="(v: number[] | undefined) => { if (v) { modelConfig.temperature = v[0]; saveModelConfig() } }" />
              </div>
              <div class="space-y-2">
                <Label>最大 Tokens</Label>
                <Input v-model.number="modelConfig.maxTokens" type="number" :min="1" @change="saveModelConfig" />
              </div>
              <div class="space-y-2">
                <Label>Top-P: {{ modelConfig.topP }}</Label>
                <Slider :model-value="[modelConfig.topP]" :min="0" :max="1" :step="0.01" @update:model-value="(v: number[] | undefined) => { if (v) { modelConfig.topP = v[0]; saveModelConfig() } }" />
              </div>
            </div>
          </CardContent>
        </Card>
        <!-- 关联知识库 -->
        <Card>
          <CardHeader>
            <div class="flex items-center justify-between">
              <CardTitle>关联知识库 ({{ selectedKbs.length }})</CardTitle>
              <Button variant="outline" size="sm" @click="showKbDialog = true">管理</Button>
            </div>
          </CardHeader>
          <CardContent>
            <div v-if="selectedKbs.length === 0" class="text-sm text-muted-foreground">未关联知识库</div>
            <div v-else class="space-y-2">
              <div v-for="kb in selectedKbs" :key="kb.id" class="flex items-center justify-between p-3 rounded-md bg-muted">
                <div>
                  <div class="font-medium text-foreground">{{ kb.name }}</div>
                  <div class="text-xs text-muted-foreground">Top-K: {{ kb.topK || 5 }}, 最大上下文: {{ kb.maxContextTokens || 2000 }} tokens</div>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        <!-- 工具开关 -->
        <Card>
          <CardHeader>
            <div class="flex items-center justify-between">
              <CardTitle>工具能力 ({{ enabledTools.length }})</CardTitle>
              <Button variant="outline" size="sm" @click="showToolsDialog = true">管理</Button>
            </div>
          </CardHeader>
          <CardContent>
            <div v-if="enabledTools.length === 0" class="text-sm text-muted-foreground">未启用工具</div>
            <div v-else class="space-y-2">
              <div v-for="toolId in enabledTools" :key="toolId" class="p-3 rounded-md bg-muted">
                <div class="font-medium text-foreground">{{ toolId }}</div>
              </div>
            </div>
          </CardContent>
        </Card>

        <!-- 测试对话区 -->
        <Card>
          <CardHeader><CardTitle>测试对话</CardTitle></CardHeader>
          <CardContent class="space-y-4">
            <div class="h-64 overflow-y-auto border border-border rounded-md p-4 bg-muted/50">
              <div v-if="testMessages.length === 0" class="text-sm text-muted-foreground text-center py-8">开始与 Agent 对话...</div>
              <div v-else class="space-y-4">
                <div v-for="msg in testMessages" :key="msg.id" class="flex" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
                  <div class="max-w-[80%] rounded-lg p-3" :class="msg.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-background border border-border'">
                    <StreamingText :content="msg.content" />
                  </div>
                </div>
              </div>
            </div>
            <div class="flex gap-2">
              <Input v-model="testInput" placeholder="输入消息..." class="flex-1" @keydown.enter="sendTestMessage" />
              <Button :disabled="!testInput.trim() || testLoading" @click="sendTestMessage">{{ testLoading ? '发送中...' : '发送' }}</Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
    <!-- 知识库管理对话框 -->
    <Dialog v-model:open="showKbDialog">
      <DialogContent class="max-w-[672px] max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>管理知识库</DialogTitle>
          <DialogDescription>选择要关联的知识库并配置参数</DialogDescription>
        </DialogHeader>
        <div class="space-y-2 my-4">
          <div v-for="kb in kbStore.list" :key="kb.id" class="flex items-center justify-between p-3 border border-border rounded-md">
            <div class="flex items-center gap-3">
              <Checkbox :checked="selectedKbs.some(k => k.id === kb.id)" @update:checked="() => toggleKb(kb.id, kb.name)" />
              <div>
                <div class="font-medium text-foreground">{{ kb.name }}</div>
                <div class="text-xs text-muted-foreground">{{ kb.description }}</div>
              </div>
            </div>
            <div v-if="selectedKbs.some(k => k.id === kb.id)" class="flex gap-2">
              <Input :model-value="selectedKbs.find(k => k.id === kb.id)?.topK ?? 5" type="number" :min="1" :max="20" placeholder="Top-K" class="w-20" @update:model-value="(v: string | number) => { const found = selectedKbs.find(k => k.id === kb.id); if (found) found.topK = Number(v) }" />
              <Input :model-value="selectedKbs.find(k => k.id === kb.id)?.maxContextTokens ?? 2000" type="number" :min="100" :max="10000" placeholder="Max Tokens" class="w-32" @update:model-value="(v: string | number) => { const found = selectedKbs.find(k => k.id === kb.id); if (found) found.maxContextTokens = Number(v) }" />
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="showKbDialog = false">取消</Button>
          <Button @click="saveKnowledgeBases">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 工具管理对话框 -->
    <Dialog v-model:open="showToolsDialog">
      <DialogContent class="max-w-[672px] max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>管理工具</DialogTitle>
          <DialogDescription>选择要启用的工具</DialogDescription>
        </DialogHeader>
        <div class="space-y-2 my-4">
          <div v-for="tool in toolStore.tools" :key="tool.id" class="flex items-center justify-between p-3 border border-border rounded-md">
            <div>
              <div class="font-medium text-foreground">{{ tool.displayName || tool.name }}</div>
              <div class="text-xs text-muted-foreground">{{ tool.description }}</div>
              <div class="text-xs text-muted-foreground mt-1">类型: {{ tool.type }}, 风险: {{ tool.riskLevel }}</div>
            </div>
            <Checkbox :checked="enabledTools.includes(tool.id)" @update:checked="() => toggleTool(tool.id)" />
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
