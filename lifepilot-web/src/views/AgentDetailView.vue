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

const route = useRoute()
const router = useRouter()
const agentStore = useAgentStore()
const kbStore = useKnowledgeBaseStore()
const toolStore = useToolStore()
const settingsStore = useSettingsStore()

const agentId = computed(() => route.params.id as string)
const agent = computed(() => agentStore.currentAgent)

// 面包屑导航
const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: 'Agents', to: { name: 'agents' } },
  { label: agent.value?.name ?? '...' }
])

// 基本信息
const editingBasic = ref(false)
const basicInfo = ref({
  name: '',
  description: '',
  tags: [] as string[]
})

// System Prompt
const systemPrompt = ref('')
const systemPromptDirty = ref(false)
const systemPromptSaving = ref(false)

// 模型配置
const modelConfig = ref({
  modelId: '',
  temperature: 0.7,
  maxTokens: 2000,
  topP: 1.0
})

// 知识库关联
const selectedKbs = ref<Array<{ id: string; name: string; topK?: number; maxContextTokens?: number }>>([])
const showKbDialog = ref(false)

// 工具开关
const enabledTools = ref<string[]>([])
const showToolsDialog = ref(false)

// 测试对话
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
  
  if (agent.value) {
    initFormData()
  }
})

watch(() => agent.value, (newAgent) => {
  if (newAgent) {
    initFormData()
  }
})

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

const availableModels = computed(() => {
  return settingsStore.providers.map(p => ({
    id: p.id,
    name: p.displayName || p.id
  }))
})

async function saveBasicInfo() {
  if (!agent.value) return
  try {
    await agentStore.updateAgent(agent.value.id, {
      name: basicInfo.value.name,
      description: basicInfo.value.description,
      tags: basicInfo.value.tags
    })
    editingBasic.value = false
  } catch (e: any) {
    alert(e.message || '保存失败')
  }
}

async function saveSystemPrompt() {
  if (!agent.value || !systemPromptDirty.value) return
  systemPromptSaving.value = true
  try {
    await agentStore.updateAgent(agent.value.id, {
      systemPrompt: systemPrompt.value
    })
    systemPromptDirty.value = false
  } catch (e: any) {
    alert(e.message || '保存失败')
  } finally {
    systemPromptSaving.value = false
  }
}

watch(systemPrompt, () => {
  systemPromptDirty.value = true
})

async function saveModelConfig() {
  if (!agent.value) return
  try {
    const payload: any = {
      // 后端 UpdateAgentRequest 字段
      modelId: modelConfig.value.modelId,
      temperature: modelConfig.value.temperature,
      maxTokens: modelConfig.value.maxTokens,
      // 模型高级参数（如 topP）通过 metadata 透传
      metadata: {}
    }
    if (modelConfig.value.topP != null) {
      payload.metadata.topP = modelConfig.value.topP
    }
    await agentStore.updateAgent(agent.value.id, payload)
  } catch (e: any) {
    alert(e.message || '保存失败')
  }
}

async function saveKnowledgeBases() {
  if (!agent.value) return
  try {
    const kbIds = selectedKbs.value.map(kb => kb.id)
    const metadata: Record<string, any> = {}

    // 将每个知识库的配置写入 metadata（kb.{id}.topK / kb.{id}.maxContextTokens）
    for (const kb of selectedKbs.value) {
      const prefix = `kb.${kb.id}.`
      if (kb.topK != null) {
        metadata[`${prefix}topK`] = kb.topK
      }
      if (kb.maxContextTokens != null) {
        metadata[`${prefix}maxContextTokens`] = kb.maxContextTokens
      }
    }

    const payload: any = {
      knowledgeBaseIds: kbIds,
      metadata
    }

    await agentStore.updateAgent(agent.value.id, payload)
    showKbDialog.value = false
  } catch (e: any) {
    alert(e.message || '保存失败')
  }
}

async function saveTools() {
  if (!agent.value) return
  try {
    const payload: any = {
      // 后端 UpdateAgentRequest 中的 toolIds
      toolIds: enabledTools.value
    }
    await agentStore.updateAgent(agent.value.id, payload)
    showToolsDialog.value = false
  } catch (e: any) {
    alert(e.message || '保存失败')
  }
}

async function toggleAgent() {
  if (!agent.value) return
  try {
    if (agent.value.enabled) {
      await agentStore.disableAgent(agent.value.id)
    } else {
      await agentStore.enableAgent(agent.value.id)
    }
  } catch (e: any) {
    alert(e.message || '操作失败')
  }
}

async function sendTestMessage() {
  if (!agent.value || !testInput.value.trim() || testLoading.value) return
  
  const userMessage: Message = {
    id: Date.now().toString(),
    role: 'user',
    content: testInput.value,
    timestamp: Date.now()
  }
  testMessages.value.push(userMessage)
  const message = testInput.value
  testInput.value = ''
  testLoading.value = true
  
  try {
    const response = await agentApi.testChat(agent.value.id, message)
    const assistantMessage: Message = {
      id: response.messageId,
      role: 'assistant',
      content: response.content,
      timestamp: Date.now()
    }
    testMessages.value.push(assistantMessage)
  } catch (e: any) {
    const errorMessage: Message = {
      id: Date.now().toString(),
      role: 'assistant',
      content: `错误: ${e.message || '请求失败'}`,
      timestamp: Date.now(),
      status: 'error'
    }
    testMessages.value.push(errorMessage)
  } finally {
    testLoading.value = false
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部 -->
    <div class="flex-shrink-0 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <!-- 面包屑导航 -->
        <Breadcrumb :items="breadcrumbItems" class="mb-2" />
        <div class="flex items-center justify-between gap-sm">
          <div class="flex items-center gap-3">
            <h2 class="text-2xl font-semibold text-foreground">
              {{ agent?.name || '加载中...' }}
            </h2>
            <span
              class="text-xs px-2 py-0.5 rounded-full"
              :class="agent?.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
            >
              {{ agent?.enabled ? '已启用' : '已禁用' }}
            </span>
          </div>
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="toggleAgent"
          >
            {{ agent?.enabled ? '禁用' : '启用' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <div v-if="!agent" class="max-w-[1200px] mx-auto px-md md:px-lg py-lg text-sm text-muted-foreground">
        加载中...
      </div>
      <div v-else class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6">
        <!-- 基本信息 -->
        <div class="border border-border rounded-lg p-6">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-foreground">基本信息</h3>
            <button
              v-if="!editingBasic"
              class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              @click="editingBasic = true"
            >
              编辑
            </button>
          </div>
          
          <div v-if="editingBasic" class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">名称</label>
              <input
                v-model="basicInfo.name"
                type="text"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">描述</label>
              <textarea
                v-model="basicInfo.description"
                rows="3"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
              />
            </div>
            <div class="flex justify-end gap-2">
              <button
                class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
                @click="editingBasic = false"
              >
                取消
              </button>
              <button
                class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                @click="saveBasicInfo"
              >
                保存
              </button>
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
                <span
                  v-for="tag in agent.tags"
                  :key="tag"
                  class="text-xs px-2 py-0.5 rounded-full bg-accent text-accent-foreground"
                >
                  {{ tag }}
                </span>
              </div>
            </div>
            <div>
              <span class="text-muted-foreground">创建时间：</span>
              <span>{{ formatDate(agent.createdAt) }}</span>
            </div>
            <div>
              <span class="text-muted-foreground">更新时间：</span>
              <span>{{ formatDate(agent.updatedAt) }}</span>
            </div>
          </div>
        </div>

        <!-- System Prompt -->
        <div class="border border-border rounded-lg p-6">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-foreground">System Prompt</h3>
            <div class="flex items-center gap-2">
              <span
                v-if="systemPromptDirty"
                class="text-xs text-muted-foreground"
              >
                未保存
              </span>
              <button
                v-if="systemPromptDirty"
                class="text-sm px-3 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                :disabled="systemPromptSaving"
                @click="saveSystemPrompt"
              >
                {{ systemPromptSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
          <textarea
            v-model="systemPrompt"
            rows="10"
            placeholder="输入 System Prompt..."
            class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground font-mono text-sm focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          />
        </div>

        <!-- 模型配置 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">模型配置</h3>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">模型</label>
              <select
                v-model="modelConfig.modelId"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                @change="saveModelConfig"
              >
                <option value="">选择模型</option>
                <option
                  v-for="model in availableModels"
                  :key="model.id"
                  :value="model.id"
                >
                  {{ model.name }}
                </option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                温度: {{ modelConfig.temperature }}
              </label>
              <input
                v-model.number="modelConfig.temperature"
                type="range"
                min="0"
                max="2"
                step="0.1"
                class="w-full"
                @change="saveModelConfig"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">最大 Tokens</label>
              <input
                v-model.number="modelConfig.maxTokens"
                type="number"
                min="1"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                @change="saveModelConfig"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                Top-P: {{ modelConfig.topP }}
              </label>
              <input
                v-model.number="modelConfig.topP"
                type="range"
                min="0"
                max="1"
                step="0.01"
                class="w-full"
                @change="saveModelConfig"
              />
            </div>
          </div>
        </div>

        <!-- 关联知识库 -->
        <div class="border border-border rounded-lg p-6">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-foreground">
              关联知识库 ({{ selectedKbs.length }})
            </h3>
            <button
              class="text-sm px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors"
              @click="showKbDialog = true"
            >
              管理
            </button>
          </div>
          <div v-if="selectedKbs.length === 0" class="text-sm text-muted-foreground">
            未关联知识库
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="kb in selectedKbs"
              :key="kb.id"
              class="flex items-center justify-between p-3 rounded-md bg-muted"
            >
              <div>
                <div class="font-medium text-foreground">{{ kb.name }}</div>
                <div class="text-xs text-muted-foreground">
                  Top-K: {{ kb.topK || 5 }}, 最大上下文: {{ kb.maxContextTokens || 2000 }} tokens
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 工具开关 -->
        <div class="border border-border rounded-lg p-6">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-foreground">
              工具能力 ({{ enabledTools.length }})
            </h3>
            <button
              class="text-sm px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors"
              @click="showToolsDialog = true"
            >
              管理
            </button>
          </div>
          <div v-if="enabledTools.length === 0" class="text-sm text-muted-foreground">
            未启用工具
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="toolId in enabledTools"
              :key="toolId"
              class="p-3 rounded-md bg-muted"
            >
              <div class="font-medium text-foreground">{{ toolId }}</div>
            </div>
          </div>
        </div>

        <!-- 测试对话区 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">测试对话</h3>
          <div class="space-y-4">
            <div class="h-64 overflow-y-auto border border-border rounded-md p-4 bg-muted/50">
              <div v-if="testMessages.length === 0" class="text-sm text-muted-foreground text-center py-8">
                开始与 Agent 对话...
              </div>
              <div v-else class="space-y-4">
                <div
                  v-for="msg in testMessages"
                  :key="msg.id"
                  class="flex"
                  :class="msg.role === 'user' ? 'justify-end' : 'justify-start'"
                >
                  <div
                    class="max-w-[80%] rounded-lg p-3"
                    :class="msg.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-background border border-border'"
                  >
                    <StreamingText :content="msg.content" />
                  </div>
                </div>
              </div>
            </div>
            <div class="flex gap-2">
              <input
                v-model="testInput"
                type="text"
                placeholder="输入消息..."
                class="flex-1 px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                @keydown.enter="sendTestMessage"
              />
              <button
                class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                :disabled="!testInput.trim() || testLoading"
                @click="sendTestMessage"
              >
                {{ testLoading ? '发送中...' : '发送' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 知识库管理对话框 -->
    <div
      v-if="showKbDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showKbDialog = false"
    >
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[672px] shadow-lg max-h-[80vh] overflow-y-auto">
        <h3 class="text-lg font-semibold text-foreground mb-4">管理知识库</h3>
        <div class="space-y-2 mb-4">
          <div
            v-for="kb in kbStore.list"
            :key="kb.id"
            class="flex items-center justify-between p-3 border border-border rounded-md"
          >
            <div class="flex items-center gap-3">
              <input
                type="checkbox"
                :checked="selectedKbs.some(k => k.id === kb.id)"
                @change="(e) => {
                  if ((e.target as HTMLInputElement).checked) {
                    selectedKbs.push({ id: kb.id, name: kb.name, topK: 5, maxContextTokens: 2000 })
                  } else {
                    selectedKbs = selectedKbs.filter(k => k.id !== kb.id)
                  }
                }"
              />
              <div>
                <div class="font-medium text-foreground">{{ kb.name }}</div>
                <div class="text-xs text-muted-foreground">{{ kb.description }}</div>
              </div>
            </div>
            <div v-if="selectedKbs.some(k => k.id === kb.id)" class="flex gap-2">
              <input
                v-model.number="selectedKbs.find(k => k.id === kb.id)!.topK"
                type="number"
                min="1"
                max="20"
                placeholder="Top-K"
                class="w-20 px-2 py-1 rounded-md border border-input bg-background text-foreground text-sm"
              />
              <input
                v-model.number="selectedKbs.find(k => k.id === kb.id)!.maxContextTokens"
                type="number"
                min="100"
                max="10000"
                placeholder="Max Tokens"
                class="w-32 px-2 py-1 rounded-md border border-input bg-background text-foreground text-sm"
              />
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2">
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="showKbDialog = false"
          >
            取消
          </button>
          <button
            class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            @click="saveKnowledgeBases"
          >
            保存
          </button>
        </div>
      </div>
    </div>

    <!-- 工具管理对话框 -->
    <div
      v-if="showToolsDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showToolsDialog = false"
    >
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[672px] shadow-lg max-h-[80vh] overflow-y-auto">
        <h3 class="text-lg font-semibold text-foreground mb-4">管理工具</h3>
        <div class="space-y-2 mb-4">
          <div
            v-for="tool in toolStore.tools"
            :key="tool.id"
            class="flex items-center justify-between p-3 border border-border rounded-md"
          >
            <div>
              <div class="font-medium text-foreground">{{ tool.displayName || tool.name }}</div>
              <div class="text-xs text-muted-foreground">{{ tool.description }}</div>
              <div class="text-xs text-muted-foreground mt-1">
                类型: {{ tool.type }}, 风险: {{ tool.riskLevel }}
              </div>
            </div>
            <input
              type="checkbox"
              :checked="enabledTools.includes(tool.id)"
              @change="(e) => {
                if ((e.target as HTMLInputElement).checked) {
                  enabledTools.push(tool.id)
                } else {
                  enabledTools = enabledTools.filter(id => id !== tool.id)
                }
              }"
            />
          </div>
        </div>
        <div class="flex justify-end gap-2">
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="showToolsDialog = false"
          >
            取消
          </button>
          <button
            class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            @click="saveTools"
          >
            保存
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
