import type {
  DiagnosticReport,
  LocalBackupFileInfo,
  LocalBackupRestorePreparationInfo,
  LocalBackupValidationInfo,
  Message,
  MissingCapability,
} from '@/types'
import { formatToolFailureCategory } from './toolExecution'

interface ErrorDiagnosticInput {
  scope: 'message' | 'global'
  error?: string | null
  message?: Message | null
  sessionId?: string | null
  route?: string | null
  streaming?: boolean
  lastPrompt?: string | null
  timestamp?: number
  diagnosticReport?: DiagnosticReport | null
  diagnosticReportError?: string | null
}

interface LocalMaintenanceDiagnosticInput {
  report?: DiagnosticReport | null
  backups?: LocalBackupFileInfo[]
  backupValidation?: LocalBackupValidationInfo | null
  backupRestorePreparation?: LocalBackupRestorePreparationInfo | null
  timestamp?: number
}

export interface MessageRecoveryBrief {
  title: string
  detail?: string | null
  contextLines: string[]
  nextActions: string[]
}

export interface DiagnosticRepairLink {
  id: string
  label: string
  title: string
  routeName: string
}

const CAPABILITY_REPAIR_LINK: DiagnosticRepairLink = {
  id: 'capabilities',
  label: '能力中心',
  title: '检查工具、技能状态和 Skill 引用',
  routeName: 'capabilities',
}

function compact(value?: string | null, maxLength = 160) {
  const text = value?.replace(/\s+/g, ' ').trim() ?? ''
  if (!text) return '-'
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1)}…`
}

function line(label: string, value: unknown) {
  const text = value === null || value === undefined || value === '' ? '-' : String(value)
  return `${label}: ${text}`
}

function summarizeReportChecks(report: DiagnosticReport) {
  return report.checks
    .slice(0, 8)
    .map(check => `${check.label}=${check.status}`)
    .join('; ')
}

function summarizeReportCheckDetails(report: DiagnosticReport) {
  return report.checks
    .slice(0, 12)
    .map(check => `${check.label}=${check.status}（${compact(check.detail, 120)}）`)
    .join('; ')
}

function hasProblem(check: { status: string }) {
  const status = check.status.toUpperCase()
  return status === 'WARN' || status === 'ERROR'
}

function hasProblemCheck(check?: { status: string } | null) {
  return !!check && hasProblem(check)
}

function valueAsNumber(value: unknown) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : Number.NaN
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : Number.NaN
  }
  return Number.NaN
}

function valueAsText(value: unknown) {
  if (value === null || value === undefined || value === '') return null
  return String(value)
}

function formatBytes(value: unknown) {
  const size = valueAsNumber(value)
  if (!Number.isFinite(size) || size < 0) return null
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function formatBytesOrUnknown(value: unknown) {
  return formatBytes(value) ?? '未知'
}

function formatRestoreSpaceStatus(status: unknown) {
  switch (String(status ?? '').toUpperCase()) {
    case 'OK':
      return '空间充足'
    case 'WARN':
      return '空间不足'
    case 'UNKNOWN':
      return '空间未知'
    default:
      return valueAsText(status) ?? '未读取'
  }
}

function formatRestoreMode(mode: unknown, manualRestoreOnly?: boolean) {
  if (manualRestoreOnly || String(mode ?? '') === 'manual-staging') return '手动暂存恢复'
  return valueAsText(mode) ?? '未读取'
}

function unique(values: string[]) {
  return Array.from(new Set(values)).filter(Boolean)
}

function isCapabilityFailureText(text: string) {
  return [
    '工具未注册',
    '未找到工具',
    'unknown tool',
    'tool not found',
    'not registered',
    'suggestedtools',
  ].some(pattern => text.includes(pattern))
}

function joinCompact(values: Array<string | null | undefined>, separator = '；') {
  return values
    .map(value => compact(value, 220))
    .filter(value => value !== '-')
    .join(separator)
}

function firstText(...values: Array<string | null | undefined>) {
  for (const value of values) {
    const text = compact(value, 180)
    if (text !== '-') return text
  }
  return null
}

function firstFiniteNumber(...values: unknown[]) {
  for (const value of values) {
    const numberValue = valueAsNumber(value)
    if (Number.isFinite(numberValue)) return numberValue
  }
  return Number.NaN
}

function formatMissingCapabilityList(capabilities?: MissingCapability[] | null) {
  const items = unique((capabilities ?? [])
    .map(item => {
      const id = item.id?.trim()
      if (!id) return ''
      const skillName = item.skillName?.trim()
      return skillName ? `${id}(${skillName})` : id
    }))
  if (!items.length) return null
  const visible = items.slice(0, 3).map(item => compact(item, 40)).join('、')
  return items.length > 3 ? `${visible} 等 ${items.length} 个` : visible
}

function formatRecoveryCheckpoint(message: Message) {
  const checkpoint = message.taskRecovery?.checkpoint ?? message.turnRecoveryContext?.checkpoint
  if (!checkpoint) return null
  const failureCategoryLabel = checkpoint.failureCategory
    ? formatToolFailureCategory(checkpoint.failureCategory) || checkpoint.failureCategory
    : null
  const missingCapabilities = formatMissingCapabilityList(checkpoint.missingCapabilities)
  return joinCompact([
    checkpoint.executionKind === 'SKILL' ? '技能步骤' : '任务步骤',
    checkpoint.toolName || checkpoint.toolId,
    checkpoint.action ? `操作=${checkpoint.action}` : null,
    failureCategoryLabel ? `类型=${failureCategoryLabel}` : null,
    missingCapabilities ? `缺失能力=${missingCapabilities}` : null,
    checkpoint.subjectNames?.length
      ? `${checkpoint.subjectLabel || '对象'}=${checkpoint.subjectNames.join('、')}`
      : null,
    checkpoint.workingDirectory ? `目录=${checkpoint.workingDirectory}` : null,
    checkpoint.inputSummary ? `输入=${checkpoint.inputSummary}` : null,
    checkpoint.outputSummary ? `输出=${checkpoint.outputSummary}` : null,
    checkpoint.generatedFilePath ? `文件=${checkpoint.generatedFilePath}` : null,
  ])
}

function recoveryFailureCategory(message?: Message | null) {
  return message?.taskRecovery?.checkpoint?.failureCategory
    ?? message?.turnRecoveryContext?.checkpoint?.failureCategory
    ?? null
}

function messageCapabilityFailureText(message?: Message | null, error?: string | null) {
  const checkpoint = message?.taskRecovery?.checkpoint ?? message?.turnRecoveryContext?.checkpoint
  return [
    error,
    message?.errorMessage,
    message?.content,
    message?.taskRecovery?.detail,
    message?.turnRecoveryContext?.detail,
    checkpoint?.outputSummary,
    checkpoint?.outputDetail,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}

export function buildMessageRepairLinks(message?: Message | null, error?: string | null): DiagnosticRepairLink[] {
  const checkpoint = message?.taskRecovery?.checkpoint ?? message?.turnRecoveryContext?.checkpoint
  if (recoveryFailureCategory(message) === 'CAPABILITY'
    || !!checkpoint?.missingCapabilities?.length
    || isCapabilityFailureText(messageCapabilityFailureText(message, error))) {
    return [CAPABILITY_REPAIR_LINK]
  }
  return []
}

function appendRecoveryLines(lines: string[], message?: Message | null) {
  if (!message) return
  const recovery = message.taskRecovery
  if (recovery) {
    lines.push(line('任务恢复', joinCompact([
      recovery.status,
      recovery.title,
      recovery.resumeMode ? `恢复方式=${recovery.resumeMode}` : null,
      recovery.actionLabel ? `动作=${recovery.actionLabel}` : null,
    ])))
    lines.push(line('恢复说明', compact(recovery.detail, 260)))
    if (recovery.nextActions?.length) {
      lines.push(line('恢复计划', recovery.nextActions.map((action, index) => `${index + 1}. ${action}`).join(' ')))
    }
  }

  const turnRecovery = message.turnRecoveryContext
  if (turnRecovery) {
    lines.push(line('本轮续接', joinCompact([
      turnRecovery.action,
      turnRecovery.title,
      turnRecovery.sourceTraceId ? `来源Trace=${turnRecovery.sourceTraceId}` : null,
    ])))
    if (turnRecovery.resumeInput) {
      lines.push(line('续接输入', compact(turnRecovery.resumeInput, 260)))
    }
    if (turnRecovery.nextActions?.length) {
      lines.push(line('续接计划', turnRecovery.nextActions.map((action, index) => `${index + 1}. ${action}`).join(' ')))
    }
  }

  const checkpoint = formatRecoveryCheckpoint(message)
  if (checkpoint) {
    lines.push(line('恢复断点', checkpoint))
  }
}

function buildMessageRecoveryAdvice(message?: Message | null) {
  if (!message) return []
  const actions: string[] = []
  const failureCategory = recoveryFailureCategory(message)
  const checkpoint = message.taskRecovery?.checkpoint ?? message.turnRecoveryContext?.checkpoint
  const missingCapabilities = formatMissingCapabilityList(checkpoint?.missingCapabilities)
  const recovery = message.taskRecovery
  if (recovery) {
    const label = recovery.actionLabel || '继续'
    if (recovery.canResume !== false) {
      switch (recovery.resumeMode) {
        case 'user_reply':
          actions.push('直接在输入框补充缺少的信息，发送后知微会接着当前任务继续。')
          break
        case 'browser':
          actions.push('先完成浏览器接管步骤，再回到知微继续当前任务。')
          break
        case 'external':
          actions.push('等待外部任务返回后再继续；排查时优先核对外部服务或网络状态。')
          break
        case 'scheduled':
          actions.push('任务已安排后续唤醒，排查时确认计划时间和本机后台状态。')
          break
        default:
          if (failureCategory === 'CAPABILITY') {
            actions.push(`先在能力中心修复缺失工具或 Skill 引用，再点击「${label}」从断点继续。`)
          } else {
            actions.push(`优先在对话里点击「${label}」，知微会带着当前断点继续。`)
          }
      }
    }
    if (failureCategory === 'CAPABILITY') {
      if (missingCapabilities) {
        actions.push(`优先补齐缺失能力：${missingCapabilities}。`)
      }
      actions.push('排查重点是工具是否注册、Skill suggestedTools 是否指向未知 ID，以及 MCP 或本地工具提供方是否启动。')
    }
    if (recovery.nextActions?.length) {
      actions.push(`继续前核对计划：${recovery.nextActions.slice(0, 3).join('；')}。`)
    }
    if (recovery.canRestart) {
      actions.push('如果断点或输入已经不可信，再重新开始这一轮。')
    }
  }

  const turnRecovery = message.turnRecoveryContext
  if (turnRecovery) {
    const source = turnRecovery.sourceTraceId ? `Trace ${turnRecovery.sourceTraceId}` : '上一轮断点'
    actions.push(`本轮已经从${source}续接，排查时先对照续接输入、断点和计划。`)
  }
  return unique(actions).slice(0, 4)
}

function formatResumeMode(mode?: string | null) {
  switch (mode) {
    case 'manual':
      return '手动继续'
    case 'user_reply':
      return '等你补充'
    case 'browser':
      return '浏览器接管'
    case 'external':
      return '等待外部任务'
    case 'scheduled':
      return '已安排唤醒'
    default:
      return valueAsText(mode) ?? null
  }
}

export function buildMessageRecoveryBrief(message?: Message | null): MessageRecoveryBrief | null {
  if (!message?.taskRecovery && !message?.turnRecoveryContext) {
    return null
  }

  const recovery = message.taskRecovery
  const turnRecovery = message.turnRecoveryContext
  const title = firstText(recovery?.title, turnRecovery?.title)
    ?? (recovery ? '任务可以从断点继续' : '本轮已接上历史任务')
  const detail = firstText(recovery?.detail, turnRecovery?.detail)
  const contextLines: string[] = []
  const resumeMode = formatResumeMode(recovery?.resumeMode)
  if (resumeMode) {
    contextLines.push(`恢复方式：${resumeMode}`)
  }
  if (turnRecovery?.sourceTraceId) {
    contextLines.push(`来源 Trace：${turnRecovery.sourceTraceId}`)
  }
  const checkpoint = compact(formatRecoveryCheckpoint(message), 180)
  if (checkpoint !== '-') {
    contextLines.push(`断点：${checkpoint}`)
  }

  const nextActions = unique([
    ...(recovery?.nextActions ?? []),
    ...(turnRecovery?.nextActions ?? []),
    ...buildMessageRecoveryAdvice(message),
  ])
    .map(action => compact(action, 160))
    .filter(action => action !== '-')
    .slice(0, 3)

  return {
    title,
    detail,
    contextLines,
    nextActions,
  }
}

function summarizeDatabaseStatus(report: DiagnosticReport) {
  const database = report.checks.find(check => check.id === 'database')
  if (!database) return null
  const metadata = database.metadata ?? {}
  return joinCompact([
    `${database.label}=${database.status}`,
    `说明=${database.detail}`,
    valueAsText(metadata.databaseLocation) ? `位置=${metadata.databaseLocation}` : null,
    valueAsText(metadata.databasePath) ? `路径=${metadata.databasePath}` : null,
    formatBytes(metadata.databaseSizeBytes) ? `大小=${formatBytes(metadata.databaseSizeBytes)}` : null,
    valueAsText(metadata.journalMode) ? `journal=${metadata.journalMode}` : null,
    valueAsText(metadata.busyTimeoutMs) ? `busyTimeout=${metadata.busyTimeoutMs}ms` : null,
    valueAsText(metadata.walExists) ? `WAL=${formatBooleanOk(metadata.walExists)}` : null,
    formatBytes(metadata.walSizeBytes) ? `WAL大小=${formatBytes(metadata.walSizeBytes)}` : null,
  ])
}

function summarizeBackupStatus(report: DiagnosticReport) {
  const backup = report.checks.find(check => check.id === 'data-backups')
  if (!backup) return null
  const metadata = backup.metadata ?? {}
  const fileCount = valueAsNumber(metadata.fileCount)
  return joinCompact([
    `${backup.label}=${backup.status}`,
    `说明=${backup.detail}`,
    valueAsText(metadata.directory) ? `目录=${metadata.directory}` : null,
    Number.isFinite(fileCount) ? `文件数=${fileCount}` : null,
    valueAsText(metadata.latestFile) ? `最新=${metadata.latestFile}` : null,
    valueAsText(metadata.latestModifiedAt) ? `时间=${metadata.latestModifiedAt}` : null,
    formatBytes(metadata.latestSizeBytes) ? `大小=${formatBytes(metadata.latestSizeBytes)}` : null,
  ])
}

function summarizeMigrationStatus(report: DiagnosticReport) {
  const migration = report.checks.find(check => check.id === 'schema-migrations')
  if (!migration) return null
  const metadata = migration.metadata ?? {}
  return joinCompact([
    `${migration.label}=${migration.status}`,
    `说明=${migration.detail}`,
    valueAsText(metadata.total) ? `迁移数=${metadata.total}` : null,
    valueAsText(metadata.failed) ? `失败数=${metadata.failed}` : null,
    valueAsText(metadata.latestVersion) ? `最新版本=${metadata.latestVersion}` : null,
    valueAsText(metadata.latestScript) ? `最新脚本=${metadata.latestScript}` : null,
    Array.isArray(metadata.failedMigrationScripts) && metadata.failedMigrationScripts.length > 0
      ? `失败脚本=${metadata.failedMigrationScripts.join('、')}`
      : null,
    metadata.historyTable === false ? '历史表=未发现' : null,
  ])
}

function summarizeDistributionStatus(report: DiagnosticReport) {
  const distribution = report.checks.find(check => check.id === 'desktop-distribution')
  if (!distribution) return null
  const metadata = distribution.metadata ?? {}
  return joinCompact([
    `${distribution.label}=${distribution.status}`,
    `说明=${distribution.detail}`,
    valueAsText(metadata.backendVersion) ? `后端=${metadata.backendVersion}` : null,
    valueAsText(metadata.packageVersion) ? `Web=${metadata.packageVersion}` : null,
    valueAsText(metadata.tauriVersion) ? `桌面端=${metadata.tauriVersion}` : null,
    valueAsText(metadata.cargoVersion) ? `Cargo=${metadata.cargoVersion}` : null,
    valueAsText(metadata.versionsAligned) ? `版本对齐=${formatBooleanOk(metadata.versionsAligned)}` : null,
    valueAsText(metadata.bundleActive) ? `打包启用=${formatBooleanOk(metadata.bundleActive)}` : null,
    valueAsText(metadata.buildScriptsAligned) ? `构建脚本=${formatBooleanOk(metadata.buildScriptsAligned)}` : null,
    valueAsText(metadata.buildToolchainReady) ? `构建环境=${formatBooleanOk(metadata.buildToolchainReady)}` : null,
    valueAsText(metadata.embeddedJreReady) ? `内嵌JRE=${formatBooleanOk(metadata.embeddedJreReady)}` : null,
    valueAsText(metadata.embeddedJreVersion) ? `JRE版本=${metadata.embeddedJreVersion}` : null,
    valueAsText(metadata.embeddedJrePrepareCommand) ? `JRE准备命令=${metadata.embeddedJrePrepareCommand}` : null,
    valueAsText(metadata.updaterReady) ? `更新就绪=${formatBooleanOk(metadata.updaterReady)}` : null,
    valueAsText(metadata.updaterConfigured) ? `更新配置=${formatBooleanOk(metadata.updaterConfigured)}` : null,
    valueAsText(metadata.updaterDependency) ? `更新依赖=${formatBooleanOk(metadata.updaterDependency)}` : null,
    valueAsText(metadata.updaterArtifactsConfigured) ? `更新产物=${formatBooleanOk(metadata.updaterArtifactsConfigured)}` : null,
    valueAsText(metadata.updaterArtifactsMode) ? `更新产物模式=${metadata.updaterArtifactsMode}` : null,
    valueAsText(metadata.updaterPubkeyConfigured) ? `更新公钥=${formatBooleanOk(metadata.updaterPubkeyConfigured)}` : null,
    valueAsText(metadata.updaterEndpointsConfigured) ? `更新端点=${formatBooleanOk(metadata.updaterEndpointsConfigured)}` : null,
    valueAsText(metadata.updaterEndpointCount) ? `更新端点数=${metadata.updaterEndpointCount}` : null,
    valueAsText(metadata.updaterInstallMode) ? `更新安装模式=${metadata.updaterInstallMode}` : null,
    valueAsText(metadata.mavenCliVersion) ? `Maven CLI=${formatCommandProbe(metadata.mavenCliAvailable, metadata.mavenCliVersion)}` : null,
    valueAsText(metadata.cargoCliVersion) ? `Cargo CLI=${formatCommandProbe(metadata.cargoCliAvailable, metadata.cargoCliVersion)}` : null,
    valueAsText(metadata.rustcCliVersion) ? `Rustc CLI=${formatCommandProbe(metadata.rustcCliAvailable, metadata.rustcCliVersion)}` : null,
    valueAsText(metadata.desktopBuildCommand) ? `打包命令=${metadata.desktopBuildCommand}` : null,
    valueAsText(metadata.windowsBuildCommand) ? `Windows命令=${metadata.windowsBuildCommand}` : null,
    valueAsText(metadata.packageArtifactCount) ? `产物数=${metadata.packageArtifactCount}` : null,
    valueAsText(metadata.latestPackageArtifact) ? `最新产物=${metadata.latestPackageArtifact}` : null,
    formatBytes(metadata.latestPackageArtifactSizeBytes) ? `产物大小=${formatBytes(metadata.latestPackageArtifactSizeBytes)}` : null,
    valueAsText(metadata.latestPackageArtifactModifiedAt) ? `产物时间=${metadata.latestPackageArtifactModifiedAt}` : null,
  ])
}

function isExplicitFalse(value: unknown) {
  return value === false || value === 'false'
}

function formatBooleanOk(value: unknown) {
  if (value === true || value === 'true') return 'OK'
  if (isExplicitFalse(value)) return 'WARN'
  return String(value)
}

function formatCommandProbe(available: unknown, detail: unknown) {
  const detailText = valueAsText(detail)
  if (available === true || available === 'true') return detailText ?? '可用'
  if (available === false || available === 'false') return detailText ? `不可用(${detailText})` : '不可用'
  return detailText ?? '未知'
}

function missingBuildToolchainParts(metadata: Record<string, unknown>) {
  return [
    metadata.mavenCliAvailable === false ? 'Maven/mvn' : null,
    metadata.cargoCliAvailable === false ? 'Cargo/cargo' : null,
    metadata.rustcCliAvailable === false ? 'Rustc/rustc' : null,
  ].filter((part): part is string => part !== null)
}

function summarizeModelServiceStatus(report: DiagnosticReport) {
  const model = report.checks.find(check => check.id === 'model-services')
  if (!model) return null
  const metadata = model.metadata ?? {}
  return joinCompact([
    `${model.label}=${model.status}`,
    `说明=${model.detail}`,
    valueAsText(metadata.generationEnabled) ? `生成=${metadata.generationEnabled}` : null,
    valueAsText(metadata.generationUnhealthy) ? `生成异常=${metadata.generationUnhealthy}` : null,
    valueAsText(metadata.embeddingEnabled) ? `向量=${metadata.embeddingEnabled}` : null,
    valueAsText(metadata.embeddingUnhealthy) ? `向量异常=${metadata.embeddingUnhealthy}` : null,
    valueAsText(metadata.rerankEnabled) ? `重排=${metadata.rerankEnabled}` : null,
  ])
}

function summarizeIntelligenceStatus(report: DiagnosticReport) {
  const intelligence = report.checks.find(check => check.id === 'intelligence')
  if (!intelligence) return null
  const metadata = intelligence.metadata ?? {}
  const configuredWait = valueAsText(metadata.experienceMatchTimeoutMs)
  const effectiveWait = valueAsText(metadata.effectiveExperienceMatchTimeoutMs)
  const foregroundWait = effectiveWait && configuredWait && configuredWait !== effectiveWait
    ? `${effectiveWait}ms/配置${configuredWait}ms`
    : effectiveWait
      ? `${effectiveWait}ms`
      : configuredWait
        ? `${configuredWait}ms`
        : null
  return joinCompact([
    `${intelligence.label}=${intelligence.status}`,
    `说明=${intelligence.detail}`,
    valueAsText(metadata.experienceMatchTrigger) ? `经验匹配=${metadata.experienceMatchTrigger}` : null,
    foregroundWait ? `前台=${foregroundWait}` : null,
    valueAsText(metadata.experienceMatchMaxPending) ? `经验后台上限=${metadata.experienceMatchMaxPending}` : null,
    valueAsText(metadata.experienceMatchBackgroundTimeoutMs)
      ? `经验后台超时=${metadata.experienceMatchBackgroundTimeoutMs}ms`
      : null,
    valueAsText(metadata.maxPendingToolExperienceRecords)
      ? `工具经验上限=${metadata.maxPendingToolExperienceRecords}`
      : null,
    valueAsText(metadata.toolExperienceRecordTimeoutMs)
      ? `工具经验超时=${metadata.toolExperienceRecordTimeoutMs}ms`
      : null,
    valueAsText(metadata.experienceMatchRecentTtlSeconds) && valueAsText(metadata.experienceMatchRecentMax)
      ? `最近复用=${metadata.experienceMatchRecentTtlSeconds}s/${metadata.experienceMatchRecentMax}`
    : null,
  ])
}

function summarizeCapabilitiesStatus(report: DiagnosticReport) {
  const capabilities = report.checks.find(check => check.id === 'capabilities')
  if (!capabilities) return null
  const metadata = capabilities.metadata ?? {}
  return joinCompact([
    `${capabilities.label}=${capabilities.status}`,
    `说明=${capabilities.detail}`,
    valueAsText(metadata.skills) ? `技能=${metadata.skills}` : null,
    valueAsText(metadata.tools) ? `工具=${metadata.tools}` : null,
    valueAsText(metadata.skillSuggestedToolReferences)
      ? `技能工具引用=${metadata.registeredSkillToolReferences ?? 0}/${metadata.skillSuggestedToolReferences}`
      : null,
    valueAsText(metadata.unknownSkillToolReferences) ? `未知工具=${metadata.unknownSkillToolReferences}` : null,
    valueAsText(metadata.missingCanonicalSkillToolReferences)
      ? `标准工具缺失=${metadata.missingCanonicalSkillToolReferences}`
      : null,
    valueAsText(metadata.highRiskTools) ? `高风险工具=${metadata.highRiskTools}` : null,
    Array.isArray(metadata.unknownSkillToolReferenceSamples) && metadata.unknownSkillToolReferenceSamples.length > 0
      ? `未知样例=${metadata.unknownSkillToolReferenceSamples.slice(0, 3).join('、')}`
      : null,
    Array.isArray(metadata.missingCanonicalSkillToolReferenceSamples)
      && metadata.missingCanonicalSkillToolReferenceSamples.length > 0
      ? `缺失样例=${metadata.missingCanonicalSkillToolReferenceSamples.slice(0, 3).join('、')}`
      : null,
  ])
}

function summarizeBackups(backups: LocalBackupFileInfo[] = []) {
  if (backups.length === 0) return '未发现备份文件'
  return backups
    .slice(0, 3)
    .map(backup => joinCompact([
      backup.fileName,
      formatBytes(backup.sizeBytes),
      backup.modifiedAt,
    ], ' / '))
    .join('; ')
}

function summarizeBackupValidation(validation?: LocalBackupValidationInfo | null) {
  if (!validation) return null
  return joinCompact([
    `${validation.fileName}=${validation.status}`,
    validation.detail,
    `条目=${validation.entryCount}`,
    `清单=${validation.manifestPresent ? '已包含' : '缺失'}`,
    validation.manifest ? `数据文件=${validation.manifest.includedFileCount}` : null,
    validation.problems.length ? `问题=${validation.problems.join('；')}` : null,
  ])
}

function summarizeRestorePlan(validation?: LocalBackupValidationInfo | null) {
  const plan = validation?.restorePlan
  if (!plan) return null
  return joinCompact([
    `恢复方式=${formatRestoreMode(plan.restoreMode, plan.manualRestoreOnly)}`,
    plan.includedTopLevelItems?.length ? `恢复范围=${plan.includedTopLevelItems.join('、')}` : null,
    plan.excludedTopLevelDirs?.length ? `不会恢复=${plan.excludedTopLevelDirs.join('、')}` : null,
    `目标HOME=${plan.targetHome}`,
    `当前数据=${plan.currentHomeFileCount}个文件`,
    `备份来源=${plan.backupSourceHome}`,
    plan.backupIncludedFileCount >= 0 ? `备份数据=${plan.backupIncludedFileCount}个文件` : '备份数据=未知',
    `恢复空间=${formatRestoreSpaceStatus(plan.restoreSpaceStatus)}`,
    `预计解压=${formatBytesOrUnknown(plan.estimatedRestoreBytes)}`,
    `可用空间=${formatBytesOrUnknown(plan.targetUsableBytes)}`,
    `暂存目录=${plan.restoreStagingDirectory}`,
    plan.warnings.length ? `风险=${plan.warnings.join('；')}` : null,
    plan.requiredSteps.length ? `步骤=${plan.requiredSteps.map((step, index) => `${index + 1}. ${step}`).join(' ')}` : null,
  ], '；')
}

function summarizeBackupRestorePreparation(preparation?: LocalBackupRestorePreparationInfo | null) {
  if (!preparation) return null
  return joinCompact([
    `${preparation.fileName}=已准备`,
    `时间=${preparation.preparedAt}`,
    `目录=${preparation.restoreDirectory}`,
    `文件=${preparation.extractedFileCount}`,
    `大小=${formatBytesOrUnknown(preparation.extractedBytes)}`,
    preparation.warnings.length ? `风险=${preparation.warnings.join('；')}` : null,
    preparation.nextSteps.length
      ? `下一步=${preparation.nextSteps.map((step, index) => `${index + 1}. ${step}`).join(' ')}`
      : null,
  ], '；')
}

export function buildBackupRestoreChecklist(validation: LocalBackupValidationInfo): string {
  const plan = validation.restorePlan
  const lines = [
    '[知微备份恢复清单]',
    line('备份文件', validation.fileName),
    line('校验状态', `${validation.status} - ${validation.detail}`),
    line('备份路径', validation.path),
    line('条目数', validation.entryCount),
    line('清单', validation.manifestPresent ? '已包含' : '缺失'),
  ]

  if (validation.manifest) {
    lines.push(line('备份来源 HOME', validation.manifest.sourceHome))
    lines.push(line('备份数据文件数', validation.manifest.includedFileCount))
    lines.push(line('备份创建时间', validation.manifest.createdAt))
  }

  if (validation.problems.length > 0) {
    lines.push('校验问题:')
    validation.problems.forEach((problem, index) => {
      lines.push(`${index + 1}. ${problem}`)
    })
  }

  if (!plan) {
    lines.push(line('恢复前预检', '未生成'))
    return `${lines.join('\n')}\n`
  }

  lines.push(line('目标 HOME', plan.targetHome))
  lines.push(line('恢复方式', formatRestoreMode(plan.restoreMode, plan.manualRestoreOnly)))
  if (plan.includedTopLevelItems.length > 0) {
    lines.push(line('恢复范围', plan.includedTopLevelItems.join('、')))
  }
  if (plan.excludedTopLevelDirs.length > 0) {
    lines.push(line('不会恢复', plan.excludedTopLevelDirs.join('、')))
  }
  lines.push(line('当前 HOME 数据', `${plan.currentHomeFileCount} 个文件`))
  lines.push(line('备份来源', plan.backupSourceHome))
  lines.push(line('备份包含数据', plan.backupIncludedFileCount >= 0 ? `${plan.backupIncludedFileCount} 个文件` : '未知'))
  lines.push(line('恢复暂存目录', plan.restoreStagingDirectory))
  lines.push(line('备份大小', formatBytesOrUnknown(plan.backupSizeBytes)))
  lines.push(line('预计解压占用', formatBytesOrUnknown(plan.estimatedRestoreBytes)))
  lines.push(line('目标可用空间', formatBytesOrUnknown(plan.targetUsableBytes)))
  lines.push(line('恢复空间预检', formatRestoreSpaceStatus(plan.restoreSpaceStatus)))

  if (plan.warnings.length > 0) {
    lines.push('风险提示:')
    plan.warnings.forEach((warning, index) => {
      lines.push(`${index + 1}. ${warning}`)
    })
  }

  lines.push('恢复步骤:')
  plan.requiredSteps.forEach((step, index) => {
    lines.push(`${index + 1}. ${step}`)
  })

  return `${lines.join('\n')}\n`
}

export function buildDiagnosticNextActions(report?: DiagnosticReport | null, error?: string | null) {
  const actions: string[] = []
  const text = error?.toLowerCase() ?? ''

  if (!report) {
    if (isCapabilityFailureText(text)) {
      actions.push('检查工具和技能是否可用；如果是 Skill 调用失败，打开能力中心核对 suggestedTools 和缺失能力。')
    }
    if (text.includes('network') || text.includes('fetch') || text.includes('连接') || text.includes('网络')) {
      actions.push('确认后端服务和网络连接正常后重试。')
    }
    return unique(actions).slice(0, 4)
  }

  const checks = new Map(report.checks.map(check => [check.id, check]))
  const modelServiceCheck = checks.get('model-services')
  const modelServiceMetadata = modelServiceCheck?.metadata ?? {}
  const modelServiceDetail = [
    modelServiceCheck?.detail,
    ...Object.values(modelServiceMetadata).map(value => valueAsText(value)),
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  const generationEnabled = firstFiniteNumber(
    report.counts['modelServices.generationEnabled'],
    modelServiceMetadata.generationEnabled,
  )
  const embeddingEnabled = firstFiniteNumber(
    report.counts['modelServices.embeddingEnabled'],
    modelServiceMetadata.embeddingEnabled,
  )
  const generationUnhealthy = firstFiniteNumber(
    report.counts['modelServices.generationUnhealthy'],
    modelServiceMetadata.generationUnhealthy,
  )
  const embeddingUnhealthy = firstFiniteNumber(
    report.counts['modelServices.embeddingUnhealthy'],
    modelServiceMetadata.embeddingUnhealthy,
  )

  if (generationEnabled === 0 || modelServiceDetail.includes('没有启用的生成模型')) {
    actions.push('到模型服务设置中启用一个生成模型，再重试当前消息。')
  } else if (generationUnhealthy > 0 || modelServiceDetail.includes('生成模型服务异常')) {
    actions.push('到模型服务设置中修复异常的生成模型，再重试当前消息。')
  } else if (
    modelServiceCheck
    && hasProblem(modelServiceCheck)
    && (embeddingEnabled === 0 || embeddingUnhealthy > 0)
  ) {
    actions.push('向量服务不可用时，知识库、记忆和语义召回会降级；需要时到模型服务设置中修复向量服务。')
  } else if (modelServiceCheck && hasProblem(modelServiceCheck)) {
    actions.push('检查模型服务配置和连接状态后重试。')
  }
  if (checks.get('schema-migrations') && checks.get('schema-migrations')!.status.toUpperCase() === 'ERROR') {
    actions.push('先暂停继续写入数据，保留启动日志并修复数据库迁移失败。')
  }
  if (checks.get('data-backups') && hasProblem(checks.get('data-backups')!)) {
    actions.push('升级、迁移或继续排查前，先备份知微 HOME 目录。')
  }
  if (checks.get('database') && checks.get('database')!.status.toUpperCase() === 'ERROR') {
    actions.push('检查数据目录权限、磁盘空间和 SQLite 文件占用后重启应用。')
  }
  if (checks.get('paths') && checks.get('paths')!.status.toUpperCase() === 'ERROR') {
    actions.push('检查 HOME/WORKSPACE 路径是否存在且可写。')
  }
  if (checks.get('mcp') && hasProblem(checks.get('mcp')!)) {
    actions.push('如果失败发生在外部工具调用，检查 MCP 服务连接状态。')
  }
  const capabilitiesCheck = checks.get('capabilities')
  if (capabilitiesCheck && hasProblem(capabilitiesCheck)) {
    const metadata = capabilitiesCheck.metadata ?? {}
    const unknown = firstFiniteNumber(
      report.counts['skills.unknownToolReferences'],
      metadata.unknownSkillToolReferences,
    )
    const missingCanonical = firstFiniteNumber(
      report.counts['skills.missingCanonicalToolReferences'],
      metadata.missingCanonicalSkillToolReferences,
    )
    if (unknown > 0 && missingCanonical > 0) {
      actions.push('修正 Skill 元数据里的未知工具引用，并确认核心工具提供方已注册。')
    } else if (unknown > 0) {
      actions.push('修正 Skill 元数据里的 suggestedTools，避免引用未知工具 ID。')
    } else if (missingCanonical > 0) {
      actions.push('先修复缺失的核心工具能力，再执行依赖这些工具的 Skill。')
    } else {
      actions.push('检查工具和技能是否正常加载，必要时重启应用。')
    }
  }
  if (checks.get('python-runtime') && hasProblem(checks.get('python-runtime')!)) {
    actions.push('如果任务依赖 Python 或文件处理能力，先安装或启用 Python 运行时。')
  }
  const distributionCheck = checks.get('desktop-distribution')
  if (distributionCheck && hasProblem(distributionCheck)) {
    const metadata = distributionCheck.metadata ?? {}
    if (isExplicitFalse(metadata.versionsAligned)) {
      actions.push('发布前先对齐后端、Web、Tauri 和 Cargo 版本号，避免安装包与运行时版本漂移。')
    } else if (isExplicitFalse(metadata.bundleActive)) {
      actions.push('发布前先启用 Tauri bundle 配置，否则不会生成桌面安装包。')
    } else if (isExplicitFalse(metadata.buildScriptsAligned)) {
      actions.push('先修正桌面端构建脚本，让 tauri:prepare 和 tauri:build 命令与发布流程一致。')
    } else if (isExplicitFalse(metadata.buildToolchainReady)) {
      const missing = missingBuildToolchainParts(metadata)
      actions.push(`发布或打包前，先补齐构建工具链：${missing.length > 0 ? missing.join('、') : 'Maven/mvn、Cargo/cargo、Rustc/rustc'}。`)
    } else if (isExplicitFalse(metadata.embeddedJreReady)) {
      const command = valueAsText(metadata.embeddedJrePrepareCommand) ?? 'cd zhiwei-web && npm run tauri:prepare:jre'
      actions.push(`Windows 安装包需要内嵌 JRE 22；先运行 ${command}，再重新打包。`)
    } else if (valueAsNumber(metadata.packageArtifactCount) === 0) {
      actions.push('发布前运行桌面端打包命令，确认 bundle 目录生成安装包产物。')
    } else if (isExplicitFalse(metadata.updaterConfigured) || isExplicitFalse(metadata.updaterDependency)) {
      actions.push('自动更新尚未完整配置；当前先用安装包手动更新，发布前补齐 Tauri updater 配置和依赖。')
    } else {
      actions.push('如果问题和版本有关，当前需要手动下载安装包更新。')
    }
  }

  if (actions.length === 0) {
    actions.push('本地诊断未发现明显异常，可重试当前消息；仍失败时查看执行轨迹。')
  }

  return unique(actions).slice(0, 4)
}

export function buildDiagnosticRepairLinks(report?: DiagnosticReport | null): DiagnosticRepairLink[] {
  if (!report) return []
  const checks = new Map(report.checks.map(check => [check.id, check]))
  const links: DiagnosticRepairLink[] = []
  const push = (link: DiagnosticRepairLink) => {
    if (!links.some(item => item.id === link.id)) {
      links.push(link)
    }
  }

  if (hasProblemCheck(checks.get('model-services'))) {
    push({
      id: 'models',
      label: '打开模型设置',
      title: '配置或修复模型服务',
      routeName: 'settingsModels',
    })
  }
  if (hasProblemCheck(checks.get('python-runtime'))) {
    push({
      id: 'code-execution',
      label: '代码执行环境',
      title: '检查 Python 和文件处理运行时',
      routeName: 'settingsCodeExecution',
    })
  }
  if (hasProblemCheck(checks.get('mcp'))) {
    push({
      id: 'channels',
      label: '集成渠道',
      title: '检查 MCP 和外部连接器',
      routeName: 'settingsChannels',
    })
  }
  if (hasProblemCheck(checks.get('capabilities'))) {
    push(CAPABILITY_REPAIR_LINK)
  }
  if (
    hasProblemCheck(checks.get('data-backups'))
    || hasProblemCheck(checks.get('database'))
    || hasProblemCheck(checks.get('schema-migrations'))
    || hasProblemCheck(checks.get('paths'))
    || hasProblemCheck(checks.get('desktop-distribution'))
  ) {
    push({
      id: 'general',
      label: '本地维护',
      title: '检查数据目录、备份、迁移和更新状态',
      routeName: 'settingsGeneral',
    })
  }

  return links.slice(0, 3)
}

export function buildErrorDiagnostic(input: ErrorDiagnosticInput): string {
  const message = input.message
  const lines = [
    '[知微诊断]',
    line('范围', input.scope === 'message' ? '消息失败' : '全局错误'),
    line('时间', new Date(input.timestamp ?? Date.now()).toISOString()),
    line('会话', input.sessionId),
    line('路由', input.route),
    line('流式中', input.streaming === undefined ? null : input.streaming ? '是' : '否'),
    line('消息ID', message?.id),
    line('轮次ID', message?.turnId),
    line('Trace', message?.traceId),
    line('角色', message?.role),
    line('状态', message?.status),
    line('轮次状态', message?.turnStatus),
    line('完成模式', message?.completionMode),
    line('错误', input.error ?? message?.errorMessage),
  ]

  if (message?.content) {
    lines.push(line('消息摘要', compact(message.content)))
  }
  appendRecoveryLines(lines, message)
  const recoveryAdvice = buildMessageRecoveryAdvice(message)
  if (recoveryAdvice.length > 0) {
    lines.push(line('恢复建议', recoveryAdvice.map((action, index) => `${index + 1}. ${action}`).join(' ')))
  }
  if (input.lastPrompt) {
    lines.push(line('最近输入摘要', compact(input.lastPrompt)))
  }
  if (input.diagnosticReport) {
    lines.push(line('本地诊断状态', input.diagnosticReport.status))
    lines.push(line('本地诊断摘要', input.diagnosticReport.summary))
    lines.push(line('本地诊断检查', summarizeReportChecks(input.diagnosticReport)))
    const databaseStatus = summarizeDatabaseStatus(input.diagnosticReport)
    if (databaseStatus) {
      lines.push(line('数据库状态', databaseStatus))
    }
    const backupStatus = summarizeBackupStatus(input.diagnosticReport)
    if (backupStatus) {
      lines.push(line('本地备份状态', backupStatus))
    }
    const migrationStatus = summarizeMigrationStatus(input.diagnosticReport)
    if (migrationStatus) {
      lines.push(line('数据迁移状态', migrationStatus))
    }
    const modelServiceStatus = summarizeModelServiceStatus(input.diagnosticReport)
    if (modelServiceStatus) {
      lines.push(line('模型服务状态', modelServiceStatus))
    }
    const distributionStatus = summarizeDistributionStatus(input.diagnosticReport)
    if (distributionStatus) {
      lines.push(line('安装更新状态', distributionStatus))
    }
    const intelligenceStatus = summarizeIntelligenceStatus(input.diagnosticReport)
    if (intelligenceStatus) {
      lines.push(line('智能增强状态', intelligenceStatus))
    }
    const capabilitiesStatus = summarizeCapabilitiesStatus(input.diagnosticReport)
    if (capabilitiesStatus) {
      lines.push(line('工具技能状态', capabilitiesStatus))
    }
    const nextActions = buildDiagnosticNextActions(input.diagnosticReport, input.error ?? message?.errorMessage)
    if (nextActions.length > 0) {
      lines.push(line('下一步建议', nextActions.map((action, index) => `${index + 1}. ${action}`).join(' ')))
    }
    if (input.diagnosticReport.hints.length > 0) {
      lines.push(line('本地诊断建议', compact(input.diagnosticReport.hints.join('；'))))
    }
  } else if (input.diagnosticReportError) {
    lines.push(line('本地诊断状态', '获取失败'))
    lines.push(line('本地诊断错误', compact(input.diagnosticReportError)))
    const nextActions = buildDiagnosticNextActions(null, input.error ?? message?.errorMessage)
    if (nextActions.length > 0) {
      lines.push(line('下一步建议', nextActions.map((action, index) => `${index + 1}. ${action}`).join(' ')))
    }
  }

  return `${lines.join('\n')}\n`
}

export function buildLocalMaintenanceDiagnostic(input: LocalMaintenanceDiagnosticInput): string {
  const report = input.report
  const lines = [
    '[知微本地维护诊断]',
    line('时间', new Date(input.timestamp ?? Date.now()).toISOString()),
    line('本地诊断状态', report?.status ?? '未读取'),
    line('本地诊断摘要', report?.summary),
  ]

  if (report) {
    lines.push(line('报告生成时间', report.generatedAt))
    lines.push(line('应用', joinCompact([
      valueAsText(report.app.name) ? `名称=${report.app.name}` : null,
      valueAsText(report.app.version) ? `版本=${report.app.version}` : null,
      valueAsText(report.app.configVersion) ? `配置=${report.app.configVersion}` : null,
    ])))
    lines.push(line('运行时', joinCompact([
      valueAsText(report.runtime.javaVersion) ? `Java=${report.runtime.javaVersion}` : null,
      valueAsText(report.runtime.osName) ? `OS=${report.runtime.osName}` : null,
      valueAsText(report.runtime.home) ? `HOME=${report.runtime.home}` : null,
      valueAsText(report.runtime.workspace) ? `WORKSPACE=${report.runtime.workspace}` : null,
    ])))
    lines.push(line('检查摘要', summarizeReportChecks(report)))
    lines.push(line('检查详情', summarizeReportCheckDetails(report)))

    const databaseStatus = summarizeDatabaseStatus(report)
    if (databaseStatus) {
      lines.push(line('数据库状态', databaseStatus))
    }
    const backupStatus = summarizeBackupStatus(report)
    if (backupStatus) {
      lines.push(line('本地备份状态', backupStatus))
    }
    const migrationStatus = summarizeMigrationStatus(report)
    if (migrationStatus) {
      lines.push(line('数据迁移状态', migrationStatus))
    }
    const modelServiceStatus = summarizeModelServiceStatus(report)
    if (modelServiceStatus) {
      lines.push(line('模型服务状态', modelServiceStatus))
    }
    const distributionStatus = summarizeDistributionStatus(report)
    if (distributionStatus) {
      lines.push(line('安装更新状态', distributionStatus))
    }
    const intelligenceStatus = summarizeIntelligenceStatus(report)
    if (intelligenceStatus) {
      lines.push(line('智能增强状态', intelligenceStatus))
    }
    const capabilitiesStatus = summarizeCapabilitiesStatus(report)
    if (capabilitiesStatus) {
      lines.push(line('工具技能状态', capabilitiesStatus))
    }
    const nextActions = buildDiagnosticNextActions(report)
    if (nextActions.length > 0) {
      lines.push(line('下一步建议', nextActions.map((action, index) => `${index + 1}. ${action}`).join(' ')))
    }
    if (report.hints.length > 0) {
      lines.push(line('本地诊断建议', compact(report.hints.join('；'), 360)))
    }
  }

  lines.push(line('最近备份', summarizeBackups(input.backups)))

  const validation = summarizeBackupValidation(input.backupValidation)
  if (validation) {
    lines.push(line('备份校验', validation))
  }
  const restorePlan = summarizeRestorePlan(input.backupValidation)
  if (restorePlan) {
    lines.push(line('恢复前预检', restorePlan))
  }
  const restorePreparation = summarizeBackupRestorePreparation(input.backupRestorePreparation)
  if (restorePreparation) {
    lines.push(line('恢复目录准备', restorePreparation))
  }

  return `${lines.join('\n')}\n`
}
