<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import type { KnowledgeBase } from '@/types'

const store = useKnowledgeBaseStore()

// 创建对话框
const showCreate = ref(false)
const createForm = ref({ name: '', description: '' })

// 删除确认
const deleteTarget = ref<{ type: 'kb' | 'doc'; id: string; kbId?: string; name: string } | null>(null)

// 文件上传
const fileInput = ref<HTMLInputElement | null>(null)

onMounted(() => store.fetchList())

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
    createForm.value = { name: '', description: '' }
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

      <div v-if="store.loading" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="store.list.length === 0" class="text-sm text-muted-foreground">暂无知识库</div>
      <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <div
          v-for="kb in store.list"
          :key="kb.id"
          class="border border-border rounded-lg p-4 cursor-pointer hover:border-primary/50 transition-colors"
          @click="selectKb(kb)"
        >
          <div class="flex items-start justify-between">
            <h3 class="font-medium text-foreground truncate">{{ kb.name }}</h3>
            <button
              class="text-muted-foreground hover:text-destructive text-sm shrink-0 ml-2"
              title="删除"
              @click.stop="deleteTarget = { type: 'kb', id: kb.id, name: kb.name }"
            >×</button>
          </div>
          <p class="text-sm text-muted-foreground mt-1 line-clamp-2">{{ kb.description || '无描述' }}</p>
          <div class="flex items-center gap-3 mt-3 text-xs text-muted-foreground">
            <span>{{ kb.documentCount }} 篇文档</span>
            <span>{{ kb.totalChunks }} 个分块</span>
          </div>
          <div class="text-xs text-muted-foreground mt-1">
            创建于 {{ new Date(kb.createdAt).toLocaleDateString() }}
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
