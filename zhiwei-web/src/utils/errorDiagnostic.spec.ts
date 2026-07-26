import { describe, expect, it } from 'vitest'
import {
  buildBackupRestoreChecklist,
  buildDiagnosticNextActions,
  buildDiagnosticRepairLinks,
  buildErrorDiagnostic,
  buildLocalMaintenanceDiagnostic,
  buildMessageRecoveryBrief,
  buildMessageRepairLinks,
} from './errorDiagnostic'
import type { Message } from '@/types'

describe('errorDiagnostic 错误诊断摘要', () => {
  it('生成可复制的消息失败诊断信息并截断正文', () => {
    const message: Message = {
      id: 'msg-1',
      turnId: 'turn-1',
      role: 'user',
      content: '这是一段很长的用户输入'.repeat(20),
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '网络连接失败',
      traceId: 'trace-1',
    } as any
    const timestamp = Date.parse('2026-07-04T10:00:00Z')

    const diagnostic = buildErrorDiagnostic({
      scope: 'message',
      message,
      error: message.errorMessage,
      timestamp,
    })

    expect(diagnostic).toContain('[知微诊断]')
    expect(diagnostic).toContain('范围: 消息失败')
    expect(diagnostic).toContain('消息ID: msg-1')
    expect(diagnostic).toContain('轮次ID: turn-1')
    expect(diagnostic).toContain('Trace: trace-1')
    expect(diagnostic).toContain('错误: 网络连接失败')
    expect(diagnostic).toContain('消息摘要:')
    expect(diagnostic).toContain('…')
  })

  it('附带本地诊断报告摘要', () => {
    const diagnostic = buildErrorDiagnostic({
      scope: 'global',
      error: '模型服务不可用',
      timestamp: Date.parse('2026-07-04T10:00:00Z'),
      diagnosticReport: {
        generatedAt: '2026-07-04T10:00:00Z',
        status: 'WARN',
        summary: '本地服务可用，但有配置或运行时风险',
        app: { name: 'zhiwei' },
        runtime: { javaVersion: '22' },
        counts: {
          'modelServices.enabled': 0,
          'modelServices.generationEnabled': 0,
          'modelServices.embeddingEnabled': 0,
          'modelServices.rerankEnabled': 0,
          'modelServices.generationUnhealthy': 0,
          'modelServices.embeddingUnhealthy': 0,
        },
        checks: [
          {
            id: 'database',
            label: '数据库',
            status: 'OK',
            detail: 'SQLite 连接可用，数据库文件可定位',
            metadata: {
              databaseLocation: 'file',
              databasePath: 'C:\\Users\\zsg\\.zhiwei\\db\\zhiwei.db',
              databaseSizeBytes: 1048576,
              journalMode: 'wal',
              busyTimeoutMs: '5000',
              walExists: true,
              walSizeBytes: 2048,
            },
          },
          {
            id: 'schema-migrations',
            label: '数据迁移',
            status: 'OK',
            detail: '数据库迁移历史正常',
            metadata: {
              total: 18,
              failed: 0,
              latestVersion: '18',
              latestScript: 'V18__agent_recovery.sql',
            },
          },
          {
            id: 'model-services',
            label: '模型服务',
            status: 'WARN',
            detail: '没有启用的生成模型服务',
            metadata: {
              enabled: 0,
              generationEnabled: 0,
              embeddingEnabled: 0,
              rerankEnabled: 0,
              generationUnhealthy: 0,
              embeddingUnhealthy: 0,
            },
          },
          {
            id: 'intelligence',
            label: '智能增强',
            status: 'OK',
            detail: '经验匹配不阻塞主对话，工具经验记录有后台名额和超时保护',
            metadata: {
              experienceMatchTrigger: 'task-like',
              experienceMatchTimeoutMs: 0,
              experienceMatchForegroundWaitCapMs: 80,
              effectiveExperienceMatchTimeoutMs: 0,
              experienceMatchMaxPending: 1,
              experienceMatchBackgroundTimeoutMs: 1200,
              experienceMatchRecentTtlSeconds: 300,
              experienceMatchRecentMax: 8,
              maxPendingToolExperienceRecords: 4,
              toolExperienceRecordTimeoutMs: 1200,
            },
          },
          {
            id: 'data-backups',
            label: '数据备份',
            status: 'OK',
            detail: '已发现本地备份文件',
            metadata: {
              directory: 'C:\\Users\\zsg\\.zhiwei\\backups',
              fileCount: 1,
              latestFile: 'zhiwei-backup-20260704-100100.zip',
              latestModifiedAt: '2026-07-04T10:01:00Z',
              latestSizeBytes: 2048,
            },
          },
          {
            id: 'desktop-distribution',
            label: '安装与更新',
            status: 'WARN',
            detail: '桌面端可打包，但自动更新尚未配置',
            metadata: {
              backendVersion: '0.2.0',
              packageVersion: '0.2.0',
              tauriVersion: '0.2.0',
              cargoVersion: '0.2.0',
              buildScriptsAligned: true,
              buildToolchainReady: false,
              updaterDependency: false,
              mavenCliAvailable: false,
              mavenCliVersion: 'missing',
              cargoCliAvailable: false,
              cargoCliVersion: 'missing',
              rustcCliAvailable: false,
              rustcCliVersion: 'missing',
              embeddedJreReady: false,
              embeddedJreAvailable: false,
              embeddedJreVersion: 'missing',
              embeddedJreMajorVersion: -1,
              embeddedJrePrepareCommand: 'cd zhiwei-web && npm run tauri:prepare:jre',
              packageArtifactCount: 1,
              latestPackageArtifact: 'ZhiWei_0.2.0_x64-setup.exe',
              latestPackageArtifactSizeBytes: 10485760,
              latestPackageArtifactModifiedAt: '2026-07-04T12:00:00Z',
            },
          },
          {
            id: 'capabilities',
            label: '工具和技能',
            status: 'WARN',
            detail: '部分技能引用了未知工具，执行前需要修正 Skill 元数据',
            metadata: {
              skills: 8,
              tools: 14,
              skillSuggestedToolReferences: 5,
              registeredSkillToolReferences: 4,
              unknownSkillToolReferences: 1,
              missingCanonicalSkillToolReferences: 0,
              highRiskTools: 2,
              unknownSkillToolReferenceSamples: ['research-assistant -> web.search'],
            },
          },
        ],
        hints: ['模型服务：没有启用的生成模型服务'],
      },
    })

    expect(diagnostic).toContain('本地诊断状态: WARN')
    expect(diagnostic).toContain('本地诊断摘要: 本地服务可用，但有配置或运行时风险')
    expect(diagnostic).toContain('本地诊断检查: 数据库=OK; 数据迁移=OK; 模型服务=WARN; 智能增强=OK; 数据备份=OK; 安装与更新=WARN; 工具和技能=WARN')
    expect(diagnostic).toContain('数据库状态: 数据库=OK；说明=SQLite 连接可用，数据库文件可定位；位置=file；路径=C:\\Users\\zsg\\.zhiwei\\db\\zhiwei.db；大小=1.0 MB；journal=wal；busyTimeout=5000ms；WAL=OK；WAL大小=2.0 KB')
    expect(diagnostic).toContain('本地备份状态: 数据备份=OK；说明=已发现本地备份文件；目录=C:\\Users\\zsg\\.zhiwei\\backups；文件数=1；最新=zhiwei-backup-20260704-100100.zip')
    expect(diagnostic).toContain('数据迁移状态: 数据迁移=OK；说明=数据库迁移历史正常；迁移数=18；失败数=0；最新版本=18；最新脚本=V18__agent_recovery.sql')
    expect(diagnostic).toContain('模型服务状态: 模型服务=WARN；说明=没有启用的生成模型服务；生成=0；生成异常=0；向量=0；向量异常=0；重排=0')
    expect(diagnostic).toContain('安装更新状态: 安装与更新=WARN；说明=桌面端可打包，但自动更新尚未配置；后端=0.2.0；Web=0.2.0；桌面端=0.2.0；Cargo=0.2.0；构建脚本=OK；构建环境=WARN；内嵌JRE=WARN；JRE版本=missing；JRE准备命令=cd zhiwei-web && npm run tauri:prepare:jre；更新依赖=WARN；Maven CLI=不可用(missing)；Cargo CLI=不可用(missing)；Rustc CLI=不可用(missing)；产物数=1；最新产物=ZhiWei_0.2.0_x64-setup.exe；产物大小=10.0 MB；产物时间=2026-07-04T12:00:00Z')
    expect(diagnostic).toContain('大小=2.0 KB')
    expect(diagnostic).toContain('智能增强状态: 智能增强=OK；说明=经验匹配不阻塞主对话，工具经验记录有后台名额和超时保护；经验匹配=task-like；前台=0ms；经验后台上限=1；经验后台超时=1200ms；工具经验上限=4；工具经验超时=1200ms；最近复用=300s/8')
    expect(diagnostic).toContain('工具技能状态: 工具和技能=WARN；说明=部分技能引用了未知工具，执行前需要修正 Skill 元数据；技能=8；工具=14；技能工具引用=4/5；未知工具=1；标准工具缺失=0；高风险工具=2；未知样例=research-assistant -> web.search')
    expect(diagnostic).toContain('下一步建议: 1. 到模型服务设置中启用一个生成模型，再重试当前消息。')
    expect(diagnostic).toContain('本地诊断建议: 模型服务：没有启用的生成模型服务')
  })

  it('消息失败诊断应附带任务恢复断点', () => {
    const message: Message = {
      id: 'assistant-failed',
      turnId: 'turn-1',
      role: 'assistant',
      content: '测试失败，可以从失败处继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      completionMode: 'DEGRADED',
      errorMessage: '命令执行失败',
      traceId: 'trace-failed',
      taskRecovery: {
        status: 'DEGRADED',
        title: 'Shell 执行 没有完成',
        detail: '命令或代码没有完成，可以修正错误后继续执行。',
        actionLabel: '修正后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
    } as any

    const diagnostic = buildErrorDiagnostic({
      scope: 'message',
      message,
      timestamp: Date.parse('2026-07-04T10:00:00Z'),
    })

    expect(diagnostic).toContain('任务恢复: DEGRADED；Shell 执行 没有完成；恢复方式=manual；动作=修正后继续')
    expect(diagnostic).toContain('恢复说明: 命令或代码没有完成，可以修正错误后继续执行。')
    expect(diagnostic).toContain('恢复计划: 1. 查看命令输出并修正报错原因 2. 从失败命令后继续执行验证')
    expect(diagnostic).toContain('恢复断点: 任务步骤；Shell 执行；操作=执行命令；类型=命令执行；目录=D:\\WorkSpace\\Project\\News；输入=执行 `npm test`；输出=测试失败')
    expect(diagnostic).toContain('恢复建议: 1. 优先在对话里点击「修正后继续」，知微会带着当前断点继续。 2. 继续前核对计划：查看命令输出并修正报错原因；从失败命令后继续执行验证。 3. 如果断点或输入已经不可信，再重新开始这一轮。')
  })

  it('能力缺口失败诊断应提示先修复工具和 Skill 引用', () => {
    const message: Message = {
      id: 'assistant-capability-failed',
      turnId: 'turn-capability',
      role: 'assistant',
      content: '当前步骤需要先修复能力后继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      completionMode: 'DEGRADED',
      errorMessage: '工具未注册：web.search',
      traceId: 'trace-capability',
      taskRecovery: {
        status: 'DEGRADED',
        title: '能力缺口需要修复',
        detail: '依赖的工具或技能当前不可用，可以在能力中心修复连接后继续。',
        actionLabel: '修复能力后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'web.search',
          toolName: '网页搜索',
          executionKind: 'TOOL',
          action: '搜索资料',
          failureCategory: 'CAPABILITY',
          missingCapabilities: [
            { kind: 'TOOL', id: 'web.search', source: 'suggested_tools', skillName: 'research-assistant' },
          ],
          outputSummary: '运行前校验失败',
        },
        nextActions: ['补齐缺失能力：web.search', '检查 Skill research-assistant 的 suggestedTools 引用', '修复后从失败步骤继续'],
      },
    } as any

    const diagnostic = buildErrorDiagnostic({
      scope: 'message',
      message,
      timestamp: Date.parse('2026-07-04T10:00:00Z'),
    })

    expect(diagnostic).toContain('恢复断点: 任务步骤；网页搜索；操作=搜索资料；类型=能力缺口；缺失能力=web.search(research-assistant)；输出=运行前校验失败')
    expect(diagnostic).toContain('先在能力中心修复缺失工具或 Skill 引用，再点击「修复能力后继续」从断点继续。')
    expect(diagnostic).toContain('优先补齐缺失能力：web.search(research-assistant)。')
    expect(diagnostic).toContain('排查重点是工具是否注册、Skill suggestedTools 是否指向未知 ID，以及 MCP 或本地工具提供方是否启动。')
  })

  it('可以为失败消息提炼首屏恢复线索', () => {
    const message: Message = {
      id: 'assistant-failed',
      turnId: 'turn-1',
      role: 'assistant',
      content: '测试失败，可以从失败处继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
      completionMode: 'DEGRADED',
      errorMessage: '命令执行失败',
      traceId: 'trace-failed',
      taskRecovery: {
        status: 'DEGRADED',
        title: 'Shell 执行没有完成',
        detail: '命令或代码没有完成，可以修正错误后继续执行。',
        actionLabel: '修正后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
    } as any

    const brief = buildMessageRecoveryBrief(message)

    expect(brief?.title).toBe('Shell 执行没有完成')
    expect(brief?.detail).toBe('命令或代码没有完成，可以修正错误后继续执行。')
    expect(brief?.contextLines).toContain('恢复方式：手动继续')
    expect(brief?.contextLines.join('\n')).toContain('断点：任务步骤；Shell 执行；操作=执行命令；类型=命令执行')
    expect(brief?.nextActions).toEqual([
      '查看命令输出并修正报错原因',
      '从失败命令后继续执行验证',
      '优先在对话里点击「修正后继续」，知微会带着当前断点继续。',
    ])
  })

  it('历史续接诊断应附带来源 Trace 和续接输入', () => {
    const message: Message = {
      id: 'assistant-resumed',
      turnId: 'turn-resume',
      role: 'assistant',
      content: '我已经继续处理完了。',
      timestamp: Date.now(),
      turnRecoveryContext: {
        action: 'RESUME',
        title: '接上上次任务',
        sourceTraceId: 'trace-failed',
        resumeInput: '继续执行：修正后继续。目标：Shell 执行。',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
        },
        nextActions: ['从失败命令后继续执行验证', '补跑相关测试'],
      },
    } as any

    const diagnostic = buildErrorDiagnostic({
      scope: 'message',
      message,
      timestamp: Date.parse('2026-07-04T10:00:00Z'),
    })

    expect(diagnostic).toContain('本轮续接: RESUME；接上上次任务；来源Trace=trace-failed')
    expect(diagnostic).toContain('续接输入: 继续执行：修正后继续。目标：Shell 执行。')
    expect(diagnostic).toContain('续接计划: 1. 从失败命令后继续执行验证 2. 补跑相关测试')
    expect(diagnostic).toContain('恢复断点: 任务步骤；Shell 执行；操作=执行命令；类型=命令执行；输入=执行 `npm test`')
    expect(diagnostic).toContain('恢复建议: 1. 本轮已经从Trace trace-failed续接，排查时先对照续接输入、断点和计划。')
  })

  it('根据失败检查生成可操作下一步', () => {
    const actions = buildDiagnosticNextActions({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'ERROR',
      summary: '本地服务存在需要处理的错误',
      app: {},
      runtime: {},
      counts: { 'modelServices.generationEnabled': 1 },
      checks: [
        { id: 'schema-migrations', label: '数据迁移', status: 'ERROR', detail: '存在失败迁移', metadata: {} },
        { id: 'database', label: '数据库', status: 'OK', detail: 'SQLite 连接可用', metadata: {} },
      ],
      hints: [],
    }, '服务器内部错误')

    expect(actions).toContain('先暂停继续写入数据，保留启动日志并修复数据库迁移失败。')
  })

  it('向量服务降级时不误导用户修复生成模型', () => {
    const actions = buildDiagnosticNextActions({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '主对话可用，向量增强降级',
      app: {},
      runtime: {},
      counts: {
        'modelServices.generationEnabled': 1,
        'modelServices.embeddingEnabled': 1,
        'modelServices.generationUnhealthy': 0,
        'modelServices.embeddingUnhealthy': 1,
      },
      checks: [
        {
          id: 'model-services',
          label: '模型服务',
          status: 'WARN',
          detail: '主对话模型可用；向量服务启动预热异常，知识库、记忆和语义召回会降级',
          metadata: {},
        },
      ],
      hints: [],
    }, '召回失败')

    expect(actions).toContain('向量服务不可用时，知识库、记忆和语义召回会降级；需要时到模型服务设置中修复向量服务。')
    expect(actions).not.toContain('到模型服务设置中启用一个生成模型，再重试当前消息。')
    expect(actions).not.toContain('到模型服务设置中修复异常的生成模型，再重试当前消息。')
  })

  it('备份缺失时提醒先保护本地数据', () => {
    const actions = buildDiagnosticNextActions({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '本地服务可用，但有配置或运行时风险',
      app: {},
      runtime: {},
      counts: { 'modelServices.generationEnabled': 1 },
      checks: [
        { id: 'data-backups', label: '数据备份', status: 'WARN', detail: '尚未发现本地备份文件', metadata: {} },
      ],
      hints: [],
    }, '发送失败')

    expect(actions).toContain('升级、迁移或继续排查前，先备份知微 HOME 目录。')
  })

  it('自动更新缺失时给出发布前补齐 updater 的明确建议', () => {
    const actions = buildDiagnosticNextActions({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '桌面端可打包，但自动更新尚未配置',
      app: {},
      runtime: {},
      counts: { 'modelServices.generationEnabled': 1 },
      checks: [
        {
          id: 'desktop-distribution',
          label: '安装与更新',
          status: 'WARN',
          detail: '桌面端可打包，但自动更新尚未配置',
          metadata: {
            versionsAligned: true,
            bundleActive: true,
            buildScriptsAligned: true,
            buildToolchainReady: true,
            updaterConfigured: false,
            updaterDependency: false,
            packageArtifactCount: 1,
          },
        },
      ],
      hints: [],
    }, '检查更新失败')

    expect(actions).toContain('自动更新尚未完整配置；当前先用安装包手动更新，发布前补齐 Tauri updater 配置和依赖。')
    expect(actions).not.toContain('如果问题和版本有关，当前需要手动下载安装包更新。')
  })

  it('内嵌 JRE 缺失时优先给出 Windows 安装包准备命令', () => {
    const actions = buildDiagnosticNextActions({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: 'Windows 安装包缺少可用的内嵌 JRE 22',
      app: {},
      runtime: {},
      counts: { 'modelServices.generationEnabled': 1 },
      checks: [
        {
          id: 'desktop-distribution',
          label: '安装与更新',
          status: 'WARN',
          detail: 'Windows 安装包缺少可用的内嵌 JRE 22',
          metadata: {
            versionsAligned: true,
            bundleActive: true,
            buildScriptsAligned: true,
            buildToolchainReady: true,
            embeddedJreReady: false,
            embeddedJreAvailable: false,
            embeddedJreVersion: 'missing',
            embeddedJrePrepareCommand: 'cd zhiwei-web && npm run tauri:prepare:jre',
            updaterConfigured: false,
            updaterDependency: false,
            packageArtifactCount: 1,
          },
        },
      ],
      hints: [],
    }, '打包失败')

    expect(actions).toContain('Windows 安装包需要内嵌 JRE 22；先运行 cd zhiwei-web && npm run tauri:prepare:jre，再重新打包。')
    expect(actions).not.toContain('自动更新尚未完整配置；当前先用安装包手动更新，发布前补齐 Tauri updater 配置和依赖。')
  })

  it('未读取本机诊断时也能识别能力缺口失败文本', () => {
    const actions = buildDiagnosticNextActions(null, 'unknown tool: web.search is not registered')

    expect(actions).toContain('检查工具和技能是否可用；如果是 Skill 调用失败，打开能力中心核对 suggestedTools 和缺失能力。')
  })

  it('技能引用未知工具时给出能力校准建议', () => {
    const actions = buildDiagnosticNextActions({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '工具和技能需要校准',
      app: {},
      runtime: {},
      counts: {
        'modelServices.generationEnabled': 1,
        'skills.unknownToolReferences': 1,
        'skills.missingCanonicalToolReferences': 0,
      },
      checks: [
        {
          id: 'capabilities',
          label: '工具和技能',
          status: 'WARN',
          detail: '部分技能引用了未知工具，执行前需要修正 Skill 元数据',
          metadata: {
            unknownSkillToolReferences: 1,
            missingCanonicalSkillToolReferences: 0,
          },
        },
      ],
      hints: [],
    }, 'Skill 执行失败')

    expect(actions).toContain('修正 Skill 元数据里的 suggestedTools，避免引用未知工具 ID。')
  })

  it('根据失败检查生成对应设置修复入口', () => {
    const links = buildDiagnosticRepairLinks({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '本地服务可用，但有配置或运行时风险',
      app: {},
      runtime: {},
      counts: {},
      checks: [
        { id: 'model-services', label: '模型服务', status: 'WARN', detail: '没有启用的生成模型服务', metadata: {} },
        { id: 'python-runtime', label: 'Python 运行时', status: 'WARN', detail: '未安装 Python', metadata: {} },
        { id: 'mcp', label: 'MCP', status: 'ERROR', detail: '连接失败', metadata: {} },
        { id: 'data-backups', label: '数据备份', status: 'WARN', detail: '尚未发现本地备份文件', metadata: {} },
      ],
      hints: [],
    })

    expect(links).toEqual([
      expect.objectContaining({ id: 'models', routeName: 'settingsModels', label: '打开模型设置' }),
      expect.objectContaining({ id: 'code-execution', routeName: 'settingsCodeExecution', label: '代码执行环境' }),
      expect.objectContaining({ id: 'channels', routeName: 'settingsChannels', label: '集成渠道' }),
    ])
  })

  it('能力诊断异常时生成能力中心修复入口', () => {
    const links = buildDiagnosticRepairLinks({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '工具和技能需要校准',
      app: {},
      runtime: {},
      counts: {},
      checks: [
        {
          id: 'capabilities',
          label: '工具和技能',
          status: 'WARN',
          detail: '部分技能引用了未知工具，执行前需要修正 Skill 元数据',
          metadata: {
            unknownSkillToolReferences: 1,
            missingCanonicalSkillToolReferences: 0,
          },
        },
      ],
      hints: [],
    })

    expect(links).toEqual([
      expect.objectContaining({
        id: 'capabilities',
        routeName: 'capabilities',
        label: '能力中心',
        title: '检查工具、技能状态和 Skill 引用',
      }),
    ])
  })

  it('能力恢复断点应直接生成能力中心修复入口', () => {
    const links = buildMessageRepairLinks({
      id: 'assistant-capability-failed',
      turnId: 'turn-capability',
      role: 'assistant',
      content: '工具没有注册，可以修复能力后继续。',
      timestamp: Date.now(),
      taskRecovery: {
        status: 'DEGRADED',
        title: '能力缺口需要修复',
        detail: '依赖的工具或技能当前不可用，可以在能力中心修复连接后继续。',
        actionLabel: '修复能力后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'web.search',
          toolName: '网页搜索',
          executionKind: 'TOOL',
          missingCapabilities: [
            { kind: 'TOOL', id: 'web.search', source: 'suggested_tools', skillName: 'research-assistant' },
          ],
          outputSummary: '工具未注册',
        },
      },
    } as any)

    expect(links).toEqual([
      expect.objectContaining({
        id: 'capabilities',
        routeName: 'capabilities',
        label: '能力中心',
      }),
    ])
  })

  it('诊断报告获取失败时仍给出网络类建议', () => {
    const diagnostic = buildErrorDiagnostic({
      scope: 'global',
      error: '网络连接失败',
      diagnosticReportError: 'fetch failed',
      timestamp: Date.parse('2026-07-04T10:00:00Z'),
    })

    expect(diagnostic).toContain('本地诊断状态: 获取失败')
    expect(diagnostic).toContain('下一步建议: 1. 确认后端服务和网络连接正常后重试。')
  })

  it('生成可复制的本地维护诊断摘要', () => {
    const diagnostic = buildLocalMaintenanceDiagnostic({
      timestamp: Date.parse('2026-07-04T10:00:00Z'),
      report: {
        generatedAt: '2026-07-04T09:59:00Z',
        status: 'WARN',
        summary: '本地服务可用，但有配置或运行时风险',
        app: { name: 'zhiwei', version: '0.2.0', configVersion: 'dev' },
        runtime: {
          javaVersion: '22',
          osName: 'Windows 11',
          home: 'D:\\zhiwei',
          workspace: 'D:\\workspace',
        },
        counts: { 'modelServices.generationEnabled': 1 },
        checks: [
          {
            id: 'database',
            label: '数据库',
            status: 'OK',
            detail: 'SQLite 连接可用，数据库文件可定位',
            metadata: {
              databaseLocation: 'file',
              databasePath: 'D:\\zhiwei\\db\\zhiwei.db',
              databaseSizeBytes: 1048576,
              journalMode: 'wal',
              busyTimeoutMs: '5000',
              walExists: true,
              walSizeBytes: 2048,
            },
          },
          {
            id: 'schema-migrations',
            label: '数据迁移',
            status: 'ERROR',
            detail: '存在失败的数据库迁移',
            metadata: {
              total: 19,
              failed: 1,
              latestVersion: '19',
              latestScript: 'V19__broken.sql',
              failedMigrationScripts: ['V19__broken.sql'],
            },
          },
          {
            id: 'data-backups',
            label: '数据备份',
            status: 'WARN',
            detail: '尚未发现本地备份文件',
            metadata: { directory: 'D:\\zhiwei\\backups', fileCount: 0 },
          },
          {
            id: 'desktop-distribution',
            label: '安装与更新',
            status: 'WARN',
            detail: '桌面端配置可读取，但尚未发现安装包产物',
            metadata: {
              backendVersion: '0.2.0',
              packageVersion: '0.2.0',
              tauriVersion: '0.2.0',
              cargoVersion: '0.2.0',
              versionsAligned: true,
              bundleActive: true,
              buildScriptsAligned: true,
              buildToolchainReady: false,
              desktopBuildCommand: 'cd zhiwei-web && npm run tauri:build',
              windowsBuildCommand: 'cd zhiwei-web && npm run tauri:build:windows',
              updaterReady: false,
              updaterConfigured: true,
              updaterDependency: false,
              updaterArtifactsConfigured: false,
              updaterArtifactsMode: 'missing',
              updaterPubkeyConfigured: false,
              updaterEndpointCount: 0,
              updaterEndpointsConfigured: false,
              updaterInstallMode: 'default',
              mavenCliAvailable: false,
              mavenCliVersion: 'missing',
              cargoCliAvailable: false,
              cargoCliVersion: 'missing',
              rustcCliAvailable: false,
              rustcCliVersion: 'missing',
              embeddedJreReady: false,
              embeddedJreAvailable: false,
              embeddedJreVersion: 'missing',
              embeddedJreMajorVersion: -1,
              embeddedJrePrepareCommand: 'cd zhiwei-web && npm run tauri:prepare:jre',
              packageArtifactCount: 0,
            },
          },
          {
            id: 'intelligence',
            label: '智能增强',
            status: 'OK',
            detail: '经验匹配不阻塞主对话，工具经验记录有后台名额和超时保护',
            metadata: {
              experienceMatchTrigger: 'task-like',
              effectiveExperienceMatchTimeoutMs: 0,
              experienceMatchMaxPending: 1,
              experienceMatchBackgroundTimeoutMs: 1200,
              experienceMatchRecentTtlSeconds: 300,
              experienceMatchRecentMax: 8,
              maxPendingToolExperienceRecords: 4,
              toolExperienceRecordTimeoutMs: 1200,
            },
          },
          {
            id: 'capabilities',
            label: '工具和技能',
            status: 'WARN',
            detail: '部分技能引用的核心工具当前不可用，相关任务会降级或需要修复',
            metadata: {
              skills: 6,
              tools: 10,
              skillSuggestedToolReferences: 4,
              registeredSkillToolReferences: 3,
              unknownSkillToolReferences: 0,
              missingCanonicalSkillToolReferences: 1,
              highRiskTools: 1,
              missingCanonicalSkillToolReferenceSamples: ['repo-auditor -> shell.exec'],
            },
          },
        ],
        hints: ['数据备份：升级或迁移前建议先备份 HOME 目录'],
      },
      backups: [{
        modifiedAt: '2026-07-04T11:00:00Z',
        fileName: 'zhiwei-backup-20260704-110000.zip',
        path: 'D:\\zhiwei\\backups\\zhiwei-backup-20260704-110000.zip',
        sizeBytes: 2048,
      }],
      backupValidation: {
        fileName: 'zhiwei-backup-20260704-110000.zip',
        path: 'D:\\zhiwei\\backups\\zhiwei-backup-20260704-110000.zip',
        status: 'OK',
        detail: '备份文件结构正常',
        sizeBytes: 2048,
        entryCount: 2,
        manifestPresent: true,
        manifest: {
          formatVersion: '1',
          createdAt: '2026-07-04T11:00:00Z',
          sourceHome: 'D:\\zhiwei',
          includedFileCount: 1,
          excludedTopLevelDirs: ['backups', 'cache'],
        },
        problems: [],
        restorePlan: {
          restoreMode: 'manual-staging',
          manualRestoreOnly: true,
          includedTopLevelItems: ['db'],
          excludedTopLevelDirs: ['backups', 'cache'],
          targetHome: 'D:\\zhiwei',
          currentHomeHasData: true,
          currentHomeFileCount: 3,
          backupSourceHome: 'D:\\old-zhiwei',
          backupIncludedFileCount: 1,
          restoreStagingDirectory: 'D:\\zhiwei\\runtime\\restore-staging',
          backupSizeBytes: 2048,
          estimatedRestoreBytes: 4096,
          targetUsableBytes: 104857600,
          restoreSpaceStatus: 'OK',
          warnings: ['当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。'],
          requiredSteps: ['关闭知微，确认没有后台进程占用 HOME 目录。'],
        },
      },
      backupRestorePreparation: {
        preparedAt: '2026-07-04T11:05:00Z',
        fileName: 'zhiwei-backup-20260704-110000.zip',
        restoreDirectory: 'D:\\zhiwei\\runtime\\restore-staging\\zhiwei-backup-20260704-110000-restore',
        extractedFileCount: 3,
        extractedBytes: 4096,
        warnings: ['当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。'],
        nextSteps: ['打开恢复准备目录，检查文件结构和备份清单。'],
      },
    })

    expect(diagnostic).toContain('[知微本地维护诊断]')
    expect(diagnostic).toContain('本地诊断状态: WARN')
    expect(diagnostic).toContain('应用: 名称=zhiwei；版本=0.2.0；配置=dev')
    expect(diagnostic).toContain('运行时: Java=22；OS=Windows 11；HOME=D:\\zhiwei；WORKSPACE=D:\\workspace')
    expect(diagnostic).toContain('检查摘要: 数据库=OK; 数据迁移=ERROR; 数据备份=WARN; 安装与更新=WARN; 智能增强=OK')
    expect(diagnostic).toContain('数据库状态: 数据库=OK；说明=SQLite 连接可用，数据库文件可定位；位置=file；路径=D:\\zhiwei\\db\\zhiwei.db；大小=1.0 MB；journal=wal；busyTimeout=5000ms；WAL=OK；WAL大小=2.0 KB')
    expect(diagnostic).toContain('本地备份状态: 数据备份=WARN；说明=尚未发现本地备份文件；目录=D:\\zhiwei\\backups；文件数=0')
    expect(diagnostic).toContain('数据迁移状态: 数据迁移=ERROR；说明=存在失败的数据库迁移；迁移数=19；失败数=1；最新版本=19；最新脚本=V19__broken.sql；失败脚本=V19__broken.sql')
    expect(diagnostic).toContain('安装更新状态: 安装与更新=WARN；说明=桌面端配置可读取，但尚未发现安装包产物；后端=0.2.0；Web=0.2.0；桌面端=0.2.0；Cargo=0.2.0；版本对齐=OK；打包启用=OK；构建脚本=OK；构建环境=WARN；内嵌JRE=WARN；JRE版本=missing；JRE准备命令=cd zhiwei-web && npm run tauri:prepare:jre；更新就绪=WARN；更新配置=OK；更新依赖=WARN；更新产物=WARN；更新产物模式=missing；更新公钥=WARN；更新端点=WARN；更新端点数=0；更新安装模式=default；Maven CLI=不可用(missing)；Cargo CLI=不可用(missing)；Rustc CLI=不可用(missing)；打包命令=cd zhiwei-web && npm run tauri:build；Windows命令=cd zhiwei-web && npm run tauri:build:windows；产物数=0')
    expect(diagnostic).toContain('工具技能状态: 工具和技能=WARN；说明=部分技能引用的核心工具当前不可用，相关任务会降级或需要修复；技能=6；工具=10；技能工具引用=3/4；未知工具=0；标准工具缺失=1；高风险工具=1；缺失样例=repo-auditor -> shell.exec')
    expect(diagnostic).toContain('最近备份: zhiwei-backup-20260704-110000.zip / 2.0 KB / 2026-07-04T11:00:00Z')
    expect(diagnostic).toContain('备份校验: zhiwei-backup-20260704-110000.zip=OK；备份文件结构正常；条目=2；清单=已包含；数据文件=1')
    expect(diagnostic).toContain('恢复前预检: 恢复方式=手动暂存恢复；恢复范围=db；不会恢复=backups、cache；目标HOME=D:\\zhiwei；当前数据=3个文件；备份来源=D:\\old-zhiwei；备份数据=1个文件；恢复空间=空间充足；预计解压=4.0 KB；可用空间=100.0 MB；暂存目录=D:\\zhiwei\\runtime\\restore-staging')
    expect(diagnostic).toContain('恢复目录准备: zhiwei-backup-20260704-110000.zip=已准备；时间=2026-07-04T11:05:00Z；目录=D:\\zhiwei\\runtime\\restore-staging\\zhiwei-backup-20260704-110000-restore；文件=3；大小=4.0 KB')
    expect(diagnostic).toContain('下一步=1. 打开恢复准备目录，检查文件结构和备份清单。')
    expect(diagnostic).toContain('下一步建议: 1. 先暂停继续写入数据，保留启动日志并修复数据库迁移失败。 2. 升级、迁移或继续排查前，先备份知微 HOME 目录。')
  })

  it('生成可复制的备份恢复清单', () => {
    const checklist = buildBackupRestoreChecklist({
      fileName: 'zhiwei-backup-20260704-110000.zip',
      path: 'D:\\zhiwei\\backups\\zhiwei-backup-20260704-110000.zip',
      status: 'OK',
      detail: '备份文件结构正常',
      sizeBytes: 2048,
      entryCount: 2,
      manifestPresent: true,
      manifest: {
        formatVersion: '1',
        createdAt: '2026-07-04T11:00:00Z',
        sourceHome: 'D:\\old-zhiwei',
        includedFileCount: 1,
        excludedTopLevelDirs: ['backups', 'cache'],
      },
      problems: [],
      restorePlan: {
        restoreMode: 'manual-staging',
        manualRestoreOnly: true,
        includedTopLevelItems: ['db'],
        excludedTopLevelDirs: ['backups', 'cache'],
        targetHome: 'D:\\zhiwei',
        currentHomeHasData: true,
        currentHomeFileCount: 3,
        backupSourceHome: 'D:\\old-zhiwei',
        backupIncludedFileCount: 1,
        restoreStagingDirectory: 'D:\\zhiwei\\runtime\\restore-staging',
        backupSizeBytes: 2048,
        estimatedRestoreBytes: 4096,
        targetUsableBytes: 104857600,
        restoreSpaceStatus: 'OK',
        warnings: ['当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。'],
        requiredSteps: [
          '关闭知微，确认没有后台进程占用 HOME 目录。',
          '先为当前 HOME 创建一份新备份，保留回退点。',
        ],
      },
    })

    expect(checklist).toContain('[知微备份恢复清单]')
    expect(checklist).toContain('备份文件: zhiwei-backup-20260704-110000.zip')
    expect(checklist).toContain('校验状态: OK - 备份文件结构正常')
    expect(checklist).toContain('目标 HOME: D:\\zhiwei')
    expect(checklist).toContain('恢复方式: 手动暂存恢复')
    expect(checklist).toContain('恢复范围: db')
    expect(checklist).toContain('不会恢复: backups、cache')
    expect(checklist).toContain('恢复暂存目录: D:\\zhiwei\\runtime\\restore-staging')
    expect(checklist).toContain('预计解压占用: 4.0 KB')
    expect(checklist).toContain('目标可用空间: 100.0 MB')
    expect(checklist).toContain('恢复空间预检: 空间充足')
    expect(checklist).toContain('风险提示:')
    expect(checklist).toContain('1. 当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。')
    expect(checklist).toContain('恢复步骤:')
    expect(checklist).toContain('2. 先为当前 HOME 创建一份新备份，保留回退点。')
  })
})
