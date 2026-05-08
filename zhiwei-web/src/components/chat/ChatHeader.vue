<script setup lang="ts">
/**
 * 对话页顶部导航条（精简版）。
 *
 * <p>空态下整个 header 不渲染（仅保留一条最小 spacing 占位）；对话态下保留
 * 左侧可编辑标题 + 右侧一个 `...` 菜单（仅"本次会话概览" / "当前会话设置"
 * 两项）。停止生成已迁到 ComposerStopPill，不再出现在头部。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { ref, watch } from 'vue'
import { EllipsisVertical, Info, Pencil, SlidersHorizontal } from 'lucide-vue-next'
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
}>()

const emit = defineEmits<{
  rename: [nextTitle: string]
  openInfo: []
  openSettings: []
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

      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            class="size-8 rounded-full"
            aria-label="更多操作"
          >
            <EllipsisVertical class="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" class="w-44">
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
</style>
