<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { Clock3, RefreshCw, ShieldAlert, ShieldCheck, ShieldPlus, Trash2 } from 'lucide-vue-next'
import { channelApi, permissionApi } from '@/api/client'
import type { PermissionGrant, PermissionGrantCreateRequest } from '@/types'
import StatePanel from '@/components/common/StatePanel.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { useUiStore } from '@/stores/ui'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

type SelectValue = AcceptableValue | undefined
type SubjectType = 'SESSION' | 'WORKSPACE' | 'TASK' | 'USER'
type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

interface Option {
  value: string
  label: string
  description: string
}

const uiStore = useUiStore()

const grants = ref<PermissionGrant[]>([])
const pluginChannelOptions = ref<Array<{ value: string; label: string }>>([])
const loading = ref(true)
const creating = ref(false)
const revokingGrantId = ref<string | null>(null)

const activeOnly = ref(true)
const filterSubjectType = ref<'ALL' | SubjectType>('ALL')
const filterSubjectId = ref('')

const form = ref({
  subjectType: 'WORKSPACE' as SubjectType,
  subjectId: '',
  actionType: 'WRITE_FILE',
  riskCeiling: 'HIGH' as RiskLevel,
  autonomousAllowed: false,
  channels: [defaultSingleChannel()] as string[],
  expiresAt: '',
  reason: '',
  scopeJson: '',
})

const subjectOptions: Option[] = [
  { value: 'SESSION', label: '本会话', description: '仅当前会话内的相似操作可复用授权。' },
  { value: 'WORKSPACE', label: '当前工作区', description: '适合文件修改、Shell、构建等项目内操作。' },
  { value: 'TASK', label: '当前任务', description: '适合定时任务、心跳巡检和无人值守工作流。' },
  { value: 'USER', label: '长期', description: '适合跨会话复用的个人长期授权。' },
]

const actionOptions: Option[] = [
  { value: 'READ_FILE', label: '读取文件', description: '查看工作区文件和目录内容。' },
  { value: 'WRITE_FILE', label: '修改文件', description: '创建、覆盖、补丁更新项目文件。' },
  { value: 'DELETE_FILE', label: '删除文件', description: '删除文件或目录，属于不可逆高风险操作。' },
  { value: 'EXECUTE_SHELL', label: '执行 Shell', description: '运行命令行、安装依赖、构建或脚本任务。' },
  { value: 'BROWSER_AUTOMATION', label: '浏览器自动化', description: '驱动网页操作、点击、输入和登录流程。' },
  { value: 'HTTP_REQUEST', label: '访问外部网络', description: '向第三方 API 或网站发起请求。' },
  { value: 'WRITE_MEMORY', label: '写入记忆', description: '写入长期记忆、偏好和经验条目。' },
  { value: 'MODIFY_DATASTORE', label: '修改数据存储', description: '更新集合、文档和结构化数据。' },
  { value: 'CREATE_SCHEDULE', label: '管理定时任务', description: '创建、修改或取消 Cron / 心跳任务。' },
  { value: 'GENERIC_TOOL_OPERATION', label: '任务级高风险操作', description: '适合 Cron、心跳和工作流复用的统一高风险授权。' },
]

const riskOptions: Option[] = [
  { value: 'LOW', label: '低风险', description: '普通操作，几乎不需要额外审查。' },
  { value: 'MEDIUM', label: '中风险', description: '可直接执行，但需要保留审计痕迹。' },
  { value: 'HIGH', label: '高风险', description: '需要明确授权后才执行。' },
  { value: 'CRITICAL', label: '关键风险', description: '涉及不可逆或高敏感操作，建议谨慎授权。' },
]

const systemChannelOptions = [
  { value: 'workflow', label: '工作流' },
  { value: 'cron', label: 'Cron' },
  { value: 'heartbeat', label: '心跳巡检' },
]

const channelOptions = computed(() => {
  const merged = [...pluginChannelOptions.value, ...systemChannelOptions]
  const unique = new Map<string, { value: string; label: string }>()
  merged.forEach(option => {
    if (!unique.has(option.value)) {
      unique.set(option.value, option)
    }
  })
  return Array.from(unique.values())
})

const interactiveChannelValues = computed(() => pluginChannelOptions.value.map(option => option.value))

const summaryItems = computed(() => {
  const activeGrants = grants.value.filter(grant => !grant.revokedAt)
  const autonomousGrants = activeGrants.filter(grant => grant.autonomousAllowed)
  const expiringSoon = activeGrants.filter(grant => {
    if (!grant.expiresAt) return false
    const expiresAt = new Date(grant.expiresAt).getTime()
    return expiresAt > Date.now() && expiresAt - Date.now() <= 24 * 60 * 60 * 1000
  })
  return [
    { label: '有效授权', value: String(activeGrants.length), hint: '当前仍可命中的授权记录。' },
    { label: '任务预授权', value: String(autonomousGrants.length), hint: '允许 Cron、心跳或工作流直接执行。' },
    { label: '24 小时内到期', value: String(expiringSoon.length), hint: '建议提前续期，避免无人值守任务中断。' },
  ]
})

const currentSubjectOption = computed(() =>
  subjectOptions.find(option => option.value === form.value.subjectType) ?? subjectOptions[0],
)

const currentActionOption = computed(() =>
  actionOptions.find(option => option.value === form.value.actionType) ?? actionOptions[0],
)

const currentRiskOption = computed(() =>
  riskOptions.find(option => option.value === form.value.riskCeiling) ?? riskOptions[2],
)

const scopePlaceholder = computed(() => {
  switch (form.value.actionType) {
    case 'READ_FILE':
    case 'WRITE_FILE':
    case 'DELETE_FILE':
      return '{\n  "workspacePath": "D:/WorkSpace/Project/News"\n}'
    case 'EXECUTE_SHELL':
      return '{\n  "workspacePath": "D:/WorkSpace/Project/News"\n}'
    case 'BROWSER_AUTOMATION':
    case 'HTTP_REQUEST':
      return '{\n  "origin": "https://github.com"\n}'
    case 'MODIFY_DATASTORE':
      return '{\n  "collection": "notes"\n}'
    case 'CREATE_SCHEDULE':
      return '{\n  "taskId": "task-123"\n}'
    default:
      return '{\n  "key": "value"\n}'
  }
})

const filteredGrants = computed(() => {
  let list = grants.value.slice()
  if (filterSubjectType.value !== 'ALL') {
    list = list.filter(grant => grant.subjectType === filterSubjectType.value)
  }
  if (filterSubjectId.value.trim()) {
    const keyword = filterSubjectId.value.trim().toLowerCase()
    list = list.filter(grant => grant.subjectId.toLowerCase().includes(keyword))
  }
  return list
})

function normalizeSelectValue(value: SelectValue): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return ''
}

function formatDateTime(value?: string | null) {
  if (!value) return '永久有效'
  return new Date(value).toLocaleString('zh-CN')
}

function riskBadgeVariant(risk: string) {
  switch (risk) {
    case 'CRITICAL':
      return 'destructive'
    case 'HIGH':
      return 'secondary'
    default:
      return 'outline'
  }
}

function actionLabel(actionType: string) {
  if (actionType === 'GENERIC_TOOL_OPERATION') {
    return '任务级高风险操作'
  }
  return actionOptions.find(option => option.value === actionType)?.label ?? actionType
}

function subjectLabel(subjectType: string) {
  return subjectOptions.find(option => option.value === subjectType)?.label ?? subjectType
}

function defaultSingleChannel() {
  return interactiveChannelValues.value[0] ?? 'web'
}

function defaultInteractiveChannels() {
  return interactiveChannelValues.value.length > 0
    ? [...interactiveChannelValues.value]
    : ['web']
}

function syncSubjectDefaults(subjectType: SubjectType) {
  if (subjectType === 'TASK') {
    form.value.channels = ['workflow', 'cron', 'heartbeat']
    form.value.autonomousAllowed = true
    return
  }
  if (subjectType === 'USER') {
    form.value.channels = defaultInteractiveChannels()
    form.value.autonomousAllowed = false
    return
  }
  form.value.channels = [defaultSingleChannel()]
  form.value.autonomousAllowed = false
}

function toggleChannel(channel: string) {
  if (form.value.channels.includes(channel)) {
    form.value.channels = form.value.channels.filter(item => item !== channel)
    return
  }
  form.value.channels = [...form.value.channels, channel]
}

function resetForm() {
  form.value = {
    subjectType: 'WORKSPACE',
    subjectId: '',
    actionType: 'WRITE_FILE',
    riskCeiling: 'HIGH',
    autonomousAllowed: false,
    channels: [defaultSingleChannel()],
    expiresAt: '',
    reason: '',
    scopeJson: '',
  }
}

async function loadChannelOptions() {
  try {
    const plugins = await channelApi.listPlugins()
    const unique = new Map<string, { value: string; label: string }>()
    plugins.forEach(plugin => {
      if (!unique.has(plugin.platform)) {
        unique.set(plugin.platform, {
          value: plugin.platform,
          label: plugin.name,
        })
      }
    })
    pluginChannelOptions.value = Array.from(unique.values())
    if (!form.value.channels.length) {
      form.value.channels = [defaultSingleChannel()]
    }
  } catch (error) {
    console.error('加载渠道选项失败:', error)
    pluginChannelOptions.value = [{ value: 'web', label: 'Web UI' }]
  }
}

async function loadGrants() {
  loading.value = true
  try {
    grants.value = await permissionApi.listGrants({
      activeOnly: activeOnly.value,
      subjectType: filterSubjectType.value === 'ALL' ? undefined : filterSubjectType.value,
      subjectId: filterSubjectId.value.trim() || undefined,
    })
  } catch (error) {
    console.error('加载授权记录失败:', error)
    uiStore.showToast('error', '加载授权记录失败')
  } finally {
    loading.value = false
  }
}

function buildCreatePayload(): PermissionGrantCreateRequest | null {
  if (!form.value.subjectId.trim()) {
    uiStore.showToast('error', '请填写授权主体标识')
    return null
  }

  let scope: Record<string, unknown> | undefined
  if (form.value.scopeJson.trim()) {
    try {
      scope = JSON.parse(form.value.scopeJson)
    } catch (error) {
      console.error('解析授权作用域失败:', error)
      uiStore.showToast('error', '作用域 JSON 格式不合法')
      return null
    }
  }

  return {
    subjectType: form.value.subjectType,
    subjectId: form.value.subjectId.trim(),
    actionType: form.value.actionType,
    riskCeiling: form.value.riskCeiling,
    scope,
    channels: form.value.channels,
    autonomousAllowed: form.value.autonomousAllowed,
    expiresAt: form.value.expiresAt ? new Date(form.value.expiresAt).toISOString() : null,
    createdBy: 'web-settings',
    sourceEntryId: null,
    reason: form.value.reason.trim() || null,
    metadata: { source: 'settings-page' },
  }
}

async function createGrant() {
  const payload = buildCreatePayload()
  if (!payload) return

  creating.value = true
  try {
    await permissionApi.createGrant(payload)
    uiStore.showToast('success', '授权已创建')
    resetForm()
    await loadGrants()
  } catch (error) {
    console.error('创建授权失败:', error)
    uiStore.showToast('error', '创建授权失败')
  } finally {
    creating.value = false
  }
}

async function revokeGrant(grant: PermissionGrant) {
  revokingGrantId.value = grant.id
  try {
    await permissionApi.revokeGrant(grant.id, {
      revokedBy: 'web-settings',
      reason: '设置页手动撤销',
    })
    uiStore.showToast('success', '授权已撤销')
    await loadGrants()
  } catch (error) {
    console.error('撤销授权失败:', error)
    uiStore.showToast('error', '撤销授权失败')
  } finally {
    revokingGrantId.value = null
  }
}

function updateSubjectType(value: SelectValue) {
  const subjectType = (normalizeSelectValue(value) || 'WORKSPACE') as SubjectType
  form.value.subjectType = subjectType
  syncSubjectDefaults(subjectType)
}

function updateActionType(value: SelectValue) {
  form.value.actionType = normalizeSelectValue(value) || 'WRITE_FILE'
}

function updateRiskCeiling(value: SelectValue) {
  form.value.riskCeiling = (normalizeSelectValue(value) || 'HIGH') as RiskLevel
}

function updateExpiresAt(value: string | number) {
  form.value.expiresAt = typeof value === 'string' ? value : String(value)
}

function updateActiveOnly(value: boolean | 'indeterminate') {
  activeOnly.value = value === true
  void loadGrants()
}

function updateFilterSubjectType(value: SelectValue) {
  filterSubjectType.value = (normalizeSelectValue(value) || 'ALL') as 'ALL' | SubjectType
}

onMounted(() => {
  void loadChannelOptions()
  void loadGrants()
})
</script>

<template>
  <div class="space-y-6">
    <StatePanel
      title="授权与自动执行"
      description="默认工具可用，高风险动作改成授权模型控制。聊天里授权是主入口，这里主要负责查看、撤销和少量高级手动创建。"
    >
      <template #icon>
        <ShieldCheck class="size-5" />
      </template>
      <template #actions>
        <Button variant="outline" class="gap-2" @click="loadGrants">
          <RefreshCw class="size-4" />
          刷新
        </Button>
      </template>

      <div class="grid gap-3 md:grid-cols-3">
        <div
          v-for="item in summaryItems"
          :key="item.label"
          class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4"
        >
          <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">{{ item.label }}</div>
          <div class="mt-2 text-2xl font-semibold text-foreground">{{ item.value }}</div>
          <div class="mt-1 text-sm text-muted-foreground">{{ item.hint }}</div>
        </div>
      </div>
    </StatePanel>

    <StatePanel
      title="推荐用法"
      description="大多数情况下，直接在对话里授权就够了。只有需要提前为项目、任务或账号批量放权时，才需要在这里手动创建授权。"
    >
      <template #icon>
        <ShieldAlert class="size-5" />
      </template>

      <div class="grid gap-3 md:grid-cols-3">
        <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
          <div class="text-sm font-medium text-foreground">聊天内授权</div>
          <div class="mt-1 text-sm leading-6 text-muted-foreground">
            适合当前会话或当前工作目录，授权一次后，同类操作不再反复审批。
          </div>
        </div>
        <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
          <div class="text-sm font-medium text-foreground">任务级预授权</div>
          <div class="mt-1 text-sm leading-6 text-muted-foreground">
            适合 Cron、心跳和工作流。任务创建时授权一次，后续自动执行直接复用。
          </div>
        </div>
        <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
          <div class="text-sm font-medium text-foreground">设置页管理</div>
          <div class="mt-1 text-sm leading-6 text-muted-foreground">
            适合续期、撤销、排查范围，或者手动补一条长期授权记录。
          </div>
        </div>
      </div>
    </StatePanel>

    <section class="detail-card p-4">
      <SettingSection
        title="高级手动创建授权"
        description="只有在需要提前批量放权时才建议使用。日常优先通过聊天内授权完成。"
      >
        <template #header-actions>
          <Badge variant="outline">{{ currentSubjectOption.label }}</Badge>
          <Badge :variant="riskBadgeVariant(form.riskCeiling)">{{ currentRiskOption.label }}</Badge>
          <Button class="gap-2" :disabled="creating" @click="createGrant">
            <ShieldPlus class="size-4" />
            创建授权
          </Button>
        </template>

        <SettingItem
          label="授权主体"
          :description="currentSubjectOption.description"
        >
          <Select :model-value="form.subjectType" @update:model-value="updateSubjectType">
            <SelectTrigger class="w-[220px]">
              <SelectValue placeholder="选择授权主体" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in subjectOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>

        <SettingItem
          label="主体标识"
          description="会话 ID、工作区路径、任务 ID 或用户标识。"
        >
          <Input
            v-model="form.subjectId"
            class="max-w-[420px]"
            :placeholder="form.subjectType === 'WORKSPACE' ? 'D:/WorkSpace/Project/News' : '请输入主体标识'"
          />
        </SettingItem>

        <SettingItem
          label="操作类型"
          :description="currentActionOption.description"
        >
          <Select :model-value="form.actionType" @update:model-value="updateActionType">
            <SelectTrigger class="w-[260px]">
              <SelectValue placeholder="选择操作类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in actionOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>

        <SettingItem
          label="到期时间"
          description="留空表示长期有效；建议给高风险授权设置过期时间。"
        >
          <Input
            :model-value="form.expiresAt"
            class="max-w-[260px]"
            type="datetime-local"
            @update:model-value="updateExpiresAt"
          />
        </SettingItem>

        <SettingItem
          label="授权说明"
          description="记录这条授权的用途，便于后续复盘和撤销。"
        >
          <Textarea
            v-model="form.reason"
            rows="3"
            class="max-w-[680px] resize-none"
            placeholder="例如：允许当前工作目录自动修改前端代码并执行构建命令。"
          />
        </SettingItem>

        <div class="pt-2">
          <details class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-3">
            <summary class="cursor-pointer select-none text-sm font-medium text-foreground">
              高级选项
            </summary>

            <div class="mt-4 space-y-4">
              <SettingItem
                label="风险上限"
                :description="currentRiskOption.description"
              >
                <Select :model-value="form.riskCeiling" @update:model-value="updateRiskCeiling">
                  <SelectTrigger class="w-[220px]">
                    <SelectValue placeholder="选择风险上限" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem v-for="option in riskOptions" :key="option.value" :value="option.value">
                      {{ option.label }}
                    </SelectItem>
                  </SelectContent>
                </Select>
              </SettingItem>

              <SettingItem
                label="自主执行"
                description="开启后，Cron、心跳和工作流等无人值守场景可以直接复用该授权。"
              >
                <Switch
                  :model-value="form.autonomousAllowed"
                  @update:model-value="value => form.autonomousAllowed = value === true"
                />
              </SettingItem>

              <SettingItem
                label="生效渠道"
                description="任务级授权默认绑定 Cron、心跳和工作流；其他授权默认只对当前渠道生效。"
              >
                <div class="flex flex-wrap gap-2">
                  <Button
                    v-for="channel in channelOptions"
                    :key="channel.value"
                    type="button"
                    size="sm"
                    :variant="form.channels.includes(channel.value) ? 'default' : 'outline'"
                    class="h-8 px-3 text-xs"
                    @click="toggleChannel(channel.value)"
                  >
                    {{ channel.label }}
                  </Button>
                </div>
              </SettingItem>

              <SettingItem
                label="作用域 JSON"
                description="可选。用于进一步限制资源范围，例如工作区路径、Origin、集合名或任务 ID。"
              >
                <Textarea
                  v-model="form.scopeJson"
                  rows="5"
                  class="max-w-[680px] font-mono text-xs"
                  :placeholder="scopePlaceholder"
                />
              </SettingItem>
            </div>
          </details>
        </div>
      </SettingSection>
    </section>

    <section class="detail-card p-4">
      <SettingSection
        title="授权记录"
        description="查看当前生效的授权、无人值守范围和已撤销记录。"
      >
        <template #header-actions>
          <div class="flex flex-wrap items-center gap-3">
            <div class="flex items-center gap-2 text-sm text-muted-foreground">
              <span>仅看有效</span>
              <Switch
                :model-value="activeOnly"
                @update:model-value="updateActiveOnly"
              />
            </div>
            <Button variant="outline" size="sm" @click="loadGrants">刷新列表</Button>
          </div>
        </template>

        <SettingItem
          label="筛选主体"
          description="按授权主体范围过滤，便于排查某个任务、会话或工作区的授权记录。"
        >
          <div class="flex flex-wrap gap-3">
            <Select
              :model-value="filterSubjectType"
              @update:model-value="updateFilterSubjectType"
            >
              <SelectTrigger class="w-[220px]">
                <SelectValue placeholder="全部主体" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部主体</SelectItem>
                <SelectItem v-for="option in subjectOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </SelectItem>
              </SelectContent>
            </Select>
            <Input
              v-model="filterSubjectId"
              class="w-[320px]"
              placeholder="输入主体标识后刷新列表"
            />
          </div>
        </SettingItem>

        <div v-if="loading" class="space-y-3 pt-4">
          <div
            v-for="index in 3"
            :key="index"
            class="h-28 animate-pulse rounded-[calc(var(--radius)+8px)] border border-border/70 bg-muted/30"
          />
        </div>

        <div v-else-if="filteredGrants.length === 0" class="pt-4">
          <StatePanel
            title="还没有匹配的授权"
            description="可以先创建一条工作区或任务级授权，减少复杂操作中的重复审批。"
            tone="warning"
          >
            <template #icon>
              <ShieldAlert class="size-5" />
            </template>
          </StatePanel>
        </div>

        <div v-else class="space-y-4 pt-4">
          <article
            v-for="grant in filteredGrants"
            :key="grant.id"
            class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 p-4"
          >
            <div class="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
              <div class="min-w-0 flex-1 space-y-3">
                <div class="flex flex-wrap items-center gap-2">
                  <Badge :variant="riskBadgeVariant(grant.riskCeiling)">
                    {{ grant.riskCeiling }}
                  </Badge>
                  <Badge variant="outline">
                    {{ actionLabel(grant.actionType) }}
                  </Badge>
                  <Badge variant="outline">
                    {{ subjectLabel(grant.subjectType) }}
                  </Badge>
                  <Badge v-if="grant.autonomousAllowed" variant="secondary">
                    可自主执行
                  </Badge>
                  <Badge v-if="grant.revokedAt" variant="outline">
                    已撤销
                  </Badge>
                </div>

                <div class="grid gap-3 text-sm md:grid-cols-2">
                  <div>
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">主体标识</div>
                    <div class="mt-1 break-all text-foreground">{{ grant.subjectId }}</div>
                  </div>
                  <div>
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">生效渠道</div>
                    <div class="mt-1 flex flex-wrap gap-2">
                      <Badge v-for="channel in grant.channels" :key="channel" variant="outline">
                        {{ channel }}
                      </Badge>
                    </div>
                  </div>
                  <div>
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">创建时间</div>
                    <div class="mt-1 text-foreground">{{ formatDateTime(grant.createdAt) }}</div>
                  </div>
                  <div>
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">到期时间</div>
                    <div class="mt-1 flex items-center gap-2 text-foreground">
                      <Clock3 class="size-4 text-muted-foreground" />
                      {{ formatDateTime(grant.expiresAt) }}
                    </div>
                  </div>
                </div>

                <div v-if="grant.reason" class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-muted/20 px-3 py-3 text-sm text-muted-foreground">
                  {{ grant.reason }}
                </div>

                <div v-if="Object.keys(grant.scope ?? {}).length > 0">
                  <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">作用域</div>
                  <pre class="mt-2 overflow-x-auto rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-3 py-3 text-xs text-foreground">{{ JSON.stringify(grant.scope, null, 2) }}</pre>
                </div>
              </div>

              <div class="flex shrink-0 items-start gap-2">
                <Button
                  v-if="!grant.revokedAt"
                  variant="outline"
                  size="sm"
                  class="gap-2"
                  :disabled="revokingGrantId === grant.id"
                  @click="revokeGrant(grant)"
                >
                  <Trash2 class="size-4" />
                  撤销
                </Button>
              </div>
            </div>
          </article>
        </div>
      </SettingSection>
    </section>
  </div>
</template>
