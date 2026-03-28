<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RotateCcw } from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import type { RelationItem, RelationListParams } from '@/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import Pagination from '@/components/common/Pagination.vue'
import { useMemoryStore } from '@/stores/memory'

const store = useMemoryStore()

const MEMORY_SCOPE_LABELS: Record<string, string> = {
  USER_PROFILE: '用户画像',
  USER_FACT: '用户事实',
  AGENT_EXPERIENCE: '执行经验',
  DOMAIN_MEMORY: '领域记忆',
}

const REALITY_TYPE_LABELS: Record<string, string> = {
  REAL: '真实',
  FICTIONAL: '虚构',
  SIMULATED: '模拟',
  UNKNOWN: '未标注',
}

// ── 筛选状态 ──
const filterEntityId = ref('')
const filterRelationType = ref<string>('')

// ── 列表状态 ──
const PAGE_SIZE = 20
const currentPage = ref(0)
const items = ref<RelationItem[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// ── 关系类型选项 ──
const RELATION_TYPES = [
  { value: 'KNOWS', label: '认识' },
  { value: 'WORKS_WITH', label: '合作' },
  { value: 'BELONGS_TO', label: '属于' },
  { value: 'LOCATED_IN', label: '位于' },
  { value: 'RELATED_TO', label: '相关' },
  { value: 'PART_OF', label: '组成部分' },
  { value: 'CREATED_BY', label: '创建者' },
  { value: 'INTERESTED_IN', label: '感兴趣' },
] as const

// ── 数据加载 ──
async function loadRelations() {
  loading.value = true
  error.value = null
  try {
    const params: RelationListParams = {
      page: currentPage.value,
      size: PAGE_SIZE,
    }
    if (filterEntityId.value.trim()) params.entityId = filterEntityId.value.trim()
    if (filterRelationType.value) params.relationType = filterRelationType.value

    const result = await memoryApi.listRelations(params)
    items.value = result.items
    total.value = result.total
  } catch (e: any) {
    console.error('加载关系列表失败:', e)
    error.value = e?.message || '加载关系列表失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 0
  loadRelations()
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadRelations()
}

// 筛选条件变化时重新加载
watch([filterRelationType], () => {
  currentPage.value = 0
  loadRelations()
})

onMounted(() => loadRelations())

// ── 点击实体名称，切换到实体 Tab ──
function navigateToEntity(_entityId: string) {
  store.requestEntityDetail(_entityId)
}

// ── 辅助函数 ──
function formatDate(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function formatMemoryScope(scope?: string | null) {
  if (!scope) return '未分配'
  return MEMORY_SCOPE_LABELS[scope] || scope
}

function formatRealityType(realityType?: string | null) {
  if (!realityType) return '未标注'
  return REALITY_TYPE_LABELS[realityType] || realityType
}

function formatSpaceId(spaceId?: string | null) {
  if (!spaceId) return '默认空间'
  return spaceId
}
</script>

<template>
  <div class="space-y-4">
    <!-- 筛选栏 -->
    <div class="detail-card p-4">
      <div class="flex flex-wrap items-end gap-3">
        <!-- 实体 ID 输入框 -->
        <div class="flex-1 min-w-[200px]">
          <label class="text-xs text-muted-foreground mb-1 block">实体 ID</label>
          <Input
            v-model="filterEntityId"
            placeholder="输入实体 ID 筛选相关关系..."
            @keydown.enter="handleSearch"
          />
        </div>

        <!-- 关系类型下拉框 -->
        <div class="w-40">
          <label class="text-xs text-muted-foreground mb-1 block">关系类型</label>
          <Select
            :model-value="filterRelationType || '__all__'"
            @update:model-value="(value) => filterRelationType = String(value ?? '') === '__all__' ? '' : String(value ?? '')"
          >
            <SelectTrigger>
              <SelectValue placeholder="全部类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">全部类型</SelectItem>
              <SelectItem v-for="t in RELATION_TYPES" :key="t.value" :value="t.value">
                {{ t.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>

        <!-- 搜索按钮 -->
        <div class="flex items-end">
          <Button @click="handleSearch">搜索</Button>
        </div>
      </div>
    </div>

    <!-- 加载中骨架屏 -->
    <div v-if="loading" class="detail-card p-4 space-y-3">
      <Skeleton v-for="i in 6" :key="i" class="h-10 w-full" />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ error }}</p>
      <Button variant="outline" class="mt-4" @click="loadRelations">
        <RotateCcw class="mr-1.5 size-4" />
        重试
      </Button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="items.length === 0" class="detail-card px-6 py-12 text-center">
      <p class="text-sm text-muted-foreground">暂无关系数据</p>
      <p class="mt-1 text-xs text-muted-foreground">关系会在对话过程中自动从实体间提取。</p>
    </div>

    <!-- 关系表格 -->
    <div v-else class="detail-card overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60">
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">源实体</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">关系类型</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">目标实体</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">强度</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="relation in items"
            :key="relation.id"
            class="border-b border-border/40 transition-colors hover:bg-muted/50"
          >
            <td class="px-4 py-3">
              <div class="space-y-1">
                <div class="flex flex-wrap items-center gap-1.5">
                  <button
                    class="font-medium text-foreground underline-offset-4 hover:underline cursor-pointer"
                    @click="navigateToEntity(relation.sourceEntityId)"
                  >
                    {{ relation.sourceEntityName }}
                  </button>
                  <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                    {{ relation.sourceEntityType }}
                  </span>
                </div>
                <div class="flex flex-wrap items-center gap-1.5">
                  <Badge variant="outline">{{ formatMemoryScope(relation.sourceEntityMemoryScope) }}</Badge>
                  <Badge variant="secondary">{{ formatRealityType(relation.sourceEntityRealityType) }}</Badge>
                </div>
                <p class="text-xs text-muted-foreground break-all">
                  {{ formatSpaceId(relation.sourceEntitySpaceId) }}
                </p>
              </div>
            </td>
            <td class="px-4 py-3">
              <div class="space-y-1">
                <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                  {{ relation.relationType }}
                </span>
              </div>
            </td>
            <td class="px-4 py-3">
              <div class="space-y-1">
                <div class="flex flex-wrap items-center gap-1.5">
                  <button
                    class="font-medium text-foreground underline-offset-4 hover:underline cursor-pointer"
                    @click="navigateToEntity(relation.targetEntityId)"
                  >
                    {{ relation.targetEntityName }}
                  </button>
                  <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                    {{ relation.targetEntityType }}
                  </span>
                </div>
                <div class="flex flex-wrap items-center gap-1.5">
                  <Badge variant="outline">{{ formatMemoryScope(relation.targetEntityMemoryScope) }}</Badge>
                  <Badge variant="secondary">{{ formatRealityType(relation.targetEntityRealityType) }}</Badge>
                </div>
                <p class="text-xs text-muted-foreground break-all">
                  {{ formatSpaceId(relation.targetEntitySpaceId) }}
                </p>
              </div>
            </td>
            <td class="px-4 py-3 text-right tabular-nums">{{ relation.strength.toFixed(2) }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(relation.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 分页控件 -->
      <div class="flex items-center justify-between border-t border-border/60 px-4 py-3">
        <span class="text-xs text-muted-foreground">共 {{ total }} 条</span>
        <Pagination
          v-if="pageCount > 1"
          :page="currentPage"
          :page-count="pageCount"
          size="sm"
          @change="handlePageChange"
        />
      </div>
    </div>
  </div>
</template>
