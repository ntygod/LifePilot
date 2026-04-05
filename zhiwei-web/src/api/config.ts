/**
 * API 连接配置
 *
 * Tauri 桌面端通过 tauri://localhost 提供前端，相对路径无法到达 Java 后端，
 * 因此需要使用绝对路径 http://localhost:{port} 直连后端。
 * 浏览器环境（Vite dev / Nginx）继续使用相对路径，通过 proxy 转发。
 *
 * 注意：使用 getter 函数延迟求值，避免模块加载时端口尚未注入的竞态问题。
 */

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown
    __ZHIWEI_BACKEND_PORT__?: number
  }
}

/** API 请求的 origin 前缀（Tauri 环境为 http://localhost:{port}，浏览器环境为空） */
export function getApiOrigin(): string {
  if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__) {
    return `http://localhost:${window.__ZHIWEI_BACKEND_PORT__ || 8080}`
  }
  return ''
}

/**
 * 向后兼容的常量导出 — 在非 Tauri 环境下为空字符串，
 * Tauri 环境下在 main.ts bootstrap 完成后调用方能获得正确值。
 * 新代码建议直接使用 getApiOrigin()。
 */
export const API_ORIGIN = ''
