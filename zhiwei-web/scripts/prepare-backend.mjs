/**
 * 构建后端 JAR 并复制到 Tauri resources 目录。
 *
 * 由 npm run tauri:prepare 调用，确保 npx tauri build 时
 * 始终使用最新的后端产物。跨平台兼容（Windows / macOS / Linux）。
 */
import { execSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(__dirname, '..', '..')
const jarSource = resolve(projectRoot, 'target', 'zhiwei.jar')
const resourcesDir = resolve(__dirname, '..', 'src-tauri', 'resources')
const jarDest = resolve(resourcesDir, 'zhiwei.jar')

// 1. 构建后端 JAR
console.log('[prepare-backend] 构建后端 JAR...')
try {
  execSync('mvn clean package -DskipTests -q', {
    cwd: projectRoot,
    stdio: 'inherit',
  })
} catch {
  console.error('[prepare-backend] Maven 构建失败')
  process.exit(1)
}

// 2. 复制到 Tauri resources
if (!existsSync(jarSource)) {
  console.error(`[prepare-backend] 未找到 ${jarSource}`)
  process.exit(1)
}

mkdirSync(resourcesDir, { recursive: true })
cpSync(jarSource, jarDest)
console.log(`[prepare-backend] 已复制 zhiwei.jar → src-tauri/resources/`)
