<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  Blocks,
  Braces,
  CircleDollarSign,
  Clock3,
  FileCog,
  PackageSearch,
  ShieldAlert,
  TestTube2,
  Wrench,
} from 'lucide-vue-next'
import { toolApi } from '@/api/client'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useToolStore } from '@/stores/tool'

const YamlEditor = defineAsyncComponent(() => import('@/components/editor/YamlEditor.vue'))
const ToolTestDialog = defineAsyncComponent(() => import('@/components/tool/ToolTestDialog.vue'))

const route = useRoute()
const router = useRouter()
const toolStore = useToolStore()

const toolId = computed(() => route.params.id as string)
const tool = computed(() => toolStore.currentTool)

const pageLoading = ref(true)
const pageError = ref<string | null>(null)
const showTestDialog = ref(false)
const usage = ref<any | null>(null)
const usageLoading = ref(false)
const usageError = ref<string | null>(null)
const yamlLoading = ref(false)
const toolYamlContent = ref('')

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '工具', to: { name: 'tools' } },
  { label: tool.value?.displayName || tool.value?.name || '详情' },
])

const sourceLabel: Record<string, string> = {
  builtin: '内置',
  yaml: 'YAML',
  mcp: 'MCP',
}

const riskBadge: Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive'; class: string }> = {
  LOW: {
    label: '低风险',
    variant: 'secondary',
    class: 'status-btn-inactive',
  },
  MEDIUM: {
    label: '中风险',
    variant: 'outline',
    class: 'border-amber-200/70 bg-amber-50 text-amber-800 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-300',
  },
  HIGH: {
    label: '高风险',
    variant: 'destructive',
    class: 'border-destructive/25 bg-destructive/8 text-destructive',
  },
}

const displayName = computed(() => tool.value?.displayName || tool.value?.name || '工具详情')
const sourceValue = computed(() => sourceLabel[tool.value?.source ?? ''] ?? tool.value?.source ?? '未知')
const riskValue = computed(() => riskBadge[tool.value?.riskLevel ?? '']?.label ?? tool.value?.riskLevel ?? '未知')
const totalUsage = computed(() => {
  if (!usage.value) return 0
  return (usage.value.skillCount ?? 0) + (usage.value.workflowCount ?? 0)
})
const hasSchemas = computed(() => Boolean(tool.value?.inputSchema || tool.value?.outputSchema))
const hasBudget = computed(() => Boolean(tool.value?.budget))
const hasYamlEditor = computed(() => tool.value?.source === 'yaml')
const hasSideEffects = computed(() => Boolean(tool.value?.sideEffects?.length))

onMounted(async () => {
  await loadData()
})

watch(() => route.params.id, async () => {
  showTestDialog.value = false
  await loadData()
})

async function loadData() {
  pageLoading.value = true
  pageError.value = null
  usage.value = null
  usageError.value = null
  toolYamlContent.value = ''

  try {
    await toolStore.fetchToolDetail(toolId.value)
    await Promise.allSettled([
      loadYamlDefinition(),
      loadUsage(),
    ])
  } catch (event: any) {
    pageError.value = event?.message || toolStore.error || '加载工具详情失败。'
  } finally {
    pageLoading.value = false
  }
}

async function loadYamlDefinition() {
  yamlLoading.value = true

  if (!hasYamlEditor.value) {
    yamlLoading.value = false
    return
  }

  try {
    toolYamlContent.value = await toolApi.getToolYaml(toolId.value)
  } finally {
    yamlLoading.value = false
  }
}

async function loadUsage() {
  usageLoading.value = true
  usageError.value = null
  try {
    usage.value = await toolStore.fetchToolUsage(toolId.value)
  } catch (event: any) {
    usage.value = null
    usageError.value = event?.message || toolStore.error || '加载引用信息失败。'
  } finally {
    usageLoading.value = false
  }
}

async function handleSaveYaml(content: string) {
  await toolApi.updateToolYaml(toolId.value, content)
  await toolStore.fetchToolDetail(toolId.value)
}

function formatMoney(cents?: number) {
  if (cents == null) return '未设置'
  return `$${(cents / 100).toFixed(2)}`
}

function goBack() {
  router.push('/tools')
}

function formatUsageLabel(items: number, singular: string, plural: string) {
  return `${items} 个${items === 1 ? singular : plural}`
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <Button type="button" variant="ghost" class="w-fit" @click="goBack">
            <ArrowLeft class="size-4" />
            返回工具列表
          </Button>
        </div>

        <template v-if="pageLoading">
          <div class="space-y-5">
            <div class="space-y-3 border-b border-border/70 pb-6">
              <Skeleton class="h-5 w-20" />
              <Skeleton class="h-10 w-72" />
              <Skeleton class="h-5 w-full max-w-[42rem]" />
            </div>
            <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
            </div>
            <Skeleton class="h-[240px] rounded-[calc(var(--radius)+6px)]" />
            <Skeleton class="h-[320px] rounded-[calc(var(--radius)+6px)]" />
          </div>
        </template>

        <StatePanel
          v-else-if="pageError || !tool"
          title="工具详情暂时不可用"
          :description="pageError || toolStore.error || '没有找到对应的工具。'"
          tone="danger"
        >
          <template #icon>
            <Wrench class="size-5" />
          </template>
          <template #actions>
            <Button type="button" variant="outline" @click="goBack">
              返回列表
            </Button>
            <Button type="button" @click="loadData">
              重试
            </Button>
          </template>
        </StatePanel>

        <template v-else>
          <PageHeader
            eyebrow="工具详情"
            :title="displayName"
            :description="tool.description || '查看这个工具的用途、运行限制、YAML 定义和使用情况。'"
          >
            <template #actions>
              <Button type="button" variant="outline" @click="loadUsage">
                刷新引用信息
              </Button>
              <Button type="button" @click="showTestDialog = true">
                <TestTube2 class="size-4" />
                测试工具
              </Button>
            </template>

            <template #meta>
              <MetricCard label="来源" :value="sourceValue" hint="当前工具实现对应的接入来源。">
                <template #icon>
                  <FileCog class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="风险级别" :value="riskValue" hint="当前工具的执行风险提示。">
                <template #icon>
                  <ShieldAlert class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="幂等性" :value="tool.idempotent ? '是' : '否'" hint="重复调用是否预期产生相同结果。">
                <template #icon>
                  <Blocks class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="下游引用" :value="usageLoading ? '加载中...' : totalUsage" hint="技能和工作流当前对它的引用总数。">
                <template #icon>
                  <PackageSearch class="size-5" />
                </template>
              </MetricCard>
            </template>
          </PageHeader>

          <PageSection
            eyebrow="基础信息"
            title="工具说明"
            description="查看工具用途和参数。"
          >
            <div class="grid gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(260px,0.85fr)]">
              <div class="detail-card p-4 sm:p-5">
                <div class="space-y-4">
                  <div>
                    <div class="surface-label mb-2 text-[0.68rem]">说明</div>
                    <p class="text-sm leading-7 text-foreground">
                      {{ tool.description || '这个工具暂时还没有说明。' }}
                    </p>
                  </div>

                  <div v-if="hasSideEffects" class="border-t border-border/60 pt-4">
                    <div class="surface-label mb-3 text-[0.68rem]">执行提醒</div>
                    <ul class="space-y-2 text-sm text-muted-foreground">
                      <li
                        v-for="(effect, index) in tool.sideEffects"
                        :key="`${tool.id}-effect-${index}`"
                        class="flex items-start gap-2"
                      >
                        <span class="mt-1 size-1.5 rounded-full bg-primary/70" />
                        <span class="leading-6">{{ effect }}</span>
                      </li>
                    </ul>
                  </div>

                  <div v-if="tool.tags?.length" class="border-t border-border/60 pt-4">
                    <div class="surface-label mb-3 text-[0.68rem]">标签</div>
                    <div class="flex flex-wrap gap-2">
                      <Badge v-for="tag in tool.tags" :key="tag" variant="secondary">
                        {{ tag }}
                      </Badge>
                    </div>
                  </div>
                </div>
              </div>

              <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 p-4">
                <div class="space-y-4">
                  <div>
                    <div class="surface-label text-[0.68rem]">当前对象</div>
                    <p class="mt-2 break-all font-mono text-sm text-muted-foreground">{{ tool.id }}</p>
                  </div>

                  <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                    <span class="surface-chip">类型：{{ tool.type }}</span>
                    <span class="surface-chip">来源：{{ sourceValue }}</span>
                    <span class="surface-chip">{{ tool.exportable ? '支持导出' : '不支持导出' }}</span>
                  </div>

                  <p class="text-sm leading-6 text-muted-foreground">
                    查看来源、类型和执行影响。
                  </p>
                </div>
              </div>
            </div>
          </PageSection>

          <div class="grid gap-5 2xl:grid-cols-2">
            <PageSection
              eyebrow="参数"
              title="输入与输出说明"
              :description="hasSchemas ? '查看这个工具接收什么参数、返回什么结果。' : '这个工具暂时没有提供输入或输出说明。'"
            >
              <StatePanel
                v-if="!hasSchemas"
                title="暂无参数说明"
                description="后续补充输入或输出说明后，可在此查看。"
              >
                <template #icon>
                  <Braces class="size-5" />
                </template>
              </StatePanel>

              <div v-else class="space-y-4">
                <div v-if="tool.inputSchema" class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-4">
                  <div class="surface-label mb-3 text-[0.68rem]">输入结构</div>
                  <pre class="overflow-x-auto rounded-[calc(var(--radius)+4px)] bg-muted/55 p-4 text-xs leading-6 text-foreground">{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre>
                </div>

                <div v-if="tool.outputSchema" class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-4">
                  <div class="surface-label mb-3 text-[0.68rem]">输出结构</div>
                  <pre class="overflow-x-auto rounded-[calc(var(--radius)+4px)] bg-muted/55 p-4 text-xs leading-6 text-foreground">{{ JSON.stringify(tool.outputSchema, null, 2) }}</pre>
                </div>
              </div>
            </PageSection>

            <PageSection
              eyebrow="预算"
              title="执行约束"
              :description="hasBudget ? '查看超时、重试次数和成本上限。' : '当前还没有预算配置。'"
            >
              <StatePanel
                v-if="!hasBudget"
                title="暂无运行预算"
                description="当前还没有设置超时、重试次数或成本上限。"
              >
                <template #icon>
                  <CircleDollarSign class="size-5" />
                </template>
              </StatePanel>

              <div v-else class="detail-card p-4 sm:p-5">
                <div class="grid gap-4 sm:grid-cols-3">
                  <div class="space-y-1.5">
                    <div class="flex items-center gap-2 text-sm font-medium text-foreground">
                      <Clock3 class="size-4 text-primary" />
                      超时
                    </div>
                    <p class="text-sm text-muted-foreground">
                      {{ tool.budget?.timeoutSeconds != null ? `${tool.budget.timeoutSeconds}s` : '未设置' }}
                    </p>
                  </div>
                  <div class="space-y-1.5 sm:border-l sm:border-border/60 sm:pl-4">
                    <div class="flex items-center gap-2 text-sm font-medium text-foreground">
                      <Blocks class="size-4 text-primary" />
                      最大重试次数
                    </div>
                    <p class="text-sm text-muted-foreground">
                      {{ tool.budget?.maxRetries ?? '未设置' }}
                    </p>
                  </div>
                  <div class="space-y-1.5 sm:border-l sm:border-border/60 sm:pl-4">
                    <div class="flex items-center gap-2 text-sm font-medium text-foreground">
                      <CircleDollarSign class="size-4 text-primary" />
                      成本上限
                    </div>
                    <p class="text-sm text-muted-foreground">
                      {{ formatMoney(tool.budget?.maxCostCents) }}
                    </p>
                  </div>
                </div>
              </div>
            </PageSection>
          </div>

          <PageSection
            v-if="hasYamlEditor"
            eyebrow="定义"
            title="YAML 源文件"
            description="查看或编辑工具的 YAML 定义。"
          >
            <Skeleton v-if="yamlLoading" class="h-72 w-full rounded-[calc(var(--radius)+6px)]" />
            <YamlEditor
              v-else
              v-model="toolYamlContent"
              title="工具 YAML"
              :on-save="handleSaveYaml"
            />
          </PageSection>

          <PageSection
            eyebrow="引用"
            title="这个工具被谁用到"
            description="查看哪些技能或工作流正在使用这个工具。"
          >
            <StatePanel
              v-if="usageError"
              title="引用信息暂时不可用"
              :description="usageError"
              tone="warning"
            >
              <template #icon>
                <PackageSearch class="size-5" />
              </template>
              <template #actions>
                <Button variant="outline" @click="loadUsage">
                  重新加载
                </Button>
              </template>
            </StatePanel>

            <div v-else-if="usageLoading" class="grid gap-3 xl:grid-cols-2">
              <Skeleton class="h-40 rounded-[calc(var(--radius)+6px)]" />
              <Skeleton class="h-40 rounded-[calc(var(--radius)+6px)]" />
            </div>

            <div v-else class="grid gap-5 xl:grid-cols-2">
              <div class="space-y-3">
                <div class="surface-label text-[0.68rem]">
                  {{ formatUsageLabel(usage?.usedBySkills?.length ?? 0, '技能', '技能') }}
                </div>

                <StatePanel
                  v-if="!usage?.usedBySkills?.length"
                  title="暂无关联技能"
                  description="有技能接入这个工具后，会显示在列表中。"
                >
                  <template #icon>
                    <FileCog class="size-5" />
                  </template>
                </StatePanel>

                <article
                  v-for="skill in usage?.usedBySkills ?? []"
                  :key="skill.id"
                  class="list-card p-4"
                >
                  <div class="flex items-center justify-between gap-4">
                    <div>
                      <div class="text-sm font-medium text-foreground">{{ skill.name }}</div>
                      <div class="text-sm text-muted-foreground">这个技能当前引用了该工具。</div>
                    </div>
                    <Button type="button" variant="outline" @click="router.push(`/skills/${skill.id}`)">
                      打开技能
                    </Button>
                  </div>
                </article>
              </div>

              <div class="space-y-3">
                <div class="surface-label text-[0.68rem]">
                  {{ formatUsageLabel(usage?.usedByWorkflows?.length ?? 0, '工作流', '工作流') }}
                </div>

                <StatePanel
                  v-if="!usage?.usedByWorkflows?.length"
                  title="暂无关联工作流"
                  description="有工作流接入这个工具后，会显示在列表中。"
                >
                  <template #icon>
                    <PackageSearch class="size-5" />
                  </template>
                </StatePanel>

                <article
                  v-for="workflow in usage?.usedByWorkflows ?? []"
                  :key="workflow.id"
                  class="list-card p-4"
                >
                  <div class="flex items-center justify-between gap-4">
                    <div>
                      <div class="text-sm font-medium text-foreground">{{ workflow.name }}</div>
                      <div class="text-sm text-muted-foreground">这个工作流当前引用了该工具。</div>
                    </div>
                    <Button type="button" variant="outline" @click="router.push(`/workflows/${workflow.id}`)">
                      打开工作流
                    </Button>
                  </div>
                </article>
              </div>
            </div>
          </PageSection>
        </template>
      </div>
    </PageContainer>

    <ToolTestDialog
      v-if="showTestDialog && tool"
      :tool="tool"
      @close="showTestDialog = false"
    />
  </div>
</template>
