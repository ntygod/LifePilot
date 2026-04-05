<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw, ServerOff, Store } from 'lucide-vue-next'
import type { ExtensionInstallation, ExtensionPackage, InstalledExtensionAsset } from '@/types'
import { marketplaceApi } from '@/api/marketplace'
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
const selectedExtensionId = ref('')
const installationLoading = ref(false)
const installationMessage = ref('')
const installationError = ref('')
const selectedInstallation = ref<ExtensionInstallation | null>(null)
const assetPreviewLoading = ref(false)
const assetPreviewPath = ref('')
const assetPreviewContent = ref('')
const assetPreviewError = ref('')

const updateIds = computed(() => new Set(updates.value.map(item => item.id)))
const installedCount = computed(() => skills.value.filter(skill => skill.installed).length)
const verifiedCount = computed(() => skills.value.filter(skill => skill.verified).length)
const hasFilters = computed(() => Boolean(search.value) || Boolean(tag.value) || Boolean(extensionType.value))
const selectedExtension = computed(() => {
  if (!selectedExtensionId.value) return skills.value[0] ?? null
  return skills.value.find(skill => skill.id === selectedExtensionId.value) ?? skills.value[0] ?? null
})
const installationAssets = computed(() => selectedInstallation.value?.assets ?? [])
const installationReadmeAsset = computed(() => installationAssets.value.find(asset => asset.kind === 'README') ?? null)
const installationIconAsset = computed(() => installationAssets.value.find(asset => asset.kind === 'ICON') ?? null)
const installationExampleAssets = computed(() => installationAssets.value.filter(asset => asset.kind === 'EXAMPLE'))
const installationExtraAssets = computed(() => installationAssets.value.filter(asset => !['README', 'ICON', 'EXAMPLE'].includes(asset.kind)))
const extensionTypeLabel = computed(() => {
  if (!extensionType.value) return '全部类型'
  if (extensionType.value === 'SKILL') return '技能'
  if (extensionType.value === 'AGENT') return '智能体'
  if (extensionType.value === 'WORKFLOW') return '工作流'
  if (extensionType.value === 'CHANNEL') return '渠道'
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
    syncSelectedExtension(result.content)
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

watch(selectedExtension, extensionPackage => {
  void loadInstallation(extensionPackage)
})

function handlePageChange(newPage: number) {
  page.value = newPage
  void loadSkills()
}

function handleRefreshAfterAction() {
  void loadSkills()
  void loadUpdates()
}

function syncSelectedExtension(items: ExtensionPackage[]) {
  if (items.length === 0) {
    selectedExtensionId.value = ''
    return
  }
  if (selectedExtensionId.value && items.some(item => item.id === selectedExtensionId.value)) {
    return
  }
  const preferred = items.find(item => item.type === 'CHANNEL' && item.installed) ?? items[0]
  selectedExtensionId.value = preferred.id
}

function selectExtension(id: string) {
  selectedExtensionId.value = id
}

function resetInstallationPreview(message = '') {
  selectedInstallation.value = null
  installationMessage.value = message
  installationError.value = ''
  assetPreviewLoading.value = false
  assetPreviewPath.value = ''
  assetPreviewContent.value = ''
  assetPreviewError.value = ''
}

function assetFileName(asset: InstalledExtensionAsset): string {
  return asset.relativePath.split('/').filter(Boolean).at(-1) ?? asset.relativePath
}

function assetKindLabel(kind: string): string {
  if (kind === 'README') return 'README'
  if (kind === 'ICON') return '图标'
  if (kind === 'EXAMPLE') return '示例'
  return kind
}

function installationAssetUrl(asset: InstalledExtensionAsset): string {
  if (!selectedInstallation.value) return '#'
  return marketplaceApi.getInstallationAssetUrl(selectedInstallation.value.packageId, asset.relativePath)
}

async function previewInstallationAsset(asset: InstalledExtensionAsset) {
  if (!selectedInstallation.value) return
  assetPreviewLoading.value = true
  assetPreviewPath.value = asset.relativePath
  assetPreviewError.value = ''
  assetPreviewContent.value = ''
  try {
    assetPreviewContent.value = await marketplaceApi.getInstallationAssetText(
      selectedInstallation.value.packageId,
      asset.relativePath,
    )
  } catch (event: any) {
    assetPreviewError.value = event?.message || '读取安装资产失败。'
  } finally {
    assetPreviewLoading.value = false
  }
}

async function loadInstallation(extensionPackage: ExtensionPackage | null) {
  if (!extensionPackage) {
    resetInstallationPreview()
    return
  }
  if (!extensionPackage.installed) {
    resetInstallationPreview('安装后可在这里直接预览本地 README、图标和示例配置。')
    return
  }

  installationLoading.value = true
  resetInstallationPreview()
  try {
    const installation = await marketplaceApi.getInstallation(extensionPackage.id)
    selectedInstallation.value = installation
    if (!installation.assets || installation.assets.length === 0) {
      installationMessage.value = '当前扩展已安装，但没有声明额外的安装资产。'
      return
    }
    installationMessage.value = '安装资产来自 Marketplace 本地安装目录。'
    const previewAsset = installation.assets.find(asset => asset.kind === 'README')
      ?? installation.assets.find(asset => asset.kind === 'EXAMPLE')
    if (previewAsset) {
      await previewInstallationAsset(previewAsset)
    }
  } catch (event: any) {
    selectedInstallation.value = null
    if (event?.code === 404) {
      installationMessage.value = '当前扩展已安装，但还没有本地安装快照。'
    } else {
      installationError.value = event?.message || '加载安装快照失败。'
    }
  } finally {
    installationLoading.value = false
  }
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
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="市场"
          title="扩展市场"
          :description="`共 ${totalElements} 个扩展，${installedCount} 个已安装，${updates.length} 个可更新，${verifiedCount} 个已验证`"
        >
          <template #actions>
            <Button variant="outline" :disabled="refreshing" @click="handleRefresh">
              <RefreshCw class="size-4" :class="refreshing ? 'animate-spin' : ''" />
              {{ refreshing ? '刷新中...' : '刷新索引' }}
            </Button>
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
          description="浏览当前市场索引中的可安装技能、智能体、工作流和渠道插件。"
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

          <div v-else class="grid gap-4 xl:grid-cols-[minmax(0,1.8fr)_minmax(320px,0.95fr)]">
            <div class="space-y-5">
              <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
                <SkillCard
                  v-for="skill in skills"
                  :key="skill.id"
                  :skill="skill"
                  :selected="selectedExtension?.id === skill.id"
                  :has-update="updateIds.has(skill.id)"
                  @refresh="handleRefreshAfterAction"
                  @inspect="selectExtension"
                />
              </div>

              <Pagination
                v-if="totalPages > 1"
                :page="page"
                :page-count="totalPages"
                @change="handlePageChange"
              />
            </div>

            <section
              v-if="selectedExtension"
              class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 p-4"
            >
              <div class="space-y-3">
                <div class="space-y-2">
                  <div class="surface-label text-[0.68rem]">详情</div>
                  <div class="flex flex-wrap items-center gap-2">
                    <h3 class="text-base font-semibold tracking-tight text-foreground">
                      {{ selectedExtension.name }}
                    </h3>
                    <span class="filter-pill">{{ selectedExtension.type }}</span>
                    <span v-if="selectedExtension.installed" class="filter-pill">已安装</span>
                  </div>
                  <p class="text-sm leading-6 text-muted-foreground">
                    {{ selectedExtension.description || '暂无描述' }}
                  </p>
                </div>

                <div class="grid gap-3 sm:grid-cols-2">
                  <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4">
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">版本</div>
                    <div class="mt-2 text-sm font-medium text-foreground">
                      v{{ selectedExtension.version }}
                    </div>
                    <div v-if="selectedExtension.installedVersion" class="mt-1 text-xs text-muted-foreground">
                      已安装 v{{ selectedExtension.installedVersion }}
                    </div>
                  </div>
                  <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4">
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">来源</div>
                    <div class="mt-2 break-all text-sm text-foreground">
                      {{ selectedExtension.repoUrl || selectedExtension.filePath }}
                    </div>
                  </div>
                </div>

                <div v-if="selectedExtension.requirements?.length" class="space-y-2">
                  <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">前置条件</div>
                  <div class="flex flex-wrap gap-2">
                    <span
                      v-for="req in selectedExtension.requirements"
                      :key="`selected-req-${req}`"
                      class="filter-pill"
                    >
                      {{ req }}
                    </span>
                  </div>
                </div>

                <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4">
                  <div class="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <div class="text-sm font-semibold text-foreground">本地安装资产</div>
                      <p class="mt-1 text-sm leading-6 text-muted-foreground">
                        已安装扩展会在这里显示安装目录、README 和示例文件。渠道插件可以直接把接入文档暴露给用户。
                      </p>
                    </div>
                    <span v-if="selectedInstallation" class="filter-pill">
                      {{ installationAssets.length }} 项资产
                    </span>
                  </div>

                  <div v-if="installationLoading" class="mt-4 space-y-3">
                    <Skeleton class="h-20 w-full rounded-[calc(var(--radius)+6px)]" />
                    <Skeleton class="h-48 w-full rounded-[calc(var(--radius)+6px)]" />
                  </div>

                  <div
                    v-else-if="installationError"
                    class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-destructive/40 bg-destructive/5 px-4 py-6 text-sm leading-6 text-destructive"
                  >
                    {{ installationError }}
                  </div>

                  <div
                    v-else-if="!selectedInstallation"
                    class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-muted/20 px-4 py-6 text-sm leading-6 text-muted-foreground"
                  >
                    {{ installationMessage }}
                  </div>

                  <div v-else class="mt-4 space-y-4">
                    <div class="grid gap-3 sm:grid-cols-[96px_minmax(0,1fr)]">
                      <div
                        v-if="installationIconAsset"
                        class="flex items-center justify-center rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 p-3"
                      >
                        <img
                          :src="installationAssetUrl(installationIconAsset)"
                          :alt="`${selectedExtension.name} 图标`"
                          class="max-h-14 w-auto object-contain"
                        />
                      </div>
                      <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-4">
                        <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">安装目录</div>
                        <div class="mt-2 break-all font-mono text-xs text-foreground">
                          {{ selectedInstallation.installRootPath }}
                        </div>
                        <div class="mt-3 text-xs uppercase tracking-[0.16em] text-muted-foreground">入口文件</div>
                        <div class="mt-2 break-all font-mono text-xs text-foreground">
                          {{ selectedInstallation.entryPath }}
                        </div>
                      </div>
                    </div>

                    <div v-if="installationReadmeAsset || installationExampleAssets.length" class="space-y-2">
                      <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">可预览文件</div>
                      <div class="flex flex-wrap gap-2">
                        <Button
                          v-if="installationReadmeAsset"
                          size="sm"
                          :variant="assetPreviewPath === installationReadmeAsset.relativePath ? 'default' : 'outline'"
                          @click="previewInstallationAsset(installationReadmeAsset)"
                        >
                          README
                        </Button>
                        <Button
                          v-for="asset in installationExampleAssets"
                          :key="`selected-example-${asset.relativePath}`"
                          size="sm"
                          :variant="assetPreviewPath === asset.relativePath ? 'default' : 'outline'"
                          @click="previewInstallationAsset(asset)"
                        >
                          {{ assetFileName(asset) }}
                        </Button>
                      </div>
                    </div>

                    <div v-if="installationExtraAssets.length" class="space-y-2">
                      <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">附加资产</div>
                      <div class="space-y-2">
                        <a
                          v-for="asset in installationExtraAssets"
                          :key="`selected-asset-${asset.relativePath}`"
                          :href="installationAssetUrl(asset)"
                          target="_blank"
                          rel="noreferrer"
                          class="block text-xs text-primary transition-colors hover:text-primary/80 hover:underline"
                        >
                          {{ assetKindLabel(asset.kind) }} · {{ assetFileName(asset) }}
                        </a>
                      </div>
                    </div>

                    <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-4">
                      <div class="flex flex-wrap items-center justify-between gap-2">
                        <div>
                          <div class="text-sm font-semibold text-foreground">README / 示例预览</div>
                          <p class="mt-1 text-xs text-muted-foreground">
                            {{ assetPreviewPath || installationMessage || '当前扩展没有可预览的文本资产。' }}
                          </p>
                        </div>
                        <a
                          v-if="assetPreviewPath"
                          :href="marketplaceApi.getInstallationAssetUrl(selectedInstallation.packageId, assetPreviewPath)"
                          target="_blank"
                          rel="noreferrer"
                          class="text-xs text-primary transition-colors hover:text-primary/80 hover:underline"
                        >
                          打开原文件
                        </a>
                      </div>

                      <div v-if="assetPreviewLoading" class="mt-4 space-y-3">
                        <Skeleton class="h-5 w-32 rounded-md" />
                        <Skeleton class="h-48 w-full rounded-[calc(var(--radius)+6px)]" />
                      </div>

                      <div
                        v-else-if="assetPreviewError"
                        class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-background/70 px-4 py-6 text-sm leading-6 text-muted-foreground"
                      >
                        {{ assetPreviewError }}
                      </div>

                      <pre
                        v-else-if="assetPreviewContent"
                        class="mt-4 max-h-[420px] overflow-auto rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4 text-xs leading-6 text-foreground whitespace-pre-wrap"
                      >{{ assetPreviewContent }}</pre>

                      <div
                        v-else
                        class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-background/70 px-4 py-6 text-sm leading-6 text-muted-foreground"
                      >
                        当前扩展没有可直接预览的文本资产。
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </PageSection>
      </div>
    </PageContainer>
  </div>
</template>
