<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { knowledgeBaseApi } from '@/api/client'
import type { KnowledgeBase } from '@/types'
import { Search, Filter, Tag, Edit2, X } from 'lucide-vue-next'

const store = useKnowledgeBaseStore()

// 创建对话框
const showCreate = ref(false)
const createForm = ref({ name: '', description: '', tags: [] as string[] })

// 编辑对话框
const editingKb = ref<KnowledgeBase | null>(null)
const editForm = ref({ description: '', tags: [] as string[] })

// 删除确认
const deleteTarget = ref<{ type: 'kb' | 'doc'; id: string; kbId?: string; name: string } | null>(null)

// 文件上传
const fileInput = ref<HTMLInputElement | null>(null)

// 搜索和过滤
const searchQuery = ref('')
const selectedTags = ref<string[]>([])
const timeRange = ref<'all' | '7d' | '30d' | 'custom'>('all')
const showFilters = ref(false)

// 所有标签（从知识库列表中提取）
const allTags = computed(() => {
  const tags = new Set<string>()
  store.list.forEach(kb => {
    // TODO: 如果后端支持tags字段，从kb.tags中提取
    // 暂时使用空数组
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
    // result = result.filter(kb => selectedTags.value.some(tag => kb.tags?.includes(tag)))
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
  store.current = kb
  store.fetchDocuments(kb.id)
}

function backToList() {
  store.current = null
  store.documents = []
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
    // TODO: 调用后端API更新知识库描述和标签
    // await knowledgeBaseApi.update(editingKb.value.id, {
    //   description: editForm.value.description,
    //   tags: editForm.value.tags
    // })
    await store.fetchList()
    editingKb.value = null
  } catch (error) {
    console.error('更新知识库失败:', error)
  }
}

function triggerUpload() {
  fileInput.value?.click()
}

async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !store.current) return
  await store.uploadDocument(store.current.id, file)
  input.value = ''
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

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function toggleTag(tag: string) {
  const index = selectedTags.value.indexOf(tag)
  if (index === -1) {
    selectedTags.value.push(tag)
  } else {
    selectedTags.value.splice(index, 1)
  }
}

const statusMap: Record<string, { label: string; class: string }> = {
  PENDING: { label: '等待中', class: 'bg-yellow-100 text-yellow-800' },
  PROCESSING: { label: '处理中', class: 'bg-blue-100 text-blue-800' },
  COMPLETED: { label: '已完成', class: 'bg-green-100 text-green-800' },
  ERROR: { label: '失败', class: 'bg-red-100 text-red-800' },
}
</script>

<template>
  <div class="flex flex-col h-full p-6">
    <!-- 错误提示 -->
    <div v-if="store.error" class="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
      {{ store.error }}
    </div>

    <!-- 知识库列表视图 -->
    <template v-if="!store.current">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-xl font-semibold text-foreground">知识库管理</h2>
        <button
          class="inline-flex items-center rounded-md text-sm font-medium h-9 px-4 bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          @click="showCreate = true"
        >
          新建知识库
        </button>
      </div>

      <!-- 搜索和过滤栏 -->
      <div class="mb-4 space-y-2">
        <div class="flex items-center gap-2">
          <div class="relative flex-1">
            <Search class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" :size="16" />
            <input
              v-model="searchQuery"
              type="search"
              placeholder="搜索知识库（名称或描述）…"
              class="w-full h-9 pl-8 pr-3 rounded-md border border-input bg-background text-sm
                     placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <button
            class="inline-flex items-center gap-1 h-9 px-3 rounded-md border border-input bg-background
                   hover:bg-accent transition-colors"
            :class="showFilters ? 'bg-accent' : ''"
            @click="showFilters = !showFilters"
          >
            <Filter :size="16" />
            <span class="text-sm">过滤</span>
          </button>
        </div>

        <!-- 过滤选项 -->
        <div v-if="showFilters" class="p-3 rounded-md border border-border bg-muted/30 space-y-3">
          <!-- 标签过滤 -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">标签</label>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="tag in allTags"
                :key="tag"
                class="inline-flex items-center gap-1 px-2 py-1 rounded text-xs border border-border
                       transition-colors"
                :class="selectedTags.includes(tag)
                  ? 'bg-primary text-primary-foreground border-primary'
                  : 'bg-background hover:bg-accent'"
                @click="toggleTag(tag)"
              >
                <Tag :size="12" />
                {{ tag }}
              </button>
            </div>
          </div>

          <!-- 时间范围过滤 -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">更新时间</label>
            <select
              v-model="timeRange"
              class="w-full h-8 rounded-md border border-input bg-background px-2 text-xs
                     focus:outline-none focus:ring-1 focus:ring-ring"
            >
              <option value="all">全部</option>
              <option value="7d">最近7天</option>
              <option value="30d">最近30天</option>
            </select>
          </div>
        </div>
      </div>

      <div v-if="store.loading" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="filteredKbs.length === 0" class="text-sm text-muted-foreground">
        {{ searchQuery || selectedTags.length > 0 || timeRange !== 'all' ? '没有找到匹配的知识库' : '暂无知识库' }}
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <div
          v-for="kb in filteredKbs"
          :key="kb.id"
          class="border border-border rounded-lg p-4 cursor-pointer hover:border-primary/50 transition-colors group"
          @click="selectKb(kb)"
        >
          <div class="flex items-start justify-between">
            <h3 class="font-medium text-foreground truncate flex-1">{{ kb.name }}</h3>
            <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
              <button
                class="text-muted-foreground hover:text-foreground p-1"
                title="编辑"
                @click.stop="startEdit(kb)"
              >
                <Edit2 :size="14" />
              </button>
              <button
                class="text-muted-foreground hover:text-destructive p-1"
                title="删除"
                @click.stop="deleteTarget = { type: 'kb', id: kb.id, name: kb.name }"
              >
                <X :size="14" />
              </button>
            </div>
          </div>
          <p class="text-sm text-muted-foreground mt-1 line-clamp-2">{{ kb.description || '无描述' }}</p>
          <div class="flex items-center gap-3 mt-3 text-xs text-muted-foreground">
            <span>{{ kb.documentCount }} 篇文档</span>
            <span>{{ kb.totalChunks }} 个分块</span>
          </div>
          <div class="text-xs text-muted-foreground mt-1">
            更新于 {{ new Date(kb.updatedAt).toLocaleDateString() }}
          </div>
        </div>
      </div>
    </template>

    <!-- 文档列表视图 -->
    <template v-else>
      <div class="flex items-center gap-3 mb-6">
        <button
          class="text-sm text-muted-foreground hover:text-foreground transition-colors"
          @click="backToList"
        >← 返回</button>
        <h2 class="text-xl font-semibold text-foreground">{{ store.current.name }}</h2>
        <button
          class="ml-auto inline-flex items-center rounded-md text-sm font-medium h-9 px-4 bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          @click="triggerUpload"
        >
          上传文档
        </button>
        <input ref="fileInput" type="file" accept=".pdf,.docx,.md,.txt" class="hidden" @change="handleFileChange" />
      </div>

      <div v-if="store.loading" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="store.documents.length === 0" class="text-sm text-muted-foreground">暂无文档，点击上方按钮上传</div>
      <div v-else class="space-y-2">
        <div
          v-for="doc in store.documents"
          :key="doc.id"
          class="flex items-center gap-4 border border-border rounded-md p-3"
        >
          <div class="flex-1 min-w-0">
            <div class="font-medium text-sm text-foreground truncate">{{ doc.fileName }}</div>
            <div class="flex items-center gap-3 text-xs text-muted-foreground mt-1">
              <span>{{ formatSize(doc.fileSize) }}</span>
              <span>{{ doc.chunkCount }} 个分块</span>
            </div>
          </div>
          <span
            class="text-xs px-2 py-0.5 rounded-full shrink-0"
            :class="statusMap[doc.status]?.class ?? 'bg-gray-100 text-gray-800'"
          >
            {{ statusMap[doc.status]?.label ?? doc.status }}
          </span>
          <button
            class="text-muted-foreground hover:text-destructive text-sm shrink-0"
            title="删除"
            @click="deleteTarget = { type: 'doc', id: doc.id, kbId: store.current!.id, name: doc.fileName }"
          >×</button>
        </div>
      </div>
    </template>

    <!-- 创建对话框 -->
    <div v-if="showCreate" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="showCreate = false">
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-md shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-4">新建知识库</h3>
        <form class="space-y-4" @submit.prevent="handleCreate">
          <div class="space-y-1.5">
            <label for="kb-name" class="text-sm font-medium">名称</label>
            <input
              id="kb-name"
              v-model="createForm.name"
              type="text"
              placeholder="输入知识库名称"
              class="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            />
          </div>
          <div class="space-y-1.5">
            <label for="kb-desc" class="text-sm font-medium">描述</label>
            <textarea
              id="kb-desc"
              v-model="createForm.description"
              rows="3"
              placeholder="输入知识库描述"
              class="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring resize-none"
            />
          </div>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
              @click="showCreate = false"
            >取消</button>
            <button
              type="submit"
              class="h-9 px-4 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >创建</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 编辑对话框 -->
    <div v-if="editingKb" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="editingKb = null">
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-md shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-4">编辑知识库</h3>
        <form class="space-y-4" @submit.prevent="handleUpdate">
          <div>
            <label class="text-sm font-medium text-foreground mb-1 block">描述</label>
            <textarea
              v-model="editForm.description"
              rows="3"
              placeholder="输入知识库描述"
              class="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring resize-none"
            />
          </div>
          <div>
            <label class="text-sm font-medium text-foreground mb-1 block">标签</label>
            <input
              :value="editForm.tags.join(', ')"
              type="text"
              placeholder="输入标签，用逗号分隔"
              class="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              @input="editForm.tags = ($event.target as HTMLInputElement).value.split(',').map(t => t.trim()).filter(t => t)"
            />
            <p class="text-xs text-muted-foreground mt-1">标签功能待后端支持</p>
          </div>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
              @click="editingKb = null"
            >取消</button>
            <button
              type="submit"
              class="h-9 px-4 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >保存</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 删除确认对话框 -->
    <div v-if="deleteTarget" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="deleteTarget = null">
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-sm shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-2">确认删除</h3>
        <p class="text-sm text-muted-foreground mb-4">
          确定要删除{{ deleteTarget.type === 'kb' ? '知识库' : '文档' }}「{{ deleteTarget.name }}」吗？此操作不可撤销。
        </p>
        <div class="flex justify-end gap-2">
          <button
            class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
            @click="deleteTarget = null"
          >取消</button>
          <button
            class="h-9 px-4 rounded-md text-sm bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
            @click="confirmDelete"
          >删除</button>
        </div>
      </div>
    </div>
  </div>
</template>
