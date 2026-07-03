<script setup lang="ts">
import type { Component } from 'vue'
import { computed } from 'vue'
import {
  BellRing,
  BookOpen,
  Brain,
  CalendarClock,
  Code2,
  FileText,
  GitBranch,
  Globe2,
  MousePointerClick,
  Paperclip,
  Sparkles,
  Terminal,
  Wrench,
} from 'lucide-vue-next'

interface ActiveCapability {
  id: string
  label: string
  reason?: string
  kind?: 'tool' | 'skill'
  outputs?: Array<'text' | 'file' | 'a2ui' | 'memory' | 'notification' | 'task'>
}

const props = defineProps<{
  compact?: boolean
  activeCapabilities?: ActiveCapability[]
}>()

const activeCapabilities = computed<ActiveCapability[]>(() =>
  (props.activeCapabilities ?? [])
    .filter(item => item.id && item.label)
    .slice(0, 6),
)

function activeIcon(item: ActiveCapability): Component {
  const id = item.id
  if (id === 'chat.intent') return Sparkles
  if (id === 'memory') return Brain
  if (id === 'knowledge' || id.startsWith('transcript.')) return BookOpen
  if (id === 'daily-manager' || id === 'proactive' || id === 'notify') return BellRing
  if (id === 'cron' || id.startsWith('schedule.')) return CalendarClock
  if (id === 'code' || id === 'code-assistant') return Code2
  if (id === 'research-assistant' || id.startsWith('web.')) return Globe2
  if (id === 'tool.execution' || id === 'tools') return Wrench
  if (id.startsWith('git.')) return GitBranch
  if (id === 'browser') return MousePointerClick
  if (id.startsWith('shell.')) return Terminal
  if (id.startsWith('file.')) return id === 'file.attach' ? Paperclip : FileText
  if (item.kind === 'skill') return Sparkles
  return Wrench
}
</script>

<template>
  <div
    v-if="activeCapabilities.length > 0"
    class="capability-hints"
    :class="{
      'capability-hints--compact': compact,
    }"
    aria-live="polite"
  >
    <div class="capability-hints__label">
      <span class="capability-hints__dot" aria-hidden="true" />
      知微正在调用能力
    </div>
    <div class="capability-hints__active-list">
      <span
        v-for="item in activeCapabilities"
        :key="item.id"
        class="capability-hints__active-item"
        :class="{ 'capability-hints__active-item--skill': item.kind === 'skill' }"
        :title="item.reason"
        :aria-label="item.reason ? `${item.label}：${item.reason}` : item.label"
      >
        <component :is="activeIcon(item)" class="capability-hints__icon" />
        <span class="capability-hints__title">{{ item.label }}</span>
      </span>
    </div>
  </div>
</template>

<style scoped>
.capability-hints {
  width: 100%;
  max-width: var(--chat-main-max-w, 720px);
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  overflow: hidden;
}

.capability-hints__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 500;
  color: var(--muted-foreground);
}

.capability-hints__dot {
  display: inline-flex;
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: var(--primary);
}

.capability-hints__active-list {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow-x: auto;
  padding-bottom: 1px;
}

.capability-hints__active-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 160px;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  border-radius: 999px;
  background: hsl(from var(--muted) h s l / 0.34);
  padding: 3px 7px;
  color: var(--muted-foreground);
}

.capability-hints__active-item--skill {
  background: hsl(from var(--accent) h s l / 0.42);
}

.capability-hints__icon {
  width: 13px;
  height: 13px;
  flex: 0 0 auto;
  color: var(--primary);
}

.capability-hints__title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.25;
}

.capability-hints--compact {
  max-width: none;
}

.capability-hints--compact .capability-hints__active-item {
  flex: 0 0 auto;
}

@media (max-width: 720px) {
  .capability-hints {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
  }
}
</style>
