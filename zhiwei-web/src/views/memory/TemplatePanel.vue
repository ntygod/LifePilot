<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Search, Trash2, RotateCcw } from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import type {
  ProcedureTemplate,
  TemplateListParams,
} from '@/types'
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import Pagination from '@/components/common/Pagination.vue'

// ── 排序选项 ──
const SORT_OPTIONS = [
  { value: 'successRate', label: '成功率' },
  { value: 'useCount', label: '使用次数' },
  { value: 'createdAt', label: '创建时间' },
] as const

const ORDER_OPTIONS = [
  { value: 'desc', label: '降序' },
  { value: 'asc', label: '升序' },
] as const

// ── 筛选状态 ──
const filterQ = ref('')
const sortBy = ref('createdAt')
const order = ref('desc')

// ── 列表状态 ──
const PAGE_SIZE = 20
const currentPage = ref(0)
const items = ref<ProcedureTemplate[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// ── 详情面板状态 ──
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailTemplate = ref<ProcedureTemplate | null>(null)

// ── 删除确认对话框 ──
const deleteOpen = ref(false)
const deleting = ref(false)
const deleteTemplateId = ref('')
const deleteTemplateName = ref('')

// ── 数据加载 ──
async function loadTemplates() {
  loading.value = true
  error.value = null
  try {
    const params: TemplateListParams = {
      page: currentPage.value,
      size: PAGE_SIZE,
      sortBy: sortBy.value,
      order: order.value,
    }
    if (filterQ.value.trim()) params.q = filterQ.value.trim()

    const result = await memoryApi.listTemplates(params)
    items.value = result.items
    total.value = result.total
  } catch (e: any) {
    console.error('加载模板列表失败:', e)
    error.value = e?.message || '加载模板列表失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 0
  loadTemplates()
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadTemplates()
}

// 排序条件变化时重新加载
watch([sortBy, order], () => {
  currentPage.value = 0
  loadTemplates()
})

onMounted(() => loadTemplates())

// ── 详情面板 ──
async function openDetail(template: ProcedureTemplate) {
  detailOpen.value = true
  detailLoading.value = true
  detailTemplate.value = null

  try {
    detailTemplate.value = await memoryApi.getTemplate(template.templateId)
  } catch (e: any) {
    console.error('加载模板详情失败:', e)
  } finally {
    detailLoading.value = false
  }
}

// ── 删除模板 ──
function openDelete(id: string, name: string) {
  deleteTemplateId.value = id
  deleteTemplateName.value = name
  deleteOpen.value = true
}

async function handleDelete() {
  deleting.value = true
  try {
    await memoryApi.deleteTemplate(deleteTemplateId.value)
    deleteOpen.value = false
    detailOpen.value = false
    loadTemplates()
  } catch (e: any) {
    console.error('删除模板失败:', e)
    alert(e?.message || '删除模板失败')
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

function formatPercent(rate: number) {
  return (rate * 100).toFixed(1) + '%'
}
</script>

<template>
  <div class="space-y-4">
    <!-- 筛选栏 -->
    <div class="detail-card p-4">
      <div class="flex flex-wrap items-end gap-3">
        <!-- 关键词搜索 -->
        <div class="flex-1 min-w-[200px]">
          <label class="text-xs text-muted-foreground mb-1 block">关键词搜索</label>
          <div class="relative">
            <Search class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              v-model="filterQ"
              placeholder="按模板名称或描述搜索..."
              class="pl-9"
              @keydown.enter="handleSearch"
            />
          </div>
        </div>

        <!-- 排序字段 -->
        <div class="w-36">
          <label class="text-xs text-muted-foreground mb-1 block">排序</label>
          <Select v-model="sortBy">
            <SelectTrigger>
              <SelectValue placeholder="排序字段" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="opt in SORT_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>

        <!-- 排序方向 -->
        <div class="w-28">
          <label class="text-xs text-muted-foreground mb-1 block">方向</label>
          <Select v-model="order">
            <SelectTrigger>
              <SelectValue placeholder="方向" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="opt in ORDER_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
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
      <Button variant="outline" class="mt-4" @click="loadTemplates">
        <RotateCcw class="mr-1.5 size-4" />
        重试
      </Button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="items.length === 0" class="detail-card px-6 py-12 text-center">
      <p class="text-sm text-muted-foreground">暂无操作模板</p>
      <p class="mt-1 text-xs text-muted-foreground">操作模板会在与 Agent 交互过程中自动学习生成。</p>
    </div>

    <!-- 模板表格 -->
    <div v-else class="detail-card overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60">
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">名称</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">触发意图</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">成功率</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">使用次数</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
            <th class="px-4 py-3 text-center font-medium text-muted-foreground">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="tpl in items"
            :key="tpl.templateId"
            class="border-b border-border/40 cursor-pointer transition-colors hover:bg-muted/50"
            @click="openDetail(tpl)"
          >
            <td class="px-4 py-3 font-medium text-foreground max-w-[200px] truncate">
              {{ tpl.name || '-' }}
            </td>
            <td class="px-4 py-3 text-muted-foreground max-w-[200px] truncate">
              {{ tpl.triggerIntent || '-' }}
            </td>
            <td class="px-4 py-3 text-right tabular-nums">{{ formatPercent(tpl.successRate) }}</td>
            <td class="px-4 py-3 text-right tabular-nums">{{ tpl.useCount }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(tpl.createdAt) }}</td>
            <td class="px-4 py-3 text-center" @click.stop>
              <Button
                size="sm"
                variant="ghost"
                class="text-destructive hover:text-destructive"
                @click="openDelete(tpl.templateId, tpl.name)"
              >
                <Trash2 class="size-3.5" />
              </Button>
            </td>
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

    <!-- 详情 Sheet -->
    <Sheet v-model:open="detailOpen">
      <SheetContent class="overflow-y-auto p-6" style="width: 100%; max-width: 36rem;">
        <SheetHeader>
          <SheetTitle>{{ detailTemplate?.name || '模板详情' }}</SheetTitle>
          <SheetDescription>
            {{ detailTemplate?.triggerIntent ? `触发意图：${detailTemplate.triggerIntent}` : '' }}
          </SheetDescription>
        </SheetHeader>

        <!-- 详情加载中 -->
        <div v-if="detailLoading" class="mt-6 space-y-3">
          <Skeleton class="h-5 w-40" />
          <Skeleton class="h-4 w-full" />
          <Skeleton class="h-4 w-3/4" />
          <Skeleton class="h-20 w-full" />
        </div>

        <!-- 详情内容 -->
        <div v-else-if="detailTemplate" class="mt-6 space-y-5">
          <!-- 操作按钮 -->
          <div class="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              class="text-destructive hover:text-destructive"
              @click="openDelete(detailTemplate!.templateId, detailTemplate!.name)"
            >
              <Trash2 class="mr-1.5 size-3.5" />
              删除
            </Button>
          </div>

          <!-- 基本信息 -->
          <div class="grid grid-cols-2 gap-3 text-sm">
            <div>
              <span class="text-muted-foreground">名称</span>
              <p class="font-medium">{{ detailTemplate.name }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">触发意图</span>
              <p class="font-medium">{{ detailTemplate.triggerIntent || '-' }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">成功率</span>
              <p class="font-medium">{{ formatPercent(detailTemplate.successRate) }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">使用次数</span>
              <p class="font-medium">{{ detailTemplate.useCount }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">创建时间</span>
              <p class="font-medium">{{ formatDate(detailTemplate.createdAt) }}</p>
            </div>
            <div>
              <span class="text-muted-foreground">最后使用</span>
              <p class="font-medium">{{ detailTemplate.lastUsedAt ? formatDate(detailTemplate.lastUsedAt) : '-' }}</p>
            </div>
          </div>

          <!-- 描述 -->
          <div v-if="detailTemplate.description" class="text-sm">
            <span class="text-muted-foreground">描述</span>
            <p class="mt-1">{{ detailTemplate.description }}</p>
          </div>

          <!-- 步骤序列 -->
          <div class="text-sm">
            <span class="text-muted-foreground">步骤序列</span>
            <div class="mt-2 space-y-3">
              <div
                v-for="step in detailTemplate.steps"
                :key="step.stepIndex"
                class="rounded-md border border-border/60 p-3"
              >
                <div class="flex items-center justify-between mb-1.5">
                  <span class="rounded-md bg-primary/10 text-primary px-1.5 py-0.5 text-xs font-medium">
                    步骤 {{ step.stepIndex }}
                  </span>
                  <span v-if="step.toolName" class="text-xs text-muted-foreground">
                    工具：{{ step.toolName }}
                  </span>
                </div>
                <p class="text-sm font-medium">{{ step.action }}</p>
                <p v-if="step.expectedOutcome" class="mt-1 text-xs text-muted-foreground">
                  预期结果：{{ step.expectedOutcome }}
                </p>
                <div v-if="step.parameters && Object.keys(step.parameters).length > 0" class="mt-2">
                  <span class="text-xs text-muted-foreground">参数：</span>
                  <pre class="mt-1 rounded-md bg-muted p-2 text-xs overflow-x-auto">{{ JSON.stringify(step.parameters, null, 2) }}</pre>
                </div>
              </div>

              <div
                v-if="!detailTemplate.steps || detailTemplate.steps.length === 0"
                class="py-4 text-center text-muted-foreground"
              >
                暂无步骤
              </div>
            </div>
          </div>

          <!-- 变量 -->
          <div v-if="detailTemplate.variables && Object.keys(detailTemplate.variables).length > 0" class="text-sm">
            <span class="text-muted-foreground">变量</span>
            <div class="mt-2 rounded-md border border-border/60 overflow-hidden">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b border-border/60 bg-muted/30">
                    <th class="px-3 py-2 text-left font-medium text-muted-foreground">变量名</th>
                    <th class="px-3 py-2 text-left font-medium text-muted-foreground">值</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(val, key) in detailTemplate.variables"
                    :key="key"
                    class="border-b border-border/40"
                  >
                    <td class="px-3 py-2 font-mono text-xs">{{ key }}</td>
                    <td class="px-3 py-2 text-xs">{{ val }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 来源追踪 ID -->
          <div v-if="detailTemplate.sourceTraceIds && detailTemplate.sourceTraceIds.length > 0" class="text-sm">
            <span class="text-muted-foreground">来源追踪 ID</span>
            <div class="mt-1 flex flex-wrap gap-1.5">
              <span
                v-for="traceId in detailTemplate.sourceTraceIds"
                :key="traceId"
                class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground font-mono"
              >
                {{ traceId }}
              </span>
            </div>
          </div>
        </div>
      </SheetContent>
    </Sheet>

    <!-- 删除确认对话框 -->
    <Dialog v-model:open="deleteOpen">
      <DialogContent class="sm:max-w-[384px]">
        <DialogHeader>
          <DialogTitle>确认删除</DialogTitle>
          <DialogDescription>
            确定要删除模板「{{ deleteTemplateName }}」吗？删除后数据将无法恢复。
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
