<script setup lang="ts">
import { ref } from 'vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingItem from '@/components/settings/SettingItem.vue'

// 快捷键定义（只读列表）
const shortcuts = ref([
  {
    category: '全局快捷键',
    items: [
      { name: '打开命令面板', keys: ['Ctrl', 'K'] },
      { name: '切换主题', keys: ['Ctrl', 'Shift', 'T'] },
      { name: '新建会话', keys: ['Ctrl', 'N'] },
      { name: '搜索会话', keys: ['Ctrl', 'F'] },
    ]
  },
  {
    category: '对话页快捷键',
    items: [
      { name: '新建会话', keys: ['Ctrl', 'N'] },
      { name: '聚焦输入框', keys: ['Ctrl', 'L'] },
      { name: '发送消息', keys: ['Enter'] },
      { name: '换行', keys: ['Shift', 'Enter'] },
      { name: '停止生成', keys: ['Ctrl', 'C'] },
      { name: '清空会话', keys: ['Ctrl', 'Shift', 'C'] },
    ]
  },
  {
    category: '编辑器快捷键',
    items: [
      { name: '复制代码块', keys: ['Ctrl', 'C'] },
      { name: '折叠代码块', keys: ['Ctrl', 'Shift', 'F'] },
      { name: '展开所有代码块', keys: ['Ctrl', 'Shift', 'E'] },
    ]
  },
])

function formatKeys(keys: string[]): string {
  return keys.join(' + ')
}
</script>

<template>
  <div class="flex flex-col h-full overflow-y-auto">
    <div class="sticky top-0 z-10 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="container mx-auto px-6 py-4">
        <h2 class="text-2xl font-semibold text-foreground">快捷键设置</h2>
        <p class="text-sm text-muted-foreground mt-1">查看当前可用快捷键（只读列表）</p>
      </div>
    </div>

    <div class="container mx-auto px-6 py-6 space-y-6 max-w-4xl">
      <div
        v-for="category in shortcuts"
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
      <div class="rounded-lg border border-border bg-muted/30 p-4">
        <p class="text-sm text-muted-foreground">
          <strong class="text-foreground">提示：</strong>
          快捷键自定义功能暂未开放。如后端支持持久化，可在此处开放部分快捷键的自定义编辑。
        </p>
      </div>
    </div>
  </div>
</template>
