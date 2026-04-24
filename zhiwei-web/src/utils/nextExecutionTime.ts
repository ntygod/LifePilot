/**
 * 定时任务"下次执行时间"人类可读格式化。
 *
 * <p>与 {@link ./relativeTime} 的"过去相对"不同，这里处理**未来**时间——定时任务
 * 只关心"下次什么时候跑"，按"今天 HH:mm" / "明天 HH:mm" / "周日 HH:mm" /
 * "M月D日 HH:mm" 依次降级展示。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */

/** 周几的中文名（周日起始，和 Date.getDay 返回的 0=周日 对齐） */
const WEEKDAY_ZH = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

/** 两位数补零 */
function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** HH:mm 时分表示 */
function hhmm(date: Date): string {
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/**
 * 判断两个 {@link Date} 是否为同一本地日历日。
 *
 * <p>不关心时/分/秒/毫秒；只比年、月、日三者完全一致。</p>
 */
function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate()
}

/**
 * 把 {@link Date} 归一到本地日期午夜 00:00:00.000（用来算"相差几天"）。
 */
function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

/**
 * 格式化定时任务的"下次执行时间"。
 *
 * <p>规则（now 相对）：</p>
 * <ul>
 *   <li>{@code null} / 无效 → "未知"</li>
 *   <li>同一天 → "今天 HH:mm"</li>
 *   <li>明天 → "明天 HH:mm"</li>
 *   <li>后天 / 3~6 天后 → "{周X} HH:mm"</li>
 *   <li>≥ 7 天 → "M月D日 HH:mm"</li>
 *   <li>过去时间（可能因 clock skew）→ "即将" （不展示"分钟前"，避免误导）</li>
 * </ul>
 *
 * @param isoString ISO 8601 字符串（如 2026-04-25T08:00:00+08:00）
 * @param now       可注入的"当前时间"，便于测试；缺省为 new Date()
 */
export function formatNextExecutionTime(
  isoString: string | null | undefined,
  now: Date = new Date(),
): string {
  if (isoString == null || isoString === '') return '未知'
  const target = new Date(isoString)
  const ts = target.getTime()
  if (Number.isNaN(ts)) return '未知'

  // 过去时间兜底：真实场景下后端只算未来时刻，这里主要防时钟漂移
  if (ts < now.getTime()) return '即将'

  const targetDay = startOfDay(target)
  const todayDay = startOfDay(now)
  const diffDays = Math.round((targetDay.getTime() - todayDay.getTime()) / 86_400_000)

  if (diffDays === 0 || isSameDay(target, now)) return `今天 ${hhmm(target)}`
  if (diffDays === 1) return `明天 ${hhmm(target)}`
  if (diffDays >= 2 && diffDays <= 6) {
    return `${WEEKDAY_ZH[target.getDay()]} ${hhmm(target)}`
  }
  return `${target.getMonth() + 1}月${target.getDate()}日 ${hhmm(target)}`
}

/**
 * 格式化执行日志的"执行时刻"：同 {@link formatNextExecutionTime} 只是过去向。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>{@code null} / 无效 → "未知"</li>
 *   <li>同一天 → "今天 HH:mm"</li>
 *   <li>昨天 → "昨天 HH:mm"</li>
 *   <li>≤ 6 天前 → "{周X} HH:mm"</li>
 *   <li>更早 → "M月D日 HH:mm"（跨年则带年份）</li>
 * </ul>
 *
 * @param isoString ISO 8601 字符串
 * @param now       可注入的"当前时间"
 */
export function formatExecutedAtTime(
  isoString: string | null | undefined,
  now: Date = new Date(),
): string {
  if (isoString == null || isoString === '') return '未知'
  const target = new Date(isoString)
  const ts = target.getTime()
  if (Number.isNaN(ts)) return '未知'

  const targetDay = startOfDay(target)
  const todayDay = startOfDay(now)
  const diffDays = Math.round((todayDay.getTime() - targetDay.getTime()) / 86_400_000)

  if (diffDays === 0 || isSameDay(target, now)) return `今天 ${hhmm(target)}`
  if (diffDays === 1) return `昨天 ${hhmm(target)}`
  if (diffDays >= 2 && diffDays <= 6) {
    return `${WEEKDAY_ZH[target.getDay()]} ${hhmm(target)}`
  }
  // 跨年时带年份，否则只展示月日
  const sameYear = target.getFullYear() === now.getFullYear()
  const prefix = sameYear
    ? ''
    : `${target.getFullYear()}年`
  return `${prefix}${target.getMonth() + 1}月${target.getDate()}日 ${hhmm(target)}`
}
