<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useSettings } from '@/composables/useSettings'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  language: 'zh-CN',
  llmProvider: '',
})

const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

onMounted(async () => {
  await loadSettings()
  if (settings.value) {
    form.value = { ...settings.value }
  }
})

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  // 保存前快照，用于失败回滚
  const snapshot = { ...form.value }
  try {
    await saveSettings(form.value)
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : '保存失败'
    // 回滚到快照
    form.value = snapshot
  } finally {
    saving.value = false
  }
}

const themeOptions = [
  { value: 'light', label: '亮色' },
  { value: 'dark', label: '暗色' },
  { value: 'system', label: '跟随系统' },
] as const
</script>

<template>
  <div class="flex flex-col h-full p-6 max-w-lg">
    <h2 class="text-xl font-semibold text-foreground mb-6">设置</h2>

    <!-- 加载中 -->
    <div v-if="loading" class="text-sm text-muted-foreground">加载中...</div>

    <!-- 加载失败 -->
    <div v-else-if="error" class="text-sm text-destructive">{{ error }}</div>

    <!-- 设置表单 -->
    <form v-else class="space-y-6" @submit.prevent="handleSave">
      <!-- 主题 -->
      <fieldset class="space-y-2">
        <legend class="text-sm font-medium">主题</legend>
        <div class="flex gap-3">
          <label
            v-for="opt in themeOptions"
            :key="opt.value"
            class="flex items-center gap-2 cursor-pointer"
          >
            <input
              v-model="form.theme"
              type="radio"
              name="theme"
              :value="opt.value"
              class="accent-primary"
            />
            <span class="text-sm">{{ opt.label }}</span>
          </label>
        </div>
      </fieldset>

      <!-- LLM Provider -->
      <div class="space-y-1.5">
        <label for="llm-provider" class="text-sm font-medium">LLM Provider</label>
        <input
          id="llm-provider"
          v-model="form.llmProvider"
          type="text"
          placeholder="例如: openai, deepseek"
          class="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        />
      </div>

      <!-- 保存按钮 -->
      <div class="flex items-center gap-3">
        <button
          type="submit"
          :disabled="saving"
          class="inline-flex items-center justify-center rounded-md text-sm font-medium h-9 px-4 py-2 bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
        >
          {{ saving ? '保存中...' : '保存' }}
        </button>

        <span v-if="saveSuccess" class="text-sm text-green-600">已保存</span>
        <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
      </div>
    </form>
  </div>
</template>
