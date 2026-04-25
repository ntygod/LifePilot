<script setup lang="ts">
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Download, Package, Sparkles, Upload } from 'lucide-vue-next'
import type { SkillSourceType } from '@/types'

// Phase B.7：Skill 来源徽章 —— 对齐后端 DB 层 sourceType 四值
const props = defineProps<{
  sourceType: SkillSourceType
}>()

interface BadgeMeta {
  icon: typeof Package
  label: string
  variant: 'default' | 'secondary' | 'outline' | 'destructive'
}

const meta = computed<BadgeMeta>(() => {
  switch (props.sourceType) {
    case 'BUILTIN':
      return { icon: Package, label: '内置', variant: 'secondary' }
    case 'USER_IMPORTED':
      return { icon: Upload, label: '导入', variant: 'outline' }
    case 'MARKETPLACE':
      return { icon: Download, label: '市场', variant: 'default' }
    case 'AUTO_GENERATED':
      return { icon: Sparkles, label: 'AI 生成', variant: 'destructive' }
  }
})
</script>

<template>
  <Badge :variant="meta.variant" class="gap-xs">
    <component :is="meta.icon" class="h-sm w-sm" />
    {{ meta.label }}
  </Badge>
</template>
