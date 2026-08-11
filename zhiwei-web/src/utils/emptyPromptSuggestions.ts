export interface EmptyPromptSuggestion {
  id: string
  label: string
  prompt: string
}

export interface EmptyPromptKnowledgeBase {
  id: string
  name?: string | null
}

export interface BuildEmptyPromptSuggestionsOptions {
  linkedKnowledgeBases?: EmptyPromptKnowledgeBase[]
  hasKnowledgeBases?: boolean
  hasMemories?: boolean
  maxCount?: number
}

const DEFAULT_MAX_SUGGESTION_COUNT = 3
const KNOWLEDGE_LABEL_MAX_LENGTH = 8
const KNOWLEDGE_PROMPT_NAME_MAX_LENGTH = 24

export const BASE_EMPTY_PROMPT_SUGGESTIONS: EmptyPromptSuggestion[] = [
  {
    id: 'sort-material',
    label: '整理资料',
    prompt: '我会贴一段资料，请先帮我提炼要点，再整理成可以直接行动的清单。\n\n',
  },
  {
    id: 'plan-next-step',
    label: '规划下一步',
    prompt: '我想推进一件事，请先帮我拆成清晰的下一步：',
  },
  {
    id: 'polish-writing',
    label: '润色文字',
    prompt: '请帮我润色下面这段文字，保留原意但更简洁、更专业。内容：\n\n',
  },
]

const MEMORY_PROMPT_SUGGESTION: EmptyPromptSuggestion = {
  id: 'use-memory',
  label: '按我的习惯推进',
  prompt: '请结合你记得的我的偏好、背景和已有信息，帮我推进这件事：',
}

function compactKnowledgeName(value: string, maxLength = KNOWLEDGE_LABEL_MAX_LENGTH) {
  const normalized = value.trim() || '资料库'
  return normalized.length > maxLength
    ? `${normalized.slice(0, maxLength - 1)}…`
    : normalized
}

export function buildEmptyPromptSuggestions(options: BuildEmptyPromptSuggestionsOptions = {}) {
  const linkedKnowledgeBases = options.linkedKnowledgeBases ?? []
  const maxCount = Math.max(0, options.maxCount ?? DEFAULT_MAX_SUGGESTION_COUNT)
  const contextual: EmptyPromptSuggestion[] = []

  if (linkedKnowledgeBases.length > 0) {
    const promptTarget = linkedKnowledgeBases.length === 1
      ? `「${compactKnowledgeName(linkedKnowledgeBases[0].name ?? '资料库', KNOWLEDGE_PROMPT_NAME_MAX_LENGTH)}」`
      : `当前会话关联的 ${linkedKnowledgeBases.length} 个资料库`
    const label = linkedKnowledgeBases.length === 1
      ? `基于「${compactKnowledgeName(linkedKnowledgeBases[0].name ?? '资料库')}」`
      : `基于 ${linkedKnowledgeBases.length} 个资料库`
    contextual.push({
      id: 'ask-linked-knowledge',
      label,
      prompt: `请基于${promptTarget}帮我回答：`,
    })
  } else if (options.hasKnowledgeBases) {
    contextual.push({
      id: 'mention-knowledge',
      label: '选资料提问',
      prompt: '@',
    })
  } else if (options.hasMemories) {
    contextual.push(MEMORY_PROMPT_SUGGESTION)
  }

  return [...contextual, ...BASE_EMPTY_PROMPT_SUGGESTIONS].slice(0, maxCount)
}
