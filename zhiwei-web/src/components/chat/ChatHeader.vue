<script setup lang="ts">
/**
 * 对话页顶部导航条（精简版）。
 *
 * <p>空态下整个 header 不渲染；对话态下保留左侧可编辑标题 + 右侧：
 * 快捷图标入口（后台任务，带角标显示数量） + `...` 菜单
 * （本次会话概览 / 当前会话设置）。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { ref, watch } from 'vue'
import {
  Activity,
  EllipsisVertical,
  Info,
  Pencil,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'

const props = defineProps<{
  /** 空态 = 不渲染 Header */
  isEmpty: boolean
  title: string
  /** 后台任务数量；> 0 时头部快捷按钮显示角标 */
  taskCount?: number
  /** 是否有活跃（进行中）后台任务，用于做一个脉冲动画提示 */
  hasActiveTask?: boolean
}>()

const emit = defineEmits<{
  rename: [nextTitle: string]
  openInfo: []
  openSettings: []
  openTasks: []
}>()

const isEditing = ref(false)
const editing = ref(props.title ?? '')

watch(() => props.title, next => {
  if (!isEditing.value) {
    editing.value = next ?? ''
  }
})

function startEdit() {
  if (props.isEmpty) return
  editing.value = props.title ?? ''
  isEditing.value = true
}

function cancelEdit() {
  editing.value = props.title ?? ''
  isEditing.value = false
}

function confirmEdit() {
  const next = editing.value.trim()
  isEditing.value = false
  if (!next) {
    editing.value = props.title ?? ''
    return
  }
  if (next !== props.title) {
    emit('rename', next)
  }
}
</script>

<template>
  <header v-if="!props.isEmpty" class="chat-header">
    <div class="chat-header__inner">
      <div class="chat-header__title-slot">
        <Input
          v-if="isEditing"
          v-model="editing"
          class="h-8 w-full max-w-[420px] bg-background/80 text-[var(--chat-text-title-md)] font-semibold"
          autofocus
          @blur="confirmEdit"
          @keyup.enter.stop="confirmEdit"
          @keyup.esc.stop="cancelEdit"
        />
        <button v-else type="button" class="chat-header__title" @click="startEdit">
          <span class="chat-header__title-text">{{ props.title || '新对话' }}</span>
          <Pencil class="chat-header__title-icon size-3.5" />
        </button>
      </div>

      <div class="chat-header__actions">
        <!-- 后台任务快捷入口（常驻，角标 / 脉冲仅在有任务时生效） -->
        <button
          type="button"
          class="chat-header__quick-btn"
          :class="{ 'chat-header__quick-btn--active': props.hasActiveTask }"
          title="后台任务"
          data-overlay-toggle
          @click="emit('openTasks')"
        >
          <Activity class="size-4" />
          <span v-if="(props.taskCount ?? 0) > 0" class="chat-header__badge">
            {{ props.taskCount }}
          </span>
        </button>

        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              class="size-8 rounded-full"
              aria-label="更多操作"
              data-overlay-toggle
            >
              <EllipsisVertical class="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" class="w-44" data-overlay-toggle>
            <DropdownMenuItem class="gap-2" @click="emit('openInfo')">
              <Info class="size-4" />
              本次会话概览
            </DropdownMenuItem>
            <DropdownMenuItem class="gap-2" @click="emit('openSettings')">
              <SlidersHorizontal class="size-4" />
              当前会话设置
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  </header>
</template>

<style scoped>
.chat-header {
  flex-shrink: 0;
  padding: 0.25rem var(--chat-gutter-x-desktop, 24px) 0;
}

.chat-header__inner {
  max-width: var(--chat-main-max-w, 720px);
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 4px;
}

.chat-header__title-slot {
  flex: 1;
  min-width: 0;
  display: flex;
}

.chat-header__title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 4px 6px;
  border-radius: 8px;
  background: transparent;
  border: none;
  font-size: var(--chat-text-title-md, 18px);
  font-weight: 600;
  line-height: 1.3;
  color: var(--foreground);
  cursor: text;
  transition: background 120ms ease;
}

.chat-header__title:hover {
  background: var(--chat-action-hover-bg);
}

.chat-header__title:hover .chat-header__title-icon {
  opacity: 1;
}

.chat-header__title-text {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-header__title-icon {
  flex-shrink: 0;
  opacity: 0;
  color: var(--muted-foreground);
  transition: opacity 160ms ease;
}

.chat-header__actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.chat-header__quick-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 999px;
  color: var(--muted-foreground);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease;
}

.chat-header__quick-btn:hover {
  color: var(--foreground);
  background: var(--chat-action-hover-bg);
}

/* 有活跃任务时图标轻微脉冲 */
.chat-header__quick-btn--active {
  color: hsl(from var(--primary) h s l / 0.9);
}

.chat-header__quick-btn--active::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 999px;
  border: 1px solid hsl(from var(--primary) h s l / 0.4);
  animation: quick-btn-pulse 1.6s ease-in-out infinite;
}

@keyframes quick-btn-pulse {
  0%, 100% {
    opacity: 0.6;
    transform: scale(1);
  }
  50% {
    opacity: 0.2;
    transform: scale(1.08);
  }
}

.chat-header__badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 14px;
  height: 14px;
  padding: 0 4px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  line-height: 14px;
  text-align: center;
  color: var(--primary-foreground);
  background: var(--primary);
  border: 1.5px solid var(--background);
  pointer-events: none;
}
</style>
