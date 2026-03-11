<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Cable, RefreshCw, Server, ServerOff, Sparkles } from 'lucide-vue-next'
import { useSkillStore } from '@/stores/skill'
import { useMcpStatusStream } from '@/composables/useMcpStatusStream'
import MetricCard from '@/components/common/MetricCard.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import McpServerForm from '@/components/mcp/McpServerForm.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

const router = useRouter()
const skillStore = useSkillStore()

// SSE 实时状态订阅
useMcpStatusStream()

const searchQuery = ref('')
const showCreateDialog = ref(false)

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
    label: '健康检查',
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

const filteredServers = computed(() => {
  if (!searchQuery.value.trim()) return skillStore.mcpServers
  const keyword = searchQuery.value.trim().toLowerCase()
  return skillStore.mcpServers.filter(server => server.name.toLowerCase().includes(keyword))
})

const connectedCount = computed(() => skillStore.mcpServers.filter(server => server.state === 'CONNECTED' || server.state === 'HEALTH_CHECK').length)
const reconnectingCount = computed(() => skillStore.mcpServers.filter(server => server.state === 'RECONNECTING').length)
const totalToolCount = computed(() => skillStore.mcpServers.reduce((sum, server) => sum + server.toolCount, 0))

function canDisconnect(state: string) {
  return state === 'CONNECTED' || state === 'HEALTH_CHECK'
}

function clearSearch() {
  searchQuery.value = ''
}

function formatConnectedSince(dateStr?: string) {
  if (!dateStr) return '暂未建立连接'
  return new Date(dateStr).toLocaleString()
}

async function refreshData() {
  await Promise.all([
    skillStore.fetchMcpServers(),
    skillStore.fetchMcpStatus(),
  ])
}

async function handleCreated() {
  showCreateDialog.value = false
  await refreshData()
}

onMounted(() => {
  void refreshData()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <PageHeader
          eyebrow="MCP 服务器"
          title="外部连接"
          description="先判断连接健康度和工具覆盖情况，再进入单个服务器继续查看配置、异常和可用工具。"
        >
          <template #actions>
            <Button variant="outline" @click="refreshData">
              <RefreshCw class="size-4" />
              刷新
            </Button>
            <Button @click="showCreateDialog = true">
              <Server class="size-4" />
              新建服务器
            </Button>
          </template>

          <template #meta>
            <MetricCard label="当前可见" :value="filteredServers.length" hint="当前搜索条件下能直接继续处理的连接数。">
              <template #icon>
                <Server class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="已连接" :value="connectedCount" hint="当前已经连通并可对外提供工具的服务器。">
              <template #icon>
                <Cable class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="重连中" :value="reconnectingCount" hint="正在尝试恢复连接、值得关注的服务器。">
              <template #icon>
                <RefreshCw class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="可用工具数" :value="totalToolCount" hint="所有 MCP 服务器上报的工具总量。">
              <template #icon>
                <Sparkles class="size-5" />
              </template>
            </MetricCard>
          </template>
        </PageHeader>

        <StatePanel
          v-if="skillStore.npxAvailable === false"
          title="当前环境不可用 Node.js"
          description="部分安装方式需要 Node.js 或 npx。已有服务仍可查看；如果要新增依赖安装的服务，请先准备相关环境。"
          tone="warning"
        >
          <template #icon>
            <ServerOff class="size-5" />
          </template>
          <template #actions>
            <a
              href="https://nodejs.org/"
              target="_blank"
              rel="noreferrer"
              class="inline-flex h-9 items-center justify-center rounded-xl border border-border/70 bg-background px-4 text-sm font-medium text-foreground transition-colors hover:bg-accent"
            >
              打开 Node.js 官网
            </a>
          </template>
        </StatePanel>

        <PageSection
          eyebrow="筛选"
          title="查找连接"
          description="按服务名称快速找到需要查看的连接。"
        >
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_300px]">
            <div class="min-w-0">
              <SearchBar
                v-model="searchQuery"
                placeholder="搜索服务器名称..."
              />
            </div>

            <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 px-4 py-4">
              <div class="space-y-3">
                <div>
                  <div class="surface-label text-[0.68rem]">当前视图</div>
                  <p class="mt-2 text-sm leading-6 text-muted-foreground">
                    先定位要排查的服务，再进入详情页查看连接、健康检查和暴露出来的工具。
                  </p>
                </div>

                <div class="flex flex-wrap gap-2 text-xs">
                  <span class="filter-pill">结果：{{ filteredServers.length }} / {{ skillStore.mcpServers.length }}</span>
                  <span class="filter-pill">已连接：{{ connectedCount }}</span>
                  <span v-if="searchQuery" class="filter-pill">关键词：{{ searchQuery }}</span>
                </div>

                <Button v-if="searchQuery" variant="ghost" class="px-0" @click="clearSearch">
                  清空搜索
                </Button>
              </div>
            </div>
          </div>
        </PageSection>

        <PageSection
          eyebrow="服务器"
          title="已注册的 MCP 连接"
          :description="`当前显示 ${filteredServers.length} / ${skillStore.mcpServers.length} 个已知服务器。`"
        >
          <div v-if="skillStore.loading" class="grid gap-4">
            <div
              v-for="index in 4"
              :key="index"
              class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-5"
            >
              <div class="space-y-3">
                <div class="flex items-center gap-2">
                  <Skeleton class="h-5 w-32" />
                  <Skeleton class="h-5 w-24 rounded-full" />
                  <Skeleton class="h-5 w-16 rounded-full" />
                </div>
                <Skeleton class="h-3 w-1/2" />
                <Skeleton class="h-3 w-full" />
              </div>
            </div>
          </div>

          <StatePanel
            v-else-if="skillStore.error && skillStore.mcpServers.length === 0"
            title="无法加载 MCP 服务器"
            :description="skillStore.error"
            tone="danger"
          >
            <template #icon>
              <ServerOff class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" size="sm" @click="refreshData">
                重试
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="skillStore.mcpServers.length === 0"
            title="暂无已注册的 MCP 服务器"
            description="添加服务器连接后，就可以接入外部工具能力。"
          >
            <template #icon>
              <Server class="size-5" />
            </template>
            <template #actions>
              <Button @click="showCreateDialog = true">
                创建服务器
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="filteredServers.length === 0"
            title="没有匹配当前搜索条件的 MCP 服务器"
            description="可以尝试更短的关键词，或清空搜索框。"
          >
            <template #icon>
              <Server class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" @click="clearSearch">
                清空搜索
              </Button>
            </template>
          </StatePanel>

          <div v-else class="grid gap-4">
            <article
              v-for="server in filteredServers"
              :key="server.name"
              class="list-card cursor-pointer p-5"
              @click="router.push(`/mcp-servers/${server.name}`)"
            >
              <div class="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
                <div class="min-w-0 space-y-3">
                  <div class="flex flex-wrap items-center gap-2">
                    <div class="text-base font-semibold tracking-tight text-foreground">
                      {{ server.name }}
                    </div>
                    <Badge variant="outline" :class="stateConfig[server.state]?.badgeClass ?? ''">
                      {{ stateConfig[server.state]?.label ?? server.state }}
                    </Badge>
                    <Badge variant="outline">
                      {{ server.toolCount }} 个工具
                    </Badge>
                  </div>

                  <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                    <span class="surface-chip">传输：{{ server.config?.transport || '未知' }}</span>
                    <span class="surface-chip">工具：{{ server.toolCount }}</span>
                    <span class="surface-chip">{{ formatConnectedSince(server.connectedSince) }}</span>
                  </div>

                  <div
                    v-if="server.lastError"
                    class="rounded-[calc(var(--radius)+4px)] border border-destructive/20 bg-destructive/6 px-3 py-2 text-sm text-destructive"
                  >
                    {{ server.lastError }}
                  </div>
                </div>

                <div class="flex flex-wrap items-center gap-2 border-t border-border/60 pt-4 xl:border-t-0 xl:pt-0" @click.stop>
                  <Button
                    v-if="server.state === 'DISCONNECTED'"
                    size="sm"
                    @click="skillStore.connectServer(server.name)"
                  >
                    连接
                  </Button>
                  <Button
                    v-if="canDisconnect(server.state)"
                    variant="outline"
                    size="sm"
                    @click="skillStore.disconnectServer(server.name)"
                  >
                    断开连接
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    @click="router.push(`/mcp-servers/${server.name}`)"
                  >
                    查看详情
                  </Button>
                </div>
              </div>
            </article>
          </div>
        </PageSection>
      </div>
    </PageContainer>

    <McpServerForm
      v-if="showCreateDialog"
      mode="create"
      @close="showCreateDialog = false"
      @saved="handleCreated"
    />
  </div>
</template>
