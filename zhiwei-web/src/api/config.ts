/**
 * API 连接配置
 *
 * Tauri 桌面端通过 tauri://localhost 提供前端，相对路径无法到达 Java 后端，
 * 因此需要使用绝对路径 http://localhost:{port} 直连后端。
 * 浏览器环境（Vite dev / Nginx）继续使用相对路径，通过 proxy 转发。
 */

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown
    __ZHIWEI_BACKEND_PORT__?: number
  }
}

function resolveApiOrigin(): string {
  if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__) {
    return `http://localhost:${window.__ZHIWEI_BACKEND_PORT__ || 8080}`
  }
  return ''
}

/** API 请求的 origin 前缀（Tauri 环境为 http://localhost:{port}，浏览器环境为空） */
export const API_ORIGIN = resolveApiOrigin()
