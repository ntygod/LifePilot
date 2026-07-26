import { buildArtifactDownloadUrl, type ArtifactRefPayload } from '@/api/artifacts'
import type { MissingCapability, ToolExecutionKind, ToolFailureCategory, ToolRecoveryAction } from '@/types'

interface ToolRecoveryPlanSource {
  toolId?: string | null
  failureCategory?: ToolFailureCategory
  executionKind?: ToolExecutionKind
  subjectNames?: string[] | null
  outputSummary?: string | null
  outputDetail?: string | null
  missingCapabilities?: MissingCapability[] | null
}

interface ToolRecoveryContextSource extends ToolRecoveryPlanSource {
  subjectLabel?: string | null
  interrupted?: boolean
  inputSummary?: string | null
  inputDetail?: string | null
  outputSummary?: string | null
  outputDetail?: string | null
  workingDirectory?: string | null
  generatedFilePath?: string | null
  artifactRefs?: Array<{ fileName?: string | null }> | null
  missingCapabilities?: MissingCapability[] | null
}

export function isSkillTool(toolId?: string | null): boolean {
  return !!toolId && (toolId === 'skill.load' || toolId.startsWith('skill.'))
}

function isSkillLoadTool(toolId?: string | null): boolean {
  return toolId === 'skill.load'
}

function startsWithAny(toolId: string, prefixes: string[]) {
  return prefixes.some(prefix => toolId.startsWith(prefix))
}

function compactText(value: string, maxLength = 34) {
  const trimmed = value.trim()
  if (trimmed.length <= maxLength) return trimmed
  return `${trimmed.slice(0, maxLength - 1)}…`
}

function basename(path: string) {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '')
  return normalized.split('/').filter(Boolean).pop() || path
}

function uniqueText(values: string[]) {
  return Array.from(new Set(values))
}

function compactFailureText(...values: Array<string | null | undefined>) {
  return values
    .map(value => value?.trim())
    .filter((value): value is string => !!value)
    .join(' ')
}

function sourceFailureText(source?: ToolRecoveryPlanSource | null) {
  return source ? compactFailureText(source.outputSummary, source.outputDetail) : ''
}

function collectMissingCapabilityIds(source?: ToolRecoveryPlanSource | null): string[] {
  const ids: string[] = []
  for (const item of source?.missingCapabilities ?? []) {
    const id = item.id?.trim()
    if (id) ids.push(id)
  }

  const text = sourceFailureText(source)
  const patterns = [
    /(?:工具未注册|工具没有注册|工具尚未注册|工具不存在|未找到工具|找不到工具|unknown tool reference|unknown suggested tool|unknown tool|tool not found|no such tool|missing tool|suggestedtools?)\s*[:：=\-]?\s*[`"']?([a-z][a-z0-9_.-]{1,80})/gi,
    /工具\s*[`"']?([a-z][a-z0-9_.-]{1,80})[`"']?\s*未注册/gi,
    /tool\s*[`"']?([a-z][a-z0-9_.-]{1,80})[`"']?\s*(?:not registered|not found|missing)/gi,
  ]
  const stopWords = new Set(['not', 'found', 'registered', 'missing', 'reference', 'tool'])
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) {
      const id = match[1]?.trim()
      if (id && !stopWords.has(id.toLowerCase())) ids.push(id)
    }
  }

  if (!ids.length && source?.toolId && !isSkillTool(source.toolId)) {
    ids.push(source.toolId)
  }

  return uniqueText(ids)
}

function collectMissingCapabilitySkillNames(source?: ToolRecoveryPlanSource | null): string[] {
  const names = [
    ...(source?.missingCapabilities ?? []).map(item => item.skillName?.trim()).filter((name): name is string => !!name),
    ...((source?.executionKind === 'SKILL' || isSkillTool(source?.toolId)) ? source?.subjectNames ?? [] : []),
  ]
  return uniqueText(names.filter(Boolean))
}

function formatCapabilityNames(names: string[], maxItems = 3): string | null {
  if (!names.length) return null
  const visible = names.slice(0, maxItems).map(name => compactText(name, 22)).join('、')
  return names.length > maxItems ? `${visible} 等 ${names.length} 个` : visible
}

function capabilitySkillReferenceAction(source: ToolRecoveryPlanSource): string {
  const skillNames = formatCapabilityNames(collectMissingCapabilitySkillNames(source), 2)
  if (skillNames) {
    return `检查 Skill ${skillNames} 的 suggestedTools 引用`
  }
  if (source.executionKind === 'SKILL' || isSkillTool(source.toolId)) {
    return '检查当前 Skill 的 suggestedTools 引用'
  }
  return '到能力中心检查 MCP 工具提供方或本地工具连接'
}

function buildCapabilityRecoveryPlan(source: ToolRecoveryPlanSource): string[] {
  const missing = formatCapabilityNames(collectMissingCapabilityIds(source))
  if (!missing) {
    return ['确认能力中心能看到所需工具和技能', '修正 Skill suggestedTools 或恢复缺失的工具提供方']
  }
  return [
    `补齐缺失能力：${missing}`,
    capabilitySkillReferenceAction(source),
    '修复后从失败步骤继续',
  ]
}

function buildCapabilityRestartRecoveryPlan(source: ToolRecoveryPlanSource): string[] {
  const missing = formatCapabilityNames(collectMissingCapabilityIds(source))
  if (!missing) {
    return ['保留缺失工具和 Skill 引用线索', '修复缺失能力后重新执行相关步骤']
  }
  return [
    `保留缺失能力线索：${missing}`,
    capabilitySkillReferenceAction(source),
    '修复后重新执行相关步骤',
  ]
}

function isCapabilityRegistryFailure(text?: string | null) {
  const value = text?.trim().toLowerCase() ?? ''
  if (!value) return false
  return value.includes('工具未注册')
    || value.includes('工具没有注册')
    || value.includes('工具尚未注册')
    || (value.includes('工具') && value.includes('未注册'))
    || value.includes('未找到工具')
    || value.includes('找不到工具')
    || value.includes('工具不存在')
    || value.includes('unknown tool')
    || value.includes('tool not found')
    || value.includes('no such tool')
    || value.includes('missing tool')
    || (value.includes('tool') && value.includes('not registered'))
    || value.includes('unknown tool reference')
    || value.includes('unknown suggested tool')
    || value.includes('suggestedtools')
}

export function mergeToolRecoveryArtifactRefs(
  ...groups: Array<Array<Partial<ArtifactRefPayload>> | undefined | null>
): ArtifactRefPayload[] | undefined {
  const seen = new Set<string>()
  const refs: ArtifactRefPayload[] = []
  for (const group of groups) {
    for (const ref of group ?? []) {
      const artifactId = ref.artifactId?.trim()
      if (!artifactId || seen.has(artifactId)) continue
      seen.add(artifactId)
      refs.push({
        artifactId,
        fileName: ref.fileName?.trim() || artifactId,
        mimeType: ref.mimeType?.trim() || 'application/octet-stream',
        kind: ref.kind === 'IMAGE' ? 'IMAGE' : 'FILE',
        size: typeof ref.size === 'number' && Number.isFinite(ref.size) ? ref.size : 0,
        downloadUrl: ref.downloadUrl?.trim() || buildArtifactDownloadUrl(artifactId),
      })
      if (refs.length >= 8) return refs
    }
  }
  return refs.length > 0 ? refs : undefined
}

function isKnowledgeTool(toolId: string) {
  return startsWithAny(toolId, ['knowledge.', 'kb.', 'rag.', 'document.', 'pdf.', 'vector.'])
}

function isWorkflowTool(toolId: string) {
  return startsWithAny(toolId, ['workflow.', 'task.', 'scheduled.'])
}

function isIntegrationTool(toolId: string) {
  return startsWithAny(toolId, ['mcp.', 'connector.', 'integration.'])
}

function isAgentTool(toolId: string) {
  return startsWithAny(toolId, ['agent.'])
}

function isModelTool(toolId: string) {
  return startsWithAny(toolId, ['llm.', 'model.', 'embedding.', 'vision.', 'image.', 'audio.', 'stt.', 'tts.'])
}

export function resolveToolExecutionKind(toolId: string): ToolExecutionKind {
  return isSkillTool(toolId) ? 'SKILL' : 'TOOL'
}

export function resolveToolAction(toolId: string): string | undefined {
  if (isSkillLoadTool(toolId)) {
    return '加载技能'
  }
  if (isSkillTool(toolId)) {
    return '执行技能'
  }
  if (toolId === 'shell.exec' || toolId === 'code') {
    return '执行命令'
  }
  if (toolId === 'file.read') {
    return '读取文件'
  }
  if (toolId === 'file.write') {
    return '写入文件'
  }
  if (toolId.startsWith('file.')) {
    return '操作文件'
  }
  if (toolId === 'web.search') {
    return '搜索资料'
  }
  if (toolId === 'web.fetch') {
    return '读取网页'
  }
  if (toolId.startsWith('web.')) {
    return '访问网络资料'
  }
  if (toolId === 'browser' || toolId.startsWith('browser.')) {
    return '操作浏览器'
  }
  if (toolId === 'memory' || toolId.startsWith('memory.')) {
    return '处理记忆'
  }
  if (toolId.startsWith('git.')) {
    return '操作仓库'
  }
  if (isKnowledgeTool(toolId)) {
    return '处理资料'
  }
  if (isWorkflowTool(toolId)) {
    return '执行工作流'
  }
  if (isIntegrationTool(toolId)) {
    return '调用连接器'
  }
  if (isAgentTool(toolId)) {
    return '协作智能体'
  }
  if (isModelTool(toolId)) {
    return '整理回答'
  }
  return undefined
}

export function resolveToolFailureCategory(toolId: string, failureText?: string | null): ToolFailureCategory {
  if (isCapabilityRegistryFailure(failureText)) {
    return 'CAPABILITY'
  }
  if (toolId === 'shell.exec' || toolId === 'code') {
    return 'COMMAND'
  }
  if (toolId.startsWith('file.')) {
    return 'FILE'
  }
  if (toolId === 'browser' || toolId.startsWith('browser.')) {
    return 'BROWSER'
  }
  if (toolId.startsWith('web.')) {
    return 'NETWORK'
  }
  if (toolId === 'memory' || toolId.startsWith('memory.')) {
    return 'MEMORY'
  }
  if (isSkillTool(toolId)) {
    return 'SKILL'
  }
  if (toolId.startsWith('git.')) {
    return 'REPOSITORY'
  }
  if (isKnowledgeTool(toolId)) {
    return 'KNOWLEDGE'
  }
  if (isWorkflowTool(toolId)) {
    return 'WORKFLOW'
  }
  if (isIntegrationTool(toolId)) {
    return 'INTEGRATION'
  }
  if (isAgentTool(toolId)) {
    return 'AGENT'
  }
  if (isModelTool(toolId)) {
    return 'MODEL'
  }
  return 'UNKNOWN'
}

export function formatToolFailureCategory(category?: ToolFailureCategory): string {
  switch (category) {
    case 'CAPABILITY':
      return '能力缺口'
    case 'COMMAND':
      return '命令执行'
    case 'FILE':
      return '文件操作'
    case 'BROWSER':
      return '浏览器操作'
    case 'NETWORK':
      return '外部访问'
    case 'MEMORY':
      return '记忆处理'
    case 'SKILL':
      return '技能步骤'
    case 'KNOWLEDGE':
      return '资料处理'
    case 'WORKFLOW':
      return '工作流'
    case 'INTEGRATION':
      return '连接器'
    case 'AGENT':
      return '智能体协作'
    case 'MODEL':
      return '模型处理'
    case 'REPOSITORY':
      return '仓库操作'
    case 'UNKNOWN':
      return '未知步骤'
    default:
      return ''
  }
}

export function resolveToolRecoveryHint(toolId: string, failureText?: string | null): string {
  if (isCapabilityRegistryFailure(failureText)) {
    return '依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。'
  }
  if (toolId === 'shell.exec' || toolId === 'code') {
    return '命令或代码没有完成，可以修正错误后继续执行。'
  }
  if (toolId.startsWith('file.')) {
    return '文件操作没有完成，检查路径或权限后继续执行。'
  }
  if (toolId === 'browser' || toolId.startsWith('browser.')) {
    return '浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。'
  }
  if (toolId.startsWith('web.')) {
    return '外部访问没有完成，可以重试或换一种资料来源继续。'
  }
  if (toolId === 'memory' || toolId.startsWith('memory.')) {
    return '记忆操作没有完成，可以调整条件后继续。'
  }
  if (isSkillLoadTool(toolId)) {
    return '技能加载没有完成，可以检查技能名称或依赖后继续。'
  }
  if (isSkillTool(toolId)) {
    return '技能执行没有完成，可以检查输入、依赖或技能步骤后继续。'
  }
  if (toolId.startsWith('git.')) {
    return '仓库操作没有完成，可以检查分支、权限或冲突后继续。'
  }
  if (isKnowledgeTool(toolId)) {
    return '资料处理没有完成，可以检查资料来源、索引或解析结果后继续。'
  }
  if (isWorkflowTool(toolId)) {
    return '工作流没有完成，可以检查当前节点状态后继续执行。'
  }
  if (isIntegrationTool(toolId)) {
    return '连接器调用没有完成，可以检查服务连接或授权后继续。'
  }
  if (isAgentTool(toolId)) {
    return '智能体协作没有完成，可以等待返回、检查委托目标或切换为本地处理。'
  }
  if (isModelTool(toolId)) {
    return '模型处理没有完成，可以检查模型配置、输入或重试策略后继续。'
  }
  return '这一步没有完成，可以让知微从失败处继续。'
}

export function resolveResumeActionLabel(category: ToolFailureCategory): string {
  switch (category) {
    case 'CAPABILITY':
      return '修复能力后继续'
    case 'FILE':
      return '检查后继续'
    case 'BROWSER':
      return '检查页面后继续'
    case 'NETWORK':
      return '重试后继续'
    case 'MEMORY':
      return '调整记忆后继续'
    case 'SKILL':
      return '检查技能后继续'
    case 'KNOWLEDGE':
      return '调整资料后继续'
    case 'WORKFLOW':
      return '检查流程后继续'
    case 'INTEGRATION':
      return '检查连接后继续'
    case 'AGENT':
      return '检查协作后继续'
    case 'MODEL':
      return '检查模型后继续'
    case 'REPOSITORY':
      return '检查仓库后继续'
    case 'UNKNOWN':
      return '继续处理'
    default:
      return '修正后继续'
  }
}

export function buildToolRecoveryActions(
  toolId: string,
  source?: ToolRecoveryPlanSource,
): ToolRecoveryAction[] {
  const category = source?.failureCategory ?? resolveToolFailureCategory(toolId, sourceFailureText(source))
  const planSource = {
    ...source,
    toolId: source?.toolId ?? toolId,
    failureCategory: category,
  }
  const resumeNextActions = buildToolRecoveryPlan(planSource).slice(0, 3)
  const restartNextActions = buildToolRestartRecoveryPlan(planSource).slice(0, 3)
  return [
    {
      id: 'resume',
      label: resolveResumeActionLabel(category),
      description: resolveResumeActionDescription(category),
      mode: 'resume',
      category,
      nextActions: resumeNextActions,
    },
    {
      id: 'restart',
      label: '重新开始',
      description: resolveRestartActionDescription(category),
      mode: 'restart',
      category,
      nextActions: restartNextActions,
    },
  ]
}

export function buildToolRecoveryContextSummary(source: ToolRecoveryContextSource): string[] {
  const category = source.failureCategory
    ?? (source.toolId ? resolveToolFailureCategory(source.toolId, sourceFailureText(source)) : 'UNKNOWN')
  const subjectNames = source.subjectNames?.filter(Boolean) ?? []
  const subjectLabel = source.subjectLabel?.trim()
    || (source.executionKind === 'SKILL' || category === 'SKILL' ? '技能' : '对象')
  const lines: string[] = []

  if (subjectNames.length) {
    const names = subjectNames.slice(0, 2).map(name => compactText(name, 20)).join('、')
    lines.push(`保留${subjectLabel} ${names}${subjectNames.length > 2 ? ` 等 ${subjectNames.length} 个` : ''}`)
  } else if (source.executionKind === 'SKILL' || category === 'SKILL') {
    lines.push('保留当前技能步骤')
  }

  if (source.inputDetail || source.inputSummary) {
    lines.push('带上原始输入')
  }
  if (source.outputDetail || source.outputSummary) {
    lines.push('带上失败输出')
  }
  if (source.workingDirectory) {
    lines.push(`工作目录 ${compactText(basename(source.workingDirectory), 24)}`)
  }
  if (source.generatedFilePath) {
    lines.push(`生成文件 ${compactText(basename(source.generatedFilePath), 24)}`)
  }
  if (source.artifactRefs?.length) {
    const firstName = source.artifactRefs.map(ref => ref.fileName).find(Boolean)
    lines.push(firstName
      ? `保留产物 ${compactText(firstName, 24)}${source.artifactRefs.length > 1 ? ` 等 ${source.artifactRefs.length} 个` : ''}`
      : `保留 ${source.artifactRefs.length} 个产物`
    )
  }
  if (source.missingCapabilities?.length) {
    const ids = source.missingCapabilities
      .map(item => item.id?.trim())
      .filter(Boolean)
    if (ids.length) {
      lines.push(`缺失能力 ${ids.slice(0, 2).map(id => compactText(id, 22)).join('、')}${ids.length > 2 ? ` 等 ${ids.length} 个` : ''}`)
    }
  }
  if (source.interrupted) {
    lines.push('从中断位置接上')
  }

  if (!lines.length) {
    lines.push(category === 'UNKNOWN' ? '保留失败步骤线索' : `保留${formatToolFailureCategory(category)}线索`)
  }

  return uniqueText(lines).slice(0, 5)
}

export function resolveResumeActionDescription(category: ToolFailureCategory): string {
  switch (category) {
    case 'CAPABILITY':
      return '保留当前进度，修复缺失工具或技能连接后从失败步骤接上。'
    case 'SKILL':
      return '保留当前进度，检查技能后从失败步骤接上。'
    case 'COMMAND':
      return '保留已完成步骤，修正命令或代码错误后继续验证。'
    case 'FILE':
      return '保留已完成修改，检查路径或权限后继续文件操作。'
    case 'BROWSER':
      return '保留当前浏览器上下文，检查页面状态、登录或元素选择后继续。'
    case 'NETWORK':
      return '保留已获取资料，恢复访问或更换来源后继续。'
    case 'MEMORY':
      return '保留当前判断，调整记忆条件后继续沉淀或检索。'
    case 'KNOWLEDGE':
      return '保留已处理资料，调整来源、索引或解析后继续。'
    case 'WORKFLOW':
      return '保留流程状态，从失败节点继续推进。'
    case 'INTEGRATION':
      return '保留调用上下文，连接或授权恢复后继续。'
    case 'AGENT':
      return '保留协作状态，合并已有结果后继续本地处理。'
    case 'MODEL':
      return '保留输入和失败原因，调整模型配置后继续生成。'
    case 'REPOSITORY':
      return '保留仓库状态，处理分支、权限或冲突后继续。'
    default:
      return '保留已有进度，从卡住的位置继续处理。'
  }
}

export function resolveRestartActionDescription(category: ToolFailureCategory): string {
  switch (category) {
    case 'CAPABILITY':
      return '保留能力缺失线索，修复后重新执行相关步骤。'
    case 'SKILL':
      return '保留技能失败线索，重新加载或执行失败技能步骤。'
    case 'COMMAND':
      return '保留失败输出，重新开始并优先修正命令错误。'
    case 'FILE':
      return '保留已完成修改线索，重新规划文件操作。'
    case 'BROWSER':
      return '保留页面状态线索，重新打开或重新操作目标页面。'
    case 'NETWORK':
      return '保留已获取资料线索，重新选择资料来源后执行。'
    case 'MEMORY':
      return '保留当前判断依据，重新执行记忆沉淀或检索。'
    case 'KNOWLEDGE':
      return '保留已处理资料线索，重新检索或重建处理步骤。'
    case 'WORKFLOW':
      return '保留流程状态，重新执行失败节点或整段流程。'
    case 'INTEGRATION':
      return '保留连接器失败原因，恢复连接后重新调用。'
    case 'AGENT':
      return '保留远程协作状态，重新委托或改为本地处理。'
    case 'MODEL':
      return '保留模型失败原因，调整配置后重新生成。'
    case 'REPOSITORY':
      return '保留仓库失败状态，重新执行并处理冲突。'
    default:
      return '保留失败线索，重新开始这一轮并优先修正卡点。'
  }
}

export function buildToolRecoveryPlan(source: ToolRecoveryPlanSource): string[] {
  const category = source.failureCategory
    ?? (source.toolId ? resolveToolFailureCategory(source.toolId, sourceFailureText(source)) : 'UNKNOWN')
  if (category === 'CAPABILITY') {
    return buildCapabilityRecoveryPlan(source)
  }
  if (source.executionKind === 'SKILL' || category === 'SKILL') {
    const subjectName = source.subjectNames?.find(Boolean)
    if (!isSkillLoadTool(source.toolId)) {
      return subjectName
        ? [`检查技能 ${subjectName} 的输入、依赖和执行步骤`, '保留当前进度并从失败技能步骤继续']
        : ['检查技能输入、依赖和执行步骤', '保留当前进度并从失败技能步骤继续']
    }
    return subjectName
      ? [`确认技能 ${subjectName} 的名称和依赖是否可用`, '重新加载技能后继续当前任务']
      : ['确认技能名称和依赖是否可用', '重新加载技能后继续当前任务']
  }

  switch (category) {
    case 'COMMAND':
      return ['查看命令输出并修正报错原因', '从失败命令后继续执行验证']
    case 'FILE':
      return ['检查文件路径或权限', '保留已完成修改并从失败文件操作继续']
    case 'BROWSER':
      return ['检查浏览器页面状态、登录或元素选择', '保留当前上下文并从失败页面操作继续']
    case 'NETWORK':
      return ['确认外部访问是否可用', '更换资料来源或重试失败请求']
    case 'MEMORY':
      return ['检查记忆条件或内容', '调整后继续沉淀或检索记忆']
    case 'KNOWLEDGE':
      return ['检查资料来源、索引或解析结果', '保留已完成资料处理并从失败处继续']
    case 'WORKFLOW':
      return ['查看当前工作流节点状态', '从失败节点继续或重新执行该节点']
    case 'INTEGRATION':
      return ['检查连接器服务、授权或参数', '连接恢复后继续当前步骤']
    case 'AGENT':
      return ['确认委托目标或远程任务状态', '合并已有结果后继续本地处理']
    case 'MODEL':
      return ['检查模型配置、输入长度或限流状态', '调整后继续生成或重试模型调用']
    case 'REPOSITORY':
      return ['检查分支、权限、冲突或仓库状态', '保留已完成修改并继续仓库操作']
    default:
      return ['查看失败步骤的输入输出', '从失败处继续或重新开始这一轮']
  }
}

function buildToolRestartRecoveryPlan(source: ToolRecoveryPlanSource): string[] {
  const category = source.failureCategory
    ?? (source.toolId ? resolveToolFailureCategory(source.toolId, sourceFailureText(source)) : 'UNKNOWN')
  if (category === 'CAPABILITY') {
    return buildCapabilityRestartRecoveryPlan(source)
  }
  if (source.executionKind === 'SKILL' || category === 'SKILL') {
    const subjectName = source.subjectNames?.find(Boolean)
    if (isSkillLoadTool(source.toolId)) {
      return subjectName
        ? [`保留技能 ${subjectName} 的加载失败原因`, '重新加载技能后重跑当前任务']
        : ['保留技能加载失败原因', '重新加载技能后重跑当前任务']
    }
    return subjectName
      ? [`保留技能 ${subjectName} 的失败输入输出`, '重新执行失败技能步骤']
      : ['保留技能失败输入输出', '重新执行失败技能步骤']
  }

  switch (category) {
    case 'COMMAND':
      return ['保留失败命令输出作为线索', '重新开始这一轮并优先修正命令错误']
    case 'FILE':
      return ['保留已完成文件修改线索', '重新开始文件操作并避开失败路径']
    case 'BROWSER':
      return ['保留页面状态和失败操作线索', '重新打开或重新操作目标页面']
    case 'NETWORK':
      return ['保留已获取资料线索', '重新选择资料来源后重新执行']
    case 'MEMORY':
      return ['保留当前记忆判断依据', '重新执行记忆沉淀或检索']
    case 'KNOWLEDGE':
      return ['保留已处理资料线索', '重新检索或重建资料处理步骤']
    case 'WORKFLOW':
      return ['保留当前工作流状态', '重新执行失败节点或整段流程']
    case 'INTEGRATION':
      return ['保留连接器失败原因', '连接恢复后重新执行调用']
    case 'AGENT':
      return ['保留远程协作状态', '重新委托或切换为本地处理']
    case 'MODEL':
      return ['保留模型调用失败原因', '调整模型配置后重新生成']
    case 'REPOSITORY':
      return ['保留仓库失败状态', '重新执行仓库操作并处理冲突']
    default:
      return ['保留失败步骤的输入输出', '重新开始这一轮并优先修正失败点']
  }
}
