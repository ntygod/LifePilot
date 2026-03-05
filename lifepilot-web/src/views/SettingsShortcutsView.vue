<script setup lang="ts">
import { computed } from 'vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { SHORTCUT_DEFINITIONS } from '@/composables/useKeyboardShortcuts'

// 按 category 分组快捷键定义
const groupedShortcuts = computed(() => {
  const map = new Map<string, { name: string; keys: string[] }[]>()
  for (const def of SHORTCUT_DEFINITIONS) {
    if (!map.has(def.category)) {
      map.set(def.category, [])
    }
    map.get(def.category)!.push({ name: def.name, keys: def.keys })
  }
  return Array.from(map.entries()).map(([category, items]) => ({ category, items }))
})
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6 max-w-4xl">
        <div
          v-for="category in groupedShortcuts"
          :key="category.category"
          class="space-y-4"
        >
          <SettingSection :title="category.category" icon="⌨️" description="">
            <div class="space-y-3">
              <div
                v-for="item in category.items"
                :key="item.name"
                class="flex items-center justify-between py-2 border-b border-border last:border-0"
              >
                <span class="text-sm text-foreground">{{ item.name }}</span>
                <div class="flex items-center gap-1">
                  <kbd
                    v-for="(key, index) in item.keys"
                    :key="index"
                    class="px-2 py-1 rounded bg-muted text-xs font-mono text-muted-foreground"
                  >
                    {{ key }}
                  </kbd>
                </div>
              </div>
            </div>
          </SettingSection>
        </div>

        <!-- 提示信息 -->
        <div class="rounded-lg border border-border bg-muted/30 p-md">
          <p class="text-sm text-muted-foreground">
            <strong class="text-foreground">提示：</strong>
            快捷键自定义功能暂未开放。如后端支持持久化，可在此处开放部分快捷键的自定义编辑。
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
