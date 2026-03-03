<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { X, ArrowRight, ArrowLeft, Check } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const chatStore = useChatStore()

const currentStep = ref(1)
const totalSteps = 4

const steps = [
  {
    id: 1,
    title: '欢迎使用 LifePilot',
    description: 'LifePilot 是一个本地运行的个人 AI Agent 助手，帮助你更高效地管理知识、执行任务和自动化工作流。',
    content: [
      {
        icon: '💬',
        title: '对话',
        description: '与 AI 进行多轮对话，支持上下文管理和流式响应'
      },
      {
        icon: '📚',
        title: '知识库',
        description: '构建个人知识库，通过 RAG 技术增强 AI 回答的准确性'
      },
      {
        icon: '🤖',
        title: 'Agent',
        description: '创建智能 Agent，自动执行复杂任务和工作流'
      }
    ]
  },
  {
    id: 2,
    title: '创建你的第一个会话',
    description: '开始与 AI 对话，体验 LifePilot 的核心功能。',
    content: null
  },
  {
    id: 3,
    title: '创建知识库',
    description: '知识库可以帮助 AI 更好地理解你的上下文，提供更准确的回答。',
    content: null
  },
  {
    id: 4,
    title: '配置示例 Agent',
    description: 'Agent 可以自动执行任务，提高你的工作效率。',
    content: null
  }
]

const canGoNext = computed(() => {
  if (currentStep.value === 2) {
    // Step 2: 检查是否已创建会话
    return chatStore.sessions.length > 0
  }
  return true
})

function nextStep() {
  if (currentStep.value < totalSteps) {
    currentStep.value++
  } else {
    completeOnboarding()
  }
}

function prevStep() {
  if (currentStep.value > 1) {
    currentStep.value--
  }
}

function skipStep() {
  if (currentStep.value < totalSteps) {
    currentStep.value++
  } else {
    completeOnboarding()
  }
}

async function createFirstSession() {
  try {
    await chatStore.createSession()
    if (chatStore.sessions.length > 0) {
      const session = chatStore.sessions[0]
      router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
      nextStep()
    }
  } catch (e) {
    console.error('Failed to create session:', e)
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
  // 保存完成状态到 localStorage
  localStorage.setItem('lifepilot_onboarding_completed', 'true')
  emit('complete')
}

const emit = defineEmits<{
  complete: []
}>()
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm">
    <div class="relative w-full max-w-[672px] mx-4 bg-card border border-border rounded-lg shadow-lg max-h-[90vh] overflow-hidden flex flex-col">
      <!-- 关闭按钮 -->
      <button
        class="absolute top-4 right-4 p-2 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors z-10"
        @click="completeOnboarding"
      >
        <X :size="18" />
      </button>

      <!-- 内容区域 -->
      <div class="flex-1 overflow-y-auto p-8">
        <!-- 步骤指示器 -->
        <div class="flex items-center justify-center gap-2 mb-8">
          <div
            v-for="step in totalSteps"
            :key="step"
            class="flex items-center"
          >
            <div
              class="w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium transition-colors"
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
              class="w-12 h-0.5 mx-2 transition-colors"
              :class="step < currentStep ? 'bg-primary' : 'bg-muted'"
            />
          </div>
        </div>

        <!-- Step 1: 欢迎页 -->
        <div v-if="currentStep === 1" class="space-y-6">
          <div class="text-center">
            <h2 class="text-3xl font-bold text-foreground mb-3">
              {{ steps[0].title }}
            </h2>
            <p class="text-muted-foreground">
              {{ steps[0].description }}
            </p>
          </div>
          
          <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mt-8">
            <div
              v-for="item in steps[0].content"
              :key="item.title"
              class="p-6 rounded-lg border border-border bg-muted/30 text-center"
            >
              <div class="text-4xl mb-3">{{ item.icon }}</div>
              <h3 class="text-lg font-semibold text-foreground mb-2">
                {{ item.title }}
              </h3>
              <p class="text-sm text-muted-foreground">
                {{ item.description }}
              </p>
            </div>
          </div>
        </div>

        <!-- Step 2: 创建会话 -->
        <div v-if="currentStep === 2" class="space-y-6 text-center">
          <h2 class="text-3xl font-bold text-foreground mb-3">
            {{ steps[1].title }}
          </h2>
          <p class="text-muted-foreground mb-6">
            {{ steps[1].description }}
          </p>
          
          <div v-if="chatStore.sessions.length === 0" class="space-y-4">
            <button
              class="px-6 py-3 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors font-medium"
              @click="createFirstSession"
            >
              创建第一个会话
            </button>
            <p class="text-sm text-muted-foreground">
              或者稍后在对话页面创建
            </p>
          </div>
          
          <div v-else class="p-6 rounded-lg border border-border bg-muted/30">
            <Check :size="24" class="mx-auto mb-2 text-primary" />
            <p class="text-sm text-foreground">已创建会话</p>
          </div>
        </div>

        <!-- Step 3: 创建知识库 -->
        <div v-if="currentStep === 3" class="space-y-6 text-center">
          <h2 class="text-3xl font-bold text-foreground mb-3">
            {{ steps[2].title }}
          </h2>
          <p class="text-muted-foreground mb-6">
            {{ steps[2].description }}
          </p>
          
          <div class="space-y-4">
            <button
              class="px-6 py-3 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors font-medium"
              @click="goToKnowledgeBases"
            >
              前往知识库页面
            </button>
            <p class="text-sm text-muted-foreground">
              或者稍后创建
            </p>
          </div>
        </div>

        <!-- Step 4: 配置 Agent -->
        <div v-if="currentStep === 4" class="space-y-6 text-center">
          <h2 class="text-3xl font-bold text-foreground mb-3">
            {{ steps[3].title }}
          </h2>
          <p class="text-muted-foreground mb-6">
            {{ steps[3].description }}
          </p>
          
          <div class="space-y-4">
            <button
              class="px-6 py-3 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors font-medium"
              @click="goToAgents"
            >
              前往 Agent 页面
            </button>
            <p class="text-sm text-muted-foreground">
              或者稍后配置
            </p>
          </div>
        </div>
      </div>

      <!-- 底部操作栏 -->
      <div class="border-t border-border p-6 flex items-center justify-between">
        <button
          v-if="currentStep > 1"
          class="px-4 py-2 rounded-md border border-input bg-background hover:bg-accent transition-colors flex items-center gap-2"
          @click="prevStep"
        >
          <ArrowLeft :size="16" />
          上一步
        </button>
        <div v-else></div>
        
        <div class="flex items-center gap-2">
          <button
            v-if="currentStep < totalSteps"
            class="px-4 py-2 rounded-md text-muted-foreground hover:text-foreground transition-colors"
            @click="skipStep"
          >
            跳过
          </button>
          <button
            class="px-6 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!canGoNext"
            @click="nextStep"
          >
            {{ currentStep === totalSteps ? '完成' : '下一步' }}
            <ArrowRight v-if="currentStep < totalSteps" :size="16" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
