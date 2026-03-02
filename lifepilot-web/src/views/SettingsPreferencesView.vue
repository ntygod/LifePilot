<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useSettings } from '@/composables/useSettings'
import { settingsApi } from '@/api/client'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSwitch from '@/components/settings/SettingSwitch.vue'
import SettingSelect from '@/components/settings/SettingSelect.vue'
import SettingSlider from '@/components/settings/SettingSlider.vue'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  language: 'zh-CN',
  layoutDensity: 'standard' as 'compact' | 'standard',
  fontSize: 'medium' as 'small' | 'medium' | 'large',
  timeFormat: '24h' as '12h' | '24h',
  // 对话偏好
  showTokenUsage: true,
  autoExpandCodeBlocks: false,
  collapseLongReplies: true,
  collapseThreshold: 1000,
})

const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

onMounted(async () => {
  await loadSettings()
  if (settings.value) {
    form.value = {
      theme: settings.value.theme ?? form.value.theme,
      language: settings.value.language ?? form.value.language,
      layoutDensity: (settings.value as any).layoutDensity ?? form.value.layoutDensity,
      fontSize: (settings.value as any).fontSize ?? form.value.fontSize,
      timeFormat: (settings.value as any).timeFormat ?? form.value.timeFormat,
      showTokenUsage: (settings.value as any).showTokenUsage ?? form.value.showTokenUsage,
      autoExpandCodeBlocks: (settings.value as any).autoExpandCodeBlocks ?? form.value.autoExpandCodeBlocks,
      collapseLongReplies: (settings.value as any).collapseLongReplies ?? form.value.collapseLongReplies,
      collapseThreshold: (settings.value as any).collapseThreshold ?? form.value.collapseThreshold,
    }
  }
})

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  const snapshot = { ...form.value }
  try {
    await saveSettings(form.value as any)
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : '保存失败'
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

const languageOptions: Array<{ value: string; label: string }> = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁体中文' },
  { value: 'en-US', label: 'English' },
  { value: 'ja-JP', label: '日本語' },
]

const densityOptions: Array<{ value: string; label: string }> = [
  { value: 'compact', label: '紧凑' },
  { value: 'standard', label: '标准' },
]

const fontSizeOptions: Array<{ value: string; label: string }> = [
  { value: 'small', label: '小' },
  { value: 'medium', label: '中' },
  { value: 'large', label: '大' },
]

const timeFormatOptions: Array<{ value: string; label: string }> = [
  { value: '24h', label: '24 小时制' },
  { value: '12h', label: '12 小时制' },
]

function restartOnboarding() {
  if (confirm('确定要重新开始新手引导吗？')) {
    localStorage.removeItem('lifepilot_onboarding_completed')
    window.location.reload()
  }
}
</script>

<template>
  <div class="flex flex-col h-full overflow-y-auto">
    <div class="sticky top-0 z-10 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="container mx-auto px-4 md:px-6 py-4">
        <h2 class="text-2xl font-semibold text-foreground leading-tight">偏好设置</h2>
        <p class="text-sm text-muted-foreground mt-1">自定义界面外观和对话偏好</p>
      </div>
    </div>

    <div class="container mx-auto px-4 md:px-6 py-6 space-y-6 max-w-4xl">
      <!-- 加载中 -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <div class="text-sm text-muted-foreground">加载中...</div>
      </div>

      <!-- 加载失败 -->
      <div v-else-if="error" class="rounded-lg border border-destructive bg-destructive/10 p-4">
        <div class="text-sm text-destructive">{{ error }}</div>
      </div>

      <!-- 设置表单 -->
      <form v-else @submit.prevent="handleSave" class="space-y-6">
        <!-- 外观设置 -->
        <SettingSection title="外观设置" icon="🎨" description="自定义界面主题和语言偏好">
          <SettingItem label="主题" description="选择您偏好的界面主题">
            <div class="flex gap-3">
              <label
                v-for="opt in themeOptions"
                :key="opt.value"
                class="flex items-center gap-2 cursor-pointer transition-colors hover:text-foreground"
              >
                <input
                  v-model="form.theme"
                  type="radio"
                  name="theme"
                  :value="opt.value"
                  class="accent-primary focus:ring-2 focus:ring-ring focus:ring-offset-2"
                />
                <span class="text-sm">{{ opt.label }}</span>
              </label>
            </div>
          </SettingItem>

          <SettingItem label="语言" description="选择界面显示语言">
            <SettingSelect
              v-model="form.language"
              :options="languageOptions"
              placeholder="请选择语言"
            />
          </SettingItem>

          <SettingItem label="布局密度" description="控制界面元素的间距">
            <SettingSelect
              v-model="form.layoutDensity"
              :options="densityOptions"
              placeholder="请选择布局密度"
            />
          </SettingItem>

          <SettingItem label="字号" description="调整界面文字大小">
            <SettingSelect
              v-model="form.fontSize"
              :options="fontSizeOptions"
              placeholder="请选择字号"
            />
          </SettingItem>
        </SettingSection>

        <!-- 时间格式 -->
        <SettingSection title="时间格式" icon="🕐" description="配置时间显示格式">
          <SettingItem label="时间格式" description="选择时间显示方式">
            <SettingSelect
              v-model="form.timeFormat"
              :options="timeFormatOptions"
              placeholder="请选择时间格式"
            />
          </SettingItem>
        </SettingSection>

        <!-- 对话偏好 -->
        <SettingSection title="对话偏好" icon="💬" description="配置对话相关的显示和行为">
          <SettingItem label="显示 Token 用量" description="在对话中显示 Token 消耗统计">
            <SettingSwitch v-model="form.showTokenUsage" />
          </SettingItem>

          <SettingItem label="自动展开代码块" description="默认展开所有代码块">
            <SettingSwitch v-model="form.autoExpandCodeBlocks" />
          </SettingItem>

          <SettingItem label="折叠超长回复" description="自动折叠超过一定长度的回复">
            <SettingSwitch v-model="form.collapseLongReplies" />
          </SettingItem>

          <SettingItem
            v-if="form.collapseLongReplies"
            label="折叠阈值"
            description="超过此字符数时自动折叠"
          >
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <SettingSlider
                  v-model="form.collapseThreshold"
                  :min="500"
                  :max="5000"
                  :step="100"
                />
              </div>
              <span class="text-sm text-muted-foreground min-w-[4rem]">{{ form.collapseThreshold }} 字符</span>
            </div>
          </SettingItem>
        </SettingSection>

        <!-- 其他设置 -->
        <SettingSection title="其他设置" icon="⚙️" description="系统相关设置">
          <SettingItem label="重新开始新手引导" description="清除新手引导完成状态，下次启动时将重新显示引导">
            <button
              type="button"
              class="px-4 py-2 rounded-lg border border-input bg-background hover:bg-accent hover:shadow-md active:scale-[0.98] transition-all duration-200 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              @click="restartOnboarding"
            >
              重新开始
            </button>
          </SettingItem>
        </SettingSection>

        <!-- 保存按钮 -->
        <div class="sticky bottom-0 bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 border-t py-4 -mx-4 md:-mx-6 px-4 md:px-6">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <button
                type="submit"
                :disabled="saving"
                class="inline-flex items-center justify-center rounded-lg text-sm font-medium h-10 px-6 bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md active:scale-[0.98] transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:shadow-none disabled:active:scale-100"
              >
                {{ saving ? '保存中...' : '保存设置' }}
              </button>

              <span v-if="saveSuccess" class="text-sm text-green-600 dark:text-green-400 flex items-center gap-2">
                <span class="w-1.5 h-1.5 rounded-full bg-green-600 dark:bg-green-400"></span>
                已保存
              </span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
            </div>
          </div>
        </div>
      </form>
    </div>
  </div>
</template>
