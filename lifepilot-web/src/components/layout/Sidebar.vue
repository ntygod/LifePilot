<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const route = useRoute()
const chatStore = useChatStore()

onMounted(() => {
  chatStore.loadSessions()
})

function selectSession(id: string) {
  chatStore.activeSessionId = id
  router.push('/')
}

async function handleDelete(id: string) {
  await chatStore.deleteSession(id)
}

function newChat() {
  chatStore.activeSessionId = null
  router.push('/')
}

const navItems = [
  { path: '/knowledge-bases', label: '知识库' },
  { path: '/skills', label: '技能' },
  { path: '/traces', label: '轨迹' },
  { path: '/workflows', label: '工作流' },
  { path: '/settings', label: '设置' },
]
</script>

<template>
  <aside class="w-[var(--sidebar-width)] border-r border-border bg-card flex flex-col h-full">
    <!-- 顶部标题 + 新建按钮 -->
    <div class="p-4 border-b border-border flex items-center justify-between">
      <h1 class="text-lg font-semibold text-foreground">LifePilot</h1>
      <button
        class="text-sm text-muted-foreground hover:text-foreground transition-colors"
        title="新建对话"
        @click="newChat"
      >
        +
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto p-2">
      <p v-if="chatStore.sessions.length === 0" class="text-sm text-muted-foreground p-2">
        暂无会话
      </p>
      <div
        v-for="session in chatStore.sessions"
        :key="session.id"
        class="group flex items-center gap-1 px-3 py-2 rounded-md text-sm cursor-pointer transition-colors"
        :class="chatStore.activeSessionId === session.id
          ? 'bg-accent text-accent-foreground'
          : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
        @click="selectSession(session.id)"
      >
        <span class="flex-1 truncate">{{ session.title || '新对话' }}</span>
        <button
          class="opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-destructive transition-opacity shrink-0"
          title="删除会话"
          @click.stop="handleDelete(session.id)"
        >
          ×
        </button>
      </div>
    </div>

    <!-- 底部导航 -->
    <div class="p-2 border-t border-border space-y-0.5">
      <router-link
        v-for="nav in navItems"
        :key="nav.path"
        :to="nav.path"
        class="block w-full text-left px-3 py-2 rounded-md text-sm transition-colors"
        :class="route.path === nav.path
          ? 'bg-accent text-accent-foreground'
          : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'"
      >{{ nav.label }}</router-link>
    </div>
  </aside>
</template>
