/** 通知内容解析工具函数 */

import { marked } from 'marked'
import type { ParsedDetail } from '@/types'

// Markdown 摘要最大长度
const MARKDOWN_SUMMARY_MAX_LENGTH = 100

/** 解析结果 */
export interface ParsedContent {
  summary: string
  type: string
}

/**
 * 解析通知 contentJson 字段，提取摘要和类型。
 * 支持 TextContent、MarkdownContent、CardContent 三种类型，
 * 解析失败降级为原始文本。
 */
export function parseNotificationContent(contentJson: string): ParsedContent {
  try {
    const parsed = JSON.parse(contentJson)

    if (!parsed || typeof parsed !== 'object' || !parsed.type) {
      return { summary: contentJson, type: 'UNKNOWN' }
    }

    switch (parsed.type) {
      case 'TEXT':
        return {
          summary: parsed.text ?? contentJson,
          type: 'TEXT',
        }

      case 'MARKDOWN':
        return {
          summary: stripMarkdown(parsed.markdown ?? ''),
          type: 'MARKDOWN',
        }

      case 'CARD':
        return {
          summary: buildCardSummary(parsed.title, parsed.body),
          type: 'CARD',
        }

      default:
        return { summary: contentJson, type: 'UNKNOWN' }
    }
  } catch {
    // JSON 解析失败，降级为原始文本
    return { summary: contentJson, type: 'UNKNOWN' }
  }
}

/** 去除 Markdown 标记，截取前 N 个字符 */
function stripMarkdown(markdown: string): string {
  const plain = markdown
    // 去除标题标记
    .replace(/^#{1,6}\s+/gm, '')
    // 去除粗体/斜体
    .replace(/\*{1,3}(.*?)\*{1,3}/g, '$1')
    .replace(/_{1,3}(.*?)_{1,3}/g, '$1')
    // 去除行内代码
    .replace(/`([^`]+)`/g, '$1')
    // 去除链接，保留文本
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    // 去除图片
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    // 去除无序列表标记
    .replace(/^[\s]*[-*+]\s+/gm, '')
    // 去除有序列表标记
    .replace(/^[\s]*\d+\.\s+/gm, '')
    // 去除引用标记
    .replace(/^>\s+/gm, '')
    // 去除水平线
    .replace(/^[-*_]{3,}$/gm, '')
    // 合并多余空白
    .replace(/\n{2,}/g, ' ')
    .replace(/\n/g, ' ')
    .trim()

  if (plain.length <= MARKDOWN_SUMMARY_MAX_LENGTH) return plain
  return plain.slice(0, MARKDOWN_SUMMARY_MAX_LENGTH) + '…'
}

/** 拼接卡片标题和正文摘要 */
function buildCardSummary(title?: string, body?: string): string {
  const parts: string[] = []
  if (title) parts.push(title)
  if (body) parts.push(body)
  const combined = parts.join(' — ')
  if (combined.length <= MARKDOWN_SUMMARY_MAX_LENGTH) return combined
  return combined.slice(0, MARKDOWN_SUMMARY_MAX_LENGTH) + '…'
}

/**
 * 解析通知 contentJson 为详情渲染数据。
 * TEXT → 完整文本; MARKDOWN → marked 渲染为 HTML; CARD → 结构化卡片数据
 */
export function parseNotificationDetail(contentJson: string): ParsedDetail {
  try {
    const parsed = JSON.parse(contentJson)

    if (!parsed || typeof parsed !== 'object' || !parsed.type) {
      return { type: 'UNKNOWN', text: contentJson }
    }

    switch (parsed.type) {
      case 'TEXT':
        return { type: 'TEXT', text: parsed.text ?? contentJson }

      case 'MARKDOWN': {
        const markdown = parsed.markdown ?? ''
        const html = marked.parse(markdown) as string
        return { type: 'MARKDOWN', text: markdown, html }
      }

      case 'CARD':
        return {
          type: 'CARD',
          title: parsed.title,
          body: parsed.body,
          actions: parsed.actions ?? [],
        }

      default:
        return { type: 'UNKNOWN', text: contentJson }
    }
  } catch {
    // JSON 解析失败，降级为原始文本
    return { type: 'UNKNOWN', text: contentJson }
  }
}
