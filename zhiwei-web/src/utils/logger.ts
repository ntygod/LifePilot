/**
 * 统一日志工具 — 生产环境静默，开发环境输出
 */
const isDev = import.meta.env.DEV

export const logger = {
  error(message: string, ...args: unknown[]) {
    if (isDev) console.error(`[ERROR] ${message}`, ...args)
  },
  warn(message: string, ...args: unknown[]) {
    if (isDev) console.warn(`[WARN] ${message}`, ...args)
  },
  info(message: string, ...args: unknown[]) {
    if (isDev) console.info(`[INFO] ${message}`, ...args)
  },
  debug(message: string, ...args: unknown[]) {
    if (isDev) console.debug(`[DEBUG] ${message}`, ...args)
  },
}
