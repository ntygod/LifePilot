<script setup lang="ts">
import type { SourceSummary } from '@/types'
import { RouterLink } from 'vue-router'
import { ArrowUpRight, LibraryBig } from 'lucide-vue-next'

defineProps<{
  source: SourceSummary
}>()
</script>

<template>
  <RouterLink
    :to="{ name: 'knowledgeBaseDetail', params: { id: source.id } }"
    class="kb-source-tag inline-flex items-center gap-2 rounded-full px-2.5 py-1 text-[11px] font-medium"
    :title="source.id"
  >
    <span class="kb-source-icon">
      <LibraryBig :size="12" />
    </span>
    <span class="truncate">{{ source.name }}</span>
    <ArrowUpRight :size="12" class="kb-source-arrow shrink-0" />
  </RouterLink>
</template>

<style scoped>
.kb-source-tag {
  max-width: min(100%, 16rem);
  border: 1px solid hsl(from var(--primary) h s l / 0.16);
  background:
    radial-gradient(circle at top left, hsl(from var(--primary) h s l / 0.12), transparent 42%),
    linear-gradient(180deg, hsl(from var(--card) h s l / 0.94), hsl(from var(--card) h s l / 0.88));
  color: hsl(from var(--primary) h s l / 0.92);
  box-shadow:
    0 14px 24px -28px hsl(var(--shadow-color) / 0.18),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.52);
  transition:
    transform 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid);
}

.kb-source-tag:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--primary) h s l / 0.24);
  box-shadow:
    0 18px 30px -30px hsl(var(--shadow-color) / 0.24),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.56);
}

.kb-source-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 1.45rem;
  width: 1.45rem;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.12);
}

.kb-source-arrow {
  opacity: 0.52;
  transition:
    opacity 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.kb-source-tag:hover .kb-source-arrow {
  opacity: 0.9;
  transform: translate(1px, -1px);
}
</style>
