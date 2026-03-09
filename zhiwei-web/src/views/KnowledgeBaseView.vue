<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { knowledgeBaseApi } from '@/api/client'
import type { KnowledgeBase } from '@/types'
import { Search, Filter, Tag, Edit2, X } from 'lucide-vue-next'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/textarea'

const store = useKnowledgeBaseStore()
const router = useRouter()

// 创建对话框
const showCreate = ref(false)
const createForm = ref({ name: '', description: '', tags: [] as string[] })

// 编辑对话框
const editingKb = ref<KnowledgeBase | null>(null)
const editForm = ref({ description: '', tags: [] as string[] })

// 删除确认
const deleteTarget = ref<{ type: 'kb' | 'doc'; id: string; kbId?: string; name: string } | null>(null)

// 搜索和过滤
const searchQuery = ref('')
const selectedTags = ref<string[]>([])
const timeRange = ref<string>('all')
const showFilters = ref(false)

// 所有标签（从知识库列表中提取）
const allTags = computed(() => {
  const tags = new Set<string>()
  store.list.forEach(kb => {
    // TODO: 如果后端支持tags字段，从kb.tags中提取
  })
  return Array.from(tags)
})

onMounted(() => store.fetchList())

// 过滤后的知识库列表
const filteredKbs = computed(() => {
  let result = [...store.list]

  // 搜索过滤
  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(kb => {
      const nameMatch = kb.name.toLowerCase().includes(query)
      const descMatch = kb.description?.toLowerCase().includes(query)
      return nameMatch || descMatch
    })
  }

  // 标签过滤
  if (selectedTags.value.length > 0) {
    // TODO: 如果后端支持tags字段，使用kb.tags进行过滤
  }

  // 时间范围过滤
  if (timeRange.value !== 'all') {
    const now = Date.now()
    const days = timeRange.value === '7d' ? 7 : 30
    const cutoff = now - days * 24 * 60 * 60 * 1000
    result = result.filter(kb => {
      const updated = new Date(kb.updatedAt).getTime()
      return updated >= cutoff
    })
  }

  return result
})

function selectKb(kb: KnowledgeBase) {
  router.push(`/knowledge-bases/${kb.id}`)
}

async function handleCreate() {
  if (!createForm.value.name.trim()) return
  const kb = await store.create(createForm.value)
  if (kb) {
    showCreate.value = false
    createForm.value = { name: '', description: '', tags: [] }
  }
}

function startEdit(kb: KnowledgeBase) {
  editingKb.value = kb
  editForm.value = {
    description: kb.description || '',
    tags: [] // TODO: 从kb.tags获取
  }
}

async function handleUpdate() {
  if (!editingKb.value) return
  try {
    await store.fetchList()
    editingKb.value = null
  } catch (error) {
    console.error('更新知识库失败:', error)
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  if (deleteTarget.value.type === 'kb') {
    await store.remove(deleteTarget.value.id)
  } else if (deleteTarget.value.kbId) {
    await store.removeDocument(deleteTarget.value.kbId, deleteTarget.value.id)
  }
  deleteTarget.value = null
}

// 删除确认对话框状态
const showDeleteConfirm = computed({
  get: () => deleteTarget.value !== null,
  set: (val: boolean) => { if (!val) deleteTarget.value = null }
})

function toggleTag(tag: string) {
  const index = selectedTags.value.indexOf(tag)
  if (index === -1) {
    selectedTags.value.push(tag)
  } else {
    selectedTags.value.splice(index, 1)
  }
}

</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- 错误提示（非致命，列表已有数据时展示横幅） -->
        <div
          v-if="store.error && store.list.length > 0"
          class="mb-md px-md py-sm rounded-md bg-destructive/10 text-destructive text-sm flex items-center justify-between"
        >
          <span>{{ store.error }}</span>
          <Button variant="ghost" size="icon-sm" @click="store.error = null">
            <X :size="16" />
          </Button>
        </div>

        <!-- 知识库列表视图 -->
        <div>
          <div class="flex items-center justify-between mb-lg gap-sm">
            <h2 class="text-2xl font-semibold text-foreground leading-tight">
              知识库管理
            </h2>
            <Button @click="showCreate = true">新建知识库</Button>
          </div>

          <!-- 搜索和过滤栏 -->
          <div class="mb-md space-y-sm">
            <div class="flex flex-wrap items-center gap-sm">
              <div class="relative flex-1 min-w-[220px]">
                <Search
                  class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground"
                />
                <Input
                  v-model="searchQuery"
                  type="search"
                  placeholder="搜索知识库（名称或描述）…"
                  class="pl-8"
                />
              </div>
              <Button
                variant="outline"
                :class="showFilters ? 'bg-accent text-accent-foreground' : ''"
                @click="showFilters = !showFilters"
              >
                <Filter :size="16" />
                <span>过滤</span>
              </Button>
            </div>

            <!-- 过滤选项 -->
            <div
              v-if="showFilters"
              class="px-md py-sm rounded-lg border border-border bg-muted/40 space-y-md"
            >
              <!-- 标签过滤 -->
              <div>
                <Label class="text-xs text-muted-foreground mb-xs block">标签</Label>
                <div class="flex flex-wrap gap-sm">
                  <Badge
                    v-for="tag in allTags"
                    :key="tag"
                    :variant="selectedTags.includes(tag) ? 'default' : 'outline'"
                    class="cursor-pointer"
                    @click="toggleTag(tag)"
                  >
                    <Tag :size="12" />
                    {{ tag }}
                  </Badge>
                </div>
              </div>

              <!-- 时间范围过滤 -->
              <div>
                <Label class="text-xs text-muted-foreground mb-xs block">更新时间</Label>
                <Select v-model="timeRange">
                  <SelectTrigger class="w-full h-8 text-xs">
                    <SelectValue placeholder="全部" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">全部</SelectItem>
                    <SelectItem value="7d">最近7天</SelectItem>
                    <SelectItem value="30d">最近30天</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>

          <!-- Skeleton 加载占位符 -->
          <div v-if="store.loading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md">
            <Card v-for="i in 6" :key="i" class="overflow-hidden">
              <CardHeader class="pb-2">
                <Skeleton class="h-5 w-3/4" />
              </CardHeader>
              <CardContent class="pb-2">
                <Skeleton class="h-4 w-full mb-2" />
                <Skeleton class="h-4 w-2/3" />
              </CardContent>
              <CardFooter>
                <Skeleton class="h-3 w-1/2" />
              </CardFooter>
            </Card>
          </div>

          <!-- 错误状态 -->
          <ErrorState
            v-else-if="store.error && filteredKbs.length === 0"
            :description="store.error"
            action-label="重试"
            :show-action="true"
            @action="store.fetchList()"
          />

          <!-- 空状态引导 -->
          <EmptyState
            v-else-if="filteredKbs.length === 0 && !searchQuery && selectedTags.length === 0 && timeRange === 'all'"
            icon="📚"
            title="暂无知识库"
            description="创建知识库后，可以上传文档并进行语义检索"
            action-label="新建知识库"
            :show-action="true"
            @action="showCreate = true"
          />

          <!-- 搜索/过滤无结果 -->
          <EmptyState
            v-else-if="filteredKbs.length === 0"
            icon="🔍"
            title="没有找到匹配的知识库"
            description="尝试调整搜索关键词或过滤条件"
          />

          <div
            v-else
            class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-md"
          >
            <Card
              v-for="kb in filteredKbs"
              :key="kb.id"
              class="list-card cursor-pointer group"
              @click="selectKb(kb)"
            >
              <CardHeader class="pb-2">
                <div class="flex items-start justify-between gap-sm">
                  <CardTitle class="text-sm leading-snug truncate flex-1">
                    {{ kb.name }}
                  </CardTitle>
                  <div class="flex items-center gap-xs opacity-0 group-hover:opacity-100 transition-opacity">
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      class="size-7"
                      title="编辑"
                      @click.stop="startEdit(kb)"
                    >
                      <Edit2 :size="14" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      class="size-7 text-muted-foreground hover:text-destructive hover:bg-destructive/10"
                      title="删除"
                      @click.stop="deleteTarget = { type: 'kb', id: kb.id, name: kb.name }"
                    >
                      <X :size="14" />
                    </Button>
                  </div>
                </div>
              </CardHeader>
              <CardContent class="pb-2">
                <p class="text-sm text-muted-foreground line-clamp-2 leading-normal">
                  {{ kb.description || '无描述' }}
                </p>
                <div class="flex items-center gap-md mt-sm text-xs text-muted-foreground">
                  <span>{{ kb.documentCount }} 篇文档</span>
                  <span>{{ kb.totalChunks }} 个分块</span>
                </div>
              </CardContent>
              <CardFooter class="pt-0">
                <div class="text-xs text-muted-foreground">
                  更新于 {{ new Date(kb.updatedAt).toLocaleDateString() }}
                </div>
              </CardFooter>
            </Card>
          </div>
        </div>
      </div>
    </div>

    <!-- 创建对话框 -->
    <Dialog v-model:open="showCreate">
      <DialogContent class="sm:max-w-[448px]">
        <DialogHeader>
          <DialogTitle>新建知识库</DialogTitle>
          <DialogDescription>创建一个新的知识库来管理文档和语义检索</DialogDescription>
        </DialogHeader>
        <form class="space-y-4" @submit.prevent="handleCreate">
          <div class="space-y-1.5">
            <Label for="kb-name">名称</Label>
            <Input
              id="kb-name"
              v-model="createForm.name"
              placeholder="输入知识库名称"
            />
          </div>
          <div class="space-y-1.5">
            <Label for="kb-desc">描述</Label>
            <Textarea
              id="kb-desc"
              v-model="createForm.description"
              :rows="3"
              placeholder="输入知识库描述"
              class="resize-none"
            />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" @click="showCreate = false">取消</Button>
            <Button type="submit">创建</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- 编辑对话框 -->
    <Dialog :open="!!editingKb" @update:open="(val: boolean) => { if (!val) editingKb = null }">
      <DialogContent class="sm:max-w-[448px]">
        <DialogHeader>
          <DialogTitle>编辑知识库</DialogTitle>
          <DialogDescription>修改知识库的描述和标签信息</DialogDescription>
        </DialogHeader>
        <form class="space-y-4" @submit.prevent="handleUpdate">
          <div class="space-y-1.5">
            <Label>描述</Label>
            <Textarea
              v-model="editForm.description"
              :rows="3"
              placeholder="输入知识库描述"
              class="resize-none"
            />
          </div>
          <div class="space-y-1.5">
            <Label>标签</Label>
            <Input
              :model-value="editForm.tags.join(', ')"
              placeholder="输入标签，用逗号分隔"
              @update:model-value="editForm.tags = ($event as string).split(',').map((t: string) => t.trim()).filter((t: string) => t)"
            />
            <p class="text-xs text-muted-foreground">标签功能待后端支持</p>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" @click="editingKb = null">取消</Button>
            <Button type="submit">保存</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- 删除确认对话框 -->
    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="确认删除"
      :message="deleteTarget ? `确定要删除${deleteTarget.type === 'kb' ? '知识库' : '文档'}「${deleteTarget.name}」吗？此操作不可撤销。` : ''"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
