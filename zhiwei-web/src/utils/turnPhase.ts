function cleanText(value?: string | null) {
  return value?.trim() ?? ''
}

function hasInternalHumanizedName(value: string) {
  const phrase = value.replace(/[._-]+/g, ' ')
  return phrase.includes('tool search')
    || phrase.includes('tool discovery')
    || phrase.includes('capability check')
    || phrase.includes('capability assess')
    || phrase.includes('capability inspection')
    || phrase.includes('capability registry')
    || phrase.includes('capability registration')
    || phrase.includes('experience match')
    || phrase.includes('experience matching')
    || phrase.includes('decision signal')
    || phrase.includes('decision enhance')
    || phrase.includes('adaptive decision')
    || phrase.includes('context assemble')
    || phrase.includes('context assembly')
    || phrase.includes('context prepare')
    || phrase.includes('context preparation')
    || phrase.includes('context load')
    || phrase.includes('context loading')
}

function isInternalStatus(text: string) {
  const lower = text.toLowerCase()
  return hasInternalHumanizedName(lower)
    || lower.includes('intent')
    || lower.includes('router')
    || lower.includes('tool.search')
    || lower.includes('experience.match')
    || lower.includes('decision signal')
    || lower.includes('adaptive decision')
    || lower.includes('context assemble')
    || lower.includes('context.prepare')
    || lower.includes('capability.')
    || text.includes('意图')
    || text.includes('路由')
    || text.includes('经验匹配')
    || text.includes('历史经验')
    || text.includes('决策信号')
    || text.includes('决策增强')
    || text.includes('后台增强')
    || text.includes('正在判断')
    || text.includes('知微正在判断')
    || text.includes('判断中')
    || text.includes('正在调用工具')
    || text.includes('工具调用完成')
    || text.includes('调用核查')
    || text.includes('能力核查')
    || text.includes('能力检查')
    || text.includes('检查能力')
    || text.includes('能力注册')
    || text.includes('工具核查')
    || text.includes('工具搜索')
    || text.includes('搜索工具')
    || text.includes('加载上下文')
    || text.includes('准备上下文')
    || text.includes('上下文装配')
    || text.includes('准备对话上下文')
    || text.includes('读取上下文')
    || text.includes('上下文加载')
    || lower.includes('loading context')
    || lower.includes('preparing context')
    || text === '推理中'
    || text === '处理中'
    || text === '正在处理'
    || text === '正在回复'
    || text === '生成回复中'
}

export function normalizeTurnStatusText(status?: string | null) {
  const text = cleanText(status)
  if (!text) return null
  if (isInternalStatus(text)) return null
  if (text === '本轮回答已完成') return null
  return text
}
