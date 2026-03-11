<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Slider } from '@/components/ui/slider'
import { Switch } from '@/components/ui/switch'
import { useSettings } from '@/composables/useSettings'
import {
  getDensityDisplayLabel,
  getFontSizeDisplayLabel,
  getThemeDisplayLabel,
} from '@/lib/settingsDisplay'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  language: 'zh-CN',
  layoutDensity: 'standard' as 'compact' | 'standard',
  fontSize: 'medium' as 'small' | 'medium' | 'large',
  timeFormat: '24h' as '12h' | '24h',
  showTokenUsage: true,
  autoExpandCodeBlocks: false,
  collapseLongReplies: true,
  collapseThreshold: 1000,
})

const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)
const showRestartOnboardingConfirm = ref(false)

const previewFontSize = computed(() => {
  if (form.value.fontSize === 'small') return '0.92rem'
  if (form.value.fontSize === 'large') return '1.08rem'
  return '1rem'
})

const previewTheme = computed(() => getThemeDisplayLabel(form.value.theme))

const previewTime = computed(() => {
  return new Intl.DateTimeFormat(form.value.language, {
    hour: '2-digit',
    minute: '2-digit',
    month: 'short',
    day: 'numeric',
    hour12: form.value.timeFormat === '12h',
  }).format(new Date('2026-03-10T18:30:00'))
})

const previewSummaryItems = computed(() => [
  { label: '主题', value: previewTheme.value },
  { label: '密度', value: getDensityDisplayLabel(form.value.layoutDensity) },
  { label: '字号', value: getFontSizeDisplayLabel(form.value.fontSize) },
  { label: '词元显示', value: form.value.showTokenUsage ? '显示' : '隐藏' },
])

onMounted(async () => {
  await loadSettings()

  if (!settings.value) {
    return
  }

  form.value = {
    theme: settings.value.theme ?? form.value.theme,
    language: settings.value.language ?? form.value.language,
    layoutDensity: settings.value.layoutDensity ?? form.value.layoutDensity,
    fontSize: settings.value.fontSize ?? form.value.fontSize,
    timeFormat: settings.value.timeFormat ?? form.value.timeFormat,
    showTokenUsage: settings.value.showTokenUsage ?? form.value.showTokenUsage,
    autoExpandCodeBlocks: settings.value.autoExpandCodeBlocks ?? form.value.autoExpandCodeBlocks,
    collapseLongReplies: settings.value.collapseLongReplies ?? form.value.collapseLongReplies,
    collapseThreshold: settings.value.collapseThreshold ?? form.value.collapseThreshold,
  }
})

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false

  try {
    await saveSettings({
      ...(settings.value ?? {}),
      ...form.value,
    })
    saveSuccess.value = true
    window.setTimeout(() => {
      saveSuccess.value = false
    }, 2200)
  } catch (event) {
    console.error('Failed to save preferences:', event)
    saveError.value = '保存偏好设置失败。'
  } finally {
    saving.value = false
  }
}

function restartOnboarding() {
  showRestartOnboardingConfirm.value = true
}

function confirmRestartOnboarding() {
  localStorage.removeItem('zhiwei_onboarding_completed')
  window.location.reload()
}

function updateBooleanSetting(
  key: 'showTokenUsage' | 'autoExpandCodeBlocks' | 'collapseLongReplies',
  value: boolean | 'indeterminate',
) {
  form.value[key] = value === true
}

const themeOptions = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
] as const

const languageOptions = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁体中文' },
  { value: 'en-US', label: '英文' },
  { value: 'ja-JP', label: '日文' },
]

const densityOptions = [
  { value: 'compact', label: '紧凑' },
  { value: 'standard', label: '标准' },
]

const fontSizeOptions = [
  { value: 'small', label: '小' },
  { value: 'medium', label: '中' },
  { value: 'large', label: '大' },
]

const timeFormatOptions = [
  { value: '24h', label: '24 小时制' },
  { value: '12h', label: '12 小时制' },
]
</script>

<template>
  <div class="space-y-8">
    <template v-if="loading">
      <div class="space-y-8">
        <Skeleton class="h-10 w-40 rounded-xl" />
        <div class="grid gap-8 xl:grid-cols-[minmax(0,1fr)_280px]">
          <div class="space-y-8">
            <Skeleton class="h-[340px] w-full rounded-xl" />
            <Skeleton class="h-[280px] w-full rounded-xl" />
            <Skeleton class="h-[140px] w-full rounded-xl" />
          </div>
          <Skeleton class="h-[240px] w-full rounded-xl" />
        </div>
      </div>
    </template>

    <form v-else class="space-y-8" @submit.prevent="handleSave">
      <section class="grid gap-4 border-b border-border/60 pb-5 xl:grid-cols-[minmax(0,1fr)_280px] xl:items-start">
        <div class="space-y-2">
          <h2 class="text-xl font-semibold text-foreground">界面与阅读</h2>
          <p class="max-w-3xl text-sm leading-6 text-muted-foreground">
            先把看得见、经常用到的设置收在前面，减少来回查找。
          </p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
          <div class="surface-label mb-2 text-[0.68rem]">当前效果</div>
          <div class="text-sm font-medium text-foreground">
            {{ previewTheme }}主题 · {{ getDensityDisplayLabel(form.layoutDensity) }}
          </div>
          <p class="mt-1 text-sm leading-6 text-muted-foreground">
            当前时间显示为 {{ previewTime }}，{{ getFontSizeDisplayLabel(form.fontSize) }}字号。
          </p>
        </div>
      </section>

      <div
        v-if="error"
        class="flex flex-col gap-3 rounded-2xl border border-amber-300/70 bg-amber-50/60 px-4 py-4 text-sm text-amber-950 dark:border-amber-900/70 dark:bg-amber-950/30 dark:text-amber-100 sm:flex-row sm:items-center sm:justify-between"
      >
        <div class="leading-6">
          暂时无法读取设置服务，先显示当前设置。
        </div>
        <Button type="button" variant="outline" @click="loadSettings">
          重试
        </Button>
      </div>

      <div class="grid gap-8 xl:grid-cols-[minmax(0,1fr)_280px]">
        <div class="space-y-8">
          <SettingSection title="基础显示" description="主题、语言、密度、字号和时间格式。">
            <SettingItem label="主题" description="设置界面的整体外观。" html-for="theme">
              <Select :model-value="form.theme" @update:model-value="(value) => form.theme = value as typeof form.theme">
                <SelectTrigger id="theme" class="w-44">
                  <SelectValue placeholder="选择主题" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in themeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>

            <SettingItem label="语言" description="切换界面语言。" html-for="language">
              <Select :model-value="form.language" @update:model-value="(value) => form.language = String(value)">
                <SelectTrigger id="language" class="w-52">
                  <SelectValue placeholder="选择语言" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in languageOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>

            <SettingItem label="密度" description="调整导航和内容区的间距。" html-for="layout-density">
              <Select :model-value="form.layoutDensity" @update:model-value="(value) => form.layoutDensity = value as typeof form.layoutDensity">
                <SelectTrigger id="layout-density" class="w-44">
                  <SelectValue placeholder="选择密度" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in densityOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>

            <SettingItem label="字号" description="调整界面的基础字号。" html-for="font-size">
              <Select :model-value="form.fontSize" @update:model-value="(value) => form.fontSize = value as typeof form.fontSize">
                <SelectTrigger id="font-size" class="w-44">
                  <SelectValue placeholder="选择字号" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in fontSizeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>

            <SettingItem label="时间格式" description="控制界面时间的显示方式。" html-for="time-format">
              <Select :model-value="form.timeFormat" @update:model-value="(value) => form.timeFormat = value as typeof form.timeFormat">
                <SelectTrigger id="time-format" class="w-44">
                  <SelectValue placeholder="选择时间格式" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in timeFormatOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>
          </SettingSection>

          <SettingSection title="阅读习惯" description="控制词元显示、代码块展开和长回复折叠。">
            <SettingItem label="显示词元用量" description="在支持的页面显示提示词和回复的词元统计。">
              <Switch
                :model-value="form.showTokenUsage"
                @update:model-value="(value) => updateBooleanSetting('showTokenUsage', value)"
              />
            </SettingItem>

            <SettingItem label="自动展开代码块" description="默认展开包含较多代码的回复。">
              <Switch
                :model-value="form.autoExpandCodeBlocks"
                @update:model-value="(value) => updateBooleanSetting('autoExpandCodeBlocks', value)"
              />
            </SettingItem>

            <SettingItem label="折叠长回复" description="超长回复默认收起，按需展开。">
              <Switch
                :model-value="form.collapseLongReplies"
                @update:model-value="(value) => updateBooleanSetting('collapseLongReplies', value)"
              />
            </SettingItem>

            <SettingItem
              v-if="form.collapseLongReplies"
              label="折叠阈值"
              description="超过这个长度的回复会先折叠。"
            >
              <div class="flex items-center gap-3">
                <Slider
                  :model-value="[form.collapseThreshold]"
                  :min="500"
                  :max="5000"
                  :step="100"
                  class="w-40"
                  @update:model-value="(value) => { if (value) form.collapseThreshold = value[0] }"
                />
                <span class="min-w-[4rem] text-right text-sm text-muted-foreground">
                  {{ form.collapseThreshold }}
                </span>
              </div>
            </SettingItem>
          </SettingSection>

          <SettingSection title="辅助操作" description="需要重新体验引导时，可重新开启。">
            <SettingItem
              label="重新开始引导"
              description="清除本地引导完成状态，刷新后重新进入引导流程。"
            >
              <Button type="button" variant="outline" @click="restartOnboarding">
                重新开始
              </Button>
            </SettingItem>
          </SettingSection>
        </div>

        <aside class="xl:sticky xl:top-6 xl:self-start">
          <section class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 p-5">
            <h3 class="text-sm font-semibold text-foreground">当前预览</h3>
            <p class="mt-1 text-sm leading-6 text-muted-foreground">
              先确认整体阅读效果，再决定是否继续细调。
            </p>

            <div class="mt-4 space-y-4" :style="{ fontSize: previewFontSize }">
              <div class="detail-card px-4 py-4">
                <div class="flex items-center justify-between gap-3">
                  <span class="text-sm font-medium text-foreground">界面示例</span>
                  <span class="text-xs text-muted-foreground">{{ previewTime }}</span>
                </div>
                <p class="mt-2 text-sm leading-6 text-muted-foreground">
                  当前效果：{{ previewTheme }}主题，{{ getDensityDisplayLabel(form.layoutDensity) }}，
                  {{ getFontSizeDisplayLabel(form.fontSize) }}字号。
                </p>
              </div>

              <div class="space-y-3">
                <div
                  v-for="item in previewSummaryItems"
                  :key="item.label"
                  class="flex items-center justify-between gap-3 border-b border-border/50 pb-3 last:border-b-0 last:pb-0"
                >
                  <span class="text-sm text-muted-foreground">{{ item.label }}</span>
                  <span class="text-sm font-medium text-foreground">{{ item.value }}</span>
                </div>
              </div>
            </div>
          </section>
        </aside>
      </div>

      <div class="sticky bottom-0 z-10 pb-2 pt-4">
        <div class="detail-card bg-background/92 px-4 py-4 backdrop-blur">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="space-y-1">
              <div class="text-sm font-medium text-foreground">保存偏好更改</div>
              <p class="text-sm leading-6 text-muted-foreground">
                主题、密度、字号和阅读行为会在保存后立即生效。
              </p>
            </div>

            <div class="flex flex-wrap items-center gap-3">
              <span v-if="saveSuccess" class="text-sm text-emerald-600 dark:text-emerald-400">已保存</span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
              <Button type="submit" :disabled="saving">
                {{ saving ? '保存中...' : '保存偏好设置' }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </form>

    <ConfirmDialog
      v-model:show="showRestartOnboardingConfirm"
      title="重新开始引导"
      message="这会清除本地引导完成标记，并在刷新后重新打开引导流程。"
      confirm-label="重新开始"
      @confirm="confirmRestartOnboarding"
    />
  </div>
</template>
