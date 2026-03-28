<script setup lang="ts">
import type { SourceSummary } from '@/types'
import { RouterLink } from 'vue-router'
import { ArrowUpRight } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  source: SourceSummary
  index?: number
}>(), {
  index: 0,
})
</script>

<template>
  <RouterLink
    :to="{ name: 'knowledgeBaseDetail', params: { id: props.source.id } }"
    class="kb-source-tag inline-flex items-center gap-2 rounded-[0.95rem] px-2.5 py-1.5 text-[11px] font-medium"
    :title="props.source.id"
  >
    <span class="kb-source-index">{{ String(props.index + 1).padStart(2, '0') }}</span>
    <span class="truncate">{{ props.source.name }}</span>
    <ArrowUpRight :size="12" class="kb-source-arrow shrink-0" />
  </RouterLink>
</template>

<style scoped>
.kb-source-tag {
  position: relative;
  max-width: min(100%, 16rem);
  overflow: hidden;
  border: 1px solid hsl(from var(--border) h s l / 0.58);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.92), hsl(from var(--background) h s l / 0.82));
  color: hsl(from var(--foreground) h s l / 0.86);
  box-shadow:
    0 10px 18px -24px hsl(var(--shadow-color) / 0.08),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.7);
  transition:
    background-color 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid),
    transform 180ms var(--ease-fluid);
}

.kb-source-tag::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 2px;
  background: linear-gradient(180deg, hsl(from var(--primary) h s l / 0.78), hsl(from var(--primary) h s l / 0.12));
}

.kb-source-tag:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--primary) h s l / 0.24);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.96), hsl(from var(--background) h s l / 0.84));
  box-shadow:
    0 12px 20px -22px hsl(var(--shadow-color) / 0.1),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.72);
}

.kb-source-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 1.7rem;
  height: 1.45rem;
  padding: 0 0.35rem;
  border-radius: 0.7rem;
  background: hsl(from var(--primary) h s l / 0.09);
  color: hsl(from var(--primary) h s l / 0.92);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.02em;
}

.kb-source-arrow {
  opacity: 0.38;
  transition:
    opacity 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.kb-source-tag:hover .kb-source-arrow {
  opacity: 0.78;
  transform: translate(1px, -1px);
}
</style>
