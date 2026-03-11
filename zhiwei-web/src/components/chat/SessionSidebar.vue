<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { ChatSession, KnowledgeBase } from '@/types'
import { Clock3, Database, MessageSquareText, PencilLine } from 'lucide-vue-next'
import InspectorRail from '@/components/layout/InspectorRail.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

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

watch(() => props.session?.title, value => {
  editTitle.value = value ?? ''
})

function handleBlur() {
  if (editTitle.value.trim()) {
    emit('updateTitle', editTitle.value.trim())
  }
}
</script>

<template>
  <InspectorRail
    title="会话信息"
    description="编辑当前对话标题，并查看时间、消息量和可用知识库。"
    @close="emit('close')"
  >
    <template #eyebrow>
      会话
    </template>

    <div class="space-y-4">
      <section class="detail-card p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-medium text-foreground">
          <PencilLine class="size-4 text-primary" />
          会话标题
        </div>
        <div class="space-y-2">
          <Label class="text-xs text-muted-foreground">标题</Label>
          <Input
            v-model="editTitle"
            class="h-9 text-sm"
            @blur="handleBlur"
          />
        </div>
      </section>

      <section class="grid gap-3">
        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Clock3 class="size-4 text-primary" />
            创建时间
          </div>
          <p class="text-sm text-muted-foreground">
            {{ session?.createdAt ? new Date(session.createdAt).toLocaleString() : '-' }}
          </p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <Clock3 class="size-4 text-primary" />
            最近更新
          </div>
          <p class="text-sm text-muted-foreground">
            {{ session?.updatedAt ? new Date(session.updatedAt).toLocaleString() : '-' }}
          </p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3">
          <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
            <MessageSquareText class="size-4 text-primary" />
            消息统计
          </div>
          <p class="text-sm text-muted-foreground">共 {{ messageCount }} 条消息</p>
        </div>
      </section>

      <section class="detail-card p-4">
        <div class="mb-3 flex items-center gap-2 text-sm font-medium text-foreground">
          <Database class="size-4 text-primary" />
          知识库
        </div>

        <div v-if="knowledgeBases.length > 0" class="space-y-2">
          <div
            v-for="kb in knowledgeBases"
            :key="kb.id"
            class="list-card flex items-center justify-between gap-3 px-3 py-2 text-sm"
          >
            <span class="min-w-0 truncate text-foreground">{{ kb.name }}</span>
            <RouterLink
              :to="{ name: 'knowledgeBases', query: { id: kb.id } }"
              class="shrink-0 text-xs font-medium text-primary transition-colors hover:text-primary/80"
            >
              查看
            </RouterLink>
          </div>
        </div>

        <p v-else class="text-sm text-muted-foreground">当前没有可用的知识库。</p>
      </section>
    </div>
  </InspectorRail>
</template>
