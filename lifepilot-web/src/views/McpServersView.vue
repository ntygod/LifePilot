<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSkillStore } from '@/stores/skill'
import type { McpServer } from '@/types'

const router = useRouter()
const skillStore = useSkillStore()

const showCreateDialog = ref(false)
const deleteTarget = ref<McpServer | null>(null)
const selectedServer = ref<McpServer | null>(null)

const newServer = ref({
  name: '',
  config: {
    transport: 'stdio' as 'stdio' | 'sse',
    command: '',
    args: [] as string[],
    env: {} as Record<string, string>
  }
})

onMounted(() => {
  skillStore.fetchMcpServers()
})

async function handleCreate() {
  if (!newServer.value.name.trim()) {
    alert('请输入 Server 名称')
    return
  }
  if (!newServer.value.config.command.trim()) {
    alert('请输入命令')
    return
  }
  try {
    await skillStore.createMcpServer(newServer.value)
    showCreateDialog.value = false
    newServer.value = {
      name: '',
      config: {
        transport: 'stdio',
        command: '',
        args: [],
        env: {}
      }
    }
  } catch (e: any) {
    alert(e.message || '创建失败')
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    // 注意：MCP Server 删除可能需要通过 API，这里暂时使用占位逻辑
    alert('删除功能待后端实现')
    deleteTarget.value = null
  } catch (e: any) {
    alert(e.message || '删除失败')
  }
}

async function selectServer(server: McpServer) {
  selectedServer.value = server
  await skillStore.fetchServerTools(server.name)
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
    <!-- 头部操作栏 -->
    <div class="flex-shrink-0 p-6 border-b border-border">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-2xl font-semibold text-foreground">MCP Server 管理</h2>
        <button
          class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          @click="showCreateDialog = true"
        >
          新建 Server
        </button>
      </div>
    </div>

    <!-- Server 列表 -->
    <div class="flex-1 overflow-y-auto p-6">
      <div v-if="skillStore.loading" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="skillStore.mcpServers.length === 0" class="text-sm text-muted-foreground">
        暂无 MCP Server，点击"新建 Server"创建
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="server in skillStore.mcpServers"
          :key="server.name"
          class="border border-border rounded-lg p-4 hover:border-primary/50 transition-colors"
        >
          <div class="flex items-center gap-3 mb-2">
            <h3
              class="font-medium text-foreground cursor-pointer flex-1"
              @click="router.push(`/mcp-servers/${server.name}`)"
            >
              {{ server.name }}
            </h3>
            <span
              class="text-xs px-2 py-0.5 rounded-full shrink-0"
              :class="stateLabel[server.state]?.class ?? 'bg-gray-100 text-gray-800'"
            >
              {{ stateLabel[server.state]?.label ?? server.state }}
            </span>
            <span class="text-xs text-muted-foreground">{{ server.toolCount }} 个工具</span>
            <div class="flex gap-2">
              <button
                v-if="server.state === 'DISCONNECTED'"
                class="text-xs px-3 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                @click="skillStore.connectServer(server.name)"
              >
                连接
              </button>
              <button
                v-if="server.state === 'CONNECTED'"
                class="text-xs px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors"
                @click="skillStore.disconnectServer(server.name)"
              >
                断开
              </button>
              <button
                class="text-xs px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors"
                @click="router.push(`/mcp-servers/${server.name}`)"
              >
                查看详情
              </button>
              <button
                class="text-xs px-3 py-1 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
                @click.stop="deleteTarget = server"
              >
                删除
              </button>
            </div>
          </div>
          <div v-if="server.lastError" class="text-xs text-destructive mt-2">{{ server.lastError }}</div>
          <div v-if="server.connectedSince" class="text-xs text-muted-foreground mt-1">
            连接时间: {{ new Date(server.connectedSince).toLocaleString() }}
          </div>

          <!-- 工具列表（展开） -->
          <div v-if="selectedServer?.name === server.name && skillStore.serverTools.length > 0" class="mt-3 border-t border-border pt-3 space-y-1">
            <div
              v-for="tool in skillStore.serverTools"
              :key="tool.id"
              class="text-sm flex gap-2"
            >
              <span class="font-mono text-foreground">{{ tool.name }}</span>
              <span class="text-muted-foreground">{{ tool.description }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建对话框 -->
    <div
      v-if="showCreateDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showCreateDialog = false"
    >
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-md shadow-lg max-h-[80vh] overflow-y-auto">
        <h3 class="text-lg font-semibold text-foreground mb-4">新建 MCP Server</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">名称 *</label>
            <input
              v-model="newServer.name"
              type="text"
              placeholder="输入 Server 名称"
              class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">传输方式</label>
            <select
              v-model="newServer.config.transport"
              class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <option value="stdio">stdio</option>
              <option value="sse">SSE</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">命令 *</label>
            <input
              v-model="newServer.config.command"
              type="text"
              placeholder="输入命令（如：node, python）"
              class="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
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
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-sm shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-2">确认删除</h3>
        <p class="text-sm text-muted-foreground mb-4">
          确定要删除 MCP Server「{{ deleteTarget.name }}」吗？此操作不可撤销。
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
