/**
 * CLI 输出流解析器 — 识别主流编码 CLI 的结构化输出并提取摘要信息。
 *
 * 支持的格式：
 * - Claude Code 的 `--output-format stream-json`（每行一个 JSON 对象）
 * - Codex 的 `--json`（每行一个 JSON 对象）
 *
 * 未识别或非结构化输出时 fallback 为"stdout 最后一行非空内容"。
 */

/** 解析结果，供气泡折叠态展示。 */
export interface CliParsedSummary {
  /** 当前动作描述，如 "调用 Read 工具"、"正在生成回复" */
  currentAction?: string
  /** 已消耗 token 数（如果 CLI 有报告） */
  tokens?: number
  /** 识别到的 CLI 类型，用于图标/品牌色区分 */
  cliKind?: CliKind
  /** 已完成的工具调用次数（用来展示任务规模） */
  toolCallCount?: number
}

export type CliKind = 'claude' | 'codex' | 'gemini' | 'unknown'

/**
 * 主入口 — 根据命令判断 CLI 类型，选择对应解析器。
 *
 * @param command  启动命令原文
 * @param stdout   stdout 累积缓冲区
 * @param stderr   stderr 累积缓冲区
 * @returns 结构化摘要；无法解析时返回 fallback 摘要
 */
export function parseCliStream(command: string, stdout: string, stderr: string): CliParsedSummary {
  const kind = detectCliKind(command)
  const content = stdout || stderr

  if (kind === 'claude') {
    const claudeSummary = parseClaudeStream(content)
    if (claudeSummary.currentAction) return { ...claudeSummary, cliKind: kind }
  }

  if (kind === 'codex') {
    const codexSummary = parseCodexStream(content)
    if (codexSummary.currentAction) return { ...codexSummary, cliKind: kind }
  }

  // 通用 fallback：取最后一行非空内容
  return {
    currentAction: lastNonEmptyLine(content) ?? undefined,
    cliKind: kind,
  }
}

/** 根据 command 字符串推断 CLI 类型。 */
function detectCliKind(command: string): CliKind {
  const cmd = command.toLowerCase().trim()
  if (cmd.startsWith('claude') || cmd.includes(' claude ')) return 'claude'
  if (cmd.startsWith('codex') || cmd.includes(' codex ')) return 'codex'
  if (cmd.startsWith('gemini') || cmd.includes(' gemini ')) return 'gemini'
  return 'unknown'
}

/**
 * 解析 Claude Code 的 stream-json 输出。
 *
 * 事件样例：
 * - {"type":"system","subtype":"init",...}
 * - {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}
 * - {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"..."}}]}}
 * - {"type":"result","subtype":"success","usage":{"input_tokens":...}}
 */
function parseClaudeStream(content: string): CliParsedSummary {
  const events = parseJsonLines(content)
  if (events.length === 0) return {}

  let currentAction: string | undefined
  let tokens: number | undefined
  let toolCallCount = 0

  for (const evt of events) {
    const type = typeof evt.type === 'string' ? evt.type : undefined

    if (type === 'assistant' && evt.message && typeof evt.message === 'object') {
      const contents = (evt.message as Record<string, unknown>).content
      if (Array.isArray(contents)) {
        for (const item of contents) {
          if (item && typeof item === 'object') {
            const itemType = (item as Record<string, unknown>).type
            if (itemType === 'tool_use') {
              toolCallCount++
              const name = (item as Record<string, unknown>).name
              const input = (item as Record<string, unknown>).input
              currentAction = formatClaudeToolUse(String(name ?? '工具'), input)
            } else if (itemType === 'text' && !currentAction) {
              const text = (item as Record<string, unknown>).text
              if (typeof text === 'string' && text.trim()) {
                currentAction = '正在生成回复'
              }
            }
          }
        }
      }
    } else if (type === 'result') {
      const usage = evt.usage as Record<string, unknown> | undefined
      if (usage && typeof usage === 'object') {
        const input = Number(usage.input_tokens ?? 0)
        const output = Number(usage.output_tokens ?? 0)
        tokens = input + output
      }
      const subtype = typeof evt.subtype === 'string' ? evt.subtype : undefined
      if (subtype === 'success') currentAction = '已完成'
      else if (subtype) currentAction = `结果：${subtype}`
    } else if (type === 'system' && !currentAction) {
      currentAction = '初始化中'
    }
  }

  return { currentAction, tokens, toolCallCount }
}

/** 把 Claude tool_use 事件格式化成短描述。 */
function formatClaudeToolUse(name: string, input: unknown): string {
  if (!input || typeof input !== 'object') return `调用 ${name}`
  const obj = input as Record<string, unknown>
  if (name === 'Read' && typeof obj.file_path === 'string') {
    return `读取 ${shortenPath(obj.file_path)}`
  }
  if (name === 'Write' && typeof obj.file_path === 'string') {
    return `写入 ${shortenPath(obj.file_path)}`
  }
  if (name === 'Edit' && typeof obj.file_path === 'string') {
    return `编辑 ${shortenPath(obj.file_path)}`
  }
  if (name === 'Bash' && typeof obj.command === 'string') {
    return `执行命令 ${truncate(obj.command, 32)}`
  }
  if ((name === 'Grep' || name === 'Glob') && typeof obj.pattern === 'string') {
    return `搜索 ${truncate(obj.pattern, 24)}`
  }
  return `调用 ${name}`
}

/**
 * 解析 Codex CLI 的 --json 输出。
 *
 * 事件样例：
 * - {"type":"session_configured", ...}
 * - {"type":"agent_message","message":"..."}
 * - {"type":"tool_call","tool":"shell","args":{...}}
 * - {"type":"task_complete","exit_reason":"..."}
 */
function parseCodexStream(content: string): CliParsedSummary {
  const events = parseJsonLines(content)
  if (events.length === 0) return {}

  let currentAction: string | undefined
  let toolCallCount = 0

  for (const evt of events) {
    const type = typeof evt.type === 'string' ? evt.type : undefined

    if (type === 'session_configured') {
      currentAction = '初始化中'
    } else if (type === 'tool_call') {
      toolCallCount++
      const tool = typeof evt.tool === 'string' ? evt.tool : '工具'
      currentAction = `调用 ${tool}`
    } else if (type === 'agent_message') {
      if (!currentAction || currentAction === '初始化中') {
        currentAction = '正在生成回复'
      }
    } else if (type === 'task_complete' || type === 'session_ended') {
      currentAction = '已完成'
    }
  }

  return { currentAction, toolCallCount }
}

/** 按行解析 JSON Lines，失败行静默忽略。 */
function parseJsonLines(content: string): Record<string, unknown>[] {
  if (!content) return []
  const results: Record<string, unknown>[] = []
  const lines = content.split('\n')
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed || !trimmed.startsWith('{')) continue
    try {
      const parsed = JSON.parse(trimmed)
      if (parsed && typeof parsed === 'object') {
        results.push(parsed as Record<string, unknown>)
      }
    } catch {
      // 非 JSON 行，忽略
    }
  }
  return results
}

/** 取最后一行非空内容。 */
function lastNonEmptyLine(content: string): string | null {
  if (!content) return null
  const lines = content.split('\n').map(l => l.trim()).filter(l => l)
  const last = lines[lines.length - 1]
  return last ? truncate(last, 60) : null
}

/** 缩短文件路径显示（只保留最后两段）。 */
function shortenPath(path: string): string {
  const parts = path.split(/[/\\]/).filter(Boolean)
  if (parts.length <= 2) return path
  return '.../' + parts.slice(-2).join('/')
}

/** 截断字符串。 */
function truncate(text: string, max: number): string {
  if (text.length <= max) return text
  return text.slice(0, max - 1) + '…'
}
