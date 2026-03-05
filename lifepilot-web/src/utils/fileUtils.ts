/**
 * 文件格式校验工具函数
 *
 * 支持 MIME 类型 + 扩展名回退校验，用于拖拽上传时的文件格式分类。
 */

/** 支持的 MIME 类型集合 */
export const SUPPORTED_TYPES = new Set([
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/msword',
  'text/markdown',
  'text/plain',
  'text/html'
])

/** 扩展名到 MIME 类型的回退映射（浏览器可能不返回 MIME 类型） */
export const EXT_TYPE_MAP: Record<string, string> = {
  '.pdf': 'application/pdf',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.doc': 'application/msword',
  '.md': 'text/markdown',
  '.txt': 'text/plain',
  '.html': 'text/html',
  '.htm': 'text/html'
}

/**
 * 根据文件扩展名推断 MIME 类型。
 *
 * @param fileName 文件名
 * @returns 推断的 MIME 类型，未知扩展名返回空字符串
 */
export function guessTypeByExtension(fileName: string): string {
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex === -1) return ''
  const ext = fileName.slice(dotIndex).toLowerCase()
  return EXT_TYPE_MAP[ext] ?? ''
}

/**
 * 将文件列表分类为有效文件和被拒绝的文件名。
 *
 * 优先使用文件的 MIME 类型判断，若 MIME 为空则回退到扩展名推断。
 *
 * @param files 待分类的文件列表
 * @returns accepted（有效文件）和 rejected（不支持格式的文件名）
 */
export function classifyFiles(files: File[]): { accepted: File[]; rejected: string[] } {
  const accepted: File[] = []
  const rejected: string[] = []

  for (const file of files) {
    const mime = file.type || guessTypeByExtension(file.name)
    if (SUPPORTED_TYPES.has(mime)) {
      accepted.push(file)
    } else {
      rejected.push(file.name)
    }
  }

  return { accepted, rejected }
}
