/** 长消息折叠相关工具函数 */

// 折叠阈值（字符数）
const COLLAPSE_THRESHOLD = 500
// 折叠后预览长度
const COLLAPSE_PREVIEW_LENGTH = 300

/** 判断消息内容是否需要折叠 */
export function shouldCollapse(content: string): boolean {
  return content.length > COLLAPSE_THRESHOLD
}

/** 获取折叠后的预览内容，在段落边界截断 */
export function getPreviewContent(content: string): string {
  if (content.length <= COLLAPSE_PREVIEW_LENGTH) return content
  const truncated = content.slice(0, COLLAPSE_PREVIEW_LENGTH)
  // 尝试在段落边界截断
  const lastNewline = truncated.lastIndexOf('\n')
  if (lastNewline > COLLAPSE_PREVIEW_LENGTH * 0.6) {
    return truncated.slice(0, lastNewline)
  }
  return truncated + '…'
}
