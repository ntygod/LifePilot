<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSkillStore } from '@/stores/skill'
import type { McpServerConfig } from '@/types'

const route = useRoute()
const router = useRouter()
const skillStore = useSkillStore()

const serverName = computed(() => route.params.id as string)
const server = computed(() => skillStore.mcpServers.find(s => s.name === serverName.value))

const editing = ref(false)
const config = ref<McpServerConfig>({
  transport: 'stdio',
  command: '',
  args: [],
  env: {}
})

const testing = ref(false)
const testResult = ref<string>('')

onMounted(async () => {
  await skillStore.fetchMcpServers()
  if (server.value) {
    await skillStore.fetchServerTools(server.value.name)
    if (server.value.config) {
      config.value = { ...server.value.config }
    }
  }
})

async function saveConfig() {
  if (!server.value) return
  try {
    await skillStore.updateMcpServer(server.value.name, {
      config: config.value
    })
    editing.value = false
    await skillStore.fetchMcpServers()
  } catch (e: any) {
    alert(e.message || '保存失败')
  }
}

async function testConnection() {
  if (!server.value || testing.value) return
  testing.value = true
  testResult.value = ''
  try {
    // 先断开再连接以测试
    if (server.value.state === 'CONNECTED') {
      await skillStore.disconnectServer(server.value.name)
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    await skillStore.connectServer(server.value.name)
    await new Promise(resolve => setTimeout(resolve, 1000))
    await skillStore.fetchMcpServers()
    if (skillStore.mcpServers.find(s => s.name === server.value!.name)?.state === 'CONNECTED') {
      testResult.value = '连接成功'
    } else {
      testResult.value = '连接失败'
    }
  } catch (e: any) {
    testResult.value = `连接失败: ${e.message}`
  } finally {
    testing.value = false
  }
}

const stateLabel: Record<string, { label: string; class: string }> = {
  CONNECTED: { label: '已连接', class: 'bg-green-100 text-green-800' },
  CONNECTING: { label: '连接中', class: 'bg-blue-100 text-blue-800' },
  DISCONNECTED: { label: '未连接', class: 'bg-gray-100 text-gray-800' },
  RECONNECTING: { label: '重连中', class: 'bg-yellow-100 text-yellow-800' },
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部 -->
    <div class="flex-shrink-0 p-6 border-b border-border">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-3">
          <button
            class="text-sm text-muted-foreground hover:text-foreground transition-colors"
            @click="router.push('/mcp-servers')"
          >
            ← 返回列表
          </button>
          <h2 class="text-2xl font-semibold text-foreground">
            {{ server?.name || '加载中...' }}
          </h2>
          <span
            v-if="server"
            class="text-xs px-2 py-0.5 rounded-full shrink-0"
            :class="stateLabel[server.state]?.class ?? 'bg-gray-100 text-gray-800'"
          >
            {{ stateLabel[server.state]?.label ?? server.state }}
          </span>
        </div>
        <div class="flex gap-2">
          <button
            v-if="server"
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            :disabled="testing"
            @click="testConnection"
          >
            {{ testing ? '测试中...' : '测试连接' }}
          </button>
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="editing = !editing"
          >
            {{ editing ? '取消编辑' : '编辑配置' }}
          </button>
        </div>
      </div>
      <div v-if="testResult" class="mt-2 text-sm" :class="testResult.includes('成功') ? 'text-green-600' : 'text-destructive'">
        {{ testResult }}
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto p-6">
      <div v-if="!server" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else class="max-w-4xl mx-auto space-y-6">
        <!-- 基础配置 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">基础配置</h3>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">传输方式</label>
              <select
                v-model="config.transport"
                :disabled="!editing"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              >
                <option value="stdio">stdio</option>
                <option value="sse">SSE</option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">命令</label>
              <input
                v-model="config.command"
                type="text"
                :disabled="!editing"
                placeholder="输入命令"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              />
            </div>
            <div v-if="config.transport === 'sse'">
              <label class="block text-sm font-medium text-foreground mb-1">Base URL</label>
              <input
                v-model="config.baseUrl"
                type="text"
                :disabled="!editing"
                placeholder="输入 Base URL"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              />
            </div>
            <div v-if="editing" class="flex justify-end gap-2">
              <button
                class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                @click="saveConfig"
              >
                保存
              </button>
            </div>
          </div>
        </div>

        <!-- 高级选项 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">高级选项</h3>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">超时时间（秒）</label>
              <input
                v-model.number="config.timeoutSeconds"
                type="number"
                :disabled="!editing"
                min="1"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">最大重试次数</label>
              <input
                v-model.number="config.maxRetries"
                type="number"
                :disabled="!editing"
                min="0"
                class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              />
            </div>
          </div>
        </div>

        <!-- 工具列表 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">
            工具列表 ({{ skillStore.serverTools.length }})
          </h3>
          <div v-if="skillStore.serverTools.length === 0" class="text-sm text-muted-foreground">
            暂无工具
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="tool in skillStore.serverTools"
              :key="tool.id"
              class="p-3 rounded-md bg-muted cursor-pointer hover:bg-muted/80 transition-colors"
              @click="router.push(`/tools/${tool.id}`)"
            >
              <div class="font-medium text-foreground">{{ tool.name }}</div>
              <div class="text-sm text-muted-foreground mt-1">{{ tool.description }}</div>
            </div>
          </div>
        </div>

        <!-- 状态信息 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">状态信息</h3>
          <div class="space-y-2 text-sm">
            <div>
              <span class="text-muted-foreground">工具数量：</span>
              <span>{{ server.toolCount }}</span>
            </div>
            <div v-if="server.connectedSince">
              <span class="text-muted-foreground">连接时间：</span>
              <span>{{ new Date(server.connectedSince).toLocaleString() }}</span>
            </div>
            <div v-if="server.lastError">
              <span class="text-muted-foreground">最后错误：</span>
              <span class="text-destructive">{{ server.lastError }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
