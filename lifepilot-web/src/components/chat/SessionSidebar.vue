<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { ChatSession, KnowledgeBase } from '@/types'
import { X } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'

const props = defineProps<{
  session: ChatSession | null
  knowledgeBases: KnowledgeBase[]
  messageCount: number
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'updateTitle', title: string): void
}>()

const editTitle = ref(props.session?.title ?? '')

watch(() => props.session?.title, v => { editTitle.value = v ?? '' })

function handleBlur() {
  if (editTitle.value.trim()) {
    emit('updateTitle', editTitle.value.trim())
  }
}
</script>

<template>
  <div class="hidden lg:flex w-80 border-l border-border bg-background text-sm flex-col">
    <div class="px-md py-sm border-b border-border flex items-center justify-between">
      <span class="font-medium text-foreground">会话信息</span>
      <button
        type="button"
        class="text-muted-foreground hover:text-foreground transition-colors"
        @click="emit('close')"
      >
        <X :size="16" />
      </button>
    </div>
    <div class="flex-1 overflow-y-auto px-md py-sm space-y-md">
      <!-- 会话名称 -->
      <div>
        <label class="text-xs font-medium text-muted-foreground mb-1 block">会话名称</label>
        <input
          v-model="editTitle"
          type="text"
          class="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm
                 focus:outline-none focus:ring-1 focus:ring-ring"
          @blur="handleBlur"
        />
      </div>

      <!-- 创建时间 -->
      <div>
        <label class="text-xs font-medium text-muted-foreground mb-1 block">创建时间</label>
        <p class="text-sm text-foreground">
          {{ session?.createdAt ? new Date(session.createdAt).toLocaleString() : '-' }}
        </p>
      </div>

      <!-- 更新时间 -->
      <div>
        <label class="text-xs font-medium text-muted-foreground mb-1 block">更新时间</label>
        <p class="text-sm text-foreground">
          {{ session?.updatedAt ? new Date(session.updatedAt).toLocaleString() : '-' }}
        </p>
      </div>

      <!-- 关联知识库 -->
      <div>
        <label class="text-xs font-medium text-muted-foreground mb-1 block">关联知识库</label>
        <div v-if="knowledgeBases.length > 0" class="space-y-1">
          <div
            v-for="kb in knowledgeBases"
            :key="kb.id"
            class="text-sm text-foreground flex items-center justify-between"
          >
            <span>{{ kb.name }}</span>
            <RouterLink
              :to="{ name: 'knowledgeBases', query: { id: kb.id } }"
              class="text-xs text-primary hover:underline"
            >
              查看
            </RouterLink>
          </div>
        </div>
        <p v-else class="text-sm text-muted-foreground">未关联知识库</p>
      </div>

      <!-- 消息统计 -->
      <div>
        <label class="text-xs font-medium text-muted-foreground mb-1 block">消息统计</label>
        <p class="text-sm text-foreground">共 {{ messageCount }} 条消息</p>
      </div>
    </div>
  </div>
</template>
