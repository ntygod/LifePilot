/**
 * 剪贴板复制工具函数。
 * 优先使用 navigator.clipboard API，降级使用 execCommand。
 *
 * @returns true 表示复制成功，false 表示完全失败
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  // 优先使用现代 Clipboard API
  if (typeof navigator !== 'undefined' && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 安全策略阻止，降级到 execCommand
    }
  }

  // 降级方案：使用 execCommand
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const success = document.execCommand('copy')
    document.body.removeChild(textarea)
    return success
  } catch {
    return false
  }
}
