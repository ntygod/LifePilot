<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Download, RefreshCw, ServerOff, ShieldCheck, Store } from 'lucide-vue-next'
import type { ExtensionPackage } from '@/types'
import { marketplaceApi } from '@/api/marketplace'
import MetricCard from '@/components/common/MetricCard.vue'
import Pagination from '@/components/common/Pagination.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import MarketplaceFilters from '@/components/marketplace/MarketplaceFilters.vue'
import SkillCard from '@/components/marketplace/SkillCard.vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

const search = ref('')
const tag = ref('')
const extensionType = ref('')
const page = ref(0)
const size = 20

const skills = ref<ExtensionPackage[]>([])
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)
const error = ref('')
const refreshing = ref(false)
const serviceUnavailable = ref(false)
const updates = ref<ExtensionPackage[]>([])

const updateIds = computed(() => new Set(updates.value.map(item => item.id)))
const installedCount = computed(() => skills.value.filter(skill => skill.installed).length)
const verifiedCount = computed(() => skills.value.filter(skill => skill.verified).length)
const hasFilters = computed(() => Boolean(search.value) || Boolean(tag.value) || Boolean(extensionType.value))
const extensionTypeLabel = computed(() => {
  if (!extensionType.value) return '全部类型'
  if (extensionType.value === 'SKILL') return '技能'
  if (extensionType.value === 'AGENT') return '智能体'
  if (extensionType.value === 'WORKFLOW') return '工作流'
  return extensionType.value
})
const tagLabel = computed(() => tag.value || '全部标签')

const availableTags = computed(() => {
  const tagSet = new Set<string>()
  skills.value.forEach(skill => skill.tags?.forEach(tagValue => tagSet.add(tagValue)))
  return Array.from(tagSet).sort()
})

async function loadSkills() {
  loading.value = true
  error.value = ''

  try {
    const result = await marketplaceApi.getSkills({
      type: extensionType.value || undefined,
      search: search.value || undefined,
      tag: tag.value || undefined,
      page: page.value,
      size,
    })
    skills.value = result.content
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch (event: any) {
    error.value = event?.message || '加载市场扩展包失败。'
  } finally {
    loading.value = false
  }
}

async function loadUpdates() {
  try {
    updates.value = await marketplaceApi.getUpdates()
  } catch {
    // Non-blocking by design.
  }
}

async function handleRefresh() {
  refreshing.value = true
  try {
    await marketplaceApi.refreshIndex()
    await loadSkills()
    await loadUpdates()
  } catch (event: any) {
    error.value = event?.message || '刷新市场索引失败。'
  } finally {
    refreshing.value = false
  }
}

function clearFilters() {
  search.value = ''
  tag.value = ''
  extensionType.value = ''
  page.value = 0
}

watch([search, tag, extensionType], () => {
  page.value = 0
  void loadSkills()
})

function handlePageChange(newPage: number) {
  page.value = newPage
  void loadSkills()
}

function handleRefreshAfterAction() {
  void loadSkills()
  void loadUpdates()
}

async function handleRetryServiceCheck() {
  serviceUnavailable.value = false
  error.value = ''
  await loadSkills()

  if (error.value && skills.value.length === 0) {
    serviceUnavailable.value = true
  } else {
    await loadUpdates()
  }
}

onMounted(() => {
  void loadSkills().then(() => {
    if (error.value && skills.value.length === 0) {
      serviceUnavailable.value = true
      return
    }
    void loadUpdates()
  })
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <PageHeader
          eyebrow="市场"
          title="扩展市场"
          description="先判断哪些扩展已经装到本地、哪些存在更新，再决定是安装新能力还是继续升级已有扩展。"
        >
          <template #actions>
            <Button variant="outline" :disabled="refreshing" @click="handleRefresh">
              <RefreshCw class="size-4" :class="refreshing ? 'animate-spin' : ''" />
              {{ refreshing ? '刷新中...' : '刷新索引' }}
            </Button>
          </template>

          <template #meta>
            <MetricCard label="当前可见" :value="totalElements" hint="当前搜索和筛选条件下返回的扩展包数量。">
              <template #icon>
                <Store class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="已安装" :value="installedCount" hint="当前结果集中本地已经接入的扩展包。">
              <template #icon>
                <Download class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="可更新" :value="updates.length" hint="市场中存在新版本、值得回头处理的扩展。">
              <template #icon>
                <RefreshCw class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="已验证" :value="verifiedCount" hint="当前筛选结果中已通过验证的扩展数量。">
              <template #icon>
                <ShieldCheck class="size-5" />
              </template>
            </MetricCard>
          </template>
        </PageHeader>

        <PageSection
          eyebrow="筛选"
          title="查找扩展"
          description="按关键词、类型和标签缩小范围。"
        >
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
            <div class="min-w-0">
              <MarketplaceFilters
                v-model:search="search"
                v-model:tag="tag"
                v-model:type="extensionType"
                :available-tags="availableTags"
              />
            </div>

            <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 px-4 py-4">
              <div class="space-y-3">
                <div>
                  <div class="surface-label text-[0.68rem]">当前视图</div>
                  <p class="mt-2 text-sm leading-6 text-muted-foreground">
                    可直接在目录里判断安装状态、更新机会和可信度，再决定是否进入安装或升级动作。
                  </p>
                </div>

                <div class="flex flex-wrap gap-2 text-xs">
                  <span class="filter-pill">结果：{{ totalElements }}</span>
                  <span class="filter-pill">类型：{{ extensionTypeLabel }}</span>
                  <span class="filter-pill">标签：{{ tagLabel }}</span>
                  <span v-if="search" class="filter-pill">关键词：{{ search }}</span>
                </div>

                <Button v-if="hasFilters" variant="ghost" class="px-0" @click="clearFilters">
                  清空筛选
                </Button>
              </div>
            </div>
          </div>
        </PageSection>

        <StatePanel
          v-if="serviceUnavailable"
          title="市场服务暂时不可用"
          description="当前无法连接扩展市场，请稍后重试。"
          tone="warning"
        >
          <template #icon>
            <ServerOff class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" size="sm" @click="handleRetryServiceCheck">
              重试
            </Button>
          </template>
        </StatePanel>

        <StatePanel
          v-else-if="error && !loading"
          title="无法加载市场扩展包"
          :description="error"
          tone="danger"
        >
          <template #icon>
            <Store class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" size="sm" @click="loadSkills">
              重试
            </Button>
          </template>
        </StatePanel>

        <PageSection
          eyebrow="扩展包"
          title="可用扩展"
          description="浏览当前市场索引中的可安装技能、智能体和工作流。"
        >
          <div v-if="loading" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <div
              v-for="index in 6"
              :key="index"
              class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-5"
            >
              <div class="space-y-3">
                <div class="flex items-center gap-2">
                  <Skeleton class="h-4 w-28" />
                  <Skeleton class="h-5 w-14 rounded-full" />
                </div>
                <Skeleton class="h-3 w-32" />
                <Skeleton class="h-3 w-full" />
                <Skeleton class="h-3 w-2/3" />
                <div class="flex gap-2">
                  <Skeleton class="h-5 w-14 rounded-full" />
                  <Skeleton class="h-5 w-14 rounded-full" />
                </div>
                <div class="flex items-center justify-between border-t border-border pt-3">
                  <Skeleton class="h-3 w-20" />
                  <Skeleton class="h-8 w-20 rounded-md" />
                </div>
              </div>
            </div>
          </div>

          <StatePanel
            v-else-if="skills.length === 0"
            title="没有匹配当前筛选条件的市场扩展包"
            description="可以清空搜索词、切换扩展类型，或刷新市场索引后重试。"
          >
            <template #icon>
              <Store class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" @click="handleRefresh">
                刷新索引
              </Button>
              <Button v-if="hasFilters" variant="ghost" @click="clearFilters">
                清空筛选
              </Button>
            </template>
          </StatePanel>

          <div v-else class="space-y-5">
            <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              <SkillCard
                v-for="skill in skills"
                :key="skill.id"
                :skill="skill"
                :has-update="updateIds.has(skill.id)"
                @refresh="handleRefreshAfterAction"
              />
            </div>

            <Pagination
              v-if="totalPages > 1"
              :page="page"
              :page-count="totalPages"
              @change="handlePageChange"
            />
          </div>
        </PageSection>
      </div>
    </PageContainer>
  </div>
</template>
