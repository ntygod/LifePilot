<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, BookOpen, Bot, Check, MessageCircle, X } from 'lucide-vue-next'
import type { Component } from 'vue'
import { useChatStore } from '@/stores/chat'
import { logger } from '@/utils/logger'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'

const emit = defineEmits<{
  complete: []
}>()

const router = useRouter()
const chatStore = useChatStore()

const currentStep = ref(1)
const totalSteps = 4

const steps: Array<{
  id: number
  title: string
  description: string
  content?: Array<{ icon: Component; title: string; description: string }>
}> = [
  {
    id: 1,
    title: '先看一圈这套工作台',
    description: '先从对话开始就够了，后面再把资料、知识库和流程慢慢接进来，不需要一次配齐。',
    content: [
      {
        icon: MessageCircle,
        title: '从对话起步',
        description: '把问题、材料或排障记录先放进来，沿着同一段上下文继续往下做。',
      },
      {
        icon: BookOpen,
        title: '把资料接进来',
        description: '常用文档接进来以后，后面的检索、摘录和追问都会更顺手。',
      },
      {
        icon: Bot,
        title: '再交给流程承接',
        description: '等规则稳定下来，再把重复步骤交给智能体和流程长期处理。',
      },
    ],
  },
  {
    id: 2,
    title: '创建你的第一个会话',
    description: '先建一段会话，把手头的问题或资料放进来，后面就能沿着这条线继续。',
  },
  {
    id: 3,
    title: '创建知识库',
    description: '把常查的资料接进来，后面的检索、摘要和追问都会更贴近你的工作内容。',
  },
  {
    id: 4,
    title: '配置一个示例智能体',
    description: '等对话和资料都跑顺了，再把常做的动作交给它长期承接。',
  },
]

const canGoNext = computed(() => {
  if (currentStep.value === 2) {
    return chatStore.sessions.length > 0
  }

  return true
})

function nextStep() {
  if (currentStep.value < totalSteps) {
    currentStep.value += 1
    return
  }

  completeOnboarding()
}

function prevStep() {
  if (currentStep.value > 1) {
    currentStep.value -= 1
  }
}

function skipStep() {
  if (currentStep.value < totalSteps) {
    currentStep.value += 1
    return
  }

  completeOnboarding()
}

async function createFirstSession() {
  try {
    await chatStore.createSession()

    if (chatStore.sessions.length > 0) {
      const session = chatStore.sessions[0]
      router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
      nextStep()
    }
  } catch (error) {
    logger.error('Failed to create session:', error)
  }
}

function goToKnowledgeBases() {
  router.push({ name: 'knowledgeBases' })
  nextStep()
}

function goToAgents() {
  router.push({ name: 'agents' })
  completeOnboarding()
}

function completeOnboarding() {
  localStorage.setItem('zhiwei_onboarding_completed', 'true')
  emit('complete')
}
</script>

<template>
  <Dialog :open="true">
    <DialogContent
      :show-close-button="false"
      class="w-[min(720px,calc(100vw-2rem))] overflow-hidden border-border/70 p-0 shadow-[0_40px_120px_-48px_hsl(var(--shadow-color)/0.9)]"
      @pointer-down-outside="(event) => event.preventDefault()"
      @escape-key-down="(event) => event.preventDefault()"
    >
      <div class="flex max-h-[90vh] min-w-0 flex-col">
        <button
          type="button"
          class="absolute top-4 right-4 z-10 rounded-full border border-border/70 bg-background/80 p-2 text-muted-foreground transition-colors hover:text-foreground"
          @click="completeOnboarding"
        >
          <X :size="18" />
        </button>

        <div class="flex-1 overflow-y-auto px-8 py-8">
          <div class="mb-8 flex items-center justify-center gap-2">
            <div v-for="step in totalSteps" :key="step" class="flex items-center">
              <div
                class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium transition-colors"
                :class="step < currentStep
                  ? 'bg-primary text-primary-foreground'
                  : step === currentStep
                    ? 'bg-primary text-primary-foreground ring-2 ring-primary ring-offset-2'
                    : 'bg-muted text-muted-foreground'"
              >
                <Check v-if="step < currentStep" :size="16" />
                <span v-else>{{ step }}</span>
              </div>
              <div
                v-if="step < totalSteps"
                class="mx-2 h-0.5 w-12 transition-colors"
                :class="step < currentStep ? 'bg-primary' : 'bg-muted'"
              />
            </div>
          </div>

          <div v-if="currentStep === 1" class="space-y-8">
            <div class="mx-auto flex w-full max-w-[34rem] flex-col items-center space-y-4 text-center">
              <div class="surface-label">欢迎</div>
              <h2 class="max-w-[12ch] text-3xl font-bold leading-tight text-foreground sm:text-[2.2rem]">
                {{ steps[0].title }}
              </h2>
              <p class="w-full text-sm leading-7 text-muted-foreground sm:text-[0.96rem]">
                {{ steps[0].description }}
              </p>
            </div>

            <div class="mt-8 grid gap-4 md:grid-cols-3">
              <article
                v-for="item in steps[0].content"
                :key="item.title"
                class="list-card flex h-full flex-col items-start p-5 text-left sm:p-6"
              >
                <div class="mb-4 flex size-12 items-center justify-center rounded-2xl border border-border/70 bg-background/82 shadow-sm">
                  <component :is="item.icon" class="size-5 text-muted-foreground" />
                </div>
                <h3 class="text-base font-semibold text-foreground sm:text-lg">
                  {{ item.title }}
                </h3>
                <p class="mt-2 text-sm leading-6 text-muted-foreground">
                  {{ item.description }}
                </p>
              </article>
            </div>
          </div>

          <div v-if="currentStep === 2" class="mx-auto w-full max-w-[32rem] space-y-6 text-center">
            <div class="space-y-3">
              <div class="surface-label">第 1 步</div>
              <h2 class="text-3xl font-bold text-foreground">
                {{ steps[1].title }}
              </h2>
              <p class="text-sm leading-7 text-muted-foreground">
                {{ steps[1].description }}
              </p>
            </div>

            <div v-if="chatStore.sessions.length === 0" class="space-y-4">
              <Button size="lg" @click="createFirstSession">
                创建第一个会话
              </Button>
              <p class="text-sm text-muted-foreground">或者稍后在对话页创建</p>
            </div>

            <div v-else class="detail-card p-6">
              <Check :size="24" class="mx-auto mb-2 text-primary" />
              <p class="text-sm text-foreground">已创建会话</p>
            </div>
          </div>

          <div v-if="currentStep === 3" class="mx-auto w-full max-w-[32rem] space-y-6 text-center">
            <div class="space-y-3">
              <div class="surface-label">第 2 步</div>
              <h2 class="text-3xl font-bold text-foreground">
                {{ steps[2].title }}
              </h2>
              <p class="text-sm leading-7 text-muted-foreground">
                {{ steps[2].description }}
              </p>
            </div>

            <div class="space-y-4">
              <Button size="lg" @click="goToKnowledgeBases">
                去知识库看看
              </Button>
              <p class="text-sm text-muted-foreground">或者稍后再创建</p>
            </div>
          </div>

          <div v-if="currentStep === 4" class="mx-auto w-full max-w-[32rem] space-y-6 text-center">
            <div class="space-y-3">
              <div class="surface-label">第 3 步</div>
              <h2 class="text-3xl font-bold text-foreground">
                {{ steps[3].title }}
              </h2>
              <p class="text-sm leading-7 text-muted-foreground">
                {{ steps[3].description }}
              </p>
            </div>

            <div class="space-y-4">
              <Button size="lg" @click="goToAgents">
                去看示例智能体
              </Button>
              <p class="text-sm text-muted-foreground">或者稍后再配置</p>
            </div>
          </div>
        </div>

        <div class="flex items-center justify-between border-t border-border/70 px-6 py-5">
          <Button v-if="currentStep > 1" variant="outline" @click="prevStep">
            <ArrowLeft :size="16" />
            上一步
          </Button>
          <div v-else />

          <div class="flex items-center gap-2">
            <Button v-if="currentStep < totalSteps" variant="ghost" @click="skipStep">
              跳过
            </Button>
            <Button :disabled="!canGoNext" @click="nextStep">
              {{ currentStep === totalSteps ? '完成' : '下一步' }}
              <ArrowRight v-if="currentStep < totalSteps" :size="16" />
            </Button>
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
