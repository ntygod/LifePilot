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
import { Clock3, CornerDownRight, MapPin, Play, Wrench, X } from 'lucide-vue-next'
import { computed } from 'vue'

const props = defineProps<{
  title: string
  detail?: string | null
  inputHint?: string | null
  checkpointLabel?: string | null
  checkpointDetail?: string | null
  nextActions?: string[] | null
  retainedContext?: string[] | null
  canResume?: boolean
  actionLabel?: string
  statusLabel?: string | null
  repairLabel?: string | null
  repairTitle?: string | null
}>()

const emit = defineEmits<{
  resume: []
  repair: []
  dismiss: []
}>()

const visibleNextActions = computed(() =>
  (props.nextActions ?? []).filter(Boolean).slice(0, 2),
)

const visibleRetainedContext = computed(() =>
  (props.retainedContext ?? []).filter(Boolean).slice(0, 3),
)
</script>

<template>
  <div class="continuation-hint" role="status">
    <CornerDownRight class="continuation-hint__icon size-4" />
    <div class="continuation-hint__body">
      <div class="continuation-hint__title">{{ title }}</div>
      <div v-if="detail" class="continuation-hint__detail">
        {{ detail }}
      </div>
      <div v-if="inputHint" class="continuation-hint__input" aria-label="输入续接提示">
        {{ inputHint }}
      </div>
      <div
        v-if="checkpointLabel || checkpointDetail"
        class="continuation-hint__checkpoint"
        aria-label="续接断点"
      >
        <MapPin class="continuation-hint__checkpoint-icon size-3.5" />
        <span v-if="checkpointLabel" class="continuation-hint__checkpoint-label">
          {{ checkpointLabel }}
        </span>
        <span v-if="checkpointDetail" class="continuation-hint__checkpoint-detail">
          {{ checkpointDetail }}
        </span>
      </div>
      <div
        v-if="visibleRetainedContext.length"
        class="continuation-hint__retained"
        aria-label="继续时保留"
      >
        <span class="continuation-hint__retained-label">继续时保留</span>
        <span
          v-for="item in visibleRetainedContext"
          :key="item"
          class="continuation-hint__retained-item"
        >
          {{ item }}
        </span>
      </div>
      <ol v-if="visibleNextActions.length" class="continuation-hint__plan" aria-label="续接计划">
        <li
          v-for="(action, index) in visibleNextActions"
          :key="action"
          class="continuation-hint__plan-item"
        >
          <span class="continuation-hint__plan-index">{{ index + 1 }}</span>
          <span class="continuation-hint__plan-text">{{ action }}</span>
        </li>
      </ol>
    </div>
    <div class="continuation-hint__actions">
      <button
        v-if="repairLabel"
        type="button"
        class="continuation-hint__repair"
        :title="repairTitle || repairLabel"
        :aria-label="repairTitle || repairLabel"
        @click="emit('repair')"
      >
        <Wrench class="size-3.5" />
        {{ repairLabel }}
      </button>
      <button
        v-if="canResume !== false"
        type="button"
        class="continuation-hint__resume"
        title="继续执行上一轮"
        :aria-label="actionLabel ? `继续执行上一轮：${actionLabel}` : '继续执行上一轮'"
        @click="emit('resume')"
      >
        <Play class="size-3.5" />
        {{ actionLabel || '继续' }}
      </button>
      <span v-else-if="statusLabel" class="continuation-hint__status">
        <Clock3 class="size-3.5" />
        {{ statusLabel }}
      </span>
    </div>
    <button
      type="button"
      class="continuation-hint__close"
      title="忽略继续提示"
      aria-label="忽略继续提示"
      @click="emit('dismiss')"
    >
      <X class="size-3.5" />
    </button>
  </div>
</template>

<style scoped>
.continuation-hint {
  display: flex;
  align-items: center;
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

.continuation-hint__input {
  margin-top: 1px;
  line-height: 1.5;
  color: hsl(from var(--primary) h s l / 0.92);
}

.continuation-hint__checkpoint {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 5px;
  margin-top: 3px;
  color: hsl(from var(--foreground) h s l / 0.78);
  line-height: 1.45;
}

.continuation-hint__checkpoint-icon {
  flex: 0 0 auto;
  margin-top: 1px;
  color: hsl(34 76% 36%);
}

.continuation-hint__checkpoint-label {
  flex: 0 0 auto;
  font-weight: 600;
  color: hsl(from var(--foreground) h s l / 0.86);
}

.continuation-hint__checkpoint-detail {
  min-width: 0;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
  overflow-wrap: anywhere;
}

.continuation-hint__retained {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
}

.continuation-hint__retained-label {
  color: hsl(from var(--muted-foreground) h s l / 0.85);
  font-size: 11px;
}

.continuation-hint__retained-item {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  max-width: 100%;
  padding: 1px 7px;
  border-radius: 999px;
  background: hsl(from var(--background) h s l / 0.72);
  color: hsl(from var(--foreground) h s l / 0.78);
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.continuation-hint__plan {
  display: grid;
  gap: 3px;
  margin: 2px 0 0;
  padding: 0;
  list-style: none;
}

.continuation-hint__plan-item {
  display: grid;
  grid-template-columns: 1rem minmax(0, 1fr);
  align-items: start;
  gap: 5px;
  line-height: 1.45;
}

.continuation-hint__plan-index {
  display: inline-flex;
  width: 1rem;
  height: 1rem;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.9);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 650;
}

.continuation-hint__plan-text {
  min-width: 0;
  overflow-wrap: anywhere;
}

.continuation-hint__actions {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.continuation-hint__resume,
.continuation-hint__repair {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background 120ms ease,
    border-color 120ms ease,
    color 120ms ease;
}

.continuation-hint__resume {
  background: hsl(from var(--primary) h s l / 0.12);
  color: hsl(from var(--primary) h s l / 0.95);
}

.continuation-hint__repair {
  border: 1px solid hsl(from var(--border) h s l / 0.72);
  background: hsl(from var(--background) h s l / 0.74);
  color: hsl(from var(--foreground) h s l / 0.82);
}

.continuation-hint__resume:hover {
  background: hsl(from var(--primary) h s l / 0.18);
}

.continuation-hint__repair:hover {
  border-color: hsl(from var(--primary) h s l / 0.38);
  background: hsl(from var(--primary) h s l / 0.08);
  color: var(--foreground);
}

.continuation-hint__resume:focus-visible,
.continuation-hint__repair:focus-visible,
.continuation-hint__close:focus-visible {
  outline: 2px solid hsl(from var(--primary) h s l / 0.35);
  outline-offset: 2px;
}

.continuation-hint__status {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  background: hsl(from var(--muted) h s l / 0.45);
  color: hsl(from var(--muted-foreground) h s l / 0.95);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
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
