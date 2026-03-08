<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import type { ExtensionPackage } from '@/types'
import { marketplaceApi } from '@/api/marketplace'
import MarketplaceFilters from '@/components/marketplace/MarketplaceFilters.vue'
import SkillCard from '@/components/marketplace/SkillCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import Pagination from '@/components/common/Pagination.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { ServerOff } from 'lucide-vue-next'

// 搜索与筛选状态
const search = ref('')
const tag = ref('')
const extensionType = ref('')
const page = ref(0)
const size = 20

// 数据状态
const skills = ref<ExtensionPackage[]>([])
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)
const error = ref('')
const refreshing = ref(false)

// 服务不可用状态（后端 API 返回 404/503 等）
const serviceUnavailable = ref(false)

// 更新信息
const updates = ref<ExtensionPackage[]>([])
const updateIds = computed(() => new Set(updates.value.map(u => u.id)))

// 从所有 Skill 中提取可用标签（去重）
const availableTags = computed(() => {
  const tagSet = new Set<string>()
  skills.value.forEach(s => s.tags?.forEach(t => tagSet.add(t)))
  return Array.from(tagSet).sort()
})

/** 加载扩展列表 */
async function loadSkills() {
  loading.value = true
  error.value = ''
  try {
    const result = await marketplaceApi.getSkills({
      type: extensionType.value || undefined,
      search: search.value || undefined,
      tag: tag.value || undefined,
      page: page.value,
      size
    })
    skills.value = result.content
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch (e: any) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

/** 加载可用更新 */
async function loadUpdates() {
  try {
    updates.value = await marketplaceApi.getUpdates()
  } catch {
    // 更新检查失败不阻塞主流程
  }
}

/** 刷新索引 */
async function handleRefresh() {
  refreshing.value = true
  try {
    await marketplaceApi.refreshIndex()
    await loadSkills()
    await loadUpdates()
  } catch (e: any) {
    error.value = e.message || '刷新失败'
  } finally {
    refreshing.value = false
  }
}

/** 搜索/筛选变化时重置分页并重新加载 */
watch([search, tag, extensionType], () => {
  page.value = 0
  loadSkills()
})

function handlePageChange(newPage: number) {
  page.value = newPage
  loadSkills()
}

function handleRefreshAfterAction() {
  loadSkills()
  loadUpdates()
}

/** 重新检查服务可用性 */
async function handleRetryServiceCheck() {
  serviceUnavailable.value = false
  error.value = ''
  await loadSkills()
  if (error.value && skills.value.length === 0) {
    serviceUnavailable.value = true
  } else {
    // 服务恢复，加载更新信息
    await loadUpdates()
  }
}

onMounted(() => {
  loadSkills().then(() => {
    // 检测服务不可用（后端 Controller 未注册导致 404 等）
    if (error.value && skills.value.length === 0) {
      serviceUnavailable.value = true
      return
    }
    loadUpdates()
  })
})
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">

        <!-- 页面标题 -->
        <div class="flex items-center justify-between mb-md gap-sm">
          <div class="space-y-xs">
            <h2 class="text-2xl font-semibold text-foreground leading-tight">
              扩展市场
            </h2>
            <p class="text-sm text-muted-foreground">
              发现和安装社区贡献的 Skill、Agent 和 Workflow，扩展 LifePilot 的能力。
            </p>
          </div>
          <Button
            variant="outline"
            :disabled="refreshing"
            @click="handleRefresh"
          >
            {{ refreshing ? '刷新中...' : '刷新索引' }}
          </Button>
        </div>

        <!-- 搜索与筛选 -->
        <div class="mb-md">
          <MarketplaceFilters
            v-model:search="search"
            v-model:tag="tag"
            v-model:type="extensionType"
            :available-tags="availableTags"
          />
        </div>

        <!-- 服务不可用提示（后端 API 未注册或不可达） -->
        <div v-if="serviceUnavailable" class="flex flex-col items-center justify-center py-16 px-4 text-center">
          <div class="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
            <ServerOff class="w-7 h-7 text-muted-foreground" />
          </div>
          <h3 class="text-lg font-semibold text-foreground mb-2">功能未启用或服务不可用</h3>
          <p class="text-sm text-muted-foreground mb-6 max-w-[448px]">
            扩展市场功能当前不可用，可能是相关服务尚未启用或后端未正确配置。请检查后端服务状态后重试。
          </p>
          <Button variant="outline" size="sm" @click="handleRetryServiceCheck">
            重新检查
          </Button>
        </div>

        <!-- Skeleton 加载占位符 -->
        <div v-if="loading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md">
          <Card v-for="i in 6" :key="i">
            <CardHeader class="pb-2">
              <div class="flex items-center gap-1.5">
                <Skeleton class="h-4 w-1/3" />
                <Skeleton class="h-5 w-14 rounded-full" />
              </div>
              <Skeleton class="h-3 w-1/4 mt-1" />
            </CardHeader>
            <CardContent class="pb-3">
              <Skeleton class="h-3 w-full mb-1" />
              <Skeleton class="h-3 w-2/3 mb-3" />
              <div class="flex gap-1 mb-3">
                <Skeleton class="h-5 w-12 rounded-full" />
                <Skeleton class="h-5 w-16 rounded-full" />
                <Skeleton class="h-5 w-10 rounded-full" />
              </div>
              <div class="flex items-center justify-between pt-sm border-t border-border">
                <Skeleton class="h-3 w-16" />
                <Skeleton class="h-7 w-16 rounded-md" />
              </div>
            </CardContent>
          </Card>
        </div>

        <!-- 错误状态（serviceUnavailable 时不显示，由上方专用提示覆盖） -->
        <ErrorState
          v-else-if="error && !serviceUnavailable"
          title="加载失败"
          :description="error"
          action-label="重试"
          :show-action="true"
          @action="loadSkills"
        />

        <!-- Skill 卡片网格 -->
        <template v-else-if="skills.length > 0">
          <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md">
            <SkillCard
              v-for="skill in skills"
              :key="skill.id"
              :skill="skill"
              :has-update="updateIds.has(skill.id)"
              @refresh="handleRefreshAfterAction"
            />
          </div>

          <!-- 分页 -->
          <Pagination
            v-if="totalPages > 1"
            :page="page"
            :page-count="totalPages"
            @change="handlePageChange"
          />
        </template>

        <!-- 空状态 -->
        <EmptyState
          v-else
          icon="🏪"
          title="暂无可用扩展"
          description="市场中还没有扩展，请尝试刷新索引或稍后再来。"
          action-label="刷新索引"
          :show-action="true"
          @action="handleRefresh"
        />
      </div>
    </div>
  </div>
</template>
