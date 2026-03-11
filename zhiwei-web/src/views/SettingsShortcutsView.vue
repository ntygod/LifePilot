<script setup lang="ts">
import { computed } from 'vue'
import { Keyboard } from 'lucide-vue-next'
import MetricCard from '@/components/common/MetricCard.vue'
import { Badge } from '@/components/ui/badge'
import { SHORTCUT_DEFINITIONS } from '@/composables/useKeyboardShortcuts'

const groupedShortcuts = computed(() => {
  const groups = new Map<string, { name: string; keys: string[] }[]>()

  for (const shortcut of SHORTCUT_DEFINITIONS) {
    if (!groups.has(shortcut.category)) {
      groups.set(shortcut.category, [])
    }

    groups.get(shortcut.category)?.push({
      name: shortcut.name,
      keys: shortcut.keys,
    })
  }

  return Array.from(groups.entries()).map(([category, items]) => ({ category, items }))
})

const totalShortcuts = computed(() => SHORTCUT_DEFINITIONS.length)
const largestGroup = computed(() => (
  [...groupedShortcuts.value].sort((left, right) => right.items.length - left.items.length)[0] ?? null
))
const shortcutSummaryItems = computed(() => [
  {
    label: '分类数量',
    value: String(groupedShortcuts.value.length),
    hint: '按功能场景拆分的快捷键类别。',
  },
  {
    label: '快捷键总数',
    value: String(totalShortcuts.value),
    hint: '当前内置的全部系统快捷键。',
  },
  {
    label: '最多的一组',
    value: largestGroup.value?.category || '暂无',
    hint: largestGroup.value ? `${largestGroup.value.items.length} 个快捷键` : '当前还没有分组数据。',
  },
])

const shortcutKeyLabels: Record<string, string> = {
  Escape: 'Esc',
}

function formatShortcutKey(key: string) {
  return shortcutKeyLabels[key] ?? key
}
</script>

<template>
  <div class="space-y-8">
    <section class="grid gap-4 border-b border-border/60 pb-5 xl:grid-cols-[minmax(0,1fr)_280px] xl:items-start">
      <div class="space-y-2">
        <h2 class="text-xl font-semibold text-foreground">键盘快捷键</h2>
        <p class="max-w-3xl text-sm leading-6 text-muted-foreground">
          查看当前可用的快捷键。
        </p>
      </div>

      <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
        <div class="surface-label mb-2 text-[0.68rem]">使用说明</div>
        <div class="text-sm font-medium text-foreground">系统预设，暂不支持自定义</div>
        <p class="mt-1 text-sm leading-6 text-muted-foreground">
          先记住高频操作，再按分类翻查低频组合键。
        </p>
      </div>
    </section>

    <section class="grid gap-3 md:grid-cols-3">
      <MetricCard
        v-for="item in shortcutSummaryItems"
        :key="item.label"
        :label="item.label"
        :value="item.value"
        :hint="item.hint"
        class="h-full"
      />
    </section>

    <div class="grid gap-6 xl:grid-cols-2">
      <section
        v-for="group in groupedShortcuts"
        :key="group.category"
        class="list-card overflow-hidden"
      >
        <div class="flex items-center justify-between gap-3 border-b border-border/70 px-4 py-3">
          <div class="flex items-center gap-2.5">
            <Keyboard class="size-4 text-muted-foreground" />
            <div>
              <h3 class="text-sm font-semibold text-foreground">{{ group.category }}</h3>
              <p class="text-sm text-muted-foreground">{{ group.items.length }} 个快捷键</p>
            </div>
          </div>
        </div>

        <div class="divide-y divide-border/60">
          <div
            v-for="item in group.items"
            :key="item.name"
            class="flex items-center justify-between gap-4 px-4 py-3 transition-colors hover:bg-muted/15"
          >
            <span class="min-w-0 text-sm text-foreground">{{ item.name }}</span>
            <div class="flex flex-wrap items-center justify-end gap-1.5">
              <template v-for="(key, index) in item.keys" :key="`${item.name}-${key}-${index}`">
                <span v-if="index > 0" class="text-xs text-muted-foreground">+</span>
                <Badge variant="outline" class="font-mono text-[0.72rem]">
                  {{ formatShortcutKey(key) }}
                </Badge>
              </template>
            </div>
          </div>
        </div>
      </section>
    </div>

    <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-amber-300/70 bg-amber-50/45 px-4 py-4 text-sm leading-6 text-amber-950 dark:border-amber-900/70 dark:bg-amber-950/20 dark:text-amber-100">
      当前快捷键为系统预设，暂不支持自定义。
    </div>
  </div>
</template>
