<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Trash2, RotateCcw } from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import { logger } from '@/utils/logger'
import type { PreferenceRule } from '@/types'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

// ── 类别选项 ──
const CATEGORY_ALL = '__all__'
const DEFAULT_CATEGORIES = ['通用', '交互', '输出', '工具'] as const

// ── 筛选状态 ──
const filterCategory = ref(CATEGORY_ALL)

// ── 列表状态 ──
const allItems = ref<PreferenceRule[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// ── 删除确认对话框 ──
const deleteOpen = ref(false)
const deleting = ref(false)
const deleteRuleId = ref('')
const deleteRuleKey = ref('')

// ── 计算属性：类别选项（从数据中提取唯一类别 + 默认类别） ──
const categoryOptions = computed(() => {
  const fromData = allItems.value.map((item) => item.category)
  const merged = new Set([...DEFAULT_CATEGORIES, ...fromData])
  return Array.from(merged).sort()
})

// ── 计算属性：按类别筛选后的列表 ──
const filteredItems = computed(() => {
  if (filterCategory.value === CATEGORY_ALL) return allItems.value
  return allItems.value.filter((item) => item.category === filterCategory.value)
})

// ── 数据加载 ──
async function loadPreferences() {
  loading.value = true
  error.value = null
  try {
    allItems.value = await memoryApi.listPreferences()
  } catch (e: any) {
    logger.error('加载偏好规则失败:', e)
    error.value = e?.message || '加载偏好规则失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(() => loadPreferences())

// ── 删除偏好规则 ──
function openDelete(id: string, key: string) {
  deleteRuleId.value = id
  deleteRuleKey.value = key
  deleteOpen.value = true
}

async function handleDelete() {
  deleting.value = true
  try {
    await memoryApi.deletePreference(deleteRuleId.value)
    deleteOpen.value = false
    loadPreferences()
  } catch (e: any) {
    logger.error('删除偏好规则失败:', e)
    alert(e?.message || '删除偏好规则失败')
  } finally {
    deleting.value = false
  }
}

// ── 辅助函数 ──
function formatDate(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function formatConfidence(confidence: number) {
  return (confidence * 100).toFixed(1) + '%'
}
</script>

<template>
  <div class="space-y-4">
    <!-- 筛选栏 -->
    <div class="detail-card p-4">
      <div class="flex flex-wrap items-end gap-3">
        <!-- 类别筛选 -->
        <div class="w-48">
          <label class="text-xs text-muted-foreground mb-1 block">类别</label>
          <Select v-model="filterCategory">
            <SelectTrigger>
              <SelectValue placeholder="全部类别" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem :value="CATEGORY_ALL">全部类别</SelectItem>
              <SelectItem v-for="cat in categoryOptions" :key="cat" :value="cat">
                {{ cat }}
              </SelectItem>
            </SelectContent>
          </Select>
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
      <Button variant="outline" class="mt-4" @click="loadPreferences">
        <RotateCcw class="mr-1.5 size-4" />
        重试
      </Button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="filteredItems.length === 0" class="detail-card px-6 py-12 text-center">
      <p class="text-sm text-muted-foreground">暂无偏好规则</p>
      <p class="mt-1 text-xs text-muted-foreground">偏好规则会在与 Agent 交互过程中自动学习生成。</p>
    </div>

    <!-- 偏好规则表格 -->
    <div v-else class="detail-card overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60">
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">类别</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">键</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">值</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">置信度</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">观察次数</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
            <th class="px-4 py-3 text-center font-medium text-muted-foreground">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="rule in filteredItems"
            :key="rule.ruleId"
            class="border-b border-border/40 transition-colors hover:bg-muted/50"
          >
            <td class="px-4 py-3">
              <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                {{ rule.category }}
              </span>
            </td>
            <td class="px-4 py-3 font-medium text-foreground max-w-[200px] truncate">
              {{ rule.key }}
            </td>
            <td class="px-4 py-3 text-muted-foreground max-w-[300px] truncate">
              {{ rule.value }}
            </td>
            <td class="px-4 py-3 text-right tabular-nums">{{ formatConfidence(rule.confidence) }}</td>
            <td class="px-4 py-3 text-right tabular-nums">{{ rule.observationCount }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(rule.createdAt) }}</td>
            <td class="px-4 py-3 text-center">
              <Button
                size="sm"
                variant="ghost"
                class="text-destructive hover:text-destructive"
                @click="openDelete(rule.ruleId, rule.key)"
              >
                <Trash2 class="size-3.5" />
              </Button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 总条数 -->
      <div class="flex items-center border-t border-border/60 px-4 py-3">
        <span class="text-xs text-muted-foreground">共 {{ filteredItems.length }} 条</span>
      </div>
    </div>

    <!-- 删除确认对话框 -->
    <Dialog v-model:open="deleteOpen">
      <DialogContent class="sm:max-w-[384px]">
        <DialogHeader>
          <DialogTitle>确认删除</DialogTitle>
          <DialogDescription>
            确定要删除偏好规则「{{ deleteRuleKey }}」吗？删除后数据将无法恢复。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" @click="deleteOpen = false">取消</Button>
          <Button variant="destructive" :disabled="deleting" @click="handleDelete">
            {{ deleting ? '删除中...' : '确认删除' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
