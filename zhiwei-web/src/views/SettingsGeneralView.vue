<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
import { AlertTriangle, Archive, CheckCircle2, Copy, FileArchive, FolderArchive, FolderOpen, Info, Loader2, Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import { logger } from '@/utils/logger'
import { useSettings } from '@/composables/useSettings'
import { diagnosticsApi, settingsApi } from '@/api/client'
import type { PathSettingsResponse } from '@/api/client'
import { useUiStore } from '@/stores/ui'
import type {
  DiagnosticBundleInfo,
  DiagnosticCheck,
  DiagnosticReport,
  LocalBackupFileInfo,
  LocalBackupRestorePlanInfo,
  LocalBackupRestorePreparationInfo,
  LocalBackupValidationInfo,
} from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import { buildBackupRestoreChecklist, buildLocalMaintenanceDiagnostic } from '@/utils/errorDiagnostic'
import { isTauriEnv, revealInFileManager } from '@/composables/useSaveFilePicker'

const isTauri = computed(() => isTauriEnv())

const { settings, saveSettings } = useSettings()
const uiStore = useUiStore()

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

// ---- 本地数据维护：诊断状态 + 备份 ----
const diagnosticReport = ref<DiagnosticReport | null>(null)
const backupFiles = ref<LocalBackupFileInfo[]>([])
const maintenanceLoading = ref(false)
const maintenanceError = ref<string | null>(null)
const maintenanceNotice = ref<string | null>(null)
const creatingDiagnosticBundle = ref(false)
const latestDiagnosticBundle = ref<DiagnosticBundleInfo | null>(null)
const creatingBackup = ref(false)
const validatingBackupFileName = ref<string | null>(null)
const backupValidation = ref<LocalBackupValidationInfo | null>(null)
const preparingBackupRestoreFileName = ref<string | null>(null)
const backupRestorePreparation = ref<LocalBackupRestorePreparationInfo | null>(null)

const backupCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'data-backups') ?? null
)
const latestBackups = computed(() => backupFiles.value.slice(0, 3))
const latestBackup = computed(() => latestBackups.value[0] ?? null)
const backupDirectory = computed(() =>
  valueAsText(backupCheck.value?.metadata?.directory) ?? '尚未读取'
)
const backupStatusLabel = computed(() => {
  const check = backupCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'OK') return '已保护'
  if (check.status === 'ERROR') return '需要处理'
  return '建议备份'
})
const backupStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (backupCheck.value?.status === 'ERROR') return 'destructive'
  if (backupCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const databaseCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'database') ?? null
)
const databaseMetadata = computed(() => databaseCheck.value?.metadata ?? {})
const databaseStatusLabel = computed(() => {
  const check = databaseCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'ERROR') return '需要处理'
  if (check.status === 'WARN') return '需确认'
  return '可访问'
})
const databaseStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (databaseCheck.value?.status === 'ERROR') return 'destructive'
  if (databaseCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const databaseRows = computed(() => [
  ['位置', formatDatabaseLocation(databaseMetadata.value.databaseLocation)],
  ['SQLite', valueAsText(databaseMetadata.value.sqliteVersion) ?? '-'],
  ['数据库文件', valueAsText(databaseMetadata.value.databasePath) ?? '-'],
  ['数据库大小', formatBytesUnknown(databaseMetadata.value.databaseSizeBytes)],
  ['WAL', formatLocalFileState(databaseMetadata.value.walExists, databaseMetadata.value.walSizeBytes)],
  ['SHM', formatLocalFileState(databaseMetadata.value.shmExists, databaseMetadata.value.shmSizeBytes)],
  ['Journal', valueAsText(databaseMetadata.value.journalMode) ?? '-'],
  ['Busy timeout', formatMilliseconds(databaseMetadata.value.busyTimeoutMs)],
])
const schemaMigrationCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'schema-migrations') ?? null
)
const schemaMigrationMetadata = computed(() => schemaMigrationCheck.value?.metadata ?? {})
const schemaMigrationStatusLabel = computed(() => {
  const check = schemaMigrationCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'ERROR') return '需要修复'
  if (check.status === 'WARN') return '需确认'
  return '正常'
})
const schemaMigrationStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (schemaMigrationCheck.value?.status === 'ERROR') return 'destructive'
  if (schemaMigrationCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const schemaMigrationRows = computed(() => [
  ['迁移数', formatCount(schemaMigrationMetadata.value.total)],
  ['失败数', formatCount(schemaMigrationMetadata.value.failed)],
  ['最新版本', valueAsText(schemaMigrationMetadata.value.latestVersion) ?? '-'],
  ['最新脚本', valueAsText(schemaMigrationMetadata.value.latestScript) ?? '-'],
  ['失败脚本', formatTextList(schemaMigrationMetadata.value.failedMigrationScripts)],
  ['历史表', schemaMigrationMetadata.value.historyTable === false ? '未发现' : '已读取'],
  ['安装时间', valueAsText(schemaMigrationMetadata.value.latestInstalledOn) ?? '-'],
])
const schemaMigrationNextSteps = computed(() => {
  const check = schemaMigrationCheck.value
  if (!check) return ['刷新本地诊断后查看数据库迁移状态。']
  if (check.status === 'ERROR') {
    return [
      '暂停继续写入数据，先保留启动日志和本地诊断摘要。',
      '检查失败迁移脚本和 flyway_schema_history 后再重启应用。',
      '修复前先创建 HOME 备份，避免二次损坏。',
    ]
  }
  if (check.status === 'WARN') {
    return [
      '如果这是首次启动，可以等待应用完成初始化后刷新诊断。',
      '如果已使用过知微，先创建备份，再检查 Flyway 历史表是否缺失。',
    ]
  }
  return ['数据库迁移历史正常；升级前仍建议先创建本地备份。']
})
const validationStatusLabel = computed(() => {
  const validation = backupValidation.value
  if (!validation) return '未校验'
  if (validation.status === 'OK') return '校验通过'
  if (validation.status === 'ERROR') return '校验失败'
  return '建议更新'
})
const validationStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (backupValidation.value?.status === 'ERROR') return 'destructive'
  if (backupValidation.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const desktopDistributionCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'desktop-distribution') ?? null
)
const desktopDistributionMetadata = computed(() => desktopDistributionCheck.value?.metadata ?? {})
const desktopDistributionStatusLabel = computed(() => {
  const check = desktopDistributionCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'OK') return '可发布'
  if (check.status === 'ERROR') return '需要处理'
  return '待完善'
})
const desktopDistributionStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (desktopDistributionCheck.value?.status === 'ERROR') return 'destructive'
  if (desktopDistributionCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const modelServiceCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'model-services') ?? null
)
const modelServiceMetadata = computed(() => modelServiceCheck.value?.metadata ?? {})
const modelServiceStatusLabel = computed(() => {
  const check = modelServiceCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'ERROR') return '配置异常'
  if (valueAsNumber(modelServiceMetadata.value.generationEnabled) === 0) return '主对话不可用'
  if (check.status === 'WARN') return '能力降级'
  return '已就绪'
})
const modelServiceStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (modelServiceCheck.value?.status === 'ERROR') return 'destructive'
  if (modelServiceCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const modelServiceRows = computed(() => [
  ['主对话模型', formatEnabledAndUnhealthy(
    modelServiceMetadata.value.generationEnabled,
    modelServiceMetadata.value.generationUnhealthy,
  )],
  ['向量增强', formatEnabledAndUnhealthy(
    modelServiceMetadata.value.embeddingEnabled,
    modelServiceMetadata.value.embeddingUnhealthy,
  )],
  ['重排服务', formatEnabledCount(modelServiceMetadata.value.rerankEnabled)],
  ['启用总数', formatEnabledCount(modelServiceMetadata.value.enabled)],
])
const intelligenceCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'intelligence') ?? null
)
const intelligenceMetadata = computed(() => intelligenceCheck.value?.metadata ?? {})
const intelligenceStatusLabel = computed(() => {
  const check = intelligenceCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'ERROR') return '配置异常'
  if (check.status === 'WARN') return '需关注'
  if (formatExperienceMatchTrigger(intelligenceMetadata.value.experienceMatchTrigger) === '已关闭') return '仅信号'
  return '后台增强'
})
const intelligenceStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (intelligenceCheck.value?.status === 'ERROR') return 'destructive'
  if (intelligenceCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const intelligenceSummaryRows = computed(() => [
  ['决策信号', formatBooleanState(intelligenceMetadata.value.decisionSignalEnabled)],
  ['经验匹配', formatExperienceMatchTrigger(intelligenceMetadata.value.experienceMatchTrigger)],
  ['前台', formatForegroundExperienceWait(intelligenceMetadata.value)],
  ['经验后台', formatMaxPending(intelligenceMetadata.value.experienceMatchMaxPending)],
  ['经验超时', formatMilliseconds(intelligenceMetadata.value.experienceMatchBackgroundTimeoutMs)],
  ['工具经验', formatMaxPending(intelligenceMetadata.value.maxPendingToolExperienceRecords)],
  ['工具超时', formatMilliseconds(intelligenceMetadata.value.toolExperienceRecordTimeoutMs)],
  ['最近复用', formatRecentReuse(
    intelligenceMetadata.value.experienceMatchRecentTtlSeconds,
    intelligenceMetadata.value.experienceMatchRecentMax,
  )],
])
const capabilitiesCheck = computed<DiagnosticCheck | null>(() =>
  diagnosticReport.value?.checks.find(check => check.id === 'capabilities') ?? null
)
const capabilitiesMetadata = computed(() => capabilitiesCheck.value?.metadata ?? {})
const capabilitiesStatusLabel = computed(() => {
  const check = capabilitiesCheck.value
  if (!check) return maintenanceLoading.value ? '读取中' : '未读取'
  if (check.status === 'ERROR') return '需要处理'
  if (check.status === 'WARN') return '需校准'
  return '可执行'
})
const capabilitiesStatusVariant = computed<'secondary' | 'destructive' | 'outline'>(() => {
  if (capabilitiesCheck.value?.status === 'ERROR') return 'destructive'
  if (capabilitiesCheck.value?.status === 'OK') return 'secondary'
  return 'outline'
})
const capabilitiesSummaryRows = computed(() => [
  ['技能', formatEnabledCount(capabilitiesMetadata.value.skills)],
  ['工具', formatEnabledCount(capabilitiesMetadata.value.tools)],
  ['技能工具引用', formatRegisteredReferences(
    capabilitiesMetadata.value.registeredSkillToolReferences,
    capabilitiesMetadata.value.skillSuggestedToolReferences,
  )],
  ['标准工具缺失', formatEnabledCount(capabilitiesMetadata.value.missingCanonicalSkillToolReferences)],
  ['未知工具', formatEnabledCount(capabilitiesMetadata.value.unknownSkillToolReferences)],
  ['高风险工具', formatEnabledCount(capabilitiesMetadata.value.highRiskTools)],
  ['工具分类', formatToolCategories(capabilitiesMetadata.value.toolCategories)],
])
const capabilitiesUnknownSamples = computed(() =>
  stringArrayValue(capabilitiesMetadata.value.unknownSkillToolReferenceSamples).slice(0, 3)
)
const capabilitiesMissingCanonicalSamples = computed(() =>
  stringArrayValue(capabilitiesMetadata.value.missingCanonicalSkillToolReferenceSamples).slice(0, 3)
)
const capabilitiesNextSteps = computed(() => {
  const check = capabilitiesCheck.value
  const metadata = capabilitiesMetadata.value
  if (!check) return ['刷新本地诊断后查看工具和技能可用状态。']
  if (check.status !== 'WARN' && check.status !== 'ERROR') {
    return ['工具和技能可用，技能引用的工具可被识别。']
  }
  const steps: string[] = []
  if (metadata.skillRegistryEnabled === false || metadata.toolRegistryEnabled === false) {
    steps.push('检查工具或技能能力是否启用，必要时重启应用。')
  }
  if ((valueAsNumber(metadata.unknownSkillToolReferences) ?? 0) > 0) {
    steps.push('修正 Skill 元数据里的 suggestedTools，避免引用未知工具 ID。')
  }
  if ((valueAsNumber(metadata.missingCanonicalSkillToolReferences) ?? 0) > 0) {
    steps.push('确认核心工具提供方可用，缺失时先修复工具能力后再执行相关 Skill。')
  }
  if ((valueAsNumber(metadata.tools) ?? 0) === 0) {
    steps.push('当前没有可用工具，复杂任务会降级到纯对话。')
  }
  if (steps.length === 0) {
    steps.push('检查工具、技能和执行授权后再重试相关任务。')
  }
  return steps
})
const distributionVersionRows = computed(() => [
  ['后端', valueAsText(desktopDistributionMetadata.value.backendVersion) ?? '-'],
  ['Web', valueAsText(desktopDistributionMetadata.value.packageVersion) ?? '-'],
  ['桌面端', valueAsText(desktopDistributionMetadata.value.tauriVersion) ?? '-'],
  ['Cargo', valueAsText(desktopDistributionMetadata.value.cargoVersion) ?? '-'],
])
const distributionArtifactRows = computed(() => [
  ['产物目录', valueAsText(desktopDistributionMetadata.value.packageArtifactDirectory) ?? '-'],
  ['产物数量', formatCount(desktopDistributionMetadata.value.packageArtifactCount)],
  ['最新产物', valueAsText(desktopDistributionMetadata.value.latestPackageArtifact) ?? '-'],
  ['产物大小', formatBytesUnknown(desktopDistributionMetadata.value.latestPackageArtifactSizeBytes)],
  ['产物时间', valueAsText(desktopDistributionMetadata.value.latestPackageArtifactModifiedAt) ?? '-'],
])
const packageArtifactRevealPath = computed(() =>
  valueAsText(desktopDistributionMetadata.value.latestPackageArtifactPath)
    ?? valueAsText(desktopDistributionMetadata.value.packageArtifactDirectory)
)
const packageArtifactRevealLabel = computed(() =>
  valueAsText(desktopDistributionMetadata.value.latestPackageArtifactPath) ? '定位安装包' : '打开产物目录'
)
const distributionCommandRows = computed(() => [
  ['准备内嵌 JRE', valueAsText(desktopDistributionMetadata.value.embeddedJrePrepareCommand) ?? 'cd zhiwei-web && npm run tauri:prepare:jre'],
  ['后端打包', valueAsText(desktopDistributionMetadata.value.backendBuildCommand) ?? 'mvn clean package -DskipTests'],
  ['前端构建', valueAsText(desktopDistributionMetadata.value.frontendBuildCommand) ?? 'cd zhiwei-web && npm run build'],
  ['桌面安装包', valueAsText(desktopDistributionMetadata.value.desktopBuildCommand) ?? 'cd zhiwei-web && npm run tauri:build'],
  ['Windows 安装包', valueAsText(desktopDistributionMetadata.value.windowsBuildCommand) ?? 'cd zhiwei-web && npm run tauri:build:windows'],
])
const distributionToolchainRows = computed(() => [
  ['Maven CLI', formatCommandProbe(
    desktopDistributionMetadata.value.mavenCliAvailable,
    desktopDistributionMetadata.value.mavenCliVersion,
  )],
  ['Cargo CLI', formatCommandProbe(
    desktopDistributionMetadata.value.cargoCliAvailable,
    desktopDistributionMetadata.value.cargoCliVersion,
  )],
  ['Rustc CLI', formatCommandProbe(
    desktopDistributionMetadata.value.rustcCliAvailable,
    desktopDistributionMetadata.value.rustcCliVersion,
  )],
])
const distributionUpdaterRows = computed(() => [
  ['更新插件', formatDistributionFlag(desktopDistributionMetadata.value.updaterDependency, '已安装', '未安装')],
  ['更新配置', formatDistributionFlag(desktopDistributionMetadata.value.updaterConfigured, '已配置', '未配置')],
  ['更新产物', formatDistributionFlag(desktopDistributionMetadata.value.updaterArtifactsConfigured, '已启用', '未启用')],
  ['签名公钥', formatDistributionFlag(desktopDistributionMetadata.value.updaterPubkeyConfigured, '已配置', '未配置')],
  ['发布源', formatUpdaterEndpointLabel(desktopDistributionMetadata.value)],
])
const missingDistributionToolchainParts = computed(() => missingBuildToolchainParts(desktopDistributionMetadata.value))
const distributionBuildToolchainLabel = computed(() => formatBuildToolchainLabel(desktopDistributionMetadata.value))
const distributionNextSteps = computed(() => {
  const metadata = desktopDistributionMetadata.value
  const steps: string[] = []
  if (!desktopDistributionCheck.value) {
    return ['刷新本地诊断后查看安装包和更新配置状态。']
  }
  if (metadata.versionsAligned === false) {
    steps.push('发布前对齐后端、Web、Tauri 和 Cargo 版本号。')
  }
  if (metadata.bundleActive === false) {
    steps.push('启用 Tauri 打包配置并生成安装包。')
  }
  if (metadata.buildScriptsAligned === false) {
    steps.push('修正 package.json 构建脚本和 Tauri beforeBuildCommand，确保发布命令能生成安装包。')
  }
  if (metadata.buildToolchainReady === false) {
    const missing = missingBuildToolchainParts(metadata)
    const labels = missing.length > 0 ? missing.map(part => part.label) : ['Maven', 'Cargo', 'Rustc']
    const commands = missing.length > 0 ? missing.map(part => part.command) : ['mvn', 'cargo', 'rustc']
    steps.push(`安装 ${labels.join('、')} 并确认 ${commands.join('、')} 可以在当前终端执行。`)
  }
  if (metadata.embeddedJreReady === false) {
    const command = valueAsText(metadata.embeddedJrePrepareCommand) ?? 'cd zhiwei-web && npm run tauri:prepare:jre'
    steps.push(`运行 ${command} 准备内嵌 JRE 22，Windows 安装包发布检查会要求它可用。`)
  }
  if (metadata.updaterDependency === false) {
    steps.push('安装并注册 Tauri updater 插件。')
  }
  if (metadata.updaterArtifactsConfigured === false) {
    steps.push('在 Tauri bundle 配置中启用 createUpdaterArtifacts，生成可发布的更新产物。')
  }
  if (metadata.updaterConfigured === false
    || metadata.updaterPubkeyConfigured === false
    || metadata.updaterEndpointsConfigured === false) {
    steps.push('配置 updater pubkey 和 endpoints，并确认发布源会返回当前平台的更新清单。')
  }
  if (valueAsNumber(metadata.packageArtifactCount) === 0) {
    steps.push('运行桌面端打包命令并确认 bundle 目录生成安装包产物。')
  }
  if (metadata.runningFromJar === false) {
    steps.push('当前可能是开发运行，安装包验证需使用打包产物。')
  }
  if (steps.length === 0) {
    steps.push('安装包和更新配置可读取，发布前继续跑完整打包验证。')
  }
  return steps
})

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

function valueAsText(value: unknown) {
  if (value === null || value === undefined || value === '') return null
  return String(value)
}

function formatTextList(value: unknown, maxItems = 3) {
  if (!Array.isArray(value) || value.length === 0) return '-'
  const items = value.map(item => valueAsText(item)).filter((item): item is string => !!item)
  if (items.length === 0) return '-'
  const visible = items.slice(0, maxItems).join('、')
  return items.length > maxItems ? `${visible} 等 ${items.length} 项` : visible
}

function canRevealPath(value: unknown) {
  const text = valueAsText(value)
  return !!text && text !== '-' && text !== '尚未读取'
}

function valueAsNumber(value: unknown) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function formatBooleanState(value: unknown) {
  if (value === true || value === 'true') return '开启'
  if (value === false || value === 'false') return '关闭'
  return '-'
}

function formatExperienceMatchTrigger(value: unknown) {
  const text = valueAsText(value)
  if (!text) return '-'
  const normalized = text.toLowerCase()
  if (normalized === 'disabled') return '已关闭'
  if (normalized === 'task-like') return '任务型触发'
  if (normalized === 'always') return '总是匹配'
  return text
}

function formatDatabaseLocation(value: unknown) {
  const text = valueAsText(value)
  if (!text) return '-'
  if (text === 'file') return '文件'
  if (text === 'memory') return '内存库'
  if (text === 'unknown') return '未知'
  return text
}

function formatLocalFileState(exists: unknown, sizeBytes: unknown) {
  if (exists === true || exists === 'true') {
    return `存在 / ${formatBytesUnknown(sizeBytes)}`
  }
  if (exists === false || exists === 'false') {
    return '未发现'
  }
  return '-'
}

function formatMilliseconds(value: unknown) {
  const numberValue = valueAsNumber(value)
  return numberValue === null ? '-' : `${numberValue}ms`
}

function formatCount(value: unknown) {
  const numberValue = valueAsNumber(value)
  return numberValue === null ? '-' : String(numberValue)
}

function formatEnabledCount(value: unknown) {
  const numberValue = valueAsNumber(value)
  return numberValue === null ? '-' : `${numberValue} 个`
}

function formatEnabledAndUnhealthy(enabled: unknown, unhealthy: unknown) {
  const enabledValue = valueAsNumber(enabled)
  const unhealthyValue = valueAsNumber(unhealthy)
  if (enabledValue === null) return '-'
  if (enabledValue === 0) return '未启用'
  if (unhealthyValue !== null && unhealthyValue > 0) {
    return `${enabledValue} 个 / 异常 ${unhealthyValue} 个`
  }
  return `${enabledValue} 个`
}

function formatRegisteredReferences(registered: unknown, total: unknown) {
  const registeredValue = valueAsNumber(registered)
  const totalValue = valueAsNumber(total)
  if (registeredValue === null && totalValue === null) return '-'
  if (totalValue === null) return `${registeredValue ?? 0} 个可用`
  return `${registeredValue ?? 0}/${totalValue} 可用`
}

function formatToolCategories(value: unknown) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '-'
  const entries = Object.entries(value as Record<string, unknown>)
    .map(([name, count]) => {
      const numberValue = valueAsNumber(count)
      return numberValue === null ? null : `${name}:${numberValue}`
    })
    .filter((entry): entry is string => entry !== null)
    .slice(0, 4)
  return entries.length > 0 ? entries.join(' / ') : '-'
}

function stringArrayValue(value: unknown) {
  return Array.isArray(value)
    ? value.map(item => String(item)).filter(Boolean)
    : []
}

function formatForegroundExperienceWait(metadata: Record<string, unknown>) {
  const effective = valueAsNumber(metadata.effectiveExperienceMatchTimeoutMs)
  const configured = valueAsNumber(metadata.experienceMatchTimeoutMs)
  if (effective === null) return formatMilliseconds(metadata.experienceMatchTimeoutMs)
  if (configured !== null && configured > effective) {
    return `${effective}ms / 配置${configured}ms`
  }
  return `${effective}ms`
}

function formatMaxPending(value: unknown) {
  const numberValue = valueAsNumber(value)
  return numberValue === null ? '-' : `${numberValue} 个`
}

function formatRecentReuse(ttlSeconds: unknown, max: unknown) {
  const ttlValue = valueAsNumber(ttlSeconds)
  const maxValue = valueAsNumber(max)
  if (ttlValue === null || maxValue === null) return '-'
  return `${ttlValue}s / ${maxValue} 条`
}

function resolveErrorMessage(event: unknown, fallback: string) {
  if (event instanceof Error) return event.message
  if (typeof event === 'object' && event !== null && 'message' in event) {
    return String((event as { message?: unknown }).message ?? fallback)
  }
  return typeof event === 'string' ? event : fallback
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value < 0) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function formatBytesUnknown(value: unknown) {
  const numberValue = valueAsNumber(value)
  return numberValue === null ? '-' : formatBytes(numberValue)
}

function formatRestoreSpaceStatus(status?: string | null) {
  switch ((status ?? '').toUpperCase()) {
    case 'OK':
      return '空间充足'
    case 'WARN':
      return '空间不足'
    case 'UNKNOWN':
      return '空间未知'
    default:
      return status || '未读取'
  }
}

function restoreSpaceStatusClass(status?: string | null) {
  switch ((status ?? '').toUpperCase()) {
    case 'OK':
      return 'text-emerald-700'
    case 'WARN':
      return 'text-destructive'
    case 'UNKNOWN':
      return 'text-amber-700'
    default:
      return 'text-muted-foreground'
  }
}

function formatRestoreMode(plan?: LocalBackupRestorePlanInfo | null) {
  if (!plan) return '未读取'
  if (plan.manualRestoreOnly || plan.restoreMode === 'manual-staging') return '手动暂存恢复'
  return valueAsText(plan.restoreMode) ?? '未读取'
}

function formatRestoreItems(items?: string[] | null) {
  const values = items?.filter(Boolean) ?? []
  return values.length > 0 ? values.join('、') : '未记录'
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

async function loadLocalMaintenance() {
  maintenanceLoading.value = true
  maintenanceError.value = null
  try {
    const [report, backups] = await Promise.all([
      diagnosticsApi.getReport(),
      diagnosticsApi.listBackups(),
    ])
    diagnosticReport.value = report
    backupFiles.value = backups
  } catch (event) {
    logger.error('加载本地数据维护状态失败:', event)
    maintenanceError.value = resolveErrorMessage(event, '加载本地数据维护状态失败')
  } finally {
    maintenanceLoading.value = false
  }
}

async function createLocalBackup() {
  if (creatingBackup.value) return
  creatingBackup.value = true
  maintenanceError.value = null
  maintenanceNotice.value = null
  try {
    const backup = await diagnosticsApi.createBackup()
    maintenanceNotice.value = `已创建本地备份：${backup.fileName}`
    uiStore.showToast('success', maintenanceNotice.value)
    await loadLocalMaintenance()
    await validateBackupFile(backup.fileName)
  } catch (event) {
    logger.error('创建本地备份失败:', event)
    const message = resolveErrorMessage(event, '创建本地备份失败')
    maintenanceError.value = message
    uiStore.showToast('error', message)
  } finally {
    creatingBackup.value = false
  }
}

async function validateBackupFile(fileName: string) {
  validatingBackupFileName.value = fileName
  maintenanceError.value = null
  backupRestorePreparation.value = null
  try {
    const result = await diagnosticsApi.validateBackup(fileName)
    backupValidation.value = result
    if (result.status === 'OK') {
      uiStore.showToast('success', '备份校验通过')
    } else if (result.status === 'ERROR') {
      uiStore.showToast('error', result.detail)
    } else {
      uiStore.showToast('info', result.detail)
    }
  } catch (event) {
    logger.error('校验本地备份失败:', event)
    const message = resolveErrorMessage(event, '校验本地备份失败')
    maintenanceError.value = message
    uiStore.showToast('error', message)
  } finally {
    validatingBackupFileName.value = null
  }
}

async function validateLatestBackup() {
  const target = latestBackup.value
  if (!target) return
  await validateBackupFile(target.fileName)
}

async function createDiagnosticBundle() {
  if (creatingDiagnosticBundle.value) return
  creatingDiagnosticBundle.value = true
  maintenanceError.value = null
  maintenanceNotice.value = null
  try {
    const bundle = await diagnosticsApi.createDiagnosticBundle()
    latestDiagnosticBundle.value = bundle
    maintenanceNotice.value = `已生成诊断包：${bundle.fileName}`
    uiStore.showToast('success', maintenanceNotice.value)
  } catch (event) {
    logger.error('生成本地诊断包失败:', event)
    const message = resolveErrorMessage(event, '生成本地诊断包失败')
    maintenanceError.value = message
    uiStore.showToast('error', message)
  } finally {
    creatingDiagnosticBundle.value = false
  }
}

async function prepareBackupRestore() {
  const target = backupValidation.value?.fileName ?? latestBackup.value?.fileName
  if (!target || preparingBackupRestoreFileName.value) return
  preparingBackupRestoreFileName.value = target
  maintenanceError.value = null
  maintenanceNotice.value = null
  try {
    const result = await diagnosticsApi.prepareBackupRestore(target)
    backupRestorePreparation.value = result
    maintenanceNotice.value = `已准备恢复目录：${result.restoreDirectory}`
    uiStore.showToast('success', '恢复目录已准备')
  } catch (event) {
    logger.error('准备备份恢复目录失败:', event)
    const message = resolveErrorMessage(event, '准备备份恢复目录失败')
    maintenanceError.value = message
    uiStore.showToast('error', message)
  } finally {
    preparingBackupRestoreFileName.value = null
  }
}

async function revealLocalPath(path: unknown, successMessage: string) {
  const target = valueAsText(path)
  if (!isTauri.value || !target) return
  try {
    await revealInFileManager(target)
    uiStore.showToast('success', successMessage)
  } catch (event) {
    const message = resolveErrorMessage(event, '打开本地位置失败')
    uiStore.showToast('error', message)
  }
}

async function copyLocalMaintenanceDiagnostic() {
  if (!diagnosticReport.value && !maintenanceLoading.value) {
    await loadLocalMaintenance()
  }
  const ok = await copyToClipboard(buildLocalMaintenanceDiagnostic({
    report: diagnosticReport.value,
    backups: backupFiles.value,
    backupValidation: backupValidation.value,
    backupRestorePreparation: backupRestorePreparation.value,
  }))
  uiStore.showToast(ok ? 'success' : 'error', ok ? '诊断摘要已复制' : '复制诊断摘要失败')
}

async function copyBackupRestoreChecklist() {
  if (!backupValidation.value) return
  const ok = await copyToClipboard(buildBackupRestoreChecklist(backupValidation.value))
  uiStore.showToast(ok ? 'success' : 'error', ok ? '恢复清单已复制' : '复制恢复清单失败')
}

async function copyDistributionChecklist() {
  if (!diagnosticReport.value && !maintenanceLoading.value) {
    await loadLocalMaintenance()
  }
  const ok = await copyToClipboard(buildDistributionChecklist())
  uiStore.showToast(ok ? 'success' : 'error', ok ? '发布自检清单已复制' : '复制发布自检清单失败')
}

async function copyToolchainRepairGuide() {
  const ok = await copyToClipboard(buildToolchainRepairGuide())
  uiStore.showToast(ok ? 'success' : 'error', ok ? '工具链修复清单已复制' : '复制工具链修复清单失败')
}

function buildDistributionChecklist() {
  const check = desktopDistributionCheck.value
  const metadata = desktopDistributionMetadata.value
  return [
    '[知微发布自检清单]',
    `本地诊断状态: ${diagnosticReport.value?.status ?? '未读取'}`,
    `发布状态: ${check ? `${check.label}=${check.status}` : '未读取'}`,
    `说明: ${check?.detail ?? '尚未读取安装与更新诊断'}`,
    '',
    '版本号:',
    ...distributionVersionRows.value.map(([label, value]) => `- ${label}: ${value}`),
    '',
    '更新配置:',
    `- 打包: ${formatDistributionFlag(metadata.bundleActive, '已启用', '未启用')}`,
    `- 版本: ${formatDistributionFlag(metadata.versionsAligned, '已对齐', '未对齐')}`,
    `- 构建脚本: ${formatDistributionFlag(metadata.buildScriptsAligned, '已对齐', '需修复')}`,
    `- Tauri 构建前命令: ${valueAsText(metadata.beforeBuildCommand) ?? '未读取'}`,
    `- 构建环境: ${distributionBuildToolchainLabel.value}`,
    `- Maven CLI: ${formatCommandProbe(metadata.mavenCliAvailable, metadata.mavenCliVersion)}`,
    `- Cargo CLI: ${formatCommandProbe(metadata.cargoCliAvailable, metadata.cargoCliVersion)}`,
    `- Rustc CLI: ${formatCommandProbe(metadata.rustcCliAvailable, metadata.rustcCliVersion)}`,
    `- 内嵌 JRE: ${formatEmbeddedJreLabel(metadata)}`,
    `- JRE 准备命令: ${valueAsText(metadata.embeddedJrePrepareCommand) ?? 'cd zhiwei-web && npm run tauri:prepare:jre'}`,
    `- 更新器状态: ${formatUpdaterStatus(metadata)}`,
    ...distributionUpdaterRows.value.map(([label, value]) => `- ${label}: ${value}`),
    `- 运行方式: ${metadata.runningFromJar === true ? '安装包/JAR' : metadata.runningFromJar === false ? '开发运行' : '未读取'}`,
    '',
    '安装包产物:',
    ...distributionArtifactRows.value.map(([label, value]) => `- ${label}: ${value}`),
    '',
    '发布前命令:',
    ...distributionCommandRows.value.map(([label, command]) => `- ${label}: ${command}`),
    '',
    '下一步:',
    ...distributionNextSteps.value.map((step, index) => `${index + 1}. ${step}`),
  ].join('\n')
}

function buildToolchainRepairGuide() {
  const missing = missingDistributionToolchainParts.value
  const missingSummary = missing.length > 0
    ? missing.map(part => `${part.label}(${part.command})`).join('、')
    : '未发现缺失工具链'
  const lines = [
    '[知微桌面打包工具链修复清单]',
    `当前缺失: ${missingSummary}`,
    '',
    '安装入口:',
  ]

  if (missing.some(part => part.command === 'mvn')) {
    lines.push('- Maven: 安装 Maven 3.9+，并把 Maven bin 目录加入 PATH。')
    lines.push('- Windows 可用: winget install Apache.Maven')
  }
  if (missing.some(part => part.command === 'cargo' || part.command === 'rustc')) {
    lines.push('- Rust/Cargo/Rustc: 安装 Rustup: https://www.rust-lang.org/tools/install')
    lines.push('- Windows 可用: winget install Rustlang.Rustup')
  }

  lines.push(
    '',
    '验证命令:',
    '- mvn -version',
    '- cargo --version',
    '- rustc --version',
    '',
    '回到知微:',
    '1. 重启终端或桌面应用，确保 PATH 生效。',
    '2. 进入 设置 / 安装与更新，点击刷新诊断。',
    `3. 重新运行: ${valueAsText(desktopDistributionMetadata.value.desktopBuildCommand) ?? 'cd zhiwei-web && npm run tauri:build'}`,
  )
  return `${lines.join('\n')}\n`
}

function formatDistributionFlag(value: unknown, enabledLabel: string, disabledLabel: string) {
  if (value === true) return enabledLabel
  if (value === false) return disabledLabel
  return '未读取'
}

function formatUpdaterEndpointLabel(metadata: Record<string, unknown>) {
  const count = valueAsNumber(metadata.updaterEndpointCount)
  if (metadata.updaterEndpointsConfigured === true) {
    return count !== null && count > 0 ? `${count} 个` : '已配置'
  }
  if (metadata.updaterEndpointsConfigured === false) return '未配置'
  return '未读取'
}

function formatUpdaterStatus(metadata: Record<string, unknown>) {
  if (metadata.updaterReady === true) return '已就绪'
  const missing = [
    metadata.updaterDependency === false ? '插件依赖' : null,
    metadata.updaterConfigured === false ? '配置块' : null,
    metadata.updaterArtifactsConfigured === false ? '更新产物' : null,
    metadata.updaterPubkeyConfigured === false ? '签名公钥' : null,
    metadata.updaterEndpointsConfigured === false ? '发布源' : null,
  ].filter(Boolean)
  if (missing.length > 0) return `缺少 ${missing.join('、')}`
  return '未读取'
}

function formatBuildToolchainLabel(metadata: Record<string, unknown>) {
  if (metadata.buildToolchainReady === true) return '已就绪'
  const missing = missingBuildToolchainParts(metadata)
  if (missing.length > 0) return `缺少 ${missing.map(part => part.label).join('/')}`
  return '未读取'
}

function formatEmbeddedJreLabel(metadata: Record<string, unknown>) {
  const version = valueAsText(metadata.embeddedJreVersion)
  const majorVersion = valueAsNumber(metadata.embeddedJreMajorVersion)
  if (metadata.embeddedJreReady === true) {
    return majorVersion !== null && majorVersion > 0 ? `已准备（Java ${majorVersion}）` : '已准备'
  }
  if (metadata.embeddedJreReady === false) {
    if (metadata.embeddedJreAvailable === false) {
      return version ? `未准备（${version}）` : '未准备'
    }
    if (majorVersion !== null && majorVersion > 0 && majorVersion < 22) {
      return `版本过低（Java ${majorVersion}）`
    }
    return version ? `不可用（${version}）` : '不可用'
  }
  return '未读取'
}

function missingBuildToolchainParts(metadata: Record<string, unknown>) {
  return [
    metadata.mavenCliAvailable === false ? { label: 'Maven', command: 'mvn' } : null,
    metadata.cargoCliAvailable === false ? { label: 'Cargo', command: 'cargo' } : null,
    metadata.rustcCliAvailable === false ? { label: 'Rustc', command: 'rustc' } : null,
  ].filter((part): part is { label: string; command: string } => part !== null)
}

function formatCommandProbe(available: unknown, detail: unknown) {
  const detailText = valueAsText(detail)
  if (available === true) return detailText ?? '可用'
  if (available === false) return detailText ? `不可用（${detailText}）` : '不可用'
  return '未读取'
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
  if (!isTauri.value) return
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
  if (!isTauri.value) return
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
  if (isTauri.value) {
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
  loadLocalMaintenance()
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

      <!-- 本地数据维护 -->
      <SettingSection title="本地数据维护" description="查看本机诊断状态，升级、迁移或排查前先创建数据备份。">
        <template #header-actions>
          <div class="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              :disabled="maintenanceLoading"
              @click="copyLocalMaintenanceDiagnostic"
            >
              <Copy class="mr-1.5 size-3.5" />
              复制诊断
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              :disabled="maintenanceLoading"
              @click="loadLocalMaintenance"
            >
              <RefreshCw class="mr-1.5 size-3.5" :class="{ 'animate-spin': maintenanceLoading }" />
              刷新
            </Button>
          </div>
        </template>

        <SettingItem label="数据保护状态" :description="backupCheck?.detail ?? '读取本机诊断后显示备份状态。'">
          <div class="flex w-full max-w-2xl flex-wrap items-center justify-end gap-2 md:justify-start">
            <Badge :variant="backupStatusVariant" class="gap-1.5">
              <CheckCircle2 v-if="backupCheck?.status === 'OK'" class="size-3.5" />
              <AlertTriangle v-else class="size-3.5" />
              {{ backupStatusLabel }}
            </Badge>
            <span class="text-[13px] text-muted-foreground">
              {{ diagnosticReport?.summary ?? '尚未读取本地诊断' }}
            </span>
          </div>
        </SettingItem>

        <SettingItem
          label="诊断包"
          :description="latestDiagnosticBundle ? ('最近生成：' + latestDiagnosticBundle.fileName) : '导出结构化诊断报告和最近日志尾部，便于排查启动、模型、工具或迁移问题。'"
        >
          <div class="flex w-full max-w-2xl flex-col items-start gap-2">
            <Button
              type="button"
              variant="outline"
              :disabled="creatingDiagnosticBundle"
              @click="createDiagnosticBundle"
            >
              <Loader2 v-if="creatingDiagnosticBundle" class="mr-1.5 size-4 animate-spin" />
              <FileArchive v-else class="mr-1.5 size-4" />
              {{ creatingDiagnosticBundle ? '生成中' : '生成诊断包' }}
            </Button>
            <div v-if="latestDiagnosticBundle" class="grid w-full gap-2 text-xs text-muted-foreground sm:grid-cols-2">
              <span class="truncate">文件：{{ latestDiagnosticBundle.fileName }}</span>
              <span>大小：{{ formatBytes(latestDiagnosticBundle.sizeBytes) }}</span>
              <span>条目：{{ latestDiagnosticBundle.includedFileCount }}</span>
              <div class="flex min-w-0 items-center gap-2 sm:col-span-2">
                <code class="min-w-0 flex-1 truncate">{{ latestDiagnosticBundle.path }}</code>
                <Button
                  v-if="isTauri"
                  type="button"
                  variant="ghost"
                  size="sm"
                  @click="revealLocalPath(latestDiagnosticBundle.path, '已打开诊断包位置')"
                >
                  <FolderOpen class="mr-1.5 size-3.5" />
                  打开位置
                </Button>
              </div>
            </div>
          </div>
        </SettingItem>

        <SettingItem label="数据库" :description="databaseCheck?.detail ?? '读取本机诊断后显示 SQLite 文件和 WAL 状态。'">
          <div class="w-full max-w-2xl space-y-2">
            <div class="flex flex-wrap items-center gap-2">
              <Badge :variant="databaseStatusVariant" class="gap-1.5">
                <CheckCircle2 v-if="databaseCheck?.status === 'OK'" class="size-3.5" />
                <AlertTriangle v-else class="size-3.5" />
                {{ databaseStatusLabel }}
              </Badge>
              <span class="text-[13px] text-muted-foreground">
                {{ databaseCheck?.detail ?? '尚未读取数据库诊断' }}
              </span>
            </div>
            <div class="grid gap-2 sm:grid-cols-2">
              <div
                v-for="[label, value] in databaseRows"
                :key="label"
                class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                <span>{{ label }}</span>
                <span class="truncate text-foreground">{{ value }}</span>
              </div>
            </div>
          </div>
        </SettingItem>

        <SettingItem label="数据迁移" :description="schemaMigrationCheck?.detail ?? '读取本机诊断后显示数据库迁移状态。'">
          <div class="w-full max-w-2xl space-y-2">
            <div class="flex flex-wrap items-center gap-2">
              <Badge :variant="schemaMigrationStatusVariant" class="gap-1.5">
                <CheckCircle2 v-if="schemaMigrationCheck?.status === 'OK'" class="size-3.5" />
                <AlertTriangle v-else class="size-3.5" />
                {{ schemaMigrationStatusLabel }}
              </Badge>
              <span class="text-[13px] text-muted-foreground">
                {{ schemaMigrationCheck?.detail ?? '尚未读取数据库迁移诊断' }}
              </span>
            </div>
            <div class="grid gap-2 sm:grid-cols-2">
              <div
                v-for="[label, value] in schemaMigrationRows"
                :key="label"
                class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                <span>{{ label }}</span>
                <span class="truncate text-foreground">{{ value }}</span>
              </div>
            </div>
            <ol class="list-decimal space-y-1 pl-4 text-xs text-muted-foreground">
              <li v-for="step in schemaMigrationNextSteps" :key="step">{{ step }}</li>
            </ol>
          </div>
        </SettingItem>

        <SettingItem label="模型服务" :description="modelServiceCheck?.detail ?? '读取本机诊断后显示主对话和向量增强状态。'">
          <div class="w-full max-w-2xl space-y-2">
            <div class="flex flex-wrap items-center gap-2">
              <Badge :variant="modelServiceStatusVariant" class="gap-1.5">
                <CheckCircle2 v-if="modelServiceCheck?.status === 'OK'" class="size-3.5" />
                <AlertTriangle v-else class="size-3.5" />
                {{ modelServiceStatusLabel }}
              </Badge>
              <span class="text-[13px] text-muted-foreground">
                {{ modelServiceCheck?.detail ?? '尚未读取模型服务诊断' }}
              </span>
            </div>
            <div class="grid gap-2 sm:grid-cols-2">
              <div
                v-for="[label, value] in modelServiceRows"
                :key="label"
                class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                <span>{{ label }}</span>
                <span class="truncate text-foreground">{{ value }}</span>
              </div>
            </div>
          </div>
        </SettingItem>

        <SettingItem label="智能增强" :description="intelligenceCheck?.detail ?? '读取本机诊断后显示主对话智能增强状态。'">
          <div class="w-full max-w-2xl space-y-2">
            <div class="flex flex-wrap items-center gap-2">
              <Badge :variant="intelligenceStatusVariant" class="gap-1.5">
                <CheckCircle2 v-if="intelligenceCheck?.status === 'OK'" class="size-3.5" />
                <AlertTriangle v-else class="size-3.5" />
                {{ intelligenceStatusLabel }}
              </Badge>
              <span class="text-[13px] text-muted-foreground">
                {{ intelligenceCheck?.detail ?? '尚未读取智能增强诊断' }}
              </span>
            </div>
            <div class="grid gap-2 sm:grid-cols-2">
              <div
                v-for="[label, value] in intelligenceSummaryRows"
                :key="label"
                class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                <span>{{ label }}</span>
                <span class="truncate text-foreground">{{ value }}</span>
              </div>
            </div>
          </div>
        </SettingItem>

        <SettingItem label="工具和技能" :description="capabilitiesCheck?.detail ?? '读取本机诊断后显示工具与 Skill 引用状态。'">
          <div class="w-full max-w-2xl space-y-2">
            <div class="flex flex-wrap items-center gap-2">
              <Badge :variant="capabilitiesStatusVariant" class="gap-1.5">
                <CheckCircle2 v-if="capabilitiesCheck?.status === 'OK'" class="size-3.5" />
                <AlertTriangle v-else class="size-3.5" />
                {{ capabilitiesStatusLabel }}
              </Badge>
              <span class="text-[13px] text-muted-foreground">
                {{ capabilitiesCheck?.detail ?? '尚未读取工具和技能诊断' }}
              </span>
            </div>
            <div class="grid gap-2 sm:grid-cols-2">
              <div
                v-for="[label, value] in capabilitiesSummaryRows"
                :key="label"
                class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                <span>{{ label }}</span>
                <span class="truncate text-foreground">{{ value }}</span>
              </div>
            </div>
            <div
              v-if="capabilitiesUnknownSamples.length > 0 || capabilitiesMissingCanonicalSamples.length > 0"
              class="space-y-1 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
            >
              <div v-if="capabilitiesUnknownSamples.length > 0">
                未知工具：{{ capabilitiesUnknownSamples.join('；') }}
              </div>
              <div v-if="capabilitiesMissingCanonicalSamples.length > 0">
                缺失标准工具：{{ capabilitiesMissingCanonicalSamples.join('；') }}
              </div>
            </div>
            <ol class="list-decimal space-y-1 pl-4 text-xs text-muted-foreground">
              <li v-for="step in capabilitiesNextSteps" :key="step">{{ step }}</li>
            </ol>
          </div>
        </SettingItem>

        <SettingItem label="备份目录" :description="backupDirectory">
          <div class="flex w-full max-w-2xl min-w-0 items-center gap-2">
            <code class="min-w-0 flex-1 truncate rounded bg-muted px-3 py-1.5 text-xs text-muted-foreground">
              {{ backupDirectory }}
            </code>
            <Button
              v-if="isTauri && canRevealPath(backupDirectory)"
              type="button"
              variant="outline"
              size="sm"
              @click="revealLocalPath(backupDirectory, '已打开备份目录')"
            >
              <FolderOpen class="mr-1.5 size-3.5" />
              打开位置
            </Button>
          </div>
        </SettingItem>

        <SettingItem
          label="最近备份"
          :description="latestBackup ? ('最近一次：' + formatDateTime(latestBackup.modifiedAt)) : '尚未发现知微本地备份文件。'"
        >
          <div class="w-full max-w-2xl space-y-2">
            <div
              v-for="backup in latestBackups"
              :key="backup.path"
              class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2 text-left"
            >
              <div class="min-w-0">
                <div class="truncate text-sm font-medium text-foreground">{{ backup.fileName }}</div>
                <div class="truncate text-xs text-muted-foreground">{{ backup.path }}</div>
              </div>
              <div class="shrink-0 text-right text-xs text-muted-foreground">
                <div>{{ formatBytes(backup.sizeBytes) }}</div>
                <div>{{ formatDateTime(backup.modifiedAt) }}</div>
              </div>
            </div>
            <p v-if="latestBackups.length === 0" class="text-[13px] text-muted-foreground">
              当前没有可显示的备份。创建后会出现在这里。
            </p>
          </div>
        </SettingItem>

        <SettingItem label="创建本地备份" description="打包 HOME 核心数据，自动排除缓存、运行时、日志和旧备份。">
          <Button
            type="button"
            variant="outline"
            :disabled="creatingBackup"
            @click="createLocalBackup"
          >
            <Loader2 v-if="creatingBackup" class="mr-1.5 size-4 animate-spin" />
            <Archive v-else class="mr-1.5 size-4" />
            {{ creatingBackup ? '备份中' : '创建备份' }}
          </Button>
        </SettingItem>

        <SettingItem label="校验备份" description="检查最近一份备份是否可读取。">
          <div class="flex w-full max-w-2xl flex-col items-start gap-3">
            <Button
              type="button"
              variant="outline"
              :disabled="!latestBackup || !!validatingBackupFileName"
              @click="validateLatestBackup"
            >
              <Loader2 v-if="validatingBackupFileName" class="mr-1.5 size-4 animate-spin" />
              <CheckCircle2 v-else class="mr-1.5 size-4" />
              {{ validatingBackupFileName ? '校验中' : '校验最新备份' }}
            </Button>

            <div v-if="backupValidation" class="w-full space-y-2 rounded-md border border-border/50 px-3 py-2">
              <div class="flex flex-wrap items-center gap-2">
                <Badge :variant="validationStatusVariant" class="gap-1.5">
                  <CheckCircle2 v-if="backupValidation.status === 'OK'" class="size-3.5" />
                  <AlertTriangle v-else class="size-3.5" />
                  {{ validationStatusLabel }}
                </Badge>
                <span class="text-sm text-foreground">{{ backupValidation.detail }}</span>
              </div>
              <div class="grid gap-1 text-xs text-muted-foreground sm:grid-cols-2">
                <span class="truncate">文件：{{ backupValidation.fileName }}</span>
                <span>条目：{{ backupValidation.entryCount }}</span>
                <span>清单：{{ backupValidation.manifestPresent ? '已包含' : '缺失' }}</span>
                <span v-if="backupValidation.manifest">数据文件：{{ backupValidation.manifest.includedFileCount }}</span>
              </div>
              <ul v-if="backupValidation.problems.length > 0" class="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
                <li v-for="problem in backupValidation.problems" :key="problem">{{ problem }}</li>
              </ul>
              <div v-if="backupValidation.restorePlan" class="space-y-2 border-t border-border/50 pt-2">
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <div class="text-xs font-medium text-foreground">恢复前预检</div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    @click="copyBackupRestoreChecklist"
                  >
                    <Copy class="mr-1.5 size-3.5" />
                    复制恢复步骤
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    :disabled="backupValidation.status === 'ERROR' || !!preparingBackupRestoreFileName"
                    @click="prepareBackupRestore"
                  >
                    <Loader2 v-if="preparingBackupRestoreFileName" class="mr-1.5 size-3.5 animate-spin" />
                    <FolderArchive v-else class="mr-1.5 size-3.5" />
                    {{ preparingBackupRestoreFileName ? '准备中' : '准备恢复目录' }}
                  </Button>
                </div>
                <div class="grid gap-1 text-xs text-muted-foreground sm:grid-cols-2">
                  <span>恢复方式：{{ formatRestoreMode(backupValidation.restorePlan) }}</span>
                  <span class="truncate">恢复范围：{{ formatRestoreItems(backupValidation.restorePlan.includedTopLevelItems) }}</span>
                  <span class="truncate">不会恢复：{{ formatRestoreItems(backupValidation.restorePlan.excludedTopLevelDirs) }}</span>
                  <span class="truncate">目标 HOME：{{ backupValidation.restorePlan.targetHome }}</span>
                  <span>当前数据：{{ backupValidation.restorePlan.currentHomeFileCount }} 个文件</span>
                  <span class="truncate">备份来源：{{ backupValidation.restorePlan.backupSourceHome }}</span>
                  <span>备份数据：{{ backupValidation.restorePlan.backupIncludedFileCount >= 0 ? backupValidation.restorePlan.backupIncludedFileCount + ' 个文件' : '未知' }}</span>
                  <span>预计解压：{{ formatBytes(backupValidation.restorePlan.estimatedRestoreBytes) }}</span>
                  <span>可用空间：{{ formatBytes(backupValidation.restorePlan.targetUsableBytes) }}</span>
                  <span>
                    恢复空间：
                    <span :class="restoreSpaceStatusClass(backupValidation.restorePlan.restoreSpaceStatus)">
                      {{ formatRestoreSpaceStatus(backupValidation.restorePlan.restoreSpaceStatus) }}
                    </span>
                  </span>
                  <span class="truncate">暂存目录：{{ backupValidation.restorePlan.restoreStagingDirectory }}</span>
                </div>
                <ul v-if="backupValidation.restorePlan.warnings.length > 0" class="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
                  <li v-for="warning in backupValidation.restorePlan.warnings" :key="warning">{{ warning }}</li>
                </ul>
                <ol class="list-decimal space-y-1 pl-4 text-xs text-muted-foreground">
                  <li v-for="step in backupValidation.restorePlan.requiredSteps" :key="step">{{ step }}</li>
                </ol>
                <div v-if="backupRestorePreparation" class="space-y-2 rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                  <div class="font-medium text-foreground">恢复目录已准备</div>
                  <div class="grid gap-1 sm:grid-cols-2">
                    <span>文件：{{ backupRestorePreparation.extractedFileCount }}</span>
                    <span>大小：{{ formatBytes(backupRestorePreparation.extractedBytes) }}</span>
                    <div class="flex min-w-0 items-center gap-2 sm:col-span-2">
                      <code class="min-w-0 flex-1 truncate">{{ backupRestorePreparation.restoreDirectory }}</code>
                      <Button
                        v-if="isTauri"
                        type="button"
                        variant="ghost"
                        size="sm"
                        @click="revealLocalPath(backupRestorePreparation.restoreDirectory, '已打开恢复目录')"
                      >
                        <FolderOpen class="mr-1.5 size-3.5" />
                        打开目录
                      </Button>
                    </div>
                  </div>
                  <ul v-if="backupRestorePreparation.warnings.length > 0" class="list-disc space-y-1 pl-4">
                    <li v-for="warning in backupRestorePreparation.warnings" :key="warning">{{ warning }}</li>
                  </ul>
                  <ol class="list-decimal space-y-1 pl-4">
                    <li v-for="step in backupRestorePreparation.nextSteps" :key="step">{{ step }}</li>
                  </ol>
                </div>
              </div>
            </div>
          </div>
        </SettingItem>

        <p v-if="maintenanceNotice" class="py-2 text-[13px] text-muted-foreground">
          {{ maintenanceNotice }}
        </p>
        <p v-if="maintenanceError" class="py-2 text-[13px] text-destructive">
          {{ maintenanceError }}
        </p>
      </SettingSection>

      <SettingSection title="安装与更新" description="查看当前桌面端打包、版本对齐和自动更新配置状态。">
        <template #header-actions>
          <Button
            type="button"
            variant="outline"
            size="sm"
            :disabled="maintenanceLoading"
            aria-label="复制发布自检清单"
            @click="copyDistributionChecklist"
          >
            <Copy class="mr-1.5 size-3.5" />
            复制发布自检
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            :disabled="maintenanceLoading"
            @click="loadLocalMaintenance"
          >
            <RefreshCw class="mr-1.5 size-3.5" :class="{ 'animate-spin': maintenanceLoading }" />
            刷新诊断
          </Button>
        </template>

        <SettingItem label="发布状态" :description="desktopDistributionCheck?.detail ?? '读取本地诊断后显示安装与更新状态。'">
          <div class="flex w-full max-w-2xl flex-wrap items-center justify-end gap-2 md:justify-start">
            <Badge :variant="desktopDistributionStatusVariant" class="gap-1.5">
              <CheckCircle2 v-if="desktopDistributionCheck?.status === 'OK'" class="size-3.5" />
              <AlertTriangle v-else class="size-3.5" />
              {{ desktopDistributionStatusLabel }}
            </Badge>
            <span class="text-[13px] text-muted-foreground">
              {{ desktopDistributionCheck?.detail ?? '尚未读取安装与更新诊断' }}
            </span>
          </div>
        </SettingItem>

        <SettingItem label="版本号" description="发布前这些版本需要保持一致。">
          <div class="grid w-full max-w-2xl gap-2 sm:grid-cols-2">
            <div
              v-for="[label, value] in distributionVersionRows"
              :key="label"
              class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2"
            >
              <span class="text-xs text-muted-foreground">{{ label }}</span>
              <code class="truncate text-xs text-foreground">{{ value }}</code>
            </div>
          </div>
        </SettingItem>

        <SettingItem label="更新配置" description="检查安装包和自动更新链路的关键开关。">
          <div class="w-full max-w-2xl space-y-2">
            <div class="grid gap-2 sm:grid-cols-2">
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                打包：{{ desktopDistributionMetadata.bundleActive === true ? '已启用' : '未启用' }}
              </div>
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                版本：{{ desktopDistributionMetadata.versionsAligned === true ? '已对齐' : '未对齐' }}
              </div>
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                脚本：{{ desktopDistributionMetadata.buildScriptsAligned === true ? '已对齐' : '需修复' }}
              </div>
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                构建环境：{{ distributionBuildToolchainLabel }}
              </div>
              <div
                v-for="[label, value] in distributionToolchainRows"
                :key="label"
                class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                {{ label }}：{{ value }}
              </div>
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                内嵌 JRE：{{ formatEmbeddedJreLabel(desktopDistributionMetadata) }}
              </div>
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                更新器：{{ formatUpdaterStatus(desktopDistributionMetadata) }}
              </div>
              <div
                v-for="[label, value] in distributionUpdaterRows"
                :key="label"
                class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground"
              >
                {{ label }}：{{ value }}
              </div>
              <div class="rounded-md border border-border/50 px-3 py-2 text-xs text-muted-foreground">
                运行方式：{{ desktopDistributionMetadata.runningFromJar === true ? '安装包/JAR' : '开发运行' }}
              </div>
            </div>
            <Button
              v-if="missingDistributionToolchainParts.length > 0"
              type="button"
              variant="outline"
              size="sm"
              @click="copyToolchainRepairGuide"
            >
              <Copy class="mr-1.5 size-3.5" />
              复制工具链修复
            </Button>
          </div>
        </SettingItem>

        <SettingItem label="安装包产物" description="检查本机是否已经生成可交付的桌面安装包。">
          <div class="w-full max-w-2xl space-y-2">
            <div class="grid gap-2 sm:grid-cols-2">
              <div
                v-for="[label, value] in distributionArtifactRows"
                :key="label"
                class="flex min-w-0 items-center justify-between gap-3 rounded-md border border-border/50 px-3 py-2"
              >
                <span class="text-xs text-muted-foreground">{{ label }}</span>
                <code class="truncate text-xs text-foreground">{{ value }}</code>
              </div>
            </div>
            <Button
              v-if="isTauri && packageArtifactRevealPath"
              type="button"
              variant="outline"
              size="sm"
              @click="revealLocalPath(packageArtifactRevealPath, '已打开安装包位置')"
            >
              <FolderOpen class="mr-1.5 size-3.5" />
              {{ packageArtifactRevealLabel }}
            </Button>
          </div>
        </SettingItem>

        <SettingItem label="下一步" description="用于发布前自检，不会自动下载或安装更新。">
          <div class="w-full max-w-2xl space-y-3">
            <ol class="list-decimal space-y-1 pl-4 text-xs text-muted-foreground">
              <li v-for="step in distributionNextSteps" :key="step">{{ step }}</li>
            </ol>
            <div class="grid gap-2" aria-label="发布前命令">
              <div
                v-for="[label, command] in distributionCommandRows"
                :key="label"
                class="grid min-w-0 gap-1 rounded-md border border-border/50 px-3 py-2 sm:grid-cols-[7rem_minmax(0,1fr)] sm:items-center"
              >
                <span class="text-xs text-muted-foreground">{{ label }}</span>
                <code class="break-all text-xs text-foreground">{{ command }}</code>
              </div>
            </div>
          </div>
        </SettingItem>
      </SettingSection>

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
