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
  skillStore.fetchMcpStatus()
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
      config: { transport: 'stdio', command: '', args: [], env: {} }
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

const filteredServers = computed(() => {
  if (!searchQuery.value) return skillStore.mcpServers
  const q = searchQuery.value.toLowerCase()
  return skillStore.mcpServers.filter(s => s.name.toLowerCase().includes(q))
})

const stateConfig: Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }> = {
  CONNECTED: { label: '已连接', variant: 'default' },
  CONNECTING: { label: '连接中', variant: 'secondary' },
  INITIALIZING: { label: '初始化中', variant: 'secondary' },
  HEALTH_CHECK: { label: '已连接', variant: 'default' },
  DISCONNECTED: { label: '未连接', variant: 'outline' },
  DISCONNECTING: { label: '断开中', variant: 'outline' },
  RECONNECTING: { label: '重连中', variant: 'secondary' },
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部操作栏 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <div class="flex items-center justify-between mb-md">
          <div class="space-y-xs">
            <h2 class="text-2xl font-semibold text-foreground leading-tight">MCP Server 管理</h2>
            <p class="text-sm text-muted-foreground">管理外部工具服务连接，扩展 ZhiWei 的能力边界。</p>
          </div>
          <Button @click="showCreateDialog = true">新建 Server</Button>
        </div>
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
        <!-- Skeleton 加载 -->
        <div v-if="skillStore.loading" class="space-y-sm">
          <Card v-for="i in 4" :key="i" class="list-card">
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

        <!-- npx 不可用提示 -->
        <div
          v-if="!skillStore.loading && skillStore.npxAvailable === false"
          class="mb-sm rounded-lg border border-yellow-200 bg-yellow-50 dark:border-yellow-900 dark:bg-yellow-950 px-4 py-3 text-sm text-yellow-800 dark:text-yellow-200"
        >
          ⚠️ 未检测到 Node.js 环境，部分 MCP Server（如 mcp-installer）无法自动注册。请
          <a href="https://nodejs.org/" target="_blank" rel="noopener noreferrer"
            class="underline font-medium hover:text-yellow-900 dark:hover:text-yellow-100"
          >安装 Node.js</a> 后重启应用。
        </div>

        <!-- 空状态 -->
        <EmptyState
          v-else-if="!skillStore.loading && skillStore.mcpServers.length === 0"
          icon="🔌"
          title="暂无 MCP Server"
          description="MCP Server 可以为 ZhiWei 提供更多外部工具能力。点击「新建 Server」来扩展功能。"
          action-label="新建 Server"
          :show-action="true"
          @action="showCreateDialog = true"
        />

        <!-- 搜索无结果 -->
        <EmptyState
          v-else-if="!skillStore.loading && filteredServers.length === 0"
          icon="🔍"
          title="未找到匹配的 Server"
          description="尝试调整搜索关键词"
        />

        <!-- Server 卡片列表 -->
        <div v-else-if="!skillStore.loading" class="space-y-sm">
          <Card
            v-for="server in filteredServers"
            :key="server.name"
            class="list-card cursor-pointer group"
            @click="router.push(`/mcp-servers/${server.name}`)"
          >
            <CardHeader class="pb-2">
              <div class="flex items-start justify-between gap-sm">
                <div class="flex items-center gap-sm flex-1 min-w-0">
                  <CardTitle class="text-sm leading-snug group-hover:text-primary transition-colors">
                    {{ server.name }}
                  </CardTitle>
                  <Badge :variant="stateConfig[server.state]?.variant ?? 'outline'">
                    {{ stateConfig[server.state]?.label ?? server.state }}
                  </Badge>
                  <span class="text-xs text-muted-foreground">{{ server.toolCount }} 个工具</span>
                </div>
                <div class="flex gap-1.5 shrink-0" @click.stop>
                  <Button
                    v-if="server.state === 'DISCONNECTED'"
                    size="sm"
                    @click="skillStore.connectServer(server.name)"
                  >连接</Button>
                  <Button
                    v-if="server.state === 'CONNECTED' || server.state === 'HEALTH_CHECK'"
                    variant="ghost" size="sm" class="action-btn-link"
                    @click="skillStore.disconnectServer(server.name)"
                  >断开</Button>
                  <Button
                    variant="ghost" size="sm" class="action-btn-link"
                    @click="router.push(`/mcp-servers/${server.name}`)"
                  >详情</Button>
                </div>
              </div>
            </CardHeader>
            <CardContent v-if="server.lastError || server.connectedSince" class="pb-3 pt-0">
              <div v-if="server.lastError" class="text-xs text-destructive mb-1">{{ server.lastError }}</div>
              <div v-if="server.connectedSince" class="text-xs text-muted-foreground">
                连接时间: {{ new Date(server.connectedSince).toLocaleString() }}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>

    <!-- 新建对话框 -->
    <Dialog v-model:open="showCreateDialog">
      <DialogContent class="max-w-[448px]">
        <DialogHeader>
          <DialogTitle>新建 MCP Server</DialogTitle>
          <DialogDescription>配置一个新的 MCP Server 连接</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-4">
          <div class="space-y-2">
            <Label for="server-name">名称 *</Label>
            <Input id="server-name" v-model="newServer.name" placeholder="输入 Server 名称" />
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
            <Input id="server-command" v-model="newServer.config.command" placeholder="输入命令（如：node, python）" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="showCreateDialog = false">取消</Button>
          <Button @click="handleCreate">创建</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 删除确认 -->
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
