<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Loader2, Play } from 'lucide-vue-next'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Checkbox } from '@/components/ui/checkbox'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { useEvalStore } from '@/stores/eval'
import type { EvalRunRequest } from '@/types'

const open = defineModel<boolean>('open', { default: false })

const store = useEvalStore()
const router = useRouter()

// 运行模式
type RunMode = 'all' | 'byTag' | 'byScenario'
const runMode = ref<RunMode>('all')
const selectedTag = ref<string>('')
const selectedScenarioIds = ref<string[]>([])
const running = ref(false)

// 重置表单状态
function resetForm() {
  runMode.value = 'all'
  selectedTag.value = ''
  selectedScenarioIds.value = []
}

// 对话框打开/关闭时重置
watch(open, (val) => {
  if (val) resetForm()
})

// 确认按钮是否可用
const canConfirm = computed(() => {
  if (running.value) return false
  if (runMode.value === 'byTag') return !!selectedTag.value
  if (runMode.value === 'byScenario') return selectedScenarioIds.value.length > 0
  return true
})

// 构建请求参数
function buildRequest(): EvalRunRequest {
  if (runMode.value === 'byTag') return { tag: selectedTag.value }
  if (runMode.value === 'byScenario') return { scenarioIds: selectedScenarioIds.value }
  return {}
}

// 切换场景选中状态
function toggleScenario(scenarioId: string) {
  const idx = selectedScenarioIds.value.indexOf(scenarioId)
  if (idx >= 0) {
    selectedScenarioIds.value.splice(idx, 1)
  } else {
    selectedScenarioIds.value.push(scenarioId)
  }
}

// 确认运行
async function handleConfirm() {
  running.value = true
  try {
    const report = await store.triggerRun(buildRequest())
    if (report) {
      open.value = false
      router.push({ name: 'evalRunDetail', params: { evalRunId: report.evalRunId } })
    }
  } finally {
    running.value = false
  }
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="sm:max-w-[32rem]">
      <DialogHeader>
        <DialogTitle>运行评估</DialogTitle>
        <DialogDescription>选择运行模式并确认开始评估</DialogDescription>
      </DialogHeader>

      <div class="space-y-5 py-2">
        <!-- 运行模式选择 -->
        <div class="space-y-2">
          <Label class="text-sm font-medium">运行模式</Label>
          <ToggleGroup
            v-model="runMode"
            type="single"
            variant="outline"
            class="justify-start"
          >
            <ToggleGroupItem value="all" class="text-xs">全部场景</ToggleGroupItem>
            <ToggleGroupItem value="byTag" class="text-xs">按标签</ToggleGroupItem>
            <ToggleGroupItem value="byScenario" class="text-xs">选择场景</ToggleGroupItem>
          </ToggleGroup>
        </div>

        <!-- 按标签模式：标签选择器 -->
        <div v-if="runMode === 'byTag'" class="space-y-2">
          <Label class="text-sm font-medium">选择标签</Label>
          <Select v-model="selectedTag">
            <SelectTrigger class="w-full">
              <SelectValue placeholder="请选择标签" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem
                v-for="tag in store.allTags"
                :key="tag"
                :value="tag"
              >
                {{ tag }}
              </SelectItem>
            </SelectContent>
          </Select>
          <p v-if="store.allTags.length === 0" class="text-xs text-muted-foreground">
            暂无可用标签
          </p>
        </div>

        <!-- 选择场景模式：场景 Checkbox 列表 -->
        <div v-if="runMode === 'byScenario'" class="space-y-2">
          <Label class="text-sm font-medium">
            选择场景
            <span v-if="selectedScenarioIds.length > 0" class="text-muted-foreground font-normal">
              （已选 {{ selectedScenarioIds.length }} 个）
            </span>
          </Label>
          <div
            v-if="store.scenarios.length > 0"
            class="max-h-56 space-y-1 overflow-y-auto rounded-md border p-2"
          >
            <label
              v-for="scenario in store.scenarios"
              :key="scenario.id"
              class="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent/50"
            >
              <Checkbox
                :model-value="selectedScenarioIds.includes(scenario.id)"
                @update:model-value="toggleScenario(scenario.id)"
              />
              <span class="truncate">{{ scenario.name }}</span>
            </label>
          </div>
          <p v-else class="text-xs text-muted-foreground">暂无可用场景</p>
        </div>
      </div>

      <DialogFooter>
        <Button variant="outline" :disabled="running" @click="open = false">
          取消
        </Button>
        <Button :disabled="!canConfirm" @click="handleConfirm">
          <Loader2 v-if="running" class="mr-1.5 size-4 animate-spin" />
          <Play v-else class="mr-1.5 size-4" />
          {{ running ? '运行中...' : '开始运行' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
