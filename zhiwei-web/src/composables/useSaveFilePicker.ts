/**
 * 统一的"另存为"路径选择器 —— Tauri 优先用 native dialog，
 * Web 用 showSaveFilePicker（Chrome/Edge），否则回落到 PromptDialog。
 */

// Web 端类型补丁：showSaveFilePicker 不是所有浏览器都支持
interface FileSystemFileHandle {
  name: string
  getFile(): Promise<File>
}
type ShowSaveFilePicker = (options?: {
  suggestedName?: string
  types?: Array<{ description?: string; accept: Record<string, string[]> }>
}) => Promise<FileSystemFileHandle>

declare global {
  interface Window {
    showSaveFilePicker?: ShowSaveFilePicker
    __TAURI_INTERNALS__?: unknown
  }
}

export interface SavePickerOptions {
  /** 默认文件名（含扩展名） */
  suggestedName?: string
  /** MIME 类型，用于决定文件类型筛选 */
  mimeType?: string
}

/**
 * 返回 null 表示用户取消或当前环境不支持原生选择器（调用方应降级到 PromptDialog）。
 * 返回字符串时：
 *   - Tauri 下是绝对路径（后端直接可用）
 *   - Web 下是用户选中的 handle 名字，但 Web 服务器端 saveAs 需要绝对路径 —— 因此
 *     Web 目前无法从 showSaveFilePicker 直接拿到绝对路径（浏览器安全限制），
 *     只能拿文件句柄。所以 Web 环境下 returns null 来降级到 PromptDialog。
 */
export async function pickSavePath(opts: SavePickerOptions = {}): Promise<string | null> {
  // Tauri 环境：用 plugin-dialog 拿绝对路径
  if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__) {
    try {
      const { save } = await import('@tauri-apps/plugin-dialog')
      const filters = buildTauriFilters(opts.mimeType)
      const picked = await save({
        defaultPath: opts.suggestedName,
        filters,
      })
      // save() 返回 string | null，null 表示用户取消
      return typeof picked === 'string' ? picked : null
    } catch (e) {
      console.warn('Tauri dialog.save 调用失败，降级到 PromptDialog', e)
      return null
    }
  }

  // Web 环境：showSaveFilePicker 拿到的是 FileSystemFileHandle，无法转成后端可用的绝对路径
  // 浏览器安全模型禁止 JS 直接得知本机路径 —— 只能用 File System Access API 读写文件，
  // 但我们后端 commitSaveAs 要求绝对路径。所以返回 null 直接降级。
  // 将来若要在 Web 上支持 saveAs，应改为"前端拿句柄 → fetch 工作副本 → 写入句柄"的下载流
  return null
}

function buildTauriFilters(mimeType?: string): Array<{ name: string; extensions: string[] }> | undefined {
  if (!mimeType) return undefined
  if (mimeType.includes('wordprocessingml')) {
    return [{ name: 'Word 文档', extensions: ['docx'] }]
  }
  if (mimeType.includes('spreadsheetml')) {
    return [{ name: 'Excel 工作簿', extensions: ['xlsx'] }]
  }
  if (mimeType.includes('presentationml')) {
    return [{ name: 'PowerPoint 演示文稿', extensions: ['pptx'] }]
  }
  return undefined
}
