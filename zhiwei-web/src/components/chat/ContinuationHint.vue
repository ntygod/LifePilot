<script setup lang="ts">
/**
 * 挂起恢复提示小卡片。
 *
 * <p>当上一轮助手消息处于 SUSPENDED 状态（latestSuspendedAssistant 非空）时，
 * 在 Composer 上方显示一张小卡片，告诉用户"这条回复会接着刚才继续"。
 * 取代旧版贴在输入框内的"继续中"装饰条，视觉更弱但语义更清晰。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { CornerDownRight, X } from 'lucide-vue-next'

defineProps<{
  title: string
  detail?: string | null
}>()

const emit = defineEmits<{
  dismiss: []
}>()
</script>

<template>
  <div class="continuation-hint" role="status">
    <CornerDownRight class="continuation-hint__icon size-4" />
    <div class="continuation-hint__body">
      <div class="continuation-hint__title">{{ title }}</div>
      <div v-if="detail" class="continuation-hint__detail">
        {{ detail }}
      </div>
    </div>
    <button
      type="button"
      class="continuation-hint__close"
      title="忽略继续提示"
      @click="emit('dismiss')"
    >
      <X class="size-3.5" />
    </button>
  </div>
</template>

<style scoped>
.continuation-hint {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px dashed hsl(from var(--primary) h s l / 0.45);
  background: hsl(from var(--primary) h s l / 0.04);
  font-size: 12px;
  color: var(--muted-foreground);
}

.continuation-hint__icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: hsl(from var(--primary) h s l / 0.9);
}

.continuation-hint__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.continuation-hint__title {
  color: var(--foreground);
  font-weight: 500;
}

.continuation-hint__detail {
  line-height: 1.5;
}

.continuation-hint__close {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  color: var(--muted-foreground);
  background: transparent;
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease;
}

.continuation-hint__close:hover {
  color: var(--foreground);
  background: var(--chat-action-hover-bg);
}
</style>
