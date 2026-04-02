<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, ChevronRight, Key, Loader2, Server } from 'lucide-vue-next'
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

// 步骤控制
const currentStep = ref(1)
const loading = ref(false)
const errorMsg = ref('')

// 数据
const templates = ref<ModelServiceTemplate[]>([])
const selectedVendor = ref<ModelServiceTemplate | null>(null)
const selectedModel = ref<ModelServiceTemplateModel | null>(null)
const apiKey = ref('')
const testResult = ref<'idle' | 'testing' | 'success' | 'fail'>('idle')

// 仅展示生成类的厂商
const vendors = computed(() => availableVendorsForKind(templates.value, 'GENERATION'))
const models = computed(() =>
  selectedVendor.value ? modelOptionsForKind(selectedVendor.value, 'GENERATION') : [],
)

// 是否为本地模型（Ollama 无需 API Key）
const isLocalProvider = computed(() => selectedVendor.value?.vendorKey === 'ollama')

// 表单校验
const canProceedStep2 = computed(() => {
  if (!selectedVendor.value || !selectedModel.value) return false
  if (isLocalProvider.value) return true
  return apiKey.value.trim().length > 0
})

onMounted(async () => {
  try {
    templates.value = await modelServiceApi.listTemplates()
  } catch (e) {
    errorMsg.value = `加载模型模板失败: ${e}`
  }
})

function selectVendor(vendor: ModelServiceTemplate) {
  selectedVendor.value = vendor
  selectedModel.value = defaultModelForKind(vendor, 'GENERATION') ?? null
  apiKey.value = ''
  testResult.value = 'idle'
}

async function testAndCreate() {
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
    currentStep.value = 3
  } catch (e: unknown) {
    testResult.value = 'fail'
    errorMsg.value = `创建失败: ${e instanceof Error ? e.message : e}`
  }
}

function finish() {
  router.replace('/conversations')
}
</script>

<template>
  <div class="flex h-screen w-screen items-center justify-center bg-background">
    <div class="w-full max-w-lg rounded-xl border border-border bg-card p-2xl shadow-lg">
      <!-- 步骤指示器 -->
      <div class="mb-xl flex items-center justify-center gap-sm">
        <div
          v-for="step in 3"
          :key="step"
          class="flex items-center gap-sm"
        >
          <div
            class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium transition-colors"
            :class="step <= currentStep
              ? 'bg-primary text-primary-foreground'
              : 'bg-muted text-muted-foreground'"
          >
            <Check v-if="step < currentStep" class="h-4 w-4" />
            <span v-else>{{ step }}</span>
          </div>
          <ChevronRight v-if="step < 3" class="h-4 w-4 text-muted-foreground" />
        </div>
      </div>

      <!-- Step 1: 选择服务商 -->
      <div v-if="currentStep === 1">
        <h2 class="mb-sm text-xl font-semibold text-foreground">选择 AI 模型服务</h2>
        <p class="mb-lg text-sm text-muted-foreground">知微需要至少配置一个 AI 模型才能使用</p>

        <div class="grid grid-cols-2 gap-sm">
          <button
            v-for="vendor in vendors"
            :key="vendor.vendorKey"
            class="flex flex-col items-start rounded-lg border p-md text-left transition-colors hover:bg-accent"
            :class="selectedVendor?.vendorKey === vendor.vendorKey
              ? 'border-primary bg-primary/5'
              : 'border-border'"
            @click="selectVendor(vendor)"
          >
            <span class="text-sm font-medium text-foreground">{{ vendor.displayName }}</span>
            <span class="mt-xs text-xs text-muted-foreground">{{ vendor.description }}</span>
          </button>
        </div>

        <button
          class="mt-lg w-full rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          :disabled="!selectedVendor"
          @click="currentStep = 2"
        >
          下一步
        </button>
      </div>

      <!-- Step 2: 配置 API Key + 选择模型 -->
      <div v-if="currentStep === 2 && selectedVendor">
        <h2 class="mb-sm text-xl font-semibold text-foreground">配置 {{ selectedVendor.displayName }}</h2>
        <p class="mb-lg text-sm text-muted-foreground">
          {{ isLocalProvider ? '确认本地服务地址和模型' : '填入 API Key 并选择模型' }}
        </p>

        <!-- API Key -->
        <div v-if="!isLocalProvider" class="mb-md">
          <label class="mb-xs block text-sm font-medium text-foreground">
            <Key class="mr-xs inline h-4 w-4" />
            API Key
          </label>
          <input
            v-model="apiKey"
            type="password"
            :placeholder="`输入 ${selectedVendor.displayName} API Key`"
            class="w-full rounded-lg border border-border bg-background px-md py-sm text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none"
          />
        </div>

        <!-- API URL -->
        <div class="mb-md">
          <label class="mb-xs block text-sm font-medium text-foreground">
            <Server class="mr-xs inline h-4 w-4" />
            API 地址
          </label>
          <input
            :value="selectedVendor.defaultApiUrl"
            disabled
            class="w-full rounded-lg border border-border bg-muted px-md py-sm text-sm text-muted-foreground"
          />
        </div>

        <!-- 模型选择 -->
        <div class="mb-lg">
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

        <!-- 错误信息 -->
        <p v-if="errorMsg" class="mb-sm text-sm text-destructive">{{ errorMsg }}</p>

        <!-- 操作按钮 -->
        <div class="flex gap-sm">
          <button
            class="flex-1 rounded-lg border border-border px-lg py-sm text-sm text-foreground hover:bg-accent"
            @click="currentStep = 1"
          >
            上一步
          </button>
          <button
            class="flex flex-1 items-center justify-center gap-xs rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            :disabled="!canProceedStep2 || testResult === 'testing'"
            @click="testAndCreate"
          >
            <Loader2 v-if="testResult === 'testing'" class="h-4 w-4 animate-spin" />
            {{ testResult === 'testing' ? '创建中...' : '创建服务' }}
          </button>
        </div>
      </div>

      <!-- Step 3: 完成 -->
      <div v-if="currentStep === 3" class="text-center">
        <div class="mb-md flex justify-center">
          <div class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
            <Check class="h-8 w-8 text-primary" />
          </div>
        </div>
        <h2 class="mb-sm text-xl font-semibold text-foreground">配置完成</h2>
        <p class="mb-lg text-sm text-muted-foreground">
          {{ selectedVendor?.displayName }} {{ selectedModel?.label }} 已就绪，开始使用知微吧
        </p>
        <button
          class="w-full rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground hover:bg-primary/90"
          @click="finish"
        >
          开始使用
        </button>
      </div>
    </div>
  </div>
</template>
