<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  BookOpen,
  Check,
  ChevronLeft,
  Key,
  Loader2,
  MessageSquare,
  Server,
  Sparkles,
  Store,
  Zap,
} from 'lucide-vue-next'
import type { CreateModelServiceRequest, ModelServiceTemplate, ModelServiceTemplateModel } from '@/api/client'
import { modelServiceApi } from '@/api/client'
import {
  availableVendorsForKind,
  buildEmptyModelServiceRequest,
  buildSuggestedServiceId,
  defaultModelForKind,
  modelOptionsForKind,
} from '@/components/settings/modelServiceCatalog'

const router = useRouter()

// === 页面控制 ===
type Page = 'welcome' | 'provider' | 'configure' | 'ready'
const page = ref<Page>('welcome')
const skippedSetup = ref(false)

// 进度点：welcome=0, provider/configure=1, ready=2
const dotIndex = computed(() =>
  page.value === 'welcome' ? 0 : page.value === 'ready' ? 2 : 1,
)

// === 模型配置 ===
const errorMsg = ref('')
const templates = ref<ModelServiceTemplate[]>([])
const selectedVendor = ref<ModelServiceTemplate | null>(null)
const selectedModel = ref<ModelServiceTemplateModel | null>(null)
const apiKey = ref('')
const testResult = ref<'idle' | 'testing' | 'success' | 'fail'>('idle')

const vendors = computed(() => availableVendorsForKind(templates.value, 'GENERATION'))
const models = computed(() =>
  selectedVendor.value ? modelOptionsForKind(selectedVendor.value, 'GENERATION') : [],
)
const isLocalProvider = computed(() => selectedVendor.value?.vendorKey === 'ollama')
const canCreate = computed(() => {
  if (!selectedVendor.value || !selectedModel.value) return false
  if (isLocalProvider.value) return true
  return apiKey.value.trim().length > 0
})

onMounted(async () => {
  try {
    templates.value = await modelServiceApi.listTemplates()
  } catch (e) {
    errorMsg.value = `加载模板失败: ${e}`
  }
})

function selectVendor(vendor: ModelServiceTemplate) {
  selectedVendor.value = vendor
  selectedModel.value = defaultModelForKind(vendor, 'GENERATION') ?? null
  apiKey.value = ''
  testResult.value = 'idle'
  errorMsg.value = ''
}

async function createService() {
  if (!selectedVendor.value || !selectedModel.value) return

  testResult.value = 'testing'
  errorMsg.value = ''

  const vendor = selectedVendor.value
  const model = selectedModel.value

  const req: CreateModelServiceRequest = {
    ...buildEmptyModelServiceRequest(),
    id: buildSuggestedServiceId('GENERATION', vendor.vendorKey, model.value),
    kind: 'GENERATION',
    type: vendor.providerType,
    vendorKey: vendor.vendorKey,
    apiUrl: vendor.defaultApiUrl,
    apiKey: isLocalProvider.value ? undefined : apiKey.value.trim(),
    modelName: model.value,
    displayName: `${vendor.displayName} ${model.label}`,
    timeoutSeconds: vendor.defaultTimeoutSeconds,
    capabilities: model.capabilities,
    scenes: model.scenes,
    supportsStreaming: model.supportsStreaming,
    maxContextWindow: model.maxContextWindow,
    enabled: true,
  }

  try {
    await modelServiceApi.createService(req)
    testResult.value = 'success'
    page.value = 'ready'
  } catch (e: unknown) {
    testResult.value = 'fail'
    errorMsg.value = `创建失败: ${e instanceof Error ? e.message : e}`
  }
}

function skipSetup() {
  skippedSetup.value = true
  page.value = 'ready'
}

function finish(path: string) {
  // 标记引导完成，避免进入主界面后 OnboardingDialog 再次弹出
  localStorage.setItem('zhiwei_onboarding_completed', 'true')
  router.replace(path)
}
</script>

<template>
  <div class="flex h-screen w-screen flex-col items-center justify-center bg-background">
    <!-- 进度指示条 -->
    <div class="absolute top-xl flex gap-sm">
      <div
        v-for="i in 3"
        :key="i"
        class="h-1.5 w-8 rounded-full transition-colors duration-300"
        :class="i - 1 <= dotIndex ? 'bg-primary' : 'bg-muted-foreground/15'"
      />
    </div>

    <Transition name="slide" mode="out-in">
      <!-- ==================== Welcome ==================== -->
      <div v-if="page === 'welcome'" key="welcome" class="w-full max-w-[672px] px-xl text-center">
        <div class="text-5xl font-bold text-foreground">知微</div>
        <p class="mt-sm text-lg text-muted-foreground">见微知著，谋定后动</p>
        <p class="mx-auto mt-md max-w-[448px] text-sm leading-relaxed text-muted-foreground">
          自托管的 AI Agent 系统 —— 多模型接入、知识库增强、工作流自动化，数据完全由你掌控。
        </p>

        <!-- 核心能力 -->
        <div class="mt-2xl grid grid-cols-3 gap-md">
          <div class="rounded-xl border border-border/60 p-lg text-left">
            <MessageSquare class="mb-sm h-6 w-6 text-primary" />
            <div class="text-sm font-medium text-foreground">智能对话</div>
            <p class="mt-xs text-xs leading-relaxed text-muted-foreground">
              多模型切换、工具调用、流式输出，像真正的助手一样协作
            </p>
          </div>
          <div class="rounded-xl border border-border/60 p-lg text-left">
            <BookOpen class="mb-sm h-6 w-6 text-primary" />
            <div class="text-sm font-medium text-foreground">知识增强</div>
            <p class="mt-xs text-xs leading-relaxed text-muted-foreground">
              导入文档构建专属知识库，让 AI 真正理解你的业务领域
            </p>
          </div>
          <div class="rounded-xl border border-border/60 p-lg text-left">
            <Zap class="mb-sm h-6 w-6 text-primary" />
            <div class="text-sm font-medium text-foreground">流程自动化</div>
            <p class="mt-xs text-xs leading-relaxed text-muted-foreground">
              可视化编排工作流，定时执行，让 Agent 自主完成复杂任务
            </p>
          </div>
        </div>

        <button
          class="mt-2xl rounded-lg bg-primary px-2xl py-sm text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          @click="page = 'provider'"
        >
          开始配置
        </button>
      </div>

      <!-- ==================== Select Provider ==================== -->
      <div v-else-if="page === 'provider'" key="provider" class="w-full max-w-[512px] px-xl">
        <h2 class="text-xl font-semibold text-foreground">接入 AI 模型</h2>
        <p class="mt-xs text-sm text-muted-foreground">
          知微需要至少一个 AI 模型才能工作，选择你常用的服务商
        </p>

        <div class="mt-lg grid grid-cols-2 gap-sm">
          <button
            v-for="vendor in vendors"
            :key="vendor.vendorKey"
            class="flex flex-col items-start rounded-lg border p-md text-left transition-colors hover:bg-accent"
            :class="selectedVendor?.vendorKey === vendor.vendorKey
              ? 'border-primary bg-primary/5' : 'border-border'"
            @click="selectVendor(vendor)"
          >
            <span class="text-sm font-medium text-foreground">{{ vendor.displayName }}</span>
            <span class="mt-xs text-xs text-muted-foreground">{{ vendor.description }}</span>
          </button>
        </div>

        <button
          class="mt-lg w-full rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          :disabled="!selectedVendor"
          @click="page = 'configure'"
        >
          下一步
        </button>

        <button
          class="mt-md w-full text-xs text-muted-foreground/60 transition-colors hover:text-muted-foreground"
          @click="skipSetup"
        >
          跳过，稍后在设置中配置
        </button>
      </div>

      <!-- ==================== Configure Provider ==================== -->
      <div v-else-if="page === 'configure'" key="configure" class="w-full max-w-[512px] px-xl">
        <button
          class="mb-lg flex items-center gap-xs text-sm text-muted-foreground transition-colors hover:text-foreground"
          @click="page = 'provider'"
        >
          <ChevronLeft class="h-4 w-4" />
          返回选择
        </button>

        <h2 class="text-xl font-semibold text-foreground">
          配置 {{ selectedVendor?.displayName }}
        </h2>
        <p class="mt-xs text-sm text-muted-foreground">
          {{ isLocalProvider ? '确认本地服务地址和模型' : '填入 API Key 并选择模型' }}
        </p>

        <div class="mt-lg flex flex-col gap-md">
          <!-- API Key -->
          <div v-if="!isLocalProvider">
            <label class="mb-xs flex items-center gap-xs text-sm font-medium text-foreground">
              <Key class="h-4 w-4" /> API Key
            </label>
            <input
              v-model="apiKey"
              type="password"
              :placeholder="`输入 ${selectedVendor?.displayName} API Key`"
              class="w-full rounded-lg border border-border bg-background px-md py-sm text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none"
            />
          </div>

          <!-- API URL -->
          <div>
            <label class="mb-xs flex items-center gap-xs text-sm font-medium text-foreground">
              <Server class="h-4 w-4" /> API 地址
            </label>
            <input
              :value="selectedVendor?.defaultApiUrl"
              disabled
              class="w-full rounded-lg border border-border bg-muted px-md py-sm text-sm text-muted-foreground"
            />
          </div>

          <!-- Model -->
          <div>
            <label class="mb-xs block text-sm font-medium text-foreground">模型</label>
            <div class="flex flex-wrap gap-xs">
              <button
                v-for="model in models"
                :key="model.value"
                class="rounded-md border px-sm py-xs text-xs transition-colors"
                :class="selectedModel?.value === model.value
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border text-muted-foreground hover:border-primary/50'"
                @click="selectedModel = model"
              >
                {{ model.label }}
                <span v-if="model.recommended" class="ml-xs text-primary">★</span>
              </button>
            </div>
          </div>
        </div>

        <p v-if="errorMsg" class="mt-md text-sm text-destructive">{{ errorMsg }}</p>

        <button
          class="mt-lg flex w-full items-center justify-center gap-xs rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          :disabled="!canCreate || testResult === 'testing'"
          @click="createService"
        >
          <Loader2 v-if="testResult === 'testing'" class="h-4 w-4 animate-spin" />
          {{ testResult === 'testing' ? '创建中...' : '创建并继续' }}
        </button>
      </div>

      <!-- ==================== Ready ==================== -->
      <div v-else key="ready" class="w-full max-w-[672px] px-xl text-center">
        <div class="flex justify-center">
          <div class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
            <Check v-if="!skippedSetup" class="h-8 w-8 text-primary" />
            <Sparkles v-else class="h-8 w-8 text-primary" />
          </div>
        </div>

        <h2 class="mt-lg text-2xl font-semibold text-foreground">
          {{ skippedSetup ? '欢迎使用知微' : '一切就绪' }}
        </h2>
        <p class="mt-sm text-sm text-muted-foreground">
          {{ skippedSetup
            ? '你可以随时在设置中配置 AI 模型'
            : `${selectedVendor?.displayName} ${selectedModel?.label} 已就绪，开始探索吧`
          }}
        </p>

        <!-- 行动卡片 -->
        <div class="mt-2xl grid grid-cols-3 gap-md">
          <button
            class="flex flex-col items-center gap-sm rounded-xl border border-primary bg-primary/5 p-lg transition-colors hover:bg-primary/10"
            @click="finish('/conversations')"
          >
            <MessageSquare class="h-6 w-6 text-primary" />
            <span class="text-sm font-medium text-foreground">开始对话</span>
          </button>
          <button
            class="flex flex-col items-center gap-sm rounded-xl border border-border p-lg transition-colors hover:bg-accent"
            @click="finish('/knowledge-bases')"
          >
            <BookOpen class="h-6 w-6 text-muted-foreground" />
            <span class="text-sm font-medium text-foreground">创建知识库</span>
          </button>
          <button
            class="flex flex-col items-center gap-sm rounded-xl border border-border p-lg transition-colors hover:bg-accent"
            @click="finish('/marketplace')"
          >
            <Store class="h-6 w-6 text-muted-foreground" />
            <span class="text-sm font-medium text-foreground">浏览市场</span>
          </button>
        </div>

        <p v-if="skippedSetup" class="mt-lg text-xs text-muted-foreground">
          提示：使用对话功能前需要先
          <button
            class="text-primary underline transition-colors hover:text-primary/80"
            @click="finish('/settings/models')"
          >
            配置至少一个 AI 模型
          </button>
        </p>

        <p v-if="!skippedSetup" class="mt-lg text-xs text-muted-foreground">
          额外配置
          <button
            class="text-primary underline transition-colors hover:text-primary/80"
            @click="finish('/settings/models')"
          >
            Embedding 模型
          </button>
          可启用记忆模块的语义检索，提升知识库与记忆召回质量
        </p>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: all 0.25s ease;
}

.slide-enter-from {
  opacity: 0;
  transform: translateX(24px);
}

.slide-leave-to {
  opacity: 0;
  transform: translateX(-24px);
}
</style>
