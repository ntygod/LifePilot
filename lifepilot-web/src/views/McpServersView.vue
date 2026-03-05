<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSkillStore } from '@/stores/skill'
import type { McpServer } from '@/types'
import SearchBar from '@/components/common/SearchBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'

const router = useRouter()
const skillStore = useSkillStore()

const searchQuery = ref('')
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

const filteredServers = computed(() => {
  if (!searchQuery.value) return skillStore.mcpServers
  const q = searchQuery.value.toLowerCase()
  return skillStore.mcpServers.filter(s =>
    s.name.toLowerCase().includes(q)
  )
})

const stateVariant: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  CONNECTED: 'default',
  CONNECTING: 'secondary',
  DISCONNECTED: 'outline',
  RECONNECTING: 'secondary',
}

const stateLabel: Record<string, string> = {
  CONNECTED: '已连接',
  CONNECTING: '连接中',
  DISCONNECTED: '未连接',
  RECONNECTING: '重连中',
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部操作栏 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <div class="flex items-center justify-between mb-md">
          <h2 class="text-2xl font-semibold text-foreground leading-tight">MCP Server 管理</h2>
          <Button @click="showCreateDialog = true">新建 Server</Button>
        </div>

        <!-- 搜索栏 -->
        <SearchBar
          v-model="searchQuery"
          placeholder="搜索 Server 名称..."
          class="max-w-[400px]"
        />
      </div>
    </div>

    <!-- Server 列表 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- Skeleton 加载占位符 -->
        <div v-if="skillStore.loading" class="space-y-sm">
          <Card v-for="i in 4" :key="i">
            <CardHeader class="pb-2">
              <div class="flex items-center gap-sm">
                <Skeleton class="h-5 w-1/4" />
                <Skeleton class="h-5 w-16 rounded-full" />
                <Skeleton class="h-5 w-20" />
              </div>
            </CardHeader>
            <CardContent class="pb-3">
              <Skeleton class="h-4 w-full mb-2" />
              <Skeleton class="h-3 w-1/3" />
            </CardContent>
          </Card>
        </div>

        <!-- 空状态 -->
        <EmptyState
          v-else-if="skillStore.mcpServers.length === 0"
          icon="🔌"
          title="暂无 MCP Server"
          description="点击「新建 Server」创建你的第一个 MCP Server"
        />

        <!-- 搜索无结果 -->
        <EmptyState
          v-else-if="filteredServers.length === 0"
          icon="🔍"
          title="未找到匹配的 Server"
          description="尝试调整搜索关键词"
        />

        <!-- Server 卡片列表 -->
        <div v-else class="space-y-sm">
          <Card
            v-for="server in filteredServers"
            :key="server.name"
            class="hover:-translate-y-0.5 hover:shadow-md hover:border-primary/50 transition-all duration-200"
          >
            <CardHeader class="pb-2">
              <div class="flex items-start justify-between gap-sm">
                <div class="flex items-center gap-sm flex-1 min-w-0">
                  <CardTitle
                    class="text-sm leading-snug cursor-pointer hover:text-primary transition-colors"
                    @click="router.push(`/mcp-servers/${server.name}`)"
                  >
                    {{ server.name }}
                  </CardTitle>
                  <Badge :variant="stateVariant[server.state] ?? 'outline'">
                    {{ stateLabel[server.state] ?? server.state }}
                  </Badge>
                  <span class="text-xs text-muted-foreground">{{ server.toolCount }} 个工具</span>
                </div>
                <div class="flex gap-1.5 shrink-0">
                  <Button
                    v-if="server.state === 'DISCONNECTED'"
                    size="sm"
                    @click="skillStore.connectServer(server.name)"
                  >
                    连接
                  </Button>
                  <Button
                    v-if="server.state === 'CONNECTED'"
                    variant="outline"
                    size="sm"
                    @click="skillStore.disconnectServer(server.name)"
                  >
                    断开
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    @click="router.push(`/mcp-servers/${server.name}`)"
                  >
                    查看详情
                  </Button>
                  <Button
                    variant="destructive"
                    size="sm"
                    @click.stop="deleteTarget = server"
                  >
                    删除
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent class="pb-3">
              <div v-if="server.lastError" class="text-xs text-destructive mb-1">{{ server.lastError }}</div>
              <div v-if="server.connectedSince" class="text-xs text-muted-foreground">
                连接时间: {{ new Date(server.connectedSince).toLocaleString() }}
              </div>

              <!-- 工具列表（展开） -->
              <div
                v-if="selectedServer?.name === server.name && skillStore.serverTools.length > 0"
                class="mt-3 border-t border-border pt-3 space-y-1"
              >
                <div
                  v-for="tool in skillStore.serverTools"
                  :key="tool.id"
                  class="text-sm flex gap-2"
                >
                  <span class="font-mono text-foreground">{{ tool.name }}</span>
                  <span class="text-muted-foreground">{{ tool.description }}</span>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>

    <!-- 新建对话框 -->
    <Dialog v-model:open="showCreateDialog">
      <DialogContent class="sm:max-w-[448px]">
        <DialogHeader>
          <DialogTitle>新建 MCP Server</DialogTitle>
          <DialogDescription>配置一个新的 MCP Server 连接</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-4">
          <div class="space-y-2">
            <Label for="server-name">名称 *</Label>
            <Input
              id="server-name"
              v-model="newServer.name"
              placeholder="输入 Server 名称"
            />
          </div>
          <div class="space-y-2">
            <Label for="server-transport">传输方式</Label>
            <Select v-model="newServer.config.transport">
              <SelectTrigger id="server-transport">
                <SelectValue placeholder="选择传输方式" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="stdio">stdio</SelectItem>
                <SelectItem value="sse">SSE</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div class="space-y-2">
            <Label for="server-command">命令 *</Label>
            <Input
              id="server-command"
              v-model="newServer.config.command"
              placeholder="输入命令（如：node, python）"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="showCreateDialog = false">取消</Button>
          <Button @click="handleCreate">创建</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 删除确认对话框 -->
    <ConfirmDialog
      v-if="deleteTarget"
      :show="!!deleteTarget"
      title="确认删除"
      :message="`确定要删除 MCP Server「${deleteTarget.name}」吗？此操作不可撤销。`"
      confirm-label="删除"
      cancel-label="取消"
      confirm-variant="destructive"
      @confirm="handleDelete"
      @cancel="deleteTarget = null"
      @update:show="!$event && (deleteTarget = null)"
    />
  </div>
</template>
