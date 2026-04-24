<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CloudDownload, Plus, Puzzle, SlidersHorizontal, Store } from 'lucide-vue-next'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import type { SkillSourceType, SkillSummary } from '@/types'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterChips from '@/components/common/FilterChips.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import SkillSourceBadge from '@/components/skill/SkillSourceBadge.vue'
import SkillInstallDialog from '@/components/skill/SkillInstallDialog.vue'
import SkillCreateDialog from '@/components/skill/SkillCreateDialog.vue'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'

type SourceFilter = 'all' | SkillSourceType

const router = useRouter()
const store = useSkillStore()
const uiStore = useUiStore()

const showInstallDialog = ref(false)
const showCreateDialog = ref(false)
const showFilters = ref(false)
const deleteTarget = ref<SkillSummary | null>(null)
const skillSearchQuery = ref('')
const skillSourceFilter = ref<SourceFilter>('all')

const skillSourceOptions: Array<{ value: SourceFilter; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'BUILTIN', label: '内置' },
  { value: 'USER_IMPORTED', label: '导入' },
  { value: 'AUTO_GENERATED', label: 'AI 生成' },
  { value: 'MARKETPLACE', label: '市场' },
]

const filteredSkills = computed(() => {
  let result = store.skills

  if (skillSearchQuery.value.trim()) {
    const keyword = skillSearchQuery.value.trim().toLowerCase()
    result = result.filter(skill =>
      skill.name.toLowerCase().includes(keyword)
      || (skill.description ?? '').toLowerCase().includes(keyword),
    )
  }

  if (skillSourceFilter.value !== 'all') {
    result = result.filter(skill => skill.sourceType === skillSourceFilter.value)
  }

  return result
})

const enabledSkillCount = computed(() => store.skills.filter(skill => skill.enabled !== false).length)
const hasFilters = computed(() => Boolean(skillSearchQuery.value.trim()) || skillSourceFilter.value !== 'all')

const showDeleteConfirm = computed({
  get: () => deleteTarget.value !== null,
  set: (value: boolean) => {
    if (!value) {
      deleteTarget.value = null
    }
  },
})

function showErrorToast(message: string) {
  uiStore.showToast('error', message)
}

function clearFilters() {
  skillSearchQuery.value = ''
  skillSourceFilter.value = 'all'
}

async function viewSkillDetail(skill: SkillSummary) {
  await router.push(`/skills/${encodeURIComponent(skill.name)}`)
}

async function confirmUnregister() {
  if (!deleteTarget.value) return

  try {
    await store.unregisterSkill(deleteTarget.value.name)
    deleteTarget.value = null
  } catch (error: any) {
    showErrorToast(error?.message || '删除技能失败。')
  }
}

async function onToggleEnabled(skill: SkillSummary, next: boolean) {
  // 乐观更新已在 store 内处理
  try {
    await store.setSkillEnabled(skill.name, next)
    uiStore.showToast('success', next ? '技能已启用。' : '技能已停用。')
  } catch (error: any) {
    // 回滚开关（fetchSkills 已刷新，但显式回滚让 UI 即时回到真值）
    skill.enabled = !next
    showErrorToast(error?.message || '更新技能状态失败。')
  }
}

function onInstalled() {
  uiStore.showToast('success', '技能目录已刷新。')
}

function onCreated() {
  // createSkill 已在 store 内部调用 fetchSkills，这里只作最终刷新提示
  uiStore.showToast('success', '技能目录已刷新。')
}

onMounted(() => {
  void store.fetchSkills()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="技能"
          title="技能目录"
          :description="`${enabledSkillCount} 个启用中，共 ${store.skills.length} 个`"
        >
          <template #actions>
            <Button variant="outline" @click="showCreateDialog = true">
              <Plus class="size-4" />
              新建技能
            </Button>
            <Button @click="showInstallDialog = true">
              <CloudDownload class="size-4" />
              导入技能
            </Button>
          </template>
        </PageHeader>

        <section class="toolbar-strip">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div class="flex flex-1 items-center gap-3">
              <SearchBar
                v-model="skillSearchQuery"
                placeholder="按技能名称或说明搜索..."
                class="flex-1"
              />
              <Button variant="outline" size="sm" @click="showFilters = !showFilters">
                <SlidersHorizontal class="size-4" />
                筛选
              </Button>
            </div>

            <div class="flex items-center gap-3">
              <span class="text-sm text-muted-foreground">结果 {{ filteredSkills.length }}</span>
              <Button v-if="hasFilters" variant="ghost" @click="clearFilters">
                清空筛选
              </Button>
            </div>
          </div>

          <div v-if="showFilters" class="mt-sm rounded-xl border border-border/40 bg-card/60 p-md">
            <div class="flex flex-wrap items-center gap-3">
              <span class="surface-label text-[0.68rem]">来源</span>
              <FilterChips v-model="skillSourceFilter" :options="skillSourceOptions" />
            </div>
          </div>
        </section>

        <PageSection
          eyebrow="目录"
          title="已注册技能"
          :description="`当前显示 ${filteredSkills.length} / ${store.skills.length} 个已注册技能。`"
        >
          <div v-if="store.loading" class="space-y-sm">
            <div
              v-for="index in 4"
              :key="index"
              class="rounded-xl border border-border/60 bg-background/72 p-md"
            >
              <div class="h-md w-48 animate-pulse rounded bg-muted" />
            </div>
          </div>

          <StatePanel
            v-else-if="store.error"
            title="技能目录暂时不可用"
            :description="store.error"
            tone="danger"
          >
            <template #icon>
              <Puzzle class="size-5" />
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="store.skills.length === 0"
            title="暂无已注册技能"
            description="可以直接新建一个，也可以从本地压缩包或技能市场导入。"
          >
            <template #icon>
              <Puzzle class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" @click="showCreateDialog = true">
                新建技能
              </Button>
              <Button @click="showInstallDialog = true">
                导入技能
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="filteredSkills.length === 0"
            title="没有匹配当前筛选条件的技能"
            description="可以放宽来源筛选，或清空搜索关键词后重试。"
          >
            <template #icon>
              <Store class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" @click="clearFilters">
                清空筛选
              </Button>
            </template>
          </StatePanel>

          <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <article
              v-for="skill in filteredSkills"
              :key="skill.name"
              class="list-card group cursor-pointer p-4"
              @click="viewSkillDetail(skill)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0 space-y-2">
                  <div class="flex flex-wrap items-center gap-2">
                    <h3 class="truncate text-base font-semibold tracking-tight text-foreground">
                      {{ skill.name }}
                    </h3>
                    <SkillSourceBadge :source-type="skill.sourceType" />
                  </div>
                  <p class="line-clamp-3 text-sm leading-6 text-muted-foreground">
                    {{ skill.description || '这个技能暂时还没有说明。' }}
                  </p>
                </div>
                <span class="text-xs text-muted-foreground">
                  v{{ skill.version }}
                </span>
              </div>

              <div class="mt-4 flex items-center justify-between gap-3 border-t border-border/60 pt-4">
                <div class="flex items-center gap-sm" @click.stop>
                  <Switch
                    :model-value="skill.enabled !== false"
                    :aria-label="skill.enabled !== false ? '停用技能' : '启用技能'"
                    @update:model-value="next => onToggleEnabled(skill, next)"
                  />
                  <span class="text-xs text-muted-foreground">
                    {{ skill.enabled !== false ? '已启用' : '已停用' }}
                  </span>
                </div>

                <div class="flex items-center gap-2" @click.stop>
                  <Button
                    variant="ghost"
                    size="sm"
                    @click="viewSkillDetail(skill)"
                  >
                    查看详情
                  </Button>
                  <Button
                    v-if="skill.sourceType !== 'BUILTIN'"
                    variant="destructive"
                    size="sm"
                    @click="deleteTarget = skill"
                  >
                    删除
                  </Button>
                </div>
              </div>
            </article>
          </div>
        </PageSection>
      </div>
    </PageContainer>

    <SkillInstallDialog
      :open="showInstallDialog"
      @update:open="value => (showInstallDialog = value)"
      @installed="onInstalled"
    />

    <SkillCreateDialog
      :open="showCreateDialog"
      @update:open="value => (showCreateDialog = value)"
      @created="onCreated"
    />

    <ConfirmDialog
      v-if="deleteTarget"
      :show="showDeleteConfirm"
      title="删除技能"
      :message="`确定删除 ${deleteTarget.name} 吗？此操作不可恢复。`"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="confirmUnregister"
      @cancel="deleteTarget = null"
      @update:show="showDeleteConfirm = $event"
    />
  </div>
</template>
