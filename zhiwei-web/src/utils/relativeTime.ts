/**
 * 相对时间格式化工具。
 * 将 ISO 8601 时间戳转换为中文相对时间文本。
 */

/**
 * 将 ISO 8601 时间戳转换为相对时间文本。
 *
 * 时间桶规则：
 * - diff < 60s 或未来时间 → "刚刚"
 * - 60s ≤ diff < 3600s → "{N}分钟前"
 * - 3600s ≤ diff < 86400s → "{N}小时前"
 * - 86400s ≤ diff < 2592000s (30天) → "{N}天前"
 * - diff ≥ 30天 → "YYYY-MM-DD"
 *
 * @param sentAt ISO 8601 格式时间字符串，可为 null/undefined
 * @returns 相对时间文本，如"刚刚"、"5分钟前"、"3天前"、"2026-03-13"
 */
export function formatRelativeTime(sentAt: string | null | undefined): string {
  // 空值检查
  if (sentAt == null || sentAt === '') return '未知时间'

  // 解析时间戳，无效格式返回"未知时间"
  const sentTime = new Date(sentAt).getTime()
  if (Number.isNaN(sentTime)) return '未知时间'

  const diffMs = Date.now() - sentTime

  // 未来时间或不足 1 分钟
  if (diffMs < 0 || diffMs < 60_000) return '刚刚'

  // 分钟级
  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 60) return `${minutes}分钟前`

  // 小时级
  const hours = Math.floor(diffMs / 3_600_000)
  if (hours < 24) return `${hours}小时前`

  // 天级（30 天内）
  const days = Math.floor(diffMs / 86_400_000)
  if (days < 30) return `${days}天前`

  // 超过 30 天，格式化为 YYYY-MM-DD
  const d = new Date(sentTime)
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
}
