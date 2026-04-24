/**
 * {@link validateSkillMarkdown} 的单元测试。
 *
 * 软校验仅用于编辑器内实时提示，覆盖 frontmatter 结构、name 命名、description 长度/前缀，
 * 以及正文三节（## 适用场景 / ## 不适用场景 / ## 工作流）。
 */
import { describe, expect, it } from 'vitest'
import { validateSkillMarkdown } from './skillValidation'

function withFrontmatter(frontmatter: string, body = SECTIONS): string {
  return `---\n${frontmatter}\n---\n\n${body}`
}

const SECTIONS = `## 适用场景
- 示例

## 不适用场景
- 示例

## 工作流
1. 示例`

const VALID_FRONTMATTER = `name: sample-skill
description: 当用户希望创建示例技能时使用。关键词 sample。
version: 1.0.0`

describe('validateSkillMarkdown', () => {
  it('合法的 SKILL.md 不产生告警', () => {
    const issues = validateSkillMarkdown(withFrontmatter(VALID_FRONTMATTER))
    expect(issues).toEqual([])
  })

  it('缺失 frontmatter 时报 1 条 + 三节也缺再报 3 条', () => {
    const issues = validateSkillMarkdown('没有 frontmatter 的纯文本')
    expect(issues).toHaveLength(4)
    expect(issues[0].message).toContain('frontmatter')
  })

  it('name 含大写字母时给出命名规则告警', () => {
    const issues = validateSkillMarkdown(withFrontmatter(`name: MySkill
description: 当测试时使用。`))
    const nameIssue = issues.find(i => i.message.includes('name'))
    expect(nameIssue).toBeTruthy()
    expect(nameIssue!.message).toContain('^[a-z0-9]')
  })

  it('description 不以推荐前缀开头时给出前缀告警', () => {
    const issues = validateSkillMarkdown(withFrontmatter(`name: sample-skill
description: 一个随便写的描述。`))
    const prefixIssue = issues.find(i => i.message.includes('当 / 用于'))
    expect(prefixIssue).toBeTruthy()
  })

  it('description 以 Use when 开头视为合法前缀', () => {
    const issues = validateSkillMarkdown(withFrontmatter(`name: sample-skill
description: Use when the user wants to generate a sample.`))
    expect(issues).toEqual([])
  })

  it('description 超过 1024 字符时给出长度告警', () => {
    const longDesc = '当' + '测'.repeat(1024) // 总长 1025
    const issues = validateSkillMarkdown(withFrontmatter(`name: sample-skill
description: ${longDesc}`))
    const lengthIssue = issues.find(i => i.message.includes('超过 1024'))
    expect(lengthIssue).toBeTruthy()
  })

  it('缺失「## 工作流」单节时只报一条小节告警', () => {
    const body = `## 适用场景
- 示例

## 不适用场景
- 示例`
    const issues = validateSkillMarkdown(withFrontmatter(VALID_FRONTMATTER, body))
    const sectionIssues = issues.filter(i => i.message.includes('缺少小节'))
    expect(sectionIssues).toHaveLength(1)
    expect(sectionIssues[0].message).toContain('## 工作流')
  })

  it('frontmatter YAML 语法错误时给出解析失败告警', () => {
    const issues = validateSkillMarkdown(withFrontmatter(`name: sample-skill
description: "未闭合的引号`))
    const parseIssue = issues.find(i => i.message.includes('frontmatter 解析失败'))
    expect(parseIssue).toBeTruthy()
  })
})
