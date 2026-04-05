<!--
  步骤类型 Schema 面板组件。
  展示所有步骤类型及其可配置参数的元数据定义，支持搜索过滤。
-->
<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { workflowApi } from '@/api/client'
import { logger } from '@/utils/logger'
import type { StepTypeSchema } from '@/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Search, Info, Zap, Wrench, Bot, GitBranch, Repeat, GitMerge, Workflow, Circle, Clock, ShieldCheck, Bell } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const schemas = ref<StepTypeSchema[]>([])
const loading = ref(false)
const searchQuery = ref('')

const stepTypeIcons: Record<string, any> = {
  skill: Zap,
  tool: Wrench,
  llm: Bot,
  condition: GitBranch,
  loop: Repeat,
  parallel: GitMerge,
  'sub-workflow': Workflow,
  noop: Circle,
  wait: Clock,
  approval: ShieldCheck,
  notify: Bell
}

const filteredSchemas = computed(() => {
  if (!searchQuery.value) return schemas.value
  const query = searchQuery.value.toLowerCase()
  return schemas.value.filter(s =>
    s.stepType.toLowerCase().includes(query) ||
    s.label.toLowerCase().includes(query) ||
    s.description.toLowerCase().includes(query) ||
    s.params.some(p => p.name.toLowerCase().includes(query))
  )
})

async function loadSchemas() {
  loading.value = true
  try {
    schemas.value = await workflowApi.getStepTypes()
  } catch (e) {
    logger.error('加载步骤类型失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.open) {
    loadSchemas()
  }
})

function close() {
  emit('update:open', false)
}
</script>

<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent class="max-w-4xl max-h-[85vh] overflow-auto">
      <DialogHeader>
        <DialogTitle>步骤类型参数 Schema</DialogTitle>
        <DialogDescription>
          查看所有步骤类型及其可配置参数的元数据定义，包括输入类型、选项列表、占位符和示例。
        </DialogDescription>
      </DialogHeader>

      <!-- 搜索框 -->
      <div class="relative">
        <Search class="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          v-model="searchQuery"
          placeholder="搜索步骤类型或参数..."
          class="pl-9"
        />
      </div>

      <!-- 加载状态 -->
      <div v-if="loading" class="py-8 text-center text-muted-foreground">
        加载中...
      </div>

      <!-- Schema 列表 -->
      <div v-else class="space-y-4">
        <div
          v-for="schema in filteredSchemas"
          :key="schema.stepType"
          class="rounded-lg border p-4"
        >
          <div class="flex items-center gap-2 mb-3">
            <component :is="stepTypeIcons[schema.stepType] || Circle" class="h-5 w-5" />
            <span class="font-medium">{{ schema.label }}</span>
            <Badge variant="outline">{{ schema.stepType }}</Badge>
          </div>
          <p class="text-sm text-muted-foreground mb-3">{{ schema.description }}</p>

          <!-- 参数列表 -->
          <div v-if="schema.params.length > 0" class="space-y-2">
            <div
              v-for="param in schema.params"
              :key="param.name"
              class="rounded border p-3 text-sm"
            >
              <div class="flex items-center gap-2 mb-1">
                <span class="font-mono font-medium">{{ param.name }}</span>
                <Badge v-if="param.required" variant="destructive" class="text-xs">必填</Badge>
                <Badge v-if="param.inputType" variant="secondary" class="text-xs">{{ param.inputType }}</Badge>
              </div>
              <div class="text-xs text-muted-foreground">
                <span>类型: {{ param.type }}</span>
                <span v-if="param.defaultValue !== undefined" class="ml-2">默认值: {{ param.defaultValue }}</span>
              </div>
              <p v-if="param.description" class="mt-1 text-xs">{{ param.description }}</p>
              <p v-if="param.placeholder" class="mt-1 text-xs text-muted-foreground">占位符: {{ param.placeholder }}</p>
              <p v-if="param.example" class="mt-1 text-xs text-muted-foreground">示例: {{ param.example }}</p>
              <!-- 选项列表 -->
              <div v-if="param.options && param.options.length > 0" class="mt-2 flex flex-wrap gap-1">
                <span class="text-xs text-muted-foreground">可选值:</span>
                <Badge v-for="opt in param.options" :key="opt.value" variant="outline" class="text-xs">
                  {{ opt.label }}
                </Badge>
              </div>
            </div>
          </div>
          <div v-else class="text-xs text-muted-foreground">
            该步骤类型无自定义参数
          </div>
        </div>
      </div>

      <div v-if="!loading && filteredSchemas.length === 0" class="py-8 text-center text-muted-foreground">
        未找到匹配的步骤类型
      </div>
    </DialogContent>
  </Dialog>
</template>
