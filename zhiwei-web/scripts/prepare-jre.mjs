/**
 * 使用 jlink 为桌面安装包准备内嵌 Java 运行时。
 */
import { spawnSync } from 'node:child_process'
import { existsSync, rmSync, statSync } from 'node:fs'
import { basename, dirname, isAbsolute, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(__dirname, '..')
const resourcesDir = resolve(webRoot, 'src-tauri', 'resources')
const defaultOutputDir = resolve(resourcesDir, 'jre')
const isWindows = process.platform === 'win32'

let hasError = false

const args = parseArgs(process.argv.slice(2))

if (args.help) {
  printHelp()
  process.exit(0)
}

const jdkHome = resolve(stripQuotes(args.from ?? process.env.JAVA_HOME ?? ''))
const outputDir = resolve(webRoot, args.output ?? defaultOutputDir)

if (!args.from && !process.env.JAVA_HOME) {
  fail('未设置 JAVA_HOME。请安装 JDK 22+，或使用 --from 指定 JDK 目录。')
}

if (basename(outputDir) !== 'jre' || !isInside(outputDir, resourcesDir)) {
  fail('输出目录必须是 src-tauri/resources 下名为 jre 的目录，避免误删其它文件。')
}

if (!existsSync(jdkHome)) {
  fail(`JDK 目录不存在：${jdkHome}`)
}

const javaCommand = resolve(jdkHome, 'bin', isWindows ? 'java.exe' : 'java')
const jlinkCommand = resolve(jdkHome, 'bin', isWindows ? 'jlink.exe' : 'jlink')
const jmodsDir = resolve(jdkHome, 'jmods')

if (!existsSync(javaCommand)) {
  fail(`未找到 java：${javaCommand}`)
}

if (!existsSync(jlinkCommand)) {
  fail(`未找到 jlink：${jlinkCommand}`)
}

if (!existsSync(jmodsDir)) {
  fail(`未找到 jmods 目录：${jmodsDir}`)
}

const javaVersion = checkJavaVersion(javaCommand)
if (javaVersion === null) {
  fail('无法识别 JDK 版本。')
} else if (javaVersion < 22) {
  fail(`JDK 版本过低：${javaVersion}，需要 22 或更高。`)
}

if (hasError) {
  process.exit(1)
}

const modules = [
  'java.base',
  'java.compiler',
  'java.datatransfer',
  'java.desktop',
  'java.instrument',
  'java.logging',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.scripting',
  'java.security.jgss',
  'java.security.sasl',
  'java.sql',
  'java.transaction.xa',
  'java.xml',
  'jdk.charsets',
  'jdk.crypto.ec',
  'jdk.jfr',
  'jdk.localedata',
  'jdk.management',
  'jdk.unsupported',
  'jdk.zipfs',
]

const jlinkHelp = runCommand(jlinkCommand, ['--help'])
const compressArgs = `${jlinkHelp.stdout}\n${jlinkHelp.stderr}`.includes('zip-')
  ? ['--compress', 'zip-6']
  : ['--compress', '2']

const jlinkArgs = [
  '--module-path',
  jmodsDir,
  '--add-modules',
  modules.join(','),
  '--output',
  outputDir,
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  ...compressArgs,
]

console.log(`[prepare-jre] JDK: ${jdkHome}`)
console.log(`[prepare-jre] 输出: ${outputDir}`)
console.log(`[prepare-jre] Java: ${javaVersion}`)

if (args.dryRun) {
  console.log(`[prepare-jre] dry-run: ${formatCommand(jlinkCommand, jlinkArgs)}`)
  process.exit(0)
}

if (existsSync(outputDir)) {
  if (!args.force) {
    fail('src-tauri/resources/jre 已存在。确认要重建时请加 --force。')
  } else if (!isSafeDirectory(outputDir)) {
    fail(`无法安全覆盖输出目录：${outputDir}`)
  } else {
    rmSync(outputDir, { recursive: true, force: true })
    console.log('[prepare-jre] 已清理旧的内嵌 JRE。')
  }
}

if (hasError) {
  process.exit(1)
}

const result = runCommand(jlinkCommand, jlinkArgs)
if (result.error || result.status !== 0) {
  const output = firstLine(result.stderr || result.stdout)
  console.error(`[prepare-jre] jlink 执行失败${output ? `：${output}` : ''}`)
  process.exit(1)
}

const embeddedJava = resolve(outputDir, 'bin', isWindows ? 'java.exe' : 'java')
const embeddedVersion = checkJavaVersion(embeddedJava)
if (embeddedVersion === null || embeddedVersion < 22) {
  console.error('[prepare-jre] 生成后的内嵌 Java 不可用，请检查 JDK 和 jlink 模块配置。')
  process.exit(1)
}

console.log(`[prepare-jre] 已生成内嵌 JRE ${embeddedVersion}。`)

function parseArgs(argv) {
  const parsed = {
    dryRun: false,
    force: false,
    from: null,
    help: false,
    output: null,
  }

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--dry-run') {
      parsed.dryRun = true
    } else if (arg === '--force') {
      parsed.force = true
    } else if (arg === '--help' || arg === '-h') {
      parsed.help = true
    } else if (arg === '--from') {
      parsed.from = readOptionValue(argv, index, '--from')
      index += 1
    } else if (arg === '--output') {
      parsed.output = readOptionValue(argv, index, '--output')
      index += 1
    } else {
      fail(`未知参数：${arg}`)
    }
  }

  return parsed
}

function readOptionValue(argv, index, optionName) {
  const value = argv[index + 1]
  if (!value || value.startsWith('--')) {
    fail(`${optionName} 缺少参数值。`)
    return ''
  }
  return value
}

function checkJavaVersion(command) {
  const result = runCommand(command, ['-version'])
  if (result.error || result.status !== 0) {
    return null
  }
  return parseJavaMajorVersion(`${result.stderr || ''}\n${result.stdout || ''}`)
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

function isSafeDirectory(path) {
  return isInside(path, resourcesDir)
    && basename(path) === 'jre'
    && existsSync(path)
    && statSync(path).isDirectory()
}

function isInside(path, parent) {
  const rel = relative(parent, path)
  return rel !== ''
    && !rel.startsWith('..')
    && !isAbsolute(rel)
}

function runCommand(command, commandArgs) {
  return spawnSync(command, commandArgs, {
    encoding: 'utf8',
    windowsHide: true,
  })
}

function fail(message) {
  hasError = true
  console.error(`[prepare-jre] ${message}`)
}

function firstLine(text) {
  if (!text) return ''
  return text.split(/\r?\n/u).find(line => line.trim().length > 0)?.trim() ?? ''
}

function stripQuotes(value) {
  return value.trim().replace(/^["']|["']$/gu, '')
}

function formatCommand(command, commandArgs) {
  return [command, ...commandArgs].map(quoteArg).join(' ')
}

function quoteArg(value) {
  return /\s/u.test(value) ? `"${value.replaceAll('"', '\\"')}"` : value
}

function printHelp() {
  console.log(`
用法：node scripts/prepare-jre.mjs [options]

Options:
  --from <path>     指定 JDK 22+ 目录，默认读取 JAVA_HOME
  --output <path>   指定输出目录，必须是 src-tauri/resources/jre
  --force           覆盖已有的内嵌 JRE
  --dry-run         只打印 jlink 命令，不生成文件
`)
}
