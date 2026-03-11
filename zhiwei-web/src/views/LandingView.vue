<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  BookOpen,
  Bot,
  MessageSquare,
  ShieldCheck,
  Workflow,
  Wrench,
} from 'lucide-vue-next'
import MetricCard from '@/components/common/MetricCard.vue'
import { Button } from '@/components/ui/button'

const router = useRouter()

function startUsing() {
  router.push({ name: 'conversations' })
}

const workspaceModules = [
  {
    title: '对话',
    description: '追问、澄清和回看都留在同一条上下文里。',
    icon: MessageSquare,
  },
  {
    title: '知识库',
    description: '把规范、文档和资料接进来，让回答有出处。',
    icon: BookOpen,
  },
  {
    title: '智能体与工具',
    description: '把常用规则、工具和模型整理成能复用的配置。',
    icon: Bot,
  },
  {
    title: '工作流',
    description: '把会重复发生的步骤沉淀成可执行流程。',
    icon: Workflow,
  },
] as const

const useCases = [
  {
    title: '需求调研与归纳',
    description: '边看资料边记录判断，最后直接收成结论和待办。',
  },
  {
    title: '开发排障与回放',
    description: '把日志、代码片段、工具调用和上下文放在同一处处理。',
  },
  {
    title: '团队交接与复核',
    description: '同事下次接手时，不需要再从零还原过程。',
  },
] as const

const principles = [
  {
    title: '本地优先',
    description: '数据来源和运行边界更清楚，不靠空泛话术来解释价值。',
    icon: ShieldCheck,
  },
  {
    title: '过程可查',
    description: '工具调用、知识引用和轨迹都能追到细节，而不是只看最终答案。',
    icon: Wrench,
  },
  {
    title: '能力可组合',
    description: '对话、智能体、工具和工作流不是分开的孤岛，可以按工作方式拼起来。',
    icon: Workflow,
  },
] as const
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <div class="mx-auto flex min-h-screen max-w-[1200px] flex-col px-6 py-8 sm:px-8 lg:px-10">
      <header class="flex items-center justify-between gap-4 border-b border-border/70 pb-5">
        <div class="space-y-1">
          <div class="text-sm font-semibold tracking-[0.18em] text-muted-foreground">ZHIWEI</div>
          <div class="text-sm text-muted-foreground">本地优先的协作工作台</div>
        </div>

        <Button @click="startUsing">
          开始使用
        </Button>
      </header>

      <main class="flex-1 space-y-16 py-10 lg:space-y-20 lg:py-14">
        <section class="grid gap-10 lg:grid-cols-[minmax(0,1.2fr)_360px] xl:gap-14">
          <div class="space-y-7">
            <div class="space-y-4">
              <div class="surface-label text-primary">给需要持续推进的工作</div>
              <h1 class="max-w-[12ch] text-5xl font-semibold leading-[1.02] tracking-[-0.055em] sm:text-6xl">
                把资料、对话和操作，放进一个能继续接手的工作台。
              </h1>
              <p class="max-w-[38rem] text-base leading-8 text-muted-foreground">
                知微更在意一次处理过的上下文、调用和结果能不能留下来，
                下次回来还能接着做，而不是重新开一张白纸。
              </p>
            </div>

            <div class="flex flex-col gap-3 sm:flex-row">
              <Button size="lg" class="h-12 px-6 text-base" @click="startUsing">
                进入工作台
                <ArrowRight class="size-4" />
              </Button>
              <Button size="lg" variant="outline" class="h-12 px-6 text-base" @click="startUsing">
                看最近会话
              </Button>
            </div>

            <div class="grid gap-3 sm:grid-cols-3">
              <MetricCard label="工作方式" value="边处理边留痕" hint="结果、来源和操作放在同一处。">
                <template #icon>
                  <MessageSquare class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="接手成本" value="下次回来能继续" hint="上下文不会只停留在一条回答里。">
                <template #icon>
                  <BookOpen class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="适用场景" value="高频工具型工作" hint="调研、排障、整理、交接都适合。">
                <template #icon>
                  <Workflow class="size-5" />
                </template>
              </MetricCard>
            </div>
          </div>

          <aside class="detail-card p-5">
            <div class="space-y-2 border-b border-border/70 pb-4">
              <div class="surface-label">今天就能开始</div>
              <h2 class="text-lg font-semibold text-foreground">先从常用入口开始，不必一次配全</h2>
              <p class="text-sm leading-6 text-muted-foreground">
                对话、资料、工具和流程可以边用边补，先把最常见的一步走通就够了。
              </p>
            </div>

            <div class="mt-1 space-y-2">
              <div
                v-for="item in workspaceModules"
                :key="item.title"
                class="list-card group flex items-start gap-3 p-4"
              >
                <div class="flex size-10 shrink-0 items-center justify-center rounded-xl border border-border/70 bg-background/85 text-primary transition-transform duration-200 group-hover:-translate-y-0.5">
                  <component :is="item.icon" class="size-4" />
                </div>
                <div class="min-w-0 flex-1 space-y-1">
                  <div class="flex items-center justify-between gap-3">
                    <div class="text-sm font-medium text-foreground">{{ item.title }}</div>
                    <span class="surface-chip">直接可用</span>
                  </div>
                  <p class="text-sm leading-6 text-muted-foreground">{{ item.description }}</p>
                </div>
              </div>
            </div>
          </aside>
        </section>

        <section class="grid gap-6 lg:grid-cols-2">
          <article class="detail-card p-6">
            <div class="space-y-2 border-b border-border/70 pb-4">
              <div class="surface-label text-primary">更像这样的工作</div>
              <h2 class="text-2xl font-semibold text-foreground">不是一次性提问，而是要持续往前推</h2>
            </div>

            <div class="mt-4 grid gap-3">
              <div
                v-for="item in useCases"
                :key="item.title"
                class="list-card p-4"
              >
                <div class="text-base font-semibold text-foreground">{{ item.title }}</div>
                <p class="mt-1 text-sm leading-6 text-muted-foreground">{{ item.description }}</p>
              </div>
            </div>
          </article>

          <article class="detail-card p-6">
            <div class="space-y-2 border-b border-border/70 pb-4">
              <div class="surface-label text-primary">为什么界面这么收</div>
              <h2 class="text-2xl font-semibold text-foreground">重点是可控、可查、可继续，而不是堆一堆听起来很厉害的概念</h2>
            </div>

            <div class="mt-4 grid gap-3">
              <div
                v-for="item in principles"
                :key="item.title"
                class="list-card flex items-start gap-3 p-4"
              >
                <div class="flex size-10 shrink-0 items-center justify-center rounded-xl border border-border/70 bg-background/85 text-primary">
                  <component :is="item.icon" class="size-4" />
                </div>
                <div>
                  <div class="text-base font-semibold text-foreground">{{ item.title }}</div>
                  <p class="mt-1 text-sm leading-6 text-muted-foreground">{{ item.description }}</p>
                </div>
              </div>
            </div>
          </article>
        </section>
      </main>

      <footer class="border-t border-border/70 pt-6">
        <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div class="text-lg font-semibold text-foreground">先从一段对话开始，也完全够用。</div>
            <p class="mt-1 text-sm text-muted-foreground">
              后面要不要接知识库、工具和工作流，等真正需要的时候再补进去。
            </p>
          </div>
          <Button size="lg" class="h-12 px-6 text-base" @click="startUsing">
            进入工作台
            <ArrowRight class="size-4" />
          </Button>
        </div>
      </footer>
    </div>
  </div>
</template>
