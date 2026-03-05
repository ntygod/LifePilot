<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import type { SkillPackage, UpdateInfo } from '@/types'
import { marketplaceApi } from '@/api/marketplace'
import MarketplaceFilters from '@/components/marketplace/MarketplaceFilters.vue'
import SkillCard from '@/components/marketplace/SkillCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import Pagination from '@/components/common/Pagination.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

// 搜索与筛选状态
const search = ref('')
const tag = ref('')
const page = ref(0)
const size = 20

// 数据状态
const skills = ref<SkillPackage[]>([])
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)
const error = ref('')
const refreshing = ref(false)

// 更新信息
const updates = ref<UpdateInfo[]>([])
const updateIds = computed(() => new Set(updates.value.map(u => u.packageId)))

// 从所有 Skill 中提取可用标签（去重）
const availableTags = computed(() => {
  const tagSet = new Set<string>()
  skills.value.forEach(s => s.tags?.forEach(t => tagSet.add(t)))
  return Array.from(tagSet).sort()
})

/** 加载 Skill 列表 */
async function loadSkills() {
  loading.value = true
  error.value = ''
  try {
    const result = await marketplaceApi.getSkills({
      search: search.value || undefined,
      tag: tag.value || undefined,
      page: page.value,
      size
    })
    skills.value = result.items ?? (result as any).content ?? []
    totalPages.value = (result as any).totalPages ?? Math.ceil((result.total ?? 0) / size)
    totalElements.value = result.total ?? (result as any).totalElements ?? 0
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
watch([search, tag], () => {
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

onMounted(() => {
  loadSkills()
  loadUpdates()
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
              Skill 市场
            </h2>
            <p class="text-sm text-muted-foreground">
              发现和安装社区贡献的 Skill，扩展 LifePilot 的能力。
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
            :available-tags="availableTags"
          />
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

        <!-- 错误状态 -->
        <ErrorState
          v-else-if="error"
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
          title="暂无可用 Skill"
          description="市场中还没有 Skill，请尝试刷新索引或稍后再来。"
          action-label="刷新索引"
          :show-action="true"
          @action="handleRefresh"
        />
      </div>
    </div>
  </div>
</template>
