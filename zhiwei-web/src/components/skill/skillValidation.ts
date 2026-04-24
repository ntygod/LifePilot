/**
 * SKILL.md 客户端软校验规则。
 *
 * 仅用于在编辑器里给用户即时反馈，不作为硬性阻塞条件；最终校验由后端
 * {@code com.lifepilot.skill.SkillInstaller} / {@code MarkdownSkillParser} 负责。
 *
 * 校验点：
 *   1. frontmatter 存在且 YAML 可解析
 *   2. name 字段存在且匹配正则 ^[a-z0-9][a-z0-9-]{0,62}$
 *   3. description 字段存在，长度 ≤ 1024，且以「当 / 用于 / Use when / Use this when」开头
 *   4. 正文包含 ## 适用场景 / ## 不适用场景 / ## 工作流 三节
 */
import { parse as parseYaml } from 'yaml'

export const SKILL_NAME_REGEX = /^[a-z0-9][a-z0-9-]{0,62}$/
export const SKILL_DESCRIPTION_MAX = 1024
export const SKILL_DESCRIPTION_PREFIXES = ['当', '用于', 'Use when', 'Use this when'] as const
export const SKILL_REQUIRED_SECTIONS = ['## 适用场景', '## 不适用场景', '## 工作流'] as const

export interface SkillValidationIssue {
  severity: 'warning'
  message: string
}

/** 提取 frontmatter 原文（返回 null 表示首行不是 ---） */
function extractFrontmatter(content: string): string | null {
  const lines = content.split('\n')
  if (lines.length < 2 || lines[0].trim() !== '---') return null

  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') {
      return lines.slice(1, i).join('\n')
    }
  }
  return null
}

/** 逐条检查 SKILL.md 并返回软警告列表 */
export function validateSkillMarkdown(content: string): SkillValidationIssue[] {
  const issues: SkillValidationIssue[] = []

  // 1. frontmatter
  const fm = extractFrontmatter(content)
  if (fm === null) {
    issues.push({ severity: 'warning', message: '缺少 YAML frontmatter（首行应为 ---）。' })
  } else {
    try {
      const parsed = parseYaml(fm) as Record<string, unknown> | null

      // name 校验
      const name = typeof parsed?.name === 'string' ? parsed.name.trim() : ''
      if (!name) {
        issues.push({ severity: 'warning', message: 'frontmatter 缺少 name 字段。' })
      } else if (!SKILL_NAME_REGEX.test(name)) {
        issues.push({
          severity: 'warning',
          message: `name "${name}" 不符合 ^[a-z0-9][a-z0-9-]{0,62}$ 规则（小写字母/数字/连字符，首字符不可为连字符）。`,
        })
      }

      // description 校验
      const description = typeof parsed?.description === 'string' ? parsed.description : ''
      if (!description.trim()) {
        issues.push({ severity: 'warning', message: 'frontmatter 缺少 description 字段。' })
      } else {
        if (description.length > SKILL_DESCRIPTION_MAX) {
          issues.push({
            severity: 'warning',
            message: `description 长度 ${description.length} 超过 ${SKILL_DESCRIPTION_MAX} 字符上限。`,
          })
        }
        const head = description.trimStart()
        const ok = SKILL_DESCRIPTION_PREFIXES.some(prefix => head.startsWith(prefix))
        if (!ok) {
          issues.push({
            severity: 'warning',
            message: 'description 建议以「当 / 用于 / Use when / Use this when」开头，方便模型快速匹配适用场景。',
          })
        }
      }
    } catch (error: any) {
      issues.push({
        severity: 'warning',
        message: `frontmatter 解析失败：${error?.message?.split('\n')[0] ?? 'YAML 语法错误'}`,
      })
    }
  }

  // 2. 正文 section —— 逐行匹配，section 标题所在行去掉首尾空白后完全相等即视为存在
  const lines = content.split('\n').map(line => line.trim())
  for (const section of SKILL_REQUIRED_SECTIONS) {
    if (!lines.includes(section)) {
      issues.push({ severity: 'warning', message: `缺少小节「${section}」。` })
    }
  }

  return issues
}
