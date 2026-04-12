<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CheckCircle, Cpu, Loader2, Plus, RefreshCw, Unplug, XCircle } from 'lucide-vue-next'
import { modelServiceApi, type ModelService } from '@/api/client'
import { logger } from '@/utils/logger'
import ModelServiceManager from '@/components/settings/ModelServiceManager.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { useUiStore } from '@/stores/ui'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'

const uiStore = useUiStore()

const services = ref<ModelService[]>([])
const loading = ref(true)
const showManager = ref(false)
const managerMode = ref<'create' | 'edit' | null>(null)
const managerServiceId = ref<string | null>(null)

const kindLabels: Record<string, string> = {
  GENERATION: '生成服务',
  EMBEDDING: '向量服务',
  RERANK: '精排服务',
}

const sortedServices = computed(() => (
  [...services.value].sort((left, right) => {
    const priorityDelta = (left.priority ?? 0) - (right.priority ?? 0)
    if (priorityDelta !== 0) return priorityDelta
    return (left.displayName || left.id).localeCompare(right.displayName || right.id)
  })
))

function kindLabel(kind: string) {
  return kindLabels[kind] ?? kind
}

function vendorLabel(service: ModelService) {
  return service.vendorKey || service.type
}

async function loadData() {
  loading.value = true
  try {
    services.value = await modelServiceApi.listServices()
  } catch (error) {
    logger.error('加载模型服务失败:', error)
    uiStore.showToast('error', '加载模型服务失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  managerMode.value = 'create'
  managerServiceId.value = null
  showManager.value = true
}

function openEdit(serviceId: string) {
  managerMode.value = 'edit'
  managerServiceId.value = serviceId
  showManager.value = true
}

const togglingIds = ref(new Set<string>())
const testingIds = ref(new Set<string>())
const testResults = ref(new Map<string, boolean | null>())

async function handleToggleEnabled(service: ModelService) {
  togglingIds.value.add(service.id)
  try {
    const updated = await modelServiceApi.toggleEnabled(service.id)
    const index = services.value.findIndex(s => s.id === service.id)
    if (index !== -1) services.value[index] = updated
    uiStore.showToast('success', `${service.displayName || service.id} 已${updated.enabled ? '启用' : '禁用'}`)
  } catch (error) {
    logger.error('切换启用状态失败:', error)
    uiStore.showToast('error', '操作失败')
  } finally {
    togglingIds.value.delete(service.id)
  }
}

async function handleTestConnection(service: ModelService) {
  testingIds.value.add(service.id)
  testResults.value.delete(service.id)
  try {
    const result = await modelServiceApi.testConnection(service.id)
    testResults.value.set(service.id, result.healthy)
    uiStore.showToast(result.healthy ? 'success' : 'error',
      result.healthy ? `${service.displayName || service.id} 连接正常` : `连接失败：${result.error || '无法连接'}`)
  } catch (error: any) {
    testResults.value.set(service.id, false)
    uiStore.showToast('error', error?.message || '测试连接失败')
  } finally {
    testingIds.value.delete(service.id)
  }
  // 5秒后清除测试结果
  setTimeout(() => { testResults.value.delete(service.id) }, 5000)
}

async function handleManagerClose() {
  showManager.value = false
  managerMode.value = null
  managerServiceId.value = null
  await loadData()
}

onMounted(() => {
  void loadData()
})
</script>

<template>
  <div class="space-y-6">
    <StatePanel title="模型服务">
      <template #icon>
        <Cpu class="size-5" />
      </template>
      <template #actions>
        <Button variant="outline" class="gap-2" @click="loadData">
          <RefreshCw class="size-4" />
          刷新
        </Button>
        <Button class="gap-2" @click="openCreate">
          <Plus class="size-4" />
          新建模型服务
        </Button>
      </template>

      <div v-if="loading" class="space-y-3">
        <Skeleton class="h-24 w-full rounded-[calc(var(--radius)+10px)]" />
        <Skeleton class="h-24 w-full rounded-[calc(var(--radius)+10px)]" />
        <Skeleton class="h-24 w-full rounded-[calc(var(--radius)+10px)]" />
      </div>

      <div
        v-else-if="sortedServices.length === 0"
        class="rounded-[calc(var(--radius)+12px)] border border-dashed border-border/70 bg-muted/10 px-6 py-12 text-center"
      >
        <h3 class="text-base font-medium text-foreground">当前还没有模型服务</h3>
        <Button class="mt-5 gap-2" @click="openCreate">
          <Plus class="size-4" />
          创建第一个模型服务
        </Button>
      </div>

      <div v-else class="space-y-3">
        <div
          v-for="service in sortedServices"
          :key="service.id"
          class="cursor-pointer rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-4 transition-colors hover:border-primary/40 hover:bg-muted/25"
          @click="openEdit(service.id)"
        >
          <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="font-medium text-foreground">
                  {{ service.displayName || service.id }}
                </span>
                <Badge variant="outline">{{ kindLabel(service.kind) }}</Badge>
                <Badge variant="outline">{{ vendorLabel(service) }}</Badge>
              </div>
              <p class="mt-1 text-sm text-muted-foreground">
                {{ service.modelName }}
              </p>
            </div>

            <div class="flex items-center gap-sm" @click.stop>
              <!-- 测试连接 -->
              <Button
                variant="ghost"
                size="sm"
                :disabled="testingIds.has(service.id) || !service.enabled"
                :title="service.enabled ? '测试连接' : '请先启用服务'"
                @click="handleTestConnection(service)"
              >
                <Loader2 v-if="testingIds.has(service.id)" class="size-4 animate-spin" />
                <CheckCircle v-else-if="testResults.get(service.id) === true" class="size-4 text-emerald-500" />
                <XCircle v-else-if="testResults.get(service.id) === false" class="size-4 text-destructive" />
                <Unplug v-else class="size-4" />
              </Button>

              <!-- 启用/禁用开关 -->
              <Switch
                :model-value="service.enabled"
                :disabled="togglingIds.has(service.id)"
                @update:model-value="handleToggleEnabled(service)"
              />
            </div>
          </div>
        </div>
      </div>
    </StatePanel>

    <ModelServiceManager
      v-if="showManager"
      :initial-mode="managerMode || undefined"
      :initial-service-id="managerServiceId"
      @close="handleManagerClose"
    />
  </div>
</template>
