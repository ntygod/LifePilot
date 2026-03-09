<script setup lang="ts">
import type { SourceSummary } from '@/types'
import { RouterLink } from 'vue-router'
import { LibraryBig } from 'lucide-vue-next'

defineProps<{
  source: SourceSummary
}>()
</script>

<template>
  <RouterLink
    :to="{ name: 'knowledgeBaseDetail', params: { id: source.id } }"
    class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium
           bg-primary/10 text-primary hover:bg-primary/20 transition-colors cursor-pointer"
    :title="source.id"
  >
    <LibraryBig :size="12" />
    <span>{{ source.name }}</span>
  </RouterLink>
</template>
