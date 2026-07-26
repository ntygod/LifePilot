const INTERNAL_CAPABILITY_IDS = new Set([
  'intent',
  'chat.intent',
  'intent.match',
  'intent.matching',
  'query.intent',
  'router',
  'chat.router',
  'router.plan',
  'router.planning',
  'tool.search',
  'tool.discovery',
  'capability.assess',
  'capability.check',
  'capability.inspect',
  'experience.match',
  'experience.matching',
  'memory.intent.match',
  'memory.extract',
  'memory.extraction',
  'memory.consolidate',
  'memory.consolidation',
  'memory.index',
  'memory.embed',
  'memory.embedding',
  'memory.vector',
  'memory.governance',
  'memory.revalidate',
  'memory.revalidation',
  'memory.lifecycle',
  'memory.profile.consolidate',
  'decision.signal',
  'decision.enhance',
  'adaptive.decision',
  'context.prepare',
  'context.assemble',
  'context.load',
])

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

export function isInternalCapabilityId(id?: string | null) {
  const normalized = id?.trim().toLowerCase() ?? ''
  if (!normalized) return true
  return INTERNAL_CAPABILITY_IDS.has(normalized)
    || hasInternalHumanizedName(normalized)
    || normalized.includes('intent')
    || normalized.includes('router')
    || normalized.includes('experience.match')
    || normalized.includes('memory.extract')
    || normalized.includes('memory.extraction')
    || normalized.includes('memory.consolid')
    || normalized.includes('memory.index')
    || normalized.includes('memory.embed')
    || normalized.includes('memory.vector')
    || normalized.includes('memory.govern')
    || normalized.includes('memory.revalid')
    || normalized.includes('memory.lifecycle')
    || normalized.includes('adaptive.decision')
    || normalized.includes('decision.signal')
    || normalized.includes('context.prepare')
    || normalized.includes('context.assemble')
    || normalized.startsWith('intent.')
    || normalized.startsWith('router.')
    || normalized.startsWith('decision.')
    || normalized.startsWith('adaptive.')
    || normalized.startsWith('context.')
    || normalized.startsWith('capability.')
    || normalized.startsWith('tool.search.')
    || normalized.startsWith('tool.discovery.')
    || normalized.includes('意图')
    || normalized.includes('路由')
    || normalized.includes('经验匹配')
    || normalized.includes('历史经验')
    || normalized.includes('记忆抽取')
    || normalized.includes('记忆提取')
    || normalized.includes('记忆沉淀')
    || normalized.includes('记忆索引')
    || normalized.includes('记忆向量')
    || normalized.includes('记忆巩固')
    || normalized.includes('记忆治理')
    || normalized.includes('记忆复核')
    || normalized.includes('画像巩固')
    || normalized.includes('决策信号')
    || normalized.includes('决策增强')
    || normalized.includes('后台增强')
    || normalized.includes('加载上下文')
    || normalized.includes('准备上下文')
    || normalized.includes('上下文装配')
    || normalized.includes('准备对话上下文')
    || normalized.includes('读取上下文')
    || normalized.includes('上下文加载')
    || normalized.includes('loading context')
    || normalized.includes('preparing context')
    || normalized.includes('调用核查')
    || normalized.includes('能力核查')
    || normalized.includes('能力检查')
    || normalized.includes('检查能力')
    || normalized.includes('能力注册')
    || normalized.includes('工具核查')
    || normalized.includes('工具搜索')
    || normalized.includes('搜索工具')
}
