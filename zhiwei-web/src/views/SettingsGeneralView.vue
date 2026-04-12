<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { logger } from '@/utils/logger'
import { useSettings } from '@/composables/useSettings'
import { settingsApi } from '@/api/client'
import type { WorkspaceSettings } from '@/api/client'

const isTauri = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__

const { settings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  layoutDensity: 'standard' as 'compact' | 'standard',
  fontSize: 'medium' as 'small' | 'medium' | 'large',
  showTokenUsage: true,
})

// ---- 数据目录 ----
const dataDir = ref<string | null>(null)
const dataDirChanged = ref(false)
const showRestartForDataDir = ref(false)

async function loadDataDir() {
  if (isTauri) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      dataDir.value = await invoke<string>('get_data_dir')
    } catch {
      dataDir.value = ''
    }
  } else {
    try {
      const result = await settingsApi.getDataDir()
      dataDir.value = result.dataDir
    } catch {
      dataDir.value = '（获取失败）'
    }
  }
}

async function browseDataDir() {
  try {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const selected = await open({ directory: true, title: '选择数据目录' })
    if (selected && typeof selected === 'string') {
      dataDir.value = selected
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('set_data_dir', { path: selected })
      dataDirChanged.value = true
    }
  } catch (e) {
    logger.error('选择目录失败:', e)
  }
}

async function resetDataDir() {
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('set_data_dir', { path: '' })
    dataDir.value = await invoke<string>('get_data_dir')
    dataDirChanged.value = true
  } catch { /* ignore */ }
}

function confirmRestartForDataDir() {
  showRestartForDataDir.value = false
  window.location.reload()
}

// ---- 默认工作目录（前后端 API 驱动，实时生效） ----
const workspaceDir = ref('')
const workspaceResolvedPath = ref('')
const workspaceSystemDefault = ref('')
const workspaceSaving = ref(false)
const workspaceInputValue = ref('')

async function loadWorkspaceDir() {
  try {
    const ws: WorkspaceSettings = await settingsApi.getWorkspaceSettings()
    workspaceDir.value = ws.defaultWorkspace ?? ''
    workspaceResolvedPath.value = ws.resolvedPath
    workspaceSystemDefault.value = ws.systemDefault
    workspaceInputValue.value = ws.defaultWorkspace ?? ''
  } catch (e) {
    logger.error('加载工作目录设置失败:', e)
  }
}

async function saveWorkspaceDir(value: string) {
  workspaceSaving.value = true
  try {
    const ws = await settingsApi.updateWorkspaceSettings({
      defaultWorkspace: value || null
    })
    workspaceDir.value = ws.defaultWorkspace ?? ''
    workspaceResolvedPath.value = ws.resolvedPath
    workspaceInputValue.value = ws.defaultWorkspace ?? ''
  } catch (e) {
    logger.error('保存工作目录失败:', e)
  } finally {
    workspaceSaving.value = false
  }
}

async function browseWorkspaceDir() {
  if (isTauri) {
    try {
      const { open } = await import('@tauri-apps/plugin-dialog')
      const selected = await open({ directory: true, title: '选择默认工作目录' })
      if (selected && typeof selected === 'string') {
        workspaceInputValue.value = selected
        await saveWorkspaceDir(selected)
      }
    } catch (e) {
      logger.error('选择工作目录失败:', e)
    }
  }
}

/** 浏览器是否支持原生目录选择 */
const supportsDirPicker = isTauri || 'showDirectoryPicker' in window

async function resetWorkspaceDir() {
  workspaceInputValue.value = ''
  await saveWorkspaceDir('')
}

function handleWorkspaceInputBlur() {
  const trimmed = workspaceInputValue.value.trim()
  if (trimmed !== (workspaceDir.value || '')) {
    saveWorkspaceDir(trimmed)
  }
}

function handleWorkspaceInputKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    (event.target as HTMLInputElement)?.blur()
  }
}

const saveError = ref<string | null>(null)
const showRestartOnboardingConfirm = ref(false)

onMounted(() => {
  form.value = {
    theme: settings.value.theme,
    layoutDensity: settings.value.layoutDensity,
    fontSize: settings.value.fontSize,
    showTokenUsage: settings.value.showTokenUsage,
  }
  loadDataDir()
  loadWorkspaceDir()
})

async function applySettings() {
  saveError.value = null

  try {
    await saveSettings({ ...form.value })
  } catch (event) {
    logger.error('Failed to save local preferences:', event)
    saveError.value = '保存本地偏好失败。'
  }
}

async function updateTheme(value: unknown) {
  if (typeof value !== 'string') return
  form.value.theme = value as typeof form.value.theme
  await applySettings()
}

async function updateLayoutDensity(value: unknown) {
  if (typeof value !== 'string') return
  form.value.layoutDensity = value as typeof form.value.layoutDensity
  await applySettings()
}

async function updateFontSize(value: unknown) {
  if (typeof value !== 'string') return
  form.value.fontSize = value as typeof form.value.fontSize
  await applySettings()
}

function restartOnboarding() {
  showRestartOnboardingConfirm.value = true
}

function confirmRestartOnboarding() {
  localStorage.removeItem('zhiwei_onboarding_completed')
  window.location.reload()
}

async function updateTokenUsage(value: boolean | 'indeterminate') {
  form.value.showTokenUsage = value === true
  await applySettings()
}

const themeOptions = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
] as const

const densityOptions = [
  { value: 'compact', label: '紧凑' },
  { value: 'standard', label: '标准' },
] as const

const fontSizeOptions = [
  { value: 'small', label: '小' },
  { value: 'medium', label: '中' },
  { value: 'large', label: '大' },
] as const
</script>

<template>
  <div class="space-y-8">
    <section class="detail-card px-5 py-5 sm:px-6">
      <div class="space-y-2">
        <div class="surface-label text-[0.68rem]">通用</div>
        <h2 class="text-xl font-semibold text-foreground">界面与显示</h2>
        <p class="max-w-[42rem] text-sm leading-6 text-muted-foreground">这部分控制当前设备上的显示方式和基础交互习惯。</p>
      </div>
      <p v-if="saveError" class="text-sm text-destructive">{{ saveError }}</p>
    </section>

    <div class="space-y-8">
      <SettingSection title="基础显示" description="主题、布局密度和全局字号。">
        <SettingItem label="主题" description="切换浅色、深色或跟随系统。" html-for="theme">
          <Select :model-value="form.theme" @update:model-value="updateTheme">
            <SelectTrigger id="theme" class="w-44"><SelectValue placeholder="选择主题" /></SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in themeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>

        <SettingItem label="布局密度" description="调整导航和内容区的整体间距。" html-for="layout-density">
          <Select :model-value="form.layoutDensity" @update:model-value="updateLayoutDensity">
            <SelectTrigger id="layout-density" class="w-44"><SelectValue placeholder="选择密度" /></SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in densityOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>

        <SettingItem label="界面字号" description="调整页面的基础字号层级。" html-for="font-size">
          <Select :model-value="form.fontSize" @update:model-value="updateFontSize">
            <SelectTrigger id="font-size" class="w-44"><SelectValue placeholder="选择字号" /></SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in fontSizeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>
      </SettingSection>

      <SettingSection title="细节显示" description="控制一些不影响业务逻辑的界面信息。">
        <SettingItem label="显示 token 用量" description="在支持的页面显示提示词和回复的 token 统计。">
          <Switch :model-value="form.showTokenUsage" @update:model-value="updateTokenUsage" />
        </SettingItem>
      </SettingSection>

      <SettingSection title="存储" description="数据目录存放数据库、知识库、技能和工作流等所有用户数据。">
        <SettingItem label="数据目录" :description="dataDir === null ? '加载中...' : (dataDir || '默认')">
          <div v-if="isTauri" class="flex items-center gap-sm">
            <Button type="button" variant="outline" size="sm" @click="browseDataDir">选择目录</Button>
            <Button type="button" variant="ghost" size="sm" @click="resetDataDir">恢复默认</Button>
          </div>
          <span v-else class="text-xs text-muted-foreground">通过启动参数 --zhiwei.data-dir 修改</span>
        </SettingItem>
        <div v-if="dataDirChanged" class="flex items-center gap-3 rounded-md bg-warning/10 px-4 py-2.5 text-sm text-warning-foreground">
          <span>数据目录已修改，重启应用后生效。</span>
          <Button type="button" variant="outline" size="sm" @click="showRestartForDataDir = true">立即重启</Button>
        </div>
      </SettingSection>

      <SettingSection
        title="工作目录"
        description="沙箱执行、Shell 命令、文件写入等操作的默认工作目录。修改后立即生效。"
      >
        <SettingItem
          label="默认工作目录"
          :description="'当前生效路径：' + (workspaceResolvedPath || '加载中...')"
        >
          <div class="flex items-center gap-sm">
            <Input
              v-model="workspaceInputValue"
              class="min-w-2xl"
              placeholder="留空使用默认目录"
              :disabled="workspaceSaving"
              @blur="handleWorkspaceInputBlur"
              @keydown="handleWorkspaceInputKeydown"
            />
            <Button v-if="supportsDirPicker" type="button" variant="outline" size="sm" :disabled="workspaceSaving" @click="browseWorkspaceDir">选择目录</Button>
            <Button type="button" variant="ghost" size="sm" :disabled="workspaceSaving" @click="resetWorkspaceDir">恢复默认</Button>
          </div>
        </SettingItem>
      </SettingSection>

      <SettingSection title="辅助操作" description="管理只在当前浏览器中生效的引导状态。">
        <SettingItem label="重新开始引导" description="清除本地引导完成标记，刷新后重新进入引导流程。">
          <Button type="button" variant="outline" @click="restartOnboarding">重新开始</Button>
        </SettingItem>
      </SettingSection>
    </div>

    <div class="border-t border-border/55 pt-4"></div>

    <ConfirmDialog
      v-model:show="showRestartOnboardingConfirm"
      title="重新开始引导"
      message="这会清除本地引导完成标记，并在刷新后重新打开引导流程。"
      confirm-label="重新开始"
      @confirm="confirmRestartOnboarding"
    />

    <ConfirmDialog
      v-model:show="showRestartForDataDir"
      title="重启应用"
      message="数据目录已修改，需要重启应用才能生效。注意：原目录中的数据不会自动迁移。"
      confirm-label="重启"
      @confirm="confirmRestartForDataDir"
    />
  </div>
</template>
