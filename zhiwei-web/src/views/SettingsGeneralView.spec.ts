import { defineComponent, h } from 'vue'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsGeneralView from './SettingsGeneralView.vue'

const mocks = vi.hoisted(() => ({
  saveSettings: vi.fn(),
  getPathSettings: vi.fn(),
  updatePathSettings: vi.fn(),
  getExternalCliBashSettings: vi.fn(),
  updateExternalCliBashSettings: vi.fn(),
  getDiagnosticReport: vi.fn(),
  listBackups: vi.fn(),
  createDiagnosticBundle: vi.fn(),
  createBackup: vi.fn(),
  validateBackup: vi.fn(),
  prepareBackupRestore: vi.fn(),
  copyToClipboard: vi.fn(),
  revealInFileManager: vi.fn(),
  isTauri: false,
  showToast: vi.fn(),
}))

vi.mock('@/composables/useSettings', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')
  return {
    useSettings: () => ({
      settings: ref({
        theme: 'system',
        layoutDensity: 'standard',
        fontSize: 'medium',
        showTokenUsage: true,
      }),
      saveSettings: mocks.saveSettings,
    }),
  }
})

vi.mock('@/api/client', () => ({
  settingsApi: {
    getPathSettings: mocks.getPathSettings,
    updatePathSettings: mocks.updatePathSettings,
    getExternalCliBashSettings: mocks.getExternalCliBashSettings,
    updateExternalCliBashSettings: mocks.updateExternalCliBashSettings,
  },
  diagnosticsApi: {
    getReport: mocks.getDiagnosticReport,
    listBackups: mocks.listBackups,
    createDiagnosticBundle: mocks.createDiagnosticBundle,
    createBackup: mocks.createBackup,
    validateBackup: mocks.validateBackup,
    prepareBackupRestore: mocks.prepareBackupRestore,
  },
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({
    showToast: mocks.showToast,
  }),
}))

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: mocks.copyToClipboard,
}))

vi.mock('@/composables/useSaveFilePicker', () => ({
  isTauriEnv: () => mocks.isTauri,
  revealInFileManager: mocks.revealInFileManager,
}))

vi.mock('@/utils/logger', () => ({
  logger: {
    error: vi.fn(),
  },
}))

function mountView() {
  const RenderDefaultStub = defineComponent({
    name: 'RenderDefaultStub',
    setup(_props, { slots }) {
      return () => h('div', slots.default?.())
    },
  })
  const SettingSectionStub = defineComponent({
    name: 'SettingSection',
    props: {
      title: String,
      description: String,
    },
    setup(props, { slots }) {
      return () => h('section', [
        h('h3', props.title as string),
        h('p', props.description as string),
        slots['header-actions']?.(),
        slots.default?.(),
      ])
    },
  })
  const SettingItemStub = defineComponent({
    name: 'SettingItem',
    props: {
      label: String,
      description: String,
    },
    setup(props, { slots }) {
      return () => h('div', [
        h('span', props.label as string),
        h('p', props.description as string),
        slots.label?.(),
        slots.description?.(),
        slots.default?.(),
      ])
    },
  })
  const ButtonStub = defineComponent({
    name: 'Button',
    props: {
      disabled: Boolean,
    },
    emits: ['click'],
    setup(props, { emit, slots }) {
      return () => h(
        'button',
        {
          type: 'button',
          disabled: props.disabled,
          onClick: () => emit('click'),
        },
        slots.default?.(),
      )
    },
  })
  const BadgeStub = defineComponent({
    name: 'Badge',
    setup(_props, { slots }) {
      return () => h('span', slots.default?.())
    },
  })

  return shallowMount(SettingsGeneralView, {
    global: {
      stubs: {
        Badge: BadgeStub,
        Button: ButtonStub,
        ConfirmDialog: RenderDefaultStub,
        Input: RenderDefaultStub,
        Select: RenderDefaultStub,
        SelectContent: RenderDefaultStub,
        SelectItem: RenderDefaultStub,
        SelectTrigger: RenderDefaultStub,
        SelectValue: RenderDefaultStub,
        SettingAdvanced: RenderDefaultStub,
        SettingItem: SettingItemStub,
        SettingSection: SettingSectionStub,
        Switch: RenderDefaultStub,
        Tooltip: RenderDefaultStub,
        TooltipContent: RenderDefaultStub,
        TooltipProvider: RenderDefaultStub,
        TooltipTrigger: RenderDefaultStub,
      },
    },
  })
}

beforeEach(() => {
  mocks.saveSettings.mockReset().mockResolvedValue(undefined)
  mocks.getPathSettings.mockReset().mockResolvedValue({
    home: 'D:\\zhiwei',
    workspace: 'D:\\workspace',
    restartRequired: false,
    pathAccess: {
      mode: 'unrestricted',
      whitelist: [],
      blacklist: [],
    },
  })
  mocks.updatePathSettings.mockReset()
  mocks.getExternalCliBashSettings.mockReset().mockResolvedValue({
    externalCliBashPath: null,
  })
  mocks.updateExternalCliBashSettings.mockReset()
  mocks.getDiagnosticReport.mockReset().mockResolvedValue({
    generatedAt: '2026-07-04T10:00:00Z',
    status: 'OK',
    summary: '本地服务状态正常',
    app: {},
    runtime: {},
    counts: {
      'modelServices.generationEnabled': 1,
      'modelServices.embeddingEnabled': 1,
      'modelServices.rerankEnabled': 0,
      'modelServices.generationUnhealthy': 0,
      'modelServices.embeddingUnhealthy': 1,
    },
    checks: [
      {
        id: 'database',
        label: '数据库',
        status: 'OK',
        detail: 'SQLite 连接可用，数据库文件可定位',
        metadata: {
          databaseLocation: 'file',
          sqliteVersion: '3.46.1',
          databasePath: 'D:\\zhiwei\\db\\zhiwei.db',
          databaseSizeBytes: 1048576,
          walExists: true,
          walSizeBytes: 2048,
          shmExists: true,
          shmSizeBytes: 32768,
          journalMode: 'wal',
          busyTimeoutMs: '5000',
        },
      },
      {
        id: 'data-backups',
        label: '数据备份',
        status: 'OK',
        detail: '已发现本地备份文件',
        metadata: {
          directory: 'D:\\zhiwei\\backups',
          fileCount: 1,
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
          latestInstalledOn: '2026-07-04 10:00:00',
        },
      },
      {
        id: 'desktop-distribution',
        label: '安装与更新',
        status: 'WARN',
        detail: '桌面端可打包，但自动更新配置不完整',
        metadata: {
          backendVersion: '0.2.0',
          packageVersion: '0.2.0',
          tauriVersion: '0.2.0',
          cargoVersion: '0.2.0',
          versionsAligned: true,
          bundleActive: true,
          beforeBuildCommand: 'npm run tauri:prepare',
          buildScriptsAligned: true,
          backendBuildCommand: 'mvn clean package -DskipTests',
          frontendBuildCommand: 'cd zhiwei-web && npm run build',
          desktopBuildCommand: 'cd zhiwei-web && npm run tauri:build',
          windowsBuildCommand: 'cd zhiwei-web && npm run tauri:build:windows',
          buildToolchainReady: false,
          mavenCliAvailable: false,
          mavenCliVersion: 'missing',
          cargoCliAvailable: false,
          cargoCliVersion: 'missing',
          rustcCliAvailable: false,
          rustcCliVersion: 'missing',
          embeddedJreAvailable: false,
          embeddedJreVersion: 'missing',
          embeddedJreMajorVersion: -1,
          embeddedJreReady: false,
          embeddedJreRequiredForWindowsBuild: true,
          embeddedJrePrepareCommand: 'cd zhiwei-web && npm run tauri:prepare:jre',
          updaterConfigured: false,
          updaterDependency: false,
          updaterReady: false,
          updaterArtifactsConfigured: false,
          updaterArtifactsMode: 'missing',
          updaterPubkeyConfigured: false,
          updaterEndpointCount: 0,
          updaterEndpointsConfigured: false,
          updaterInstallMode: 'default',
          runningFromJar: false,
          packageArtifactDirectory: 'D:\\WorkSpace\\Project\\News\\zhiwei-web\\src-tauri\\target\\release\\bundle',
          packageArtifactCount: 1,
          latestPackageArtifact: 'ZhiWei_0.2.0_x64-setup.exe',
          latestPackageArtifactPath: 'D:\\WorkSpace\\Project\\News\\zhiwei-web\\src-tauri\\target\\release\\bundle\\nsis\\ZhiWei_0.2.0_x64-setup.exe',
          latestPackageArtifactSizeBytes: 10485760,
          latestPackageArtifactModifiedAt: '2026-07-04T12:00:00Z',
        },
      },
      {
        id: 'model-services',
        label: '模型服务',
        status: 'WARN',
        detail: '主对话模型可用；向量服务启动预热异常，知识库、记忆和语义召回会降级',
        metadata: {
          total: 2,
          enabled: 2,
          generationEnabled: 1,
          embeddingEnabled: 1,
          rerankEnabled: 0,
          generationUnhealthy: 0,
          embeddingUnhealthy: 1,
        },
      },
      {
        id: 'intelligence',
        label: '智能增强',
        status: 'OK',
        detail: '经验匹配不阻塞主对话，工具经验记录有后台名额和超时保护',
        metadata: {
          enabled: true,
          decisionSignalEnabled: true,
          experienceMatchTimeoutMs: 0,
          experienceMatchForegroundWaitCapMs: 80,
          experienceMatchBackgroundTimeoutMs: 1200,
          effectiveExperienceMatchTimeoutMs: 0,
          experienceMatchTrigger: 'task-like',
          experienceMatchMaxPending: 1,
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
        detail: '部分技能引用了未知工具，且有核心工具当前不可用，相关任务会降级或需要修复',
        metadata: {
          skills: 8,
          tools: 14,
          skillRegistryEnabled: true,
          toolRegistryEnabled: true,
          skillSuggestedToolReferences: 5,
          registeredSkillToolReferences: 3,
          canonicalSkillToolReferences: 4,
          unknownSkillToolReferences: 1,
          missingCanonicalSkillToolReferences: 1,
          unknownSkillToolReferenceSamples: ['research-assistant -> web.search'],
          missingCanonicalSkillToolReferenceSamples: ['repo-auditor -> shell.exec'],
          highRiskTools: 2,
          toolCategories: {
            LOCAL: 8,
            NETWORK: 3,
          },
        },
      },
    ],
    hints: [],
  })
  mocks.listBackups.mockReset().mockResolvedValue([
    {
      modifiedAt: '2026-07-04T11:00:00Z',
      fileName: 'zhiwei-backup-20260704-110000.zip',
      path: 'D:\\zhiwei\\backups\\zhiwei-backup-20260704-110000.zip',
      sizeBytes: 2048,
    },
  ])
  mocks.createDiagnosticBundle.mockReset().mockResolvedValue({
    createdAt: '2026-07-07T10:00:00Z',
    fileName: 'zhiwei-diagnostic-20260707-100000.zip',
    path: 'D:\\zhiwei\\diagnostics\\zhiwei-diagnostic-20260707-100000.zip',
    sizeBytes: 4096,
    includedFileCount: 3,
  })
  mocks.createBackup.mockReset().mockResolvedValue({
    createdAt: '2026-07-04T12:00:00Z',
    fileName: 'zhiwei-backup-20260704-120000.zip',
    path: 'D:\\zhiwei\\backups\\zhiwei-backup-20260704-120000.zip',
    sizeBytes: 4096,
    includedFileCount: 8,
  })
  mocks.validateBackup.mockReset().mockResolvedValue({
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
      excludedTopLevelDirs: ['backups', 'cache', 'logs', 'runtime'],
    },
    problems: [],
    restorePlan: {
      restoreMode: 'manual-staging',
      manualRestoreOnly: true,
      includedTopLevelItems: ['db'],
      excludedTopLevelDirs: ['backups', 'cache', 'logs', 'runtime'],
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
  })
  mocks.prepareBackupRestore.mockReset().mockResolvedValue({
    preparedAt: '2026-07-07T10:00:00Z',
    fileName: 'zhiwei-backup-20260704-110000.zip',
    restoreDirectory: 'D:\\zhiwei\\runtime\\restore-staging\\zhiwei-backup-20260704-110000-restore',
    extractedFileCount: 3,
    extractedBytes: 4096,
    warnings: ['当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。'],
    nextSteps: ['打开恢复准备目录，检查文件结构和备份清单。'],
  })
  mocks.copyToClipboard.mockReset().mockResolvedValue(true)
  mocks.revealInFileManager.mockReset().mockResolvedValue(undefined)
  mocks.isTauri = false
  mocks.showToast.mockReset()
})

describe('SettingsGeneralView 本地数据维护', () => {
  it('加载诊断状态和最近备份', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(mocks.getDiagnosticReport).toHaveBeenCalledTimes(1)
    expect(mocks.listBackups).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('本地数据维护')
    expect(wrapper.text()).toContain('已保护')
    expect(wrapper.text()).toContain('数据库')
    expect(wrapper.text()).toContain('可访问')
    expect(wrapper.text()).toContain('SQLite 连接可用，数据库文件可定位')
    expect(wrapper.text()).toContain('D:\\zhiwei\\db\\zhiwei.db')
    expect(wrapper.text()).toContain('1.0 MB')
    expect(wrapper.text()).toContain('存在 / 2.0 KB')
    expect(wrapper.text()).toContain('wal')
    expect(wrapper.text()).toContain('5000ms')
    expect(wrapper.text()).toContain('数据迁移')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).toContain('模型服务')
    expect(wrapper.text()).toContain('能力降级')
    expect(wrapper.text()).toContain('向量服务启动预热异常')
    expect(wrapper.text()).toContain('主对话模型1 个')
    expect(wrapper.text()).toContain('向量增强1 个 / 异常 1 个')
    expect(wrapper.text()).toContain('D:\\zhiwei\\backups')
    expect(wrapper.text()).toContain('zhiwei-backup-20260704-110000.zip')
    expect(wrapper.text()).toContain('2.0 KB')
  })

  it('展示数据迁移诊断状态和失败处置建议', async () => {
    mocks.getDiagnosticReport.mockResolvedValueOnce({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'ERROR',
      summary: '本地服务存在需要处理的错误',
      app: {},
      runtime: {},
      counts: {},
      checks: [
        {
          id: 'schema-migrations',
          label: '数据迁移',
          status: 'ERROR',
          detail: '存在失败的数据库迁移，请检查启动日志和 Flyway 历史',
          metadata: {
            total: 19,
            failed: 1,
            latestVersion: '19',
            latestScript: 'V19__broken.sql',
            failedMigrationScripts: ['V19__broken.sql'],
            latestInstalledOn: '2026-07-04 10:01:00',
          },
        },
      ],
      hints: ['数据迁移：存在失败的数据库迁移'],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('数据迁移')
    expect(wrapper.text()).toContain('需要修复')
    expect(wrapper.text()).toContain('存在失败的数据库迁移')
    expect(wrapper.text()).toContain('迁移数19')
    expect(wrapper.text()).toContain('失败数1')
    expect(wrapper.text()).toContain('最新版本19')
    expect(wrapper.text()).toContain('最新脚本V19__broken.sql')
    expect(wrapper.text()).toContain('失败脚本V19__broken.sql')
    expect(wrapper.text()).toContain('暂停继续写入数据，先保留启动日志和本地诊断摘要。')
    expect(wrapper.text()).toContain('修复前先创建 HOME 备份，避免二次损坏。')
  })

  it('可以生成本地诊断包并展示文件位置', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('生成诊断包'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.createDiagnosticBundle).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('最近生成：zhiwei-diagnostic-20260707-100000.zip')
    expect(wrapper.text()).toContain('文件：zhiwei-diagnostic-20260707-100000.zip')
    expect(wrapper.text()).toContain('大小：4.0 KB')
    expect(wrapper.text()).toContain('条目：3')
    expect(wrapper.text()).toContain('D:\\zhiwei\\diagnostics\\zhiwei-diagnostic-20260707-100000.zip')
    expect(mocks.showToast).toHaveBeenCalledWith(
      'success',
      '已生成诊断包：zhiwei-diagnostic-20260707-100000.zip',
    )
  })

  it('展示安装与更新诊断状态', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('安装与更新')
    expect(wrapper.text()).toContain('待完善')
    expect(wrapper.text()).toContain('桌面端可打包，但自动更新配置不完整')
    expect(wrapper.text()).toContain('后端')
    expect(wrapper.text()).toContain('Web')
    expect(wrapper.text()).toContain('桌面端')
    expect(wrapper.text()).toContain('0.2.0')
    expect(wrapper.text()).toContain('打包：已启用')
    expect(wrapper.text()).toContain('版本：已对齐')
    expect(wrapper.text()).toContain('脚本：已对齐')
    expect(wrapper.text()).toContain('构建环境：缺少 Maven/Cargo/Rustc')
    expect(wrapper.text()).toContain('Maven CLI：不可用（missing）')
    expect(wrapper.text()).toContain('Cargo CLI：不可用（missing）')
    expect(wrapper.text()).toContain('Rustc CLI：不可用（missing）')
    expect(wrapper.text()).toContain('内嵌 JRE：未准备（missing）')
    expect(wrapper.text()).toContain('复制工具链修复')
    expect(wrapper.text()).toContain('更新器：缺少 插件依赖、配置块、更新产物、签名公钥、发布源')
    expect(wrapper.text()).toContain('更新插件：未安装')
    expect(wrapper.text()).toContain('更新配置：未配置')
    expect(wrapper.text()).toContain('更新产物：未启用')
    expect(wrapper.text()).toContain('签名公钥：未配置')
    expect(wrapper.text()).toContain('发布源：未配置')
    expect(wrapper.text()).toContain('运行方式：开发运行')
    expect(wrapper.text()).toContain('安装包产物')
    expect(wrapper.text()).toContain('产物数量1')
    expect(wrapper.text()).toContain('ZhiWei_0.2.0_x64-setup.exe')
    expect(wrapper.text()).toContain('10.0 MB')
    expect(wrapper.text()).toContain('运行 cd zhiwei-web && npm run tauri:prepare:jre 准备内嵌 JRE 22')
    expect(wrapper.text()).toContain('安装并注册 Tauri updater 插件。')
    expect(wrapper.text()).toContain('在 Tauri bundle 配置中启用 createUpdaterArtifacts')
    expect(wrapper.text()).toContain('配置 updater pubkey 和 endpoints')
    expect(wrapper.text()).toContain('准备内嵌 JRE')
    expect(wrapper.text()).toContain('cd zhiwei-web && npm run tauri:prepare:jre')
    expect(wrapper.text()).toContain('mvn clean package -DskipTests')
    expect(wrapper.text()).toContain('cd zhiwei-web && npm run build')
    expect(wrapper.text()).toContain('cd zhiwei-web && npm run tauri:build')
    expect(wrapper.text()).toContain('cd zhiwei-web && npm run tauri:build:windows')
  })

  it('可以复制发布自检清单', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('复制发布自检'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const checklist = mocks.copyToClipboard.mock.calls[0][0]
    expect(checklist).toContain('[知微发布自检清单]')
    expect(checklist).toContain('本地诊断状态: OK')
    expect(checklist).toContain('发布状态: 安装与更新=WARN')
    expect(checklist).toContain('后端: 0.2.0')
    expect(checklist).toContain('构建脚本: 已对齐')
    expect(checklist).toContain('Tauri 构建前命令: npm run tauri:prepare')
    expect(checklist).toContain('构建环境: 缺少 Maven/Cargo/Rustc')
    expect(checklist).toContain('Maven CLI: 不可用（missing）')
    expect(checklist).toContain('Cargo CLI: 不可用（missing）')
    expect(checklist).toContain('Rustc CLI: 不可用（missing）')
    expect(checklist).toContain('内嵌 JRE: 未准备（missing）')
    expect(checklist).toContain('JRE 准备命令: cd zhiwei-web && npm run tauri:prepare:jre')
    expect(checklist).toContain('更新器状态: 缺少 插件依赖、配置块、更新产物、签名公钥、发布源')
    expect(checklist).toContain('更新插件: 未安装')
    expect(checklist).toContain('更新配置: 未配置')
    expect(checklist).toContain('更新产物: 未启用')
    expect(checklist).toContain('签名公钥: 未配置')
    expect(checklist).toContain('发布源: 未配置')
    expect(checklist).toContain('最新产物: ZhiWei_0.2.0_x64-setup.exe')
    expect(checklist).toContain('准备内嵌 JRE: cd zhiwei-web && npm run tauri:prepare:jre')
    expect(checklist).toContain('桌面安装包: cd zhiwei-web && npm run tauri:build')
    expect(checklist).toContain('Windows 安装包: cd zhiwei-web && npm run tauri:build:windows')
    expect(checklist).toContain('安装 Maven、Cargo、Rustc 并确认 mvn、cargo、rustc 可以在当前终端执行。')
    expect(checklist).toContain('运行 cd zhiwei-web && npm run tauri:prepare:jre 准备内嵌 JRE 22')
    expect(checklist).toContain('安装并注册 Tauri updater 插件。')
    expect(checklist).toContain('在 Tauri bundle 配置中启用 createUpdaterArtifacts')
    expect(checklist).toContain('配置 updater pubkey 和 endpoints')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '发布自检清单已复制')
  })

  it('可以复制桌面打包工具链修复清单', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('复制工具链修复'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const guide = mocks.copyToClipboard.mock.calls[0][0]
    expect(guide).toContain('[知微桌面打包工具链修复清单]')
    expect(guide).toContain('当前缺失: Maven(mvn)、Cargo(cargo)、Rustc(rustc)')
    expect(guide).toContain('Maven: 安装 Maven 3.9+')
    expect(guide).toContain('Rust/Cargo/Rustc: 安装 Rustup: https://www.rust-lang.org/tools/install')
    expect(guide).toContain('mvn -version')
    expect(guide).toContain('cargo --version')
    expect(guide).toContain('rustc --version')
    expect(guide).toContain('进入 设置 / 安装与更新，点击刷新诊断。')
    expect(guide).toContain('重新运行: cd zhiwei-web && npm run tauri:build')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '工具链修复清单已复制')
  })

  it('桌面端可以定位安装包产物', async () => {
    mocks.isTauri = true
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('定位安装包'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.revealInFileManager).toHaveBeenCalledWith(
      'D:\\WorkSpace\\Project\\News\\zhiwei-web\\src-tauri\\target\\release\\bundle\\nsis\\ZhiWei_0.2.0_x64-setup.exe',
    )
    expect(mocks.showToast).toHaveBeenCalledWith('success', '已打开安装包位置')
  })

  it('展示智能增强诊断状态', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('智能增强')
    expect(wrapper.text()).toContain('后台增强')
    expect(wrapper.text()).toContain('经验匹配不阻塞主对话，工具经验记录有后台名额和超时保护')
    expect(wrapper.text()).toContain('决策信号开启')
    expect(wrapper.text()).toContain('经验匹配任务型触发')
    expect(wrapper.text()).toContain('前台0ms')
    expect(wrapper.text()).toContain('经验后台1 个')
    expect(wrapper.text()).toContain('经验超时1200ms')
    expect(wrapper.text()).toContain('工具经验4 个')
    expect(wrapper.text()).toContain('工具超时1200ms')
    expect(wrapper.text()).toContain('最近复用300s / 8 条')
    expect(wrapper.text()).toContain('工具和技能')
    expect(wrapper.text()).toContain('需校准')
    expect(wrapper.text()).toContain('部分技能引用了未知工具，且有核心工具当前不可用')
    expect(wrapper.text()).toContain('技能8 个')
    expect(wrapper.text()).toContain('工具14 个')
    expect(wrapper.text()).toContain('技能工具引用3/5 可用')
    expect(wrapper.text()).toContain('标准工具缺失1 个')
    expect(wrapper.text()).toContain('未知工具1 个')
    expect(wrapper.text()).toContain('高风险工具2 个')
    expect(wrapper.text()).toContain('工具分类LOCAL:8 / NETWORK:3')
    expect(wrapper.text()).toContain('未知工具：research-assistant -> web.search')
    expect(wrapper.text()).toContain('缺失标准工具：repo-auditor -> shell.exec')
    expect(wrapper.text()).toContain('修正 Skill 元数据里的 suggestedTools')
    expect(wrapper.text()).toContain('确认核心工具提供方可用')
  })

  it('智能增强前台等待被限流时提示关注', async () => {
    mocks.getDiagnosticReport.mockResolvedValueOnce({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '智能增强需要关注',
      app: {},
      runtime: {},
      counts: {},
      checks: [
        {
          id: 'intelligence',
          label: '智能增强',
          status: 'WARN',
          detail: '经验匹配最多等待 80ms；慢结果会转入后台复用',
          metadata: {
            enabled: true,
            decisionSignalEnabled: true,
            experienceMatchTimeoutMs: 250,
            experienceMatchForegroundWaitCapMs: 80,
            experienceMatchBackgroundTimeoutMs: 1200,
            effectiveExperienceMatchTimeoutMs: 80,
            experienceMatchTrigger: 'always',
            experienceMatchMaxPending: 1,
            experienceMatchRecentTtlSeconds: 300,
            experienceMatchRecentMax: 8,
            maxPendingToolExperienceRecords: 4,
            toolExperienceRecordTimeoutMs: 1200,
          },
        },
      ],
      hints: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('需关注')
    expect(wrapper.text()).toContain('经验匹配最多等待 80ms；慢结果会转入后台复用')
    expect(wrapper.text()).toContain('经验匹配总是匹配')
    expect(wrapper.text()).toContain('前台80ms / 配置250ms')
  })

  it('智能增强工具经验无后台超时时提示关注', async () => {
    mocks.getDiagnosticReport.mockResolvedValueOnce({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '智能增强需要关注',
      app: {},
      runtime: {},
      counts: {},
      checks: [
        {
          id: 'intelligence',
          label: '智能增强',
          status: 'WARN',
          detail: '工具经验记录未设置后台超时，慢探针可能长期占用后台名额',
          metadata: {
            enabled: true,
            decisionSignalEnabled: true,
            experienceMatchTimeoutMs: 0,
            experienceMatchForegroundWaitCapMs: 80,
            experienceMatchBackgroundTimeoutMs: 1200,
            effectiveExperienceMatchTimeoutMs: 0,
            experienceMatchTrigger: 'task-like',
            experienceMatchMaxPending: 1,
            experienceMatchRecentTtlSeconds: 300,
            experienceMatchRecentMax: 8,
            maxPendingToolExperienceRecords: 4,
            toolExperienceRecordTimeoutMs: 0,
          },
        },
      ],
      hints: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('需关注')
    expect(wrapper.text()).toContain('工具经验记录未设置后台超时')
    expect(wrapper.text()).toContain('工具经验4 个')
    expect(wrapper.text()).toContain('工具超时0ms')
  })

  it('创建备份后刷新维护状态并提示用户', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('创建备份'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.createBackup).toHaveBeenCalledTimes(1)
    expect(mocks.validateBackup).toHaveBeenCalledWith('zhiwei-backup-20260704-120000.zip')
    expect(mocks.getDiagnosticReport).toHaveBeenCalledTimes(2)
    expect(mocks.listBackups).toHaveBeenCalledTimes(2)
    expect(mocks.showToast).toHaveBeenCalledWith(
      'success',
      '已创建本地备份：zhiwei-backup-20260704-120000.zip',
    )
  })

  it('可以校验最近备份并展示结果', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('校验最新备份'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.validateBackup).toHaveBeenCalledWith('zhiwei-backup-20260704-110000.zip')
    expect(wrapper.text()).toContain('校验通过')
    expect(wrapper.text()).toContain('备份文件结构正常')
    expect(wrapper.text()).toContain('条目：2')
    expect(wrapper.text()).toContain('数据文件：1')
    expect(wrapper.text()).toContain('恢复前预检')
    expect(wrapper.text()).toContain('恢复方式：手动暂存恢复')
    expect(wrapper.text()).toContain('恢复范围：db')
    expect(wrapper.text()).toContain('不会恢复：backups、cache、logs、runtime')
    expect(wrapper.text()).toContain('目标 HOME：D:\\zhiwei')
    expect(wrapper.text()).toContain('当前数据：3 个文件')
    expect(wrapper.text()).toContain('备份来源：D:\\old-zhiwei')
    expect(wrapper.text()).toContain('预计解压：4.0 KB')
    expect(wrapper.text()).toContain('可用空间：100.0 MB')
    expect(wrapper.text()).toContain('恢复空间：')
    expect(wrapper.text()).toContain('空间充足')
    expect(wrapper.text()).toContain('暂存目录：D:\\zhiwei\\runtime\\restore-staging')
    expect(wrapper.text()).toContain('当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。')
    expect(wrapper.text()).toContain('复制恢复步骤')
    expect(wrapper.text()).toContain('准备恢复目录')
  })

  it('可以准备备份恢复目录并展示暂存位置', async () => {
    const wrapper = mountView()
    await flushPromises()

    const validateButton = wrapper.findAll('button').find(item => item.text().includes('校验最新备份'))
    expect(validateButton).toBeTruthy()
    await validateButton!.trigger('click')
    await flushPromises()

    const prepareButton = wrapper.findAll('button').find(item => item.text().includes('准备恢复目录'))
    expect(prepareButton).toBeTruthy()
    await prepareButton!.trigger('click')
    await flushPromises()

    expect(mocks.prepareBackupRestore).toHaveBeenCalledWith('zhiwei-backup-20260704-110000.zip')
    expect(wrapper.text()).toContain('恢复目录已准备')
    expect(wrapper.text()).toContain('文件：3')
    expect(wrapper.text()).toContain('大小：4.0 KB')
    expect(wrapper.text()).toContain('D:\\zhiwei\\runtime\\restore-staging\\zhiwei-backup-20260704-110000-restore')
    expect(wrapper.text()).toContain('打开恢复准备目录，检查文件结构和备份清单。')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '恢复目录已准备')
  })

  it('桌面端可以打开已准备的恢复目录', async () => {
    mocks.isTauri = true
    const wrapper = mountView()
    await flushPromises()

    const validateButton = wrapper.findAll('button').find(item => item.text().includes('校验最新备份'))
    expect(validateButton).toBeTruthy()
    await validateButton!.trigger('click')
    await flushPromises()

    const prepareButton = wrapper.findAll('button').find(item => item.text().includes('准备恢复目录'))
    expect(prepareButton).toBeTruthy()
    await prepareButton!.trigger('click')
    await flushPromises()

    const openButton = wrapper.findAll('button').find(item => item.text().includes('打开目录'))
    expect(openButton).toBeTruthy()
    await openButton!.trigger('click')
    await flushPromises()

    expect(mocks.revealInFileManager).toHaveBeenCalledWith(
      'D:\\zhiwei\\runtime\\restore-staging\\zhiwei-backup-20260704-110000-restore',
    )
    expect(mocks.showToast).toHaveBeenCalledWith('success', '已打开恢复目录')
  })

  it('复制本地维护诊断时包含已准备的恢复目录', async () => {
    const wrapper = mountView()
    await flushPromises()

    const validateButton = wrapper.findAll('button').find(item => item.text().includes('校验最新备份'))
    expect(validateButton).toBeTruthy()
    await validateButton!.trigger('click')
    await flushPromises()

    const prepareButton = wrapper.findAll('button').find(item => item.text().includes('准备恢复目录'))
    expect(prepareButton).toBeTruthy()
    await prepareButton!.trigger('click')
    await flushPromises()

    const copyButton = wrapper.findAll('button').find(item => item.text().includes('复制诊断'))
    expect(copyButton).toBeTruthy()
    await copyButton!.trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const diagnostic = mocks.copyToClipboard.mock.calls[0][0]
    expect(diagnostic).toContain('恢复目录准备: zhiwei-backup-20260704-110000.zip=已准备')
    expect(diagnostic).toContain('目录=D:\\zhiwei\\runtime\\restore-staging\\zhiwei-backup-20260704-110000-restore')
    expect(diagnostic).toContain('下一步=1. 打开恢复准备目录，检查文件结构和备份清单。')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '诊断摘要已复制')
  })

  it('可以复制恢复前操作清单', async () => {
    const wrapper = mountView()
    await flushPromises()

    const validateButton = wrapper.findAll('button').find(item => item.text().includes('校验最新备份'))
    expect(validateButton).toBeTruthy()
    await validateButton!.trigger('click')
    await flushPromises()

    const copyButton = wrapper.findAll('button').find(item => item.text().includes('复制恢复步骤'))
    expect(copyButton).toBeTruthy()
    await copyButton!.trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const checklist = mocks.copyToClipboard.mock.calls[0][0]
    expect(checklist).toContain('[知微备份恢复清单]')
    expect(checklist).toContain('备份文件: zhiwei-backup-20260704-110000.zip')
    expect(checklist).toContain('恢复方式: 手动暂存恢复')
    expect(checklist).toContain('恢复范围: db')
    expect(checklist).toContain('不会恢复: backups、cache、logs、runtime')
    expect(checklist).toContain('目标 HOME: D:\\zhiwei')
    expect(checklist).toContain('风险提示:')
    expect(checklist).toContain('恢复步骤:')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '恢复清单已复制')
  })

  it('可以复制本地维护诊断摘要', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('复制诊断'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const diagnostic = mocks.copyToClipboard.mock.calls[0][0]
    expect(diagnostic).toContain('[知微本地维护诊断]')
    expect(diagnostic).toContain('本地诊断状态: OK')
    expect(diagnostic).toContain('本地备份状态: 数据备份=OK')
    expect(diagnostic).toContain('数据迁移状态: 数据迁移=OK')
    expect(diagnostic).toContain('模型服务状态: 模型服务=WARN')
    expect(diagnostic).toContain('向量异常=1')
    expect(diagnostic).toContain('安装更新状态: 安装与更新=WARN')
    expect(diagnostic).toContain('最新产物=ZhiWei_0.2.0_x64-setup.exe')
    expect(diagnostic).toContain('智能增强状态: 智能增强=OK')
    expect(diagnostic).toContain('工具技能状态: 工具和技能=WARN')
    expect(diagnostic).toContain('未知样例=research-assistant -> web.search')
    expect(diagnostic).toContain('最近备份: zhiwei-backup-20260704-110000.zip')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '诊断摘要已复制')
  })
})
