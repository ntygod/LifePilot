<script setup lang="ts">
import { computed } from 'vue'
import { Brain, ChevronRight, EyeOff } from 'lucide-vue-next'
import type { SourceSummary } from '@/types'
import {
  buildMemoryChangeImpact,
  buildMemorySourceExplanation,
  buildMemoryTrustText,
  isDeleteMemoryChange,
  memorySourceInspectableNoun,
} from '@/utils/memorySource'

const props = withDefaults(defineProps<{
  source: SourceSummary
  index?: number
  variant?: 'source' | 'change'
}>(), {
  index: 0,
  variant: 'source',
})

const emit = defineEmits<{
  inspect: [source: SourceSummary]
}>()

const typeLabel = computed(() => {
  const value = props.source.extra?.entityTypeLabel
  const label = typeof value === 'string' && value.trim() ? value : '记忆'
  const operation = props.source.extra?.operationLabel
  if (props.variant === 'change' && typeof operation === 'string' && operation.trim()) {
    return `${operation} · ${label}`
  }
  return label
})

const description = computed(() => {
  const value = props.source.extra?.description
  return typeof value === 'string' && value.trim() ? value : ''
})

const evidenceExcerpt = computed(() => {
  const value = props.source.extra?.evidenceExcerpt
  return typeof value === 'string' && value.trim() ? value.trim() : ''
})

const contextLabel = computed(() => {
  const value = props.source.extra?.sourceKindLabel
  return typeof value === 'string' && value.trim() ? value.trim() : ''
})

const usageReason = computed(() => {
  const value = props.source.extra?.usageReason
  return typeof value === 'string' && value.trim() ? value.trim() : ''
})

const sourceImpact = computed(() => {
  const value = props.source.extra?.usageImpact
  return typeof value === 'string' && value.trim() ? value.trim() : ''
})

function truncateVisibleText(value: string, maxLength = 18) {
  return value.length > maxLength ? `${value.slice(0, maxLength - 1)}…` : value
}

const visibleReason = computed(() => {
  if (props.variant === 'change') {
    if (evidenceExcerpt.value) {
      return `因「${truncateVisibleText(evidenceExcerpt.value)}」`
    }
    if (description.value) {
      return truncateVisibleText(description.value, 22)
    }
    return ''
  }

  if (usageReason.value) {
    return `因「${truncateVisibleText(usageReason.value, 20)}」`
  }
  if (evidenceExcerpt.value) {
    return `来自「${truncateVisibleText(evidenceExcerpt.value, 18)}」`
  }
  if (description.value) {
    return `贴合${truncateVisibleText(typeLabel.value, 8)}`
  }
  return ''
})

const explanation = computed(() => buildMemorySourceExplanation(props.source, props.variant))

const trustText = computed(() => buildMemoryTrustText(props.source))

const impactText = computed(() =>
  props.variant === 'change' ? buildMemoryChangeImpact(props.source) : sourceImpact.value,
)

const inspectNoun = computed(() => memorySourceInspectableNoun(props.source))

const isDeletedChange = computed(() => props.variant === 'change' && isDeleteMemoryChange(props.source))

const sourceIcon = computed(() => isDeletedChange.value ? EyeOff : Brain)

const inspectActionText = computed(() =>
  isDeletedChange.value
    ? `点击查看这条${inspectNoun.value}为什么被忘记`
    : `点击查看和编辑这条${inspectNoun.value}`,
)

const visibleImpact = computed(() => {
  if (!impactText.value) return ''
  const parts = impactText.value.split('；').map(item => item.trim()).filter(Boolean)
  const typeImpact = parts.find(item => item.startsWith('会'))
  const timeImpact = parts.find(item => ['长期生效', '短期参考', '本轮优先参考'].includes(item))
  return truncateVisibleText(typeImpact || timeImpact || parts[0], 16)
})

const tooltip = computed(() => {
  const parts = [`${typeLabel.value}: ${props.source.name}`]
  parts.push(`为什么出现：${explanation.value}`)
  if (impactText.value) {
    parts.push(`后续影响：${impactText.value}`)
  }
  if (contextLabel.value) {
    parts.push(`上下文：${contextLabel.value}`)
  }
  if (evidenceExcerpt.value) {
    parts.push(`依据：${evidenceExcerpt.value}`)
  }
  if (description.value) {
    parts.push(`说明：${description.value}`)
  }
  if (trustText.value) {
    parts.push(`可信度：${trustText.value}`)
  }
  parts.push(inspectActionText.value)
  return parts.join('\n')
})
</script>

<template>
  <button
    type="button"
    class="memory-source-tag"
    :class="{
      'memory-source-tag--change': variant === 'change',
      'memory-source-tag--deleted': isDeletedChange,
    }"
    :title="tooltip"
    :aria-label="tooltip"
    @click="emit('inspect', props.source)"
  >
    <span class="memory-source-index">{{ String(props.index + 1).padStart(2, '0') }}</span>
    <component :is="sourceIcon" class="memory-source-icon" data-test="memory-source-state-icon" />
    <span class="memory-source-name">{{ props.source.name }}</span>
    <span class="memory-source-type">{{ typeLabel }}</span>
    <span v-if="visibleReason" class="memory-source-reason">{{ visibleReason }}</span>
    <span v-if="visibleImpact" class="memory-source-impact">{{ visibleImpact }}</span>
    <ChevronRight
      class="memory-source-open-icon"
      aria-hidden="true"
      data-test="memory-source-open-icon"
    />
  </button>
</template>

<style scoped>
.memory-source-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: min(100%, 18rem);
  min-height: 28px;
  padding: 3px 8px 3px 6px;
  overflow: hidden;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  border-radius: 999px;
  background: hsl(from var(--background) h s l / 0.74);
  color: hsl(from var(--foreground) h s l / 0.82);
  font-size: 11px;
  line-height: 1.35;
  text-align: left;
  transition:
    border-color 160ms var(--ease-fluid),
    background-color 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.memory-source-tag:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--primary) h s l / 0.26);
  background: hsl(from var(--muted) h s l / 0.3);
}

.memory-source-tag--change {
  border-color: hsl(from var(--primary) h s l / 0.22);
  background: hsl(from var(--primary) h s l / 0.06);
}

.memory-source-tag--deleted {
  border-color: hsl(from var(--destructive) h s l / 0.18);
  background: hsl(from var(--destructive) h s l / 0.045);
}

.memory-source-tag--deleted .memory-source-icon,
.memory-source-tag--deleted .memory-source-impact {
  color: hsl(from var(--destructive) h s l / 0.76);
}

.memory-source-tag:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.memory-source-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 1.5rem;
  height: 1.25rem;
  border-radius: 999px;
  background: hsl(from var(--muted) h s l / 0.52);
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  font-family: var(--font-mono);
  font-size: 10px;
}

.memory-source-icon {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  color: hsl(from var(--primary) h s l / 0.82);
}

.memory-source-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-source-type {
  flex: 0 0 auto;
  color: hsl(from var(--muted-foreground) h s l / 0.82);
}

.memory-source-reason {
  min-width: 0;
  max-width: 9rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: hsl(from var(--muted-foreground) h s l / 0.78);
}

.memory-source-impact {
  min-width: 0;
  max-width: 7rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: hsl(from var(--primary) h s l / 0.84);
}

.memory-source-open-icon {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  color: hsl(from var(--muted-foreground) h s l / 0.68);
  transition: color 160ms var(--ease-fluid), transform 160ms var(--ease-fluid);
}

.memory-source-tag:hover .memory-source-open-icon {
  color: hsl(from var(--primary) h s l / 0.82);
  transform: translateX(1px);
}

@media (max-width: 640px) {
  .memory-source-reason {
    display: none;
  }

  .memory-source-impact {
    max-width: 8rem;
  }
}
</style>
