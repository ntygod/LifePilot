<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSkillStore } from '@/stores/skill'
import { mcpApi } from '@/api/client'
import type { McpServerConfig, McpConnectionLog } from '@/types'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'

const route = useRoute()
const router = useRouter()
const skillStore = useSkillStore()

const serverName = computed(() => route.params.id as string)
const server = computed(() => skillStore.mcpServers.find(s => s.name === serverName.value))

// 面包屑导航
const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: 'MCP Servers', to: { name: 'mcpServers' } },
  { label: server.value?.name ?? '...' }
])

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

// 连接日志状态
const connectionLogs = ref<McpConnectionLog[]>([])
const logsLoading = ref(false)

// 连接事件类型样式映射
const eventTypeStyle: Record<string, { label: string; class: string }> = {
  CONNECT:    { label: '已连接', class: 'text-green-600 bg-green-50 dark:text-green-400 dark:bg-green-950' },
  DISCONNECT: { label: '断开',   class: 'text-muted-foreground bg-muted' },
  ERROR:      { label: '错误',   class: 'text-destructive bg-destructive/10' },
  RECONNECT:  { label: '重连',   class: 'text-yellow-600 bg-yellow-50 dark:text-yellow-400 dark:bg-yellow-950' },
}

const stateVariant: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  CONNECTED: 'default',
  CONNECTING: 'secondary',
  INITIALIZING: 'secondary',
  HEALTH_CHECK: 'default',
  DISCONNECTED: 'outline',
  DISCONNECTING: 'outline',
  RECONNECTING: 'secondary',
}

const stateLabel: Record<string, string> = {
  CONNECTED: '已连接',
  CONNECTING: '连接中',
  INITIALIZING: '初始化中',
  HEALTH_CHECK: '已连接',
  DISCONNECTED: '未连接',
  DISCONNECTING: '断开中',
  RECONNECTING: '重连中',
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <Breadcrumb :items="breadcrumbItems" class="mb-2" />
        <div class="flex items-center justify-between mb-md">
          <div class="flex items-center gap-sm">
            <h2 class="text-2xl font-semibold text-foreground leading-tight">
              {{ server?.name || '加载中...' }}
            </h2>
            <Badge
              v-if="server"
              :variant="stateVariant[server.state] ?? 'outline'"
            >
              {{ stateLabel[server.state] ?? server.state }}
            </Badge>
          </div>
          <div class="flex gap-1.5">
            <Button
              v-if="server"
              variant="ghost" size="sm" class="action-btn-link"
              :disabled="testing"
              @click="testConnection"
            >
              {{ testing ? '测试中...' : '测试连接' }}
            </Button>
            <Button
              variant="ghost" size="sm" class="action-btn-link"
              @click="editing = !editing"
            >
              {{ editing ? '取消编辑' : '编辑配置' }}
            </Button>
          </div>
        </div>
        <div
          v-if="testResult"
          class="mt-2 text-sm"
          :class="testResult.includes('成功') ? 'text-green-600' : 'text-destructive'"
        >
          {{ testResult }}
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- Skeleton 加载占位符 -->
        <div v-if="!server" class="space-y-md">
          <Card class="detail-card">
            <CardHeader>
              <Skeleton class="h-6 w-1/4" />
            </CardHeader>
            <CardContent class="space-y-4">
              <Skeleton class="h-9 w-full" />
              <Skeleton class="h-9 w-full" />
              <Skeleton class="h-9 w-2/3" />
            </CardContent>
          </Card>
          <Card class="detail-card">
            <CardHeader>
              <Skeleton class="h-6 w-1/3" />
            </CardHeader>
            <CardContent class="space-y-3">
              <Skeleton class="h-12 w-full" />
              <Skeleton class="h-12 w-full" />
            </CardContent>
          </Card>
        </div>

        <div v-else class="space-y-md">
          <!-- 基础配置 -->
          <Card class="detail-card">
            <CardHeader>
              <CardTitle class="section-title">基础配置</CardTitle>
            </CardHeader>
            <CardContent class="space-y-4">
              <div class="space-y-2">
                <Label for="detail-transport">传输方式</Label>
                <Select v-model="config.transport" :disabled="!editing">
                  <SelectTrigger id="detail-transport">
                    <SelectValue placeholder="选择传输方式" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="stdio">stdio</SelectItem>
                    <SelectItem value="sse">SSE</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div class="space-y-2">
                <Label for="detail-command">命令</Label>
                <Input
                  id="detail-command"
                  v-model="config.command"
                  :disabled="!editing"
                  placeholder="输入命令"
                />
              </div>
              <div v-if="config.transport === 'sse'" class="space-y-2">
                <Label for="detail-base-url">Base URL</Label>
                <Input
                  id="detail-base-url"
                  v-model="config.baseUrl"
                  :disabled="!editing"
                  placeholder="输入 Base URL"
                />
              </div>
              <div v-if="editing" class="flex justify-end">
                <Button @click="saveConfig">保存</Button>
              </div>
            </CardContent>
          </Card>

          <!-- 高级选项 -->
          <Card class="detail-card">
            <CardHeader>
              <CardTitle class="section-title">高级选项</CardTitle>
            </CardHeader>
            <CardContent class="space-y-4">
              <div class="space-y-2">
                <Label for="detail-timeout">超时时间（秒）</Label>
                <Input
                  id="detail-timeout"
                  v-model.number="config.timeoutSeconds"
                  type="number"
                  :disabled="!editing"
                  :min="1"
                />
              </div>
              <div class="space-y-2">
                <Label for="detail-retries">最大重试次数</Label>
                <Input
                  id="detail-retries"
                  v-model.number="config.maxRetries"
                  type="number"
                  :disabled="!editing"
                  :min="0"
                />
              </div>
            </CardContent>
          </Card>

          <!-- 工具列表 -->
          <Card class="detail-card">
            <CardHeader>
              <CardTitle class="section-title">工具列表 ({{ skillStore.serverTools.length }})</CardTitle>
            </CardHeader>
            <CardContent>
              <div v-if="skillStore.serverTools.length === 0" class="text-sm text-muted-foreground">
                暂无工具
              </div>
              <div v-else class="space-y-2">
                <div
                  v-for="tool in skillStore.serverTools"
                  :key="tool.id"
                  class="list-card p-3 flex items-center justify-between cursor-pointer group"
                  @click="router.push(`/tools/${tool.id}`)"
                >
                  <div>
                    <div class="font-medium text-foreground text-sm group-hover:text-primary transition-colors">{{ tool.name }}</div>
                    <div class="text-xs text-muted-foreground mt-1">{{ tool.description }}</div>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    class="action-btn-link shrink-0"
                    @click.stop="router.push(`/tools/${tool.id}`)"
                  >
                    测试调用
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>

          <!-- 状态信息 -->
          <Card class="detail-card">
            <CardHeader>
              <CardTitle class="section-title">状态信息</CardTitle>
            </CardHeader>
            <CardContent class="space-y-2 text-sm">
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
            </CardContent>
          </Card>

          <!-- 连接日志 -->
          <Card class="detail-card">
            <CardHeader>
              <CardTitle class="section-title">连接日志</CardTitle>
            </CardHeader>
            <CardContent>
              <Skeleton v-if="logsLoading" class="h-32 w-full" />
              <div v-else-if="connectionLogs.length === 0" class="text-sm text-muted-foreground">
                暂无连接日志
              </div>
              <div v-else class="space-y-2">
                <div
                  v-for="(log, idx) in connectionLogs"
                  :key="idx"
                  class="flex items-start gap-3 p-2 rounded-md text-sm"
                  :class="log.eventType === 'ERROR' ? 'bg-destructive/5' : ''"
                >
                  <span class="text-muted-foreground whitespace-nowrap text-xs mt-0.5">
                    {{ new Date(log.timestamp).toLocaleString() }}
                  </span>
                  <Badge
                    variant="secondary"
                    class="text-xs px-1.5 py-0.5 whitespace-nowrap"
                    :class="eventTypeStyle[log.eventType]?.class ?? ''"
                  >
                    {{ eventTypeStyle[log.eventType]?.label ?? log.eventType }}
                  </Badge>
                  <span
                    class="text-sm flex-1"
                    :class="log.eventType === 'ERROR' ? 'text-destructive' : 'text-foreground'"
                  >
                    {{ log.description }}
                  </span>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  </div>
</template>
