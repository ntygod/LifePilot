<script setup lang="ts">
import type { Component } from 'vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  BellRing,
  BookOpen,
  Bot,
  Brain,
  CheckCircle2,
  Compass,
  Database,
  FileSearch,
  GitBranch,
  Lightbulb,
  MessageSquare,
  RefreshCw,
  Route,
  Sparkles,
  Target,
  Wrench,
} from 'lucide-vue-next'
import PageContainer from '@/components/layout/PageContainer.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useMemoryStore } from '@/stores/memory'
import { useProactiveStore } from '@/stores/proactive'
import { useToolStore } from '@/stores/tool'

type CapabilityId = 'memory' | 'project' | 'proactive' | 'knowledge' | 'experience' | 'execution'

interface CapabilityCard {
  id: CapabilityId
  title: string
  subtitle: string
  description: string
  icon: Component
  route: string
  routeLabel: string
  prompt: string
  tags: string[]
}

interface ScenarioCard {
  id: string
  title: string
  description: string
  prompt: string
  capabilityIds: CapabilityId[]
}

interface PlaybookStep {
  label: string
  route: string
}

interface Playbook {
  id: string
  title: string
  description: string
  icon: Component
  steps: PlaybookStep[]
  prompt: string
}

interface InsightAction {
  id: string
  title: string
  description: string
  route?: string
  prompt?: string
  icon: Component
}

const router = useRouter()
const chatStore = useChatStore()
const memoryStore = useMemoryStore()
const knowledgeBaseStore = useKnowledgeBaseStore()
const proactiveStore = useProactiveStore()
const toolStore = useToolStore()

const loading = ref(true)
const loadError = ref<string | null>(null)

const capabilities: CapabilityCard[] = [
  {
    id: 'memory',
    title: '长期记忆',
    subtitle: '偏好、事实、经验',
    description: '把零散对话沉淀成可检索、可修正、可巩固的长期上下文。',
    icon: Brain,
    route: '/memories',
    routeLabel: '记忆工作台',
    prompt: '请整理你现在记得的我的偏好、事实和执行经验，按可靠程度分组，并列出需要我确认或修正的地方。',
    tags: ['画像', '事实', '经验'],
  },
  {
    id: 'project',
    title: '项目理解',
    subtitle: '空间、资料、会话',
    description: '围绕一个项目组织记忆、知识库、对话和后续行动。',
    icon: GitBranch,
    route: '/conversations/new',
    routeLabel: '建立项目上下文',
    prompt: '我想让你接管一个项目。请先用最少的问题确认项目目标、当前资料、关键约束和下一步，然后给出一个可持续跟进的工作方式。',
    tags: ['项目空间', '隔离记忆', '资料绑定'],
  },
  {
    id: 'proactive',
    title: '主动跟进',
    subtitle: '提醒、追问、升级',
    description: '把目标、等待事项和节奏偏好转成可控的主动协作。',
    icon: BellRing,
    route: '/settings/proactive',
    routeLabel: '主动助手',
    prompt: '我想把一件事交给你持续跟进。请帮我拆成目标、检查点、提醒时机、需要我授权的主动行为，并给出第一版跟进计划。',
    tags: ['提醒', '信任等级', '待阅队列'],
  },
  {
    id: 'knowledge',
    title: '资料工作',
    subtitle: '知识库、检索、引用',
    description: '围绕上传资料、项目文档和历史上下文完成问答、整理和产出。',
    icon: BookOpen,
    route: '/knowledge-bases',
    routeLabel: '知识库',
    prompt: '我会给你一组资料。请帮我建立资料工作流：先判断资料类型，再告诉我适合做检索问答、摘要、对照分析还是持续项目上下文。',
    tags: ['RAG', '文档', '资料库'],
  },
  {
    id: 'experience',
    title: '复盘沉淀',
    subtitle: '反思、模板、偏好',
    description: '把一次任务的做法抽成经验、偏好规则或可复用流程。',
    icon: Lightbulb,
    route: '/memories?tab=templates',
    routeLabel: '程序记忆',
    prompt: '请复盘我们最近完成的一件事：哪些做法值得保留，哪些应该避免，是否能沉淀成偏好、经验或可复用操作模板。',
    tags: ['复盘', '模板', '巩固'],
  },
  {
    id: 'execution',
    title: '工具执行',
    subtitle: '工具、技能、轨迹',
    description: '让知微在合适的时候调用工具、技能和外部服务，并留下可回看的执行轨迹。',
    icon: Wrench,
    route: '/tools',
    routeLabel: '工具目录',
    prompt: '请判断这件事需要哪些工具或技能协作完成。先列出执行路径、风险点和需要我确认的授权，再开始处理。',
    tags: ['工具', '技能', '轨迹'],
  },
]

const scenarios: ScenarioCard[] = [
  {
    id: 'profile-audit',
    title: '让知微校准对你的理解',
    description: '适合刚建立长期记忆、刚做过大量对话，或担心记忆有偏差时使用。',
    prompt: '请审计你对我的理解：列出长期偏好、稳定事实、近期目标、项目上下文和不确定项。对每一类标注来源可靠度，并把需要我确认的问题集中问出来。',
    capabilityIds: ['memory', 'experience'],
  },
  {
    id: 'project-kickoff',
    title: '启动一个项目搭档模式',
    description: '把目标、资料、会话和后续跟进收束到一个可持续工作的项目空间。',
    prompt: '我准备启动一个项目。请引导我建立项目搭档模式：确认目标、资料入口、记忆边界、关键里程碑、例行跟进方式，并输出第一版项目运行清单。',
    capabilityIds: ['project', 'knowledge', 'proactive'],
  },
  {
    id: 'weekly-review',
    title: '做一次本周复盘',
    description: '从对话、记忆和任务里找线索，沉淀下周可执行的改进动作。',
    prompt: '请帮我做一次本周复盘：回顾最近对话和记忆线索，整理完成事项、卡点、反复出现的问题、值得沉淀的经验，并生成下周行动建议。',
    capabilityIds: ['memory', 'experience', 'proactive'],
  },
  {
    id: 'tool-plan',
    title: '把复杂任务变成执行路线',
    description: '适合需要查资料、写代码、调用工具、生成文件或跨步骤推进的任务。',
    prompt: '我有一个复杂任务要交给你。请先规划执行路线：需要哪些工具、每步产出什么、哪些地方需要我授权、失败时怎么回退，然后再开始第一步。',
    capabilityIds: ['execution', 'knowledge', 'experience'],
  },
]

const playbooks: Playbook[] = [
  {
    id: 'visible-memory',
    title: '记忆闭环',
    description: '看见知微记住了什么，并把错误、过期和有价值的内容持续修正。',
    icon: Brain,
    steps: [
      { label: '查看记忆', route: '/memories' },
      { label: '检查偏好', route: '/memories?tab=preferences' },
      { label: '沉淀模板', route: '/memories?tab=templates' },
    ],
    prompt: '请帮我维护记忆闭环：先检查当前长期记忆，再找出需要确认、删除、合并、升级为偏好或沉淀为模板的内容。',
  },
  {
    id: 'knowledge-work',
    title: '资料驱动',
    description: '让资料库、项目会话和检索结果一起进入工作上下文。',
    icon: FileSearch,
    steps: [
      { label: '准备知识库', route: '/knowledge-bases' },
      { label: '进入对话', route: '/conversations/new' },
      { label: '回看轨迹', route: '/traces' },
    ],
    prompt: '请帮我设计一个资料驱动的工作流程：我会给你资料，请判断知识库组织方式、检索策略、输出结构和后续维护动作。',
  },
  {
    id: 'proactive-rhythm',
    title: '主动节奏',
    description: '把目标、提醒、待阅队列和信任升级收束成可控节奏。',
    icon: Target,
    steps: [
      { label: '配置主动助手', route: '/settings/proactive' },
      { label: '管理定时任务', route: '/scheduled-tasks' },
      { label: '继续对话', route: '/conversations/new' },
    ],
    prompt: '请帮我建立主动协作节奏：把我的目标转成提醒、检查点、待阅项和可升级的主动行为，并说明哪些动作需要先征得我同意。',
  },
]

const capabilityById = computed(() => new Map(capabilities.map(item => [item.id, item])))

const memoryEntityCount = computed(() => memoryStore.stats?.entityCount ?? 0)
const memoryTemplateCount = computed(() => memoryStore.stats?.templateCount ?? 0)
const knowledgeBaseCount = computed(() => knowledgeBaseStore.list.length)
const knowledgeDocumentCount = computed(() => knowledgeBaseStore.list.reduce((sum, item) => sum + item.documentCount, 0))
const proactiveEnabled = computed(() => proactiveStore.config?.enabled ?? false)
const proactiveQueueCount = computed(() => proactiveStore.queueCount)
const pendingUpgradeCount = computed(() => proactiveStore.pendingUpgrades.length)
const toolCount = computed(() => toolStore.tools.length)

const readinessItems = computed(() => [
  {
    label: '长期记忆',
    value: `${memoryEntityCount.value} 条实体`,
    ready: memoryEntityCount.value > 0,
    icon: Brain,
  },
  {
    label: '资料库',
    value: `${knowledgeBaseCount.value} 个库 / ${knowledgeDocumentCount.value} 份资料`,
    ready: knowledgeBaseCount.value > 0,
    icon: Database,
  },
  {
    label: '主动助手',
    value: proactiveEnabled.value ? `已启用，待阅 ${proactiveQueueCount.value}` : '未启用',
    ready: proactiveEnabled.value,
    icon: Sparkles,
  },
  {
    label: '工具执行',
    value: `${toolCount.value} 个工具`,
    ready: toolCount.value > 0,
    icon: Wrench,
  },
])

const insightActions = computed<InsightAction[]>(() => {
  const actions: InsightAction[] = []
  if (memoryEntityCount.value === 0) {
    actions.push({
      id: 'seed-memory',
      title: '先建立你的长期上下文',
      description: '从偏好、稳定事实和近期目标开始，让后续回答有连续性。',
      prompt: '请帮我建立第一版长期上下文。你先问我 5 个必要问题，覆盖偏好、工作方式、近期目标、重要项目和不希望你误记的边界。',
      icon: Brain,
    })
  } else {
    actions.push({
      id: 'audit-memory',
      title: '校准已有记忆',
      description: `当前有 ${memoryEntityCount.value} 条实体，可以检查可靠度、冲突和过期项。`,
      route: '/memories',
      prompt: '请帮我审计已有长期记忆：找出可能过期、冲突、不够可靠或值得升级为偏好规则的内容，并集中向我确认。',
      icon: Brain,
    })
  }

  if (knowledgeBaseCount.value === 0) {
    actions.push({
      id: 'create-kb',
      title: '给项目喂资料',
      description: '先准备一个知识库，后续对话就能围绕资料检索、引用和产出。',
      route: '/knowledge-bases',
      icon: BookOpen,
    })
  }

  if (!proactiveEnabled.value) {
    actions.push({
      id: 'enable-proactive',
      title: '开启可控主动性',
      description: '让知微能在目标、提醒和等待事项上主动跟进。',
      route: '/settings/proactive',
      icon: BellRing,
    })
  } else if (pendingUpgradeCount.value > 0) {
    actions.push({
      id: 'trust-upgrade',
      title: '处理主动行为升级',
      description: `${pendingUpgradeCount.value} 个行为表现稳定，可以确认是否提高自主度。`,
      route: '/settings/proactive',
      icon: CheckCircle2,
    })
  }

  if (memoryTemplateCount.value === 0) {
    actions.push({
      id: 'extract-template',
      title: '沉淀第一个复用流程',
      description: '把一次完成得不错的任务抽成程序记忆，减少下次重复沟通。',
      prompt: '请帮我从最近一次任务中提炼一个可复用流程：说明触发条件、步骤、工具、参数模板和需要避免的错误。',
      icon: Route,
    })
  }

  return actions.slice(0, 4)
})

async function loadOverview() {
  loading.value = true
  loadError.value = null
  const tasks = [
    memoryStore.loadStats(),
    knowledgeBaseStore.fetchList(),
    proactiveStore.fetchConfig(),
    proactiveStore.fetchQueue(),
    proactiveStore.fetchTrustStatus(),
    toolStore.fetchTools(),
  ]
  const results = await Promise.allSettled(tasks)
  if (results.every(result => result.status === 'rejected')) {
    loadError.value = '能力概览暂时不可用'
  }
  loading.value = false
}

function navigateTo(route: string) {
  void router.push(route)
}

function startPrompt(prompt: string) {
  chatStore.activeSessionId = null
  chatStore.pendingDraftMessage = prompt
  void router.push({ name: 'newConversation' })
}

function openCapability(card: CapabilityCard) {
  navigateTo(card.route)
}

function capabilityLabel(id: CapabilityId) {
  return capabilityById.value.get(id)?.title ?? id
}

onMounted(() => {
  void loadOverview()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="mx-auto flex max-w-5xl flex-col gap-xl">
        <header class="flex flex-col gap-md sm:flex-row sm:items-start sm:justify-between">
          <div class="min-w-0">
            <div class="surface-label">个人工作台</div>
            <h1 class="mt-xs text-2xl font-semibold tracking-tight text-foreground">今天让知微做什么？</h1>
          </div>
          <div class="flex flex-wrap items-center gap-sm">
            <Button type="button" variant="outline" :disabled="loading" @click="loadOverview">
              <RefreshCw class="size-4" :class="{ 'animate-spin': loading }" />
              刷新
            </Button>
            <Button type="button" @click="startPrompt(scenarios[0].prompt)">
              <MessageSquare class="size-4" />
              开始校准
            </Button>
          </div>
        </header>

        <section class="rounded-lg border border-border/50 bg-card/70 p-lg">
          <div class="flex flex-col gap-md sm:flex-row sm:items-start sm:justify-between">
            <div class="max-w-2xl">
              <div class="surface-label">轻起手</div>
              <h2 class="mt-xs text-xl font-semibold text-foreground">先把意图说清楚</h2>
            </div>
            <Button type="button" class="w-fit" @click="startPrompt(scenarios[0].prompt)">
              <MessageSquare class="size-4" />
              开始校准
            </Button>
          </div>

          <div class="mt-lg grid grid-cols-1 gap-sm md:grid-cols-2">
            <button
              v-for="scenario in scenarios"
              :key="scenario.id"
              type="button"
              class="group flex cursor-pointer items-start gap-md rounded-lg border border-border/45 bg-background/60 p-md text-left transition-colors hover:border-primary/30 hover:bg-accent/40"
              @click="startPrompt(scenario.prompt)"
            >
              <span class="mt-xs flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Compass class="size-4" />
              </span>
              <span class="min-w-0 flex-1">
                <span class="block text-sm font-semibold text-foreground">{{ scenario.title }}</span>
                <span class="mt-xs block text-sm leading-6 text-muted-foreground">{{ scenario.description }}</span>
                <span class="mt-sm flex flex-wrap gap-xs">
                  <Badge
                    v-for="id in scenario.capabilityIds"
                    :key="id"
                    variant="secondary"
                  >
                    {{ capabilityLabel(id) }}
                  </Badge>
                </span>
              </span>
              <ArrowRight class="mt-xs size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-primary" />
            </button>
          </div>
        </section>

        <StatePanel
          v-if="loadError"
          title="能力概览加载失败"
          :description="loadError"
          tone="danger"
        >
          <template #actions>
            <Button type="button" variant="outline" @click="loadOverview">
              重试
            </Button>
          </template>
        </StatePanel>

        <section class="flex flex-wrap items-center gap-sm border-y border-border/50 py-md">
          <div class="mr-xs text-sm font-medium text-muted-foreground">当前状态</div>
          <div
            v-for="item in readinessItems"
            :key="item.label"
            class="inline-flex items-center gap-sm rounded-full border border-border/50 bg-background/60 px-md py-sm"
          >
            <span
              class="flex size-6 shrink-0 items-center justify-center rounded-full"
              :class="item.ready ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'"
            >
              <component :is="item.icon" class="size-3.5" />
            </span>
            <span class="text-sm text-foreground">{{ item.label }}</span>
            <span class="text-sm text-muted-foreground">{{ item.value }}</span>
          </div>
        </section>

        <section class="grid grid-cols-1 gap-xl lg:grid-cols-3">
          <div class="space-y-xl lg:col-span-2">
            <section>
              <div class="mb-md flex items-center justify-between gap-md">
                <div>
                  <h2 class="text-lg font-semibold text-foreground">能力入口</h2>
                  <p class="text-sm text-muted-foreground">
                    记忆、资料、提醒、工具。
                  </p>
                </div>
                <Badge variant="outline">{{ capabilities.length }} 项</Badge>
              </div>

              <div v-if="loading" class="space-y-sm">
                <div v-for="index in 4" :key="index" class="rounded-lg border border-border/45 bg-card/60 p-md">
                  <Skeleton class="h-5 w-32" />
                  <Skeleton class="mt-sm h-4 w-full" />
                </div>
              </div>

              <div v-else class="divide-y divide-border/50 rounded-lg border border-border/50 bg-card/60">
                <article
                  v-for="card in capabilities"
                  :key="card.id"
                  class="flex flex-col gap-md p-md sm:flex-row sm:items-center"
                >
                  <div class="flex min-w-0 flex-1 items-start gap-md">
                    <div class="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                      <component :is="card.icon" class="size-4" />
                    </div>
                    <div class="min-w-0">
                      <div class="flex flex-wrap items-center gap-sm">
                        <h3 class="text-base font-semibold text-foreground">{{ card.title }}</h3>
                        <span class="text-sm text-muted-foreground">{{ card.subtitle }}</span>
                      </div>
                      <p class="mt-xs text-sm leading-6 text-muted-foreground">
                        {{ card.description }}
                      </p>
                      <div class="mt-sm flex flex-wrap gap-xs">
                        <span v-for="tag in card.tags" :key="tag" class="surface-chip">
                          {{ tag }}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div class="flex shrink-0 gap-sm sm:justify-end">
                    <Button type="button" variant="outline" size="sm" @click="openCapability(card)">
                      {{ card.routeLabel }}
                    </Button>
                    <Button type="button" variant="ghost" size="sm" @click="startPrompt(card.prompt)">
                      <Sparkles class="size-4" />
                      带入对话
                    </Button>
                  </div>
                </article>
              </div>
            </section>

            <section>
              <div class="mb-md flex items-center justify-between gap-md">
                <div>
                  <h2 class="text-lg font-semibold text-foreground">常用串联</h2>
                  <p class="text-sm text-muted-foreground">
                    记忆闭环、资料驱动、主动节奏。
                  </p>
                </div>
                <Route class="size-5 text-primary" />
              </div>

              <div class="grid grid-cols-1 gap-sm">
                <article
                  v-for="playbook in playbooks"
                  :key="playbook.id"
                  class="rounded-lg border border-border/50 bg-card/60 p-md"
                >
                  <div class="flex items-start gap-md">
                    <div class="flex size-9 shrink-0 items-center justify-center rounded-lg bg-background text-primary">
                      <component :is="playbook.icon" class="size-4" />
                    </div>
                    <div class="min-w-0 flex-1">
                      <h3 class="text-base font-semibold text-foreground">{{ playbook.title }}</h3>
                      <p class="mt-xs text-sm leading-6 text-muted-foreground">{{ playbook.description }}</p>
                      <div class="mt-sm flex flex-wrap gap-xs">
                        <button
                          v-for="step in playbook.steps"
                          :key="step.label"
                          type="button"
                          class="surface-chip cursor-pointer transition-colors hover:border-primary/30 hover:text-foreground"
                          @click="navigateTo(step.route)"
                        >
                          {{ step.label }}
                        </button>
                      </div>
                    </div>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      class="shrink-0"
                      @click="startPrompt(playbook.prompt)"
                    >
                      串起来
                      <ArrowRight class="size-4" />
                    </Button>
                  </div>
                </article>
              </div>
            </section>
          </div>

          <aside class="space-y-xl">
            <section>
              <div class="mb-md flex items-center justify-between gap-md">
                <div>
                  <h2 class="text-base font-semibold text-foreground">下一步建议</h2>
                  <p class="text-sm text-muted-foreground">按当前状态生成。</p>
                </div>
                <Sparkles class="size-5 text-primary" />
              </div>

              <div v-if="loading" class="space-y-md">
                <Skeleton v-for="index in 3" :key="index" class="h-20 rounded-lg" />
              </div>

              <div v-else class="space-y-xs">
                <article
                  v-for="action in insightActions"
                  :key="action.id"
                  class="rounded-lg border border-border/50 bg-card/60 p-md"
                >
                  <div class="flex items-start gap-sm">
                    <component :is="action.icon" class="mt-xs size-4 shrink-0 text-primary" />
                    <div class="min-w-0 flex-1">
                      <h3 class="text-sm font-semibold text-foreground">{{ action.title }}</h3>
                      <p class="mt-xs text-sm leading-6 text-muted-foreground">{{ action.description }}</p>
                    </div>
                  </div>
                  <div class="mt-md flex justify-end gap-sm">
                    <Button
                      v-if="action.route"
                      type="button"
                      variant="outline"
                      size="sm"
                      @click="navigateTo(action.route)"
                    >
                      打开
                    </Button>
                    <Button
                      v-if="action.prompt"
                      type="button"
                      size="sm"
                      @click="startPrompt(action.prompt)"
                    >
                      开始
                    </Button>
                  </div>
                </article>
              </div>
            </section>

            <section class="rounded-lg border border-border/50 bg-card/60 p-md">
              <div class="flex items-start gap-md">
                <Bot class="size-5 shrink-0 text-primary" />
                <div class="min-w-0">
                  <h2 class="text-base font-semibold text-foreground">本地个人节奏</h2>
                  <p class="mt-xs text-sm leading-6 text-muted-foreground">
                    安静、可控、少打扰。
                  </p>
                </div>
              </div>
            </section>
          </aside>
        </section>
      </div>
    </PageContainer>
  </div>
</template>
