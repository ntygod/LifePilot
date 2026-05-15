<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingAdvanced from '@/components/settings/SettingAdvanced.vue'
import { Badge } from '@/components/ui/badge'
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
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { AlertTriangle, Info, Plus, Trash2 } from 'lucide-vue-next'
import { logger } from '@/utils/logger'
import { useSettings } from '@/composables/useSettings'
import { settingsApi } from '@/api/client'
import type { PathSettingsResponse } from '@/api/client'

const isTauri = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__

const { settings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  layoutDensity: 'standard' as 'compact' | 'standard',
  fontSize: 'medium' as 'small' | 'medium' | 'large',
  showTokenUsage: true,
})

// ---- 路径统一配置（HOME / WORKSPACE / PathAccessControl） ----
const pathSettings = ref<PathSettingsResponse | null>(null)
const pathLoading = ref(false)
const homePath = ref('')
const homeModified = ref(false)
const homeSaving = ref(false)
const workspacePath = ref('')
const workspaceSaving = ref(false)
const showRestartConfirm = ref(false)

// PathAccessControl 状态
const accessMode = ref('unrestricted')
const whitelist = ref<string[]>([])
const blacklist = ref<string[]>([])
const newWhitelistEntry = ref('')
const newBlacklistEntry = ref('')
const accessSaving = ref(false)

const accessModeOptions = [
  { value: 'unrestricted', label: '不限制' },
  { value: 'whitelist-only', label: '仅白名单' },
  { value: 'blacklist-only', label: '仅黑名单' },
  { value: 'whitelist-plus-blacklist', label: '白名单 + 黑名单' },
] as const

async function loadPathSettings() {
  pathLoading.value = true
  try {
    const result = await settingsApi.getPathSettings()
    pathSettings.value = result
    homePath.value = result.home
    workspacePath.value = result.workspace
    accessMode.value = result.pathAccess.mode
    whitelist.value = [...result.pathAccess.whitelist]
    blacklist.value = [...result.pathAccess.blacklist]
    homeModified.value = result.restartRequired
  } catch (e) {
    logger.error('加载路径配置失败:', e)
  } finally {
    pathLoading.value = false
  }
}

async function saveHomePath() {
  const trimmed = homePath.value.trim()
  if (!trimmed || trimmed === pathSettings.value?.home) return
  homeSaving.value = true
  try {
    const result = await settingsApi.updatePathSettings({ home: trimmed })
    pathSettings.value = result
    homePath.value = result.home
    homeModified.value = result.restartRequired
  } catch (e) {
    logger.error('保存 HOME 路径失败:', e)
  } finally {
    homeSaving.value = false
  }
}

async function saveWorkspacePath() {
  const trimmed = workspacePath.value.trim()
  if (!trimmed || trimmed === pathSettings.value?.workspace) return
  workspaceSaving.value = true
  try {
    const result = await settingsApi.updatePathSettings({ workspace: trimmed })
    pathSettings.value = result
    workspacePath.value = result.workspace
  } catch (e) {
    logger.error('保存 WORKSPACE 路径失败:', e)
  } finally {
    workspaceSaving.value = false
  }
}

async function browseHomePath() {
  if (!isTauri) return
  try {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const selected = await open({ directory: true, title: '选择 HOME 目录' })
    if (selected && typeof selected === 'string') {
      homePath.value = selected
      await saveHomePath()
    }
  } catch (e) {
    logger.error('选择目录失败:', e)
  }
}

async function browseWorkspacePath() {
  if (!isTauri) return
  try {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const selected = await open({ directory: true, title: '选择工作目录' })
    if (selected && typeof selected === 'string') {
      workspacePath.value = selected
      await saveWorkspacePath()
    }
  } catch (e) {
    logger.error('选择目录失败:', e)
  }
}

function handleHomeInputBlur() {
  const trimmed = homePath.value.trim()
  if (trimmed && trimmed !== pathSettings.value?.home) {
    saveHomePath()
  }
}

function handleHomeInputKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    (event.target as HTMLInputElement)?.blur()
  }
}

function handleWorkspaceInputBlur() {
  const trimmed = workspacePath.value.trim()
  if (trimmed && trimmed !== pathSettings.value?.workspace) {
    saveWorkspacePath()
  }
}

function handleWorkspaceInputKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    (event.target as HTMLInputElement)?.blur()
  }
}

function confirmRestart() {
  showRestartConfirm.value = false
  window.location.reload()
}

// ---- PathAccessControl 操作 ----
async function updateAccessMode(value: unknown) {
  if (typeof value !== 'string') return
  accessMode.value = value
  await savePathAccess()
}

function addWhitelistEntry() {
  const entry = newWhitelistEntry.value.trim()
  if (!entry || whitelist.value.includes(entry)) return
  whitelist.value.push(entry)
  newWhitelistEntry.value = ''
  savePathAccess()
}

function removeWhitelistEntry(index: number) {
  whitelist.value.splice(index, 1)
  savePathAccess()
}

function addBlacklistEntry() {
  const entry = newBlacklistEntry.value.trim()
  if (!entry || blacklist.value.includes(entry)) return
  blacklist.value.push(entry)
  newBlacklistEntry.value = ''
  savePathAccess()
}

function removeBlacklistEntry(index: number) {
  blacklist.value.splice(index, 1)
  savePathAccess()
}

function handleWhitelistKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault()
    addWhitelistEntry()
  }
}

function handleBlacklistKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault()
    addBlacklistEntry()
  }
}

async function savePathAccess() {
  accessSaving.value = true
  try {
    const result = await settingsApi.updatePathSettings({
      pathAccess: {
        mode: accessMode.value,
        whitelist: whitelist.value,
        blacklist: blacklist.value,
      }
    })
    pathSettings.value = result
    accessMode.value = result.pathAccess.mode
    whitelist.value = [...result.pathAccess.whitelist]
    blacklist.value = [...result.pathAccess.blacklist]
  } catch (e) {
    logger.error('保存路径权限配置失败:', e)
  } finally {
    accessSaving.value = false
  }
}

// ---- 外部 CLI Bash 依赖（Claude Code / Codex 在 Windows 上需要 Unix bash） ----
const externalCliBashPath = ref('')
const externalCliBashInputValue = ref('')
const externalCliBashSaving = ref(false)

async function loadExternalCliBash() {
  try {
    const r = await settingsApi.getExternalCliBashSettings()
    externalCliBashPath.value = r.externalCliBashPath ?? ''
    externalCliBashInputValue.value = r.externalCliBashPath ?? ''
  } catch (e) {
    logger.error('加载外部 CLI Bash 设置失败:', e)
  }
}

async function saveExternalCliBash(value: string) {
  externalCliBashSaving.value = true
  try {
    const r = await settingsApi.updateExternalCliBashSettings({
      externalCliBashPath: value || null
    })
    externalCliBashPath.value = r.externalCliBashPath ?? ''
    externalCliBashInputValue.value = r.externalCliBashPath ?? ''
  } catch (e) {
    logger.error('保存外部 CLI Bash 路径失败:', e)
  } finally {
    externalCliBashSaving.value = false
  }
}

async function browseExternalCliBash() {
  if (isTauri) {
    try {
      const { open } = await import('@tauri-apps/plugin-dialog')
      const selected = await open({
        title: '选择 Bash 可执行文件',
        filters: [{ name: 'bash', extensions: ['exe'] }],
      })
      if (selected && typeof selected === 'string') {
        externalCliBashInputValue.value = selected
        await saveExternalCliBash(selected)
      }
    } catch (e) {
      logger.error('选择 Bash 文件失败:', e)
    }
  }
}

async function resetExternalCliBash() {
  externalCliBashInputValue.value = ''
  await saveExternalCliBash('')
}

function handleExternalCliBashBlur() {
  const trimmed = externalCliBashInputValue.value.trim()
  if (trimmed !== (externalCliBashPath.value || '')) {
    saveExternalCliBash(trimmed)
  }
}

function handleExternalCliBashKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    (event.target as HTMLInputElement)?.blur()
  }
}

// ---- 通用设置 ----
const saveError = ref<string | null>(null)
const showRestartOnboardingConfirm = ref(false)

/** 浏览器是否支持原生目录选择（仅 Tauri 桌面端） */
const supportsDirPicker = isTauri

onMounted(() => {
  form.value = {
    theme: settings.value.theme,
    layoutDensity: settings.value.layoutDensity,
    fontSize: settings.value.fontSize,
    showTokenUsage: settings.value.showTokenUsage,
  }
  loadPathSettings()
  loadExternalCliBash()
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
    <section class="pb-4 border-b border-border/40">
      <div class="space-y-1.5">
        <div class="surface-label text-[0.68rem]">通用</div>
        <h2 class="text-xl font-semibold text-foreground">界面与显示</h2>
        <p class="max-w-[42rem] text-[13px] leading-relaxed text-muted-foreground">这部分控制当前设备上的显示方式和基础交互习惯。</p>
      </div>
      <p v-if="saveError" class="mt-3 text-sm text-destructive">{{ saveError }}</p>
    </section>

    <div class="space-y-10">
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

      <!-- 目录管理区域 -->
      <SettingAdvanced
        title="目录管理"
        description="HOME 数据目录、工作目录和路径权限控制。"
      >
        <div class="space-y-8">
          <!-- HOME 路径 -->
          <SettingSection title="HOME 数据目录" description="存放数据库、知识库、技能和工作流等所有用户数据。修改后需重启应用。">
            <SettingItem label="HOME 路径" :description="pathLoading ? '加载中...' : ('当前生效路径：' + (pathSettings?.home ?? ''))">
              <div class="flex items-center gap-sm">
                <Input
                  v-model="homePath"
                  class="min-w-2xl"
                  placeholder="绝对路径，如 D:/zhiwei"
                  :disabled="homeSaving"
                  @blur="handleHomeInputBlur"
                  @keydown="handleHomeInputKeydown"
                />
                <Button v-if="supportsDirPicker" type="button" variant="outline" size="sm" :disabled="homeSaving" @click="browseHomePath">选择目录</Button>
              </div>
            </SettingItem>
            <!-- restart-required 警告 -->
            <div v-if="homeModified" class="flex items-center gap-3 rounded-md bg-warning/10 px-4 py-2.5 text-sm text-warning-foreground">
              <AlertTriangle class="size-4 shrink-0" />
              <span>HOME 目录已修改，重启应用后生效。</span>
              <Badge variant="destructive" class="ml-auto">需要重启</Badge>
              <Button v-if="isTauri" type="button" variant="outline" size="sm" @click="showRestartConfirm = true">立即重启</Button>
            </div>
          </SettingSection>

          <!-- WORKSPACE 路径 -->
          <SettingSection title="工作目录" description="Agent 默认工作目录（Shell、文件写入等操作的 cwd）。修改后立即生效，无需重启。">
            <SettingItem label="WORKSPACE 路径" :description="pathLoading ? '加载中...' : ('当前生效路径：' + (pathSettings?.workspace ?? ''))">
              <div class="flex items-center gap-sm">
                <Input
                  v-model="workspacePath"
                  class="min-w-2xl"
                  placeholder="绝对路径，如 D:/workspace"
                  :disabled="workspaceSaving"
                  @blur="handleWorkspaceInputBlur"
                  @keydown="handleWorkspaceInputKeydown"
                />
                <Button v-if="supportsDirPicker" type="button" variant="outline" size="sm" :disabled="workspaceSaving" @click="browseWorkspacePath">选择目录</Button>
              </div>
            </SettingItem>
          </SettingSection>

          <!-- PathAccessControl 路径权限控制 -->
          <SettingSection title="路径权限控制" description="控制 Agent 可访问的本机目录范围，防止误操作敏感文件。">
            <SettingItem label="访问控制模式" description="选择路径权限的控制策略。">
              <Select :model-value="accessMode" @update:model-value="updateAccessMode">
                <SelectTrigger class="w-56"><SelectValue placeholder="选择模式" /></SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in accessModeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>

            <!-- 白名单管理 -->
            <SettingItem
              v-if="accessMode === 'whitelist-only' || accessMode === 'whitelist-plus-blacklist'"
              label="白名单"
              description="仅允许 Agent 访问以下路径前缀下的文件。"
            >
              <div class="space-y-2 w-full max-w-2xl">
                <div v-for="(entry, index) in whitelist" :key="'wl-' + index" class="flex items-center gap-2">
                  <code class="flex-1 rounded bg-muted px-3 py-1.5 text-sm font-mono text-foreground truncate">{{ entry }}</code>
                  <Button type="button" variant="ghost" size="sm" :disabled="accessSaving" @click="removeWhitelistEntry(index)">
                    <Trash2 class="size-4 text-muted-foreground" />
                  </Button>
                </div>
                <div class="flex items-center gap-2">
                  <Input
                    v-model="newWhitelistEntry"
                    class="flex-1"
                    placeholder="输入路径前缀，如 D:/Projects"
                    :disabled="accessSaving"
                    @keydown="handleWhitelistKeydown"
                  />
                  <Button type="button" variant="outline" size="sm" :disabled="accessSaving || !newWhitelistEntry.trim()" @click="addWhitelistEntry">
                    <Plus class="size-4" />
                  </Button>
                </div>
              </div>
            </SettingItem>

            <!-- 黑名单管理 -->
            <SettingItem
              v-if="accessMode === 'blacklist-only' || accessMode === 'whitelist-plus-blacklist'"
              label="黑名单"
              description="禁止 Agent 访问以下路径前缀下的文件。"
            >
              <div class="space-y-2 w-full max-w-2xl">
                <div v-for="(entry, index) in blacklist" :key="'bl-' + index" class="flex items-center gap-2">
                  <code class="flex-1 rounded bg-muted px-3 py-1.5 text-sm font-mono text-foreground truncate">{{ entry }}</code>
                  <Button type="button" variant="ghost" size="sm" :disabled="accessSaving" @click="removeBlacklistEntry(index)">
                    <Trash2 class="size-4 text-muted-foreground" />
                  </Button>
                </div>
                <div class="flex items-center gap-2">
                  <Input
                    v-model="newBlacklistEntry"
                    class="flex-1"
                    placeholder="输入路径前缀，如 C:/Windows"
                    :disabled="accessSaving"
                    @keydown="handleBlacklistKeydown"
                  />
                  <Button type="button" variant="outline" size="sm" :disabled="accessSaving || !newBlacklistEntry.trim()" @click="addBlacklistEntry">
                    <Plus class="size-4" />
                  </Button>
                </div>
              </div>
            </SettingItem>
          </SettingSection>

          <!-- 外部 CLI Bash 依赖 -->
          <SettingSection
            title="外部 CLI 依赖"
            description="为需要 Unix bash 的外部 CLI（Claude Code、Codex 等）提供 bash 可执行文件路径。仅 Windows 下需要配置。"
          >
            <SettingItem>
              <template #label>
                <div class="flex items-center gap-xs">
                  <span>Bash 可执行文件路径</span>
                  <TooltipProvider :delay-duration="200">
                    <Tooltip>
                      <TooltipTrigger as-child>
                        <button
                          type="button"
                          class="inline-flex size-4 items-center justify-center rounded-full text-muted-foreground hover:text-foreground focus:outline-none"
                          aria-label="查看说明"
                        >
                          <Info class="size-4" />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent side="right" class="max-w-[24rem] text-xs leading-relaxed">
                        <p class="mb-xs font-medium">为什么需要这个？</p>
                        <p class="mb-xs">
                          Claude Code / Codex 等 CLI 在 Windows 上会自动调用 Unix 命令（<code>grep</code>、<code>sed</code>、<code>git</code> 等），
                          需要一个兼容 POSIX 的 bash 环境。最常见的来源是 <strong>Git for Windows</strong> 附带的 <code>bash.exe</code>。
                        </p>
                        <p class="mb-xs font-medium">典型路径</p>
                        <ul class="mb-xs list-disc space-y-xs pl-md">
                          <li><code>C:\Program Files\Git\bin\bash.exe</code></li>
                          <li><code>D:\WorkSpace\Git\usr\bin\bash.exe</code></li>
                        </ul>
                        <p class="text-muted-foreground">
                          配置后，知微启动 claude/codex 时会自动注入 <code>CLAUDE_CODE_GIT_BASH_PATH</code> 环境变量。留空表示不注入，CLI 会自行处理失败。
                        </p>
                      </TooltipContent>
                    </Tooltip>
                  </TooltipProvider>
                </div>
              </template>
              <template #description>
                <span v-if="externalCliBashPath">当前配置：{{ externalCliBashPath }}</span>
                <span v-else>未配置（Claude Code / Codex 在 Windows 上可能无法启动）</span>
              </template>
              <div class="flex items-center gap-sm">
                <Input
                  v-model="externalCliBashInputValue"
                  class="min-w-2xl"
                  placeholder="留空不注入环境变量"
                  :disabled="externalCliBashSaving"
                  @blur="handleExternalCliBashBlur"
                  @keydown="handleExternalCliBashKeydown"
                />
                <Button v-if="isTauri" type="button" variant="outline" size="sm" :disabled="externalCliBashSaving" @click="browseExternalCliBash">选择文件</Button>
                <Button type="button" variant="ghost" size="sm" :disabled="externalCliBashSaving" @click="resetExternalCliBash">清除</Button>
              </div>
            </SettingItem>
          </SettingSection>
        </div>
      </SettingAdvanced>

      <SettingSection title="辅助操作" description="管理只在当前浏览器中生效的引导状态。">
        <SettingItem label="重新开始引导" description="清除本地引导完成标记，刷新后重新进入引导流程。">
          <Button type="button" variant="outline" @click="restartOnboarding">重新开始</Button>
        </SettingItem>
      </SettingSection>
    </div>

    <ConfirmDialog
      v-model:show="showRestartOnboardingConfirm"
      title="重新开始引导"
      message="这会清除本地引导完成标记，并在刷新后重新打开引导流程。"
      confirm-label="重新开始"
      @confirm="confirmRestartOnboarding"
    />

    <ConfirmDialog
      v-model:show="showRestartConfirm"
      title="重启应用"
      message="HOME 目录已修改，需要重启应用才能生效。数据将自动迁移到新目录。"
      confirm-label="重启"
      @confirm="confirmRestart"
    />
  </div>
</template>
