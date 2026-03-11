<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  Cable,
  Clock3,
  FileWarning,
  RefreshCw,
  Server,
  Settings2,
  Sparkles,
} from 'lucide-vue-next'
import { mcpApi } from '@/api/client'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import type { McpConnectionLog, McpServerConfig } from '@/types'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'

const route = useRoute()
const router = useRouter()
const skillStore = useSkillStore()
const uiStore = useUiStore()

const serverName = computed(() => route.params.id as string)
const server = computed(() => skillStore.mcpServers.find(item => item.name === serverName.value))

const editing = ref(false)
const testing = ref(false)
const testResult = ref('')
const logsLoading = ref(false)
const connectionLogs = ref<McpConnectionLog[]>([])

const config = ref<McpServerConfig>({
  transport: 'stdio',
  command: '',
  args: [],
  env: {},
})

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: 'MCP 服务器', to: { name: 'mcpServers' } },
  { label: server.value?.name ?? '服务器详情' },
])

const transportOptions = [
  { value: 'stdio', label: 'stdio' },
  { value: 'sse', label: 'SSE（旧协议）' },
  { value: 'STDIO', label: 'STDIO' },
  { value: 'STREAMABLE_HTTP', label: '流式 HTTP' },
  { value: 'SSE_LEGACY', label: 'SSE 旧协议' },
]

const stateConfig: Record<string, { label: string; badgeClass: string }> = {
  CONNECTED: {
    label: '已连接',
    badgeClass: 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
  },
  CONNECTING: {
    label: '连接中',
    badgeClass: 'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  INITIALIZING: {
    label: '初始化中',
    badgeClass: 'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  HEALTH_CHECK: {
    label: '健康',
    badgeClass: 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
  },
  DISCONNECTED: {
    label: '已断开',
    badgeClass: 'border-slate-200/80 bg-slate-50/80 text-slate-700 dark:border-slate-500/20 dark:bg-slate-500/10 dark:text-slate-200',
  },
  DISCONNECTING: {
    label: '断开中',
    badgeClass: 'border-slate-200/80 bg-slate-50/80 text-slate-700 dark:border-slate-500/20 dark:bg-slate-500/10 dark:text-slate-200',
  },
  RECONNECTING: {
    label: '重连中',
    badgeClass: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
}

const eventTypeStyle: Record<string, { label: string; badgeClass: string }> = {
  CONNECT: {
    label: '连接',
    badgeClass: 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
  },
  DISCONNECT: {
    label: '断开',
    badgeClass: 'border-slate-200/80 bg-slate-50/80 text-slate-700 dark:border-slate-500/20 dark:bg-slate-500/10 dark:text-slate-200',
  },
  ERROR: {
    label: '错误',
    badgeClass: 'border-destructive/25 bg-destructive/10 text-destructive',
  },
  RECONNECT: {
    label: '重连',
    badgeClass: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
}

const usesCommandTransport = computed(() => {
  const transport = String(config.value.transport)
  return transport === 'stdio' || transport === 'STDIO'
})

const usesUrlTransport = computed(() => !usesCommandTransport.value)

function cloneConfig(nextConfig?: McpServerConfig) {
  config.value = {
    transport: nextConfig?.transport ?? 'stdio',
    command: nextConfig?.command ?? '',
    args: [...(nextConfig?.args ?? [])],
    env: { ...(nextConfig?.env ?? {}) },
    baseUrl: nextConfig?.baseUrl ?? '',
    url: nextConfig?.url ?? '',
    timeoutSeconds: nextConfig?.timeoutSeconds ?? nextConfig?.timeout,
    timeout: nextConfig?.timeout ?? nextConfig?.timeoutSeconds,
    maxRetries: nextConfig?.maxRetries ?? 0,
    autoConnect: nextConfig?.autoConnect ?? false,
    reconnect: nextConfig?.reconnect ?? false,
    reconnectDelay: nextConfig?.reconnectDelay,
    maxReconnectAttempts: nextConfig?.maxReconnectAttempts,
    healthCheckInterval: nextConfig?.healthCheckInterval,
  }
}

function formatDate(value?: string) {
  if (!value) return '暂无'
  return new Date(value).toLocaleString()
}

function showErrorToast(message: string) {
  uiStore.showToast('error', message)
}

async function loadConnectionLogs() {
  if (!serverName.value) return

  logsLoading.value = true
  try {
    connectionLogs.value = await mcpApi.getConnectionLogs(serverName.value)
  } catch {
    connectionLogs.value = []
  } finally {
    logsLoading.value = false
  }
}

async function refreshData() {
  await skillStore.fetchMcpServers()

  if (!server.value) {
    return
  }

  cloneConfig(server.value.config)

  await Promise.all([
    skillStore.fetchServerTools(server.value.name),
    loadConnectionLogs(),
  ])
}

async function saveConfig() {
  if (!server.value) return

  try {
    await skillStore.updateMcpServer(server.value.name, {
      config: config.value,
    })
    editing.value = false
    await refreshData()
  } catch (error: any) {
    showErrorToast(error?.message ?? '保存服务器配置失败。')
  }
}

async function testConnection() {
  if (!server.value || testing.value) return

  testing.value = true
  testResult.value = ''

  try {
    if (server.value.state === 'CONNECTED' || server.value.state === 'HEALTH_CHECK') {
      await skillStore.disconnectServer(server.value.name)
      await new Promise(resolve => setTimeout(resolve, 500))
    }

    await skillStore.connectServer(server.value.name)
    await new Promise(resolve => setTimeout(resolve, 1000))
    await refreshData()

    const nextState = skillStore.mcpServers.find(item => item.name === server.value?.name)?.state
    testResult.value = nextState === 'CONNECTED' || nextState === 'HEALTH_CHECK'
      ? '连接测试成功完成。'
      : '连接测试已完成，但服务器未进入已连接状态。'
  } catch (error: any) {
    testResult.value = error?.message ?? '连接测试失败。'
  } finally {
    testing.value = false
  }
}

watch(server, nextServer => {
  cloneConfig(nextServer?.config)
}, { immediate: true })

watch(serverName, () => {
  editing.value = false
  testResult.value = ''
  void refreshData()
}, { immediate: false })

onMounted(() => {
  void refreshData()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <Button type="button" variant="ghost" class="w-fit" @click="router.push({ name: 'mcpServers' })">
            <ArrowLeft class="size-4" />
            返回服务器列表
          </Button>
        </div>

        <template v-if="skillStore.loading && !server">
          <div class="space-y-5">
            <div class="space-y-3 border-b border-border/70 pb-6">
              <Skeleton class="h-5 w-24" />
              <Skeleton class="h-10 w-80" />
              <Skeleton class="h-5 w-full max-w-[44rem]" />
            </div>
            <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <Skeleton v-for="index in 4" :key="index" class="h-24 rounded-[calc(var(--radius)+6px)]" />
            </div>
            <Skeleton class="h-[320px] rounded-[calc(var(--radius)+6px)]" />
            <Skeleton class="h-[220px] rounded-[calc(var(--radius)+6px)]" />
          </div>
        </template>

        <StatePanel
          v-else-if="!server"
          title="当前 MCP 服务器不可用"
          description="未在当前 MCP 注册表中找到该服务器，它可能已被删除或重命名。"
          tone="danger"
        >
          <template #icon>
            <FileWarning class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" @click="router.push({ name: 'mcpServers' })">
              返回列表
            </Button>
          </template>
        </StatePanel>

        <template v-else>
          <PageHeader
            eyebrow="MCP 服务器"
            :title="server.name"
            description="查看连接状态、调整接入参数，并确认当前有哪些可用工具。"
          >
            <template #actions>
              <Button variant="outline" @click="refreshData">
                <RefreshCw class="size-4" />
                刷新
              </Button>
              <Button variant="outline" :disabled="testing" @click="testConnection">
                <Cable class="size-4" />
                {{ testing ? '测试中...' : '测试连接' }}
              </Button>
              <Button :variant="editing ? 'secondary' : 'default'" @click="editing = !editing">
                <Settings2 class="size-4" />
                {{ editing ? '停止编辑' : '编辑配置' }}
              </Button>
            </template>

            <template #meta>
              <MetricCard label="连接状态" :value="stateConfig[server.state]?.label ?? server.state" hint="以服务器当前状态为准。">
                <template #icon>
                  <Cable class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="可用工具" :value="server.toolCount" hint="当前这台服务器已经暴露出来的工具数量。">
                <template #icon>
                  <Sparkles class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="传输方式" :value="String(config.transport ?? server.config?.transport ?? 'stdio')" hint="当前正在使用的连接方式。">
                <template #icon>
                  <Settings2 class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="最近连接" :value="formatDate(server.connectedSince)" hint="最近一次成功建立连接的时间。">
                <template #icon>
                  <Clock3 class="size-5" />
                </template>
              </MetricCard>
            </template>
          </PageHeader>

          <StatePanel
            v-if="testResult"
            :title="testResult.includes('成功') ? '连接测试成功' : '连接测试完成，但存在提示'"
            :description="testResult"
            :tone="testResult.includes('成功') ? 'default' : 'warning'"
          >
            <template #icon>
              <Cable class="size-5" />
            </template>
          </StatePanel>

          <StatePanel
            v-if="server.lastError"
            title="最近一次失败"
            :description="server.lastError"
            tone="danger"
          >
            <template #icon>
              <FileWarning class="size-5" />
            </template>
          </StatePanel>

          <PageSection
            eyebrow="配置"
            title="连接设置"
            description="查看并修改连接方式、超时和重试设置。"
          >
            <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_280px]">
              <div class="grid gap-4 md:grid-cols-2">
                <div class="space-y-2">
                  <Label for="mcp-detail-transport">传输方式</Label>
                  <Select v-model="config.transport" :disabled="!editing">
                    <SelectTrigger id="mcp-detail-transport">
                      <SelectValue placeholder="选择传输方式" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem
                        v-for="option in transportOptions"
                        :key="option.value"
                        :value="option.value"
                      >
                        {{ option.label }}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <div class="space-y-2">
                  <Label for="mcp-detail-timeout">超时（秒）</Label>
                  <Input
                    id="mcp-detail-timeout"
                    v-model.number="config.timeoutSeconds"
                    type="number"
                    :disabled="!editing"
                    :min="1"
                    placeholder="30"
                  />
                </div>

                <div v-if="usesCommandTransport" class="space-y-2">
                  <Label for="mcp-detail-command">命令</Label>
                  <Input
                    id="mcp-detail-command"
                    v-model="config.command"
                    :disabled="!editing"
                    placeholder="npx"
                  />
                </div>

                <div v-if="usesUrlTransport" class="space-y-2">
                  <Label for="mcp-detail-url">基础 URL</Label>
                  <Input
                    id="mcp-detail-url"
                    v-model="config.baseUrl"
                    :disabled="!editing"
                    placeholder="https://example.com/mcp"
                  />
                </div>

                <div class="space-y-2">
                  <Label for="mcp-detail-retries">最大重试次数</Label>
                  <Input
                    id="mcp-detail-retries"
                    v-model.number="config.maxRetries"
                    type="number"
                    :disabled="!editing"
                    :min="0"
                    placeholder="0"
                  />
                </div>

                <div class="space-y-2">
                  <Label for="mcp-detail-args">参数</Label>
                  <Input
                    id="mcp-detail-args"
                    :model-value="(config.args ?? []).join(' ')"
                    disabled
                    placeholder="无参数"
                  />
                </div>

                <div v-if="editing" class="md:col-span-2 flex justify-end">
                  <Button @click="saveConfig">
                    保存配置
                  </Button>
                </div>
              </div>

              <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 p-4">
                <div class="space-y-4">
                  <div>
                    <div class="surface-label text-[0.68rem]">连接策略</div>
                    <p class="mt-2 text-sm leading-6 text-muted-foreground">
                      先看自动连接、重连和健康检查，再决定是否需要调整命令、URL 或超时参数。
                    </p>
                  </div>

                  <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                    <span class="surface-chip">{{ config.autoConnect ? '自动连接已启用' : '自动连接已停用' }}</span>
                    <span class="surface-chip">{{ config.reconnect ? '自动重连已启用' : '自动重连已停用' }}</span>
                    <span class="surface-chip">健康检查 {{ config.healthCheckInterval ?? '未设置' }}</span>
                    <span class="surface-chip">重连上限 {{ config.maxReconnectAttempts ?? '未设置' }}</span>
                  </div>
                </div>
              </div>
            </div>
          </PageSection>

          <PageSection
            eyebrow="工具"
            :title="`可用工具（${skillStore.serverTools.length}）`"
            description="确认可用工具是否齐全，再决定是否接入技能或工作流。"
          >
            <StatePanel
              v-if="skillStore.serverTools.length === 0"
              title="当前没有可用工具"
              description="请先连接服务器，或检查配置是否正确。"
            >
              <template #icon>
                <Sparkles class="size-5" />
              </template>
            </StatePanel>

            <div v-else class="grid gap-3">
              <button
                v-for="tool in skillStore.serverTools"
                :key="tool.id"
                type="button"
                class="list-card px-4 py-4 text-left"
                @click="router.push(`/tools/${tool.id}`)"
              >
                <div class="flex items-start justify-between gap-4">
                  <div class="min-w-0 space-y-1">
                    <div class="text-sm font-semibold text-foreground">
                      {{ tool.name }}
                    </div>
                    <p class="text-sm leading-6 text-muted-foreground">
                      {{ tool.description }}
                    </p>
                  </div>
                  <Button variant="outline" size="sm" @click.stop="router.push(`/tools/${tool.id}`)">
                    打开工具
                  </Button>
                </div>
              </button>
            </div>
          </PageSection>

          <PageSection
            eyebrow="日志"
            title="连接记录"
            description="查看最近的连接、断开、错误和重连记录。"
          >
            <div v-if="logsLoading" class="space-y-3">
              <Skeleton v-for="index in 4" :key="index" class="h-14 w-full" />
            </div>

            <StatePanel
              v-else-if="connectionLogs.length === 0"
              title="暂无连接日志"
              description="连接成功、断开或报错后，会保留最近记录。"
            >
              <template #icon>
                <Clock3 class="size-5" />
              </template>
            </StatePanel>

            <div v-else class="space-y-3">
              <article
                v-for="(log, index) in connectionLogs"
                :key="`${log.timestamp}-${index}`"
                class="list-card px-4 py-4"
                :class="log.eventType === 'ERROR' ? 'border-destructive/20 bg-destructive/5' : ''"
              >
                <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                  <div class="flex min-w-0 items-start gap-3">
                    <Badge
                      variant="outline"
                      :class="eventTypeStyle[log.eventType]?.badgeClass"
                    >
                      {{ eventTypeStyle[log.eventType]?.label ?? log.eventType }}
                    </Badge>
                    <p class="text-sm leading-6" :class="log.eventType === 'ERROR' ? 'text-destructive' : 'text-foreground'">
                      {{ log.description }}
                    </p>
                  </div>
                  <div class="text-xs text-muted-foreground">
                    {{ formatDate(log.timestamp) }}
                  </div>
                </div>
              </article>
            </div>
          </PageSection>
        </template>
      </div>
    </PageContainer>
  </div>
</template>
