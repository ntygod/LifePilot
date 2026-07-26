/**
 * 检查 Tauri 打包所需的本机环境。
 */
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(__dirname, '..')
const tauriConfigPath = resolve(webRoot, 'src-tauri', 'tauri.conf.json')
const resourcesDir = resolve(webRoot, 'src-tauri', 'resources')
const isWindows = process.platform === 'win32'
const args = process.argv.slice(2)
const requireEmbeddedJre = args.includes('--require-embedded-jre')
  || process.env.ZHIWEI_REQUIRE_EMBEDDED_JRE === '1'

if (args.includes('--help') || args.includes('-h')) {
  printUsage()
  process.exit(0)
}

const unknownArgs = args.filter(arg => arg !== '--require-embedded-jre')
if (unknownArgs.length > 0) {
  console.error(`[tauri-build-env] 未识别参数：${unknownArgs.join(' ')}`)
  printUsage()
  process.exit(1)
}

const commandChecks = [
  {
    command: 'cargo',
    args: ['--version'],
    installHint: '请安装 Rust 工具链并重启终端：https://www.rust-lang.org/tools/install',
  },
  {
    command: 'rustc',
    args: ['--version'],
    installHint: '请确认 rustc 已随 Rust 工具链加入 PATH。',
  },
  {
    command: isWindows ? 'mvn.cmd' : 'mvn',
    args: ['--version'],
    label: 'mvn',
    installHint: '请安装 Maven 并确认 mvn 已加入 PATH，打包前需要构建后端 JAR。',
  },
]

let hasError = false

for (const check of commandChecks) {
  runRequiredCommand(check.command, check.args, check.installHint, check.label)
}

checkLocalTauriCli()
checkJavaRuntime()
checkTauriConfig()
checkResourcesDirectory()

if (hasError) {
  const retryCommand = requireEmbeddedJre ? 'npm run tauri:build:windows' : 'npm run tauri:build'
  console.error(`[tauri-build-env] 桌面安装包构建已停止。修复后可重新运行 ${retryCommand}。`)
  process.exit(1)
}

function runRequiredCommand(command, args, installHint, displayName) {
  const result = runCommand(command, args, { shell: isWindows && command.endsWith('.cmd') })
  const label = displayName ?? command.replace(/\.cmd$/iu, '')

  if (result.error) {
    hasError = true
    console.error(`[tauri-build-env] 未找到 ${label}。${installHint}`)
    return null
  }

  if (result.status !== 0) {
    hasError = true
    const output = firstLine(result.stderr || result.stdout)
    console.error(`[tauri-build-env] ${label} 执行失败${output ? `：${output}` : ''}`)
    return null
  }

  const version = firstLine(result.stdout)
  console.log(`[tauri-build-env] ${label}: ${version || '可用'}`)
  return result
}

function checkLocalTauriCli() {
  const localTauri = resolve(webRoot, 'node_modules', '.bin', isWindows ? 'tauri.cmd' : 'tauri')
  if (!existsSync(localTauri)) {
    hasError = true
    console.error('[tauri-build-env] 未找到本地 Tauri CLI。请先在 zhiwei-web 目录运行 npm install。')
    return
  }

  const result = runCommand(localTauri, ['--version'], { shell: isWindows })
  if (result.error || result.status !== 0) {
    hasError = true
    const output = firstLine(result.stderr || result.stdout)
    console.error(`[tauri-build-env] Tauri CLI 执行失败${output ? `：${output}` : ''}`)
    return
  }

  console.log(`[tauri-build-env] tauri: ${firstLine(result.stdout) || '可用'}`)
}

function checkJavaRuntime() {
  const embeddedJava = resolve(
    resourcesDir,
    'jre',
    'bin',
    isWindows ? 'java.exe' : 'java',
  )
  const candidates = []

  if (existsSync(embeddedJava)) {
    const embedded = checkJavaCandidate('内嵌 JRE', embeddedJava)
    if (embedded.ok) {
      return
    }
    if (requireEmbeddedJre) {
      hasError = true
      console.error('[tauri-build-env] 发布安装包要求内嵌 JRE 22，但当前内嵌 JRE 不可用。')
      return
    }
  } else {
    const message = '[tauri-build-env] 未检测到内嵌 JRE，安装包将依赖用户本机 Java 22。'
    if (requireEmbeddedJre) {
      hasError = true
      console.error(`${message} 发布安装包必须把 JRE 22 放入 src-tauri/resources/jre。`)
      return
    }
    console.warn(message)
  }

  const javaHome = process.env.JAVA_HOME?.trim()
  if (javaHome) {
    candidates.push({
      label: 'JAVA_HOME',
      command: resolve(javaHome, 'bin', isWindows ? 'java.exe' : 'java'),
    })
  }
  candidates.push({ label: 'PATH', command: isWindows ? 'java.exe' : 'java' })

  for (const candidate of candidates) {
    if (checkJavaCandidate(candidate.label, candidate.command).ok) return
  }

  hasError = true
  console.error('[tauri-build-env] 未找到可用 Java 22。请安装 JDK/JRE 22，或把 jre/ 放入 src-tauri/resources。')
}

function checkJavaCandidate(label, command) {
  const result = runCommand(command, ['-version'])
  if (result.error || result.status !== 0) {
    return { ok: false }
  }
  const output = `${result.stderr || ''}\n${result.stdout || ''}`
  const version = parseJavaMajorVersion(output)
  if (version === null) {
    console.warn(`[tauri-build-env] ${label} Java 版本无法识别：${firstLine(output) || '无输出'}`)
    return { ok: false }
  }
  if (version < 22) {
    console.warn(`[tauri-build-env] ${label} Java 版本过低：${firstLine(output)}，需要 22 或更高。`)
    return { ok: false }
  }
  console.log(`[tauri-build-env] java(${label}): ${firstLine(output)}`)
  return { ok: true, version }
}

function checkTauriConfig() {
  if (!existsSync(tauriConfigPath)) {
    hasError = true
    console.error('[tauri-build-env] 未找到 src-tauri/tauri.conf.json。')
    return
  }

  let config
  try {
    config = JSON.parse(readFileSync(tauriConfigPath, 'utf8'))
  } catch (error) {
    hasError = true
    console.error(`[tauri-build-env] tauri.conf.json 解析失败：${error.message}`)
    return
  }

  const beforeBuildCommand = config?.build?.beforeBuildCommand
  if (beforeBuildCommand !== 'npm run tauri:prepare') {
    hasError = true
    console.error('[tauri-build-env] beforeBuildCommand 必须是 npm run tauri:prepare，确保每次打包都刷新后端 JAR 和前端 dist。')
  } else {
    console.log('[tauri-build-env] beforeBuildCommand: npm run tauri:prepare')
  }

  const resources = Array.isArray(config?.bundle?.resources) ? config.bundle.resources : []
  if (!resources.includes('resources/**/*')) {
    hasError = true
    console.error('[tauri-build-env] bundle.resources 缺少 resources/**/*，后端 JAR/JRE 将不会进入安装包。')
  } else {
    console.log('[tauri-build-env] bundle.resources: resources/**/*')
  }
}

function checkResourcesDirectory() {
  if (!existsSync(resourcesDir)) {
    hasError = true
    console.error('[tauri-build-env] 未找到 src-tauri/resources 目录。')
    return
  }
  console.log('[tauri-build-env] resources 目录可用')
}

function runCommand(command, args, options = {}) {
  return spawnSync(command, args, {
    encoding: 'utf8',
    windowsHide: true,
    ...options,
  })
}

function parseJavaMajorVersion(text) {
  const match = text.match(/version\s+"([^"]+)"/u)
  if (!match) return null
  const version = match[1]
  if (version.startsWith('1.')) {
    return Number.parseInt(version.split('.')[1] ?? '', 10) || null
  }
  return Number.parseInt(version.split('.')[0] ?? '', 10) || null
}

function firstLine(text) {
  if (!text) return ''
  return text.split(/\r?\n/u).find(line => line.trim().length > 0)?.trim() ?? ''
}

function printUsage() {
  console.log(`
用法：node scripts/check-tauri-build-env.mjs [options]

检查桌面安装包构建前的本机环境，包括 Rust/Tauri/Maven/Java、Tauri 配置和资源目录。

Options:
  --require-embedded-jre  要求 src-tauri/resources/jre 中存在可用 Java 22，用于发布 Windows 安装包
  -h, --help              显示帮助
`.trim())
}
