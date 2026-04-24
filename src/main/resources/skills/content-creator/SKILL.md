---
name: content-creator
description: 当用户要撰写文章、报告、邮件、博客、提案或对已有文本做润色改写时使用。关键词：写文章、写报告、起草邮件、写文案、润色、改写、博客、提案、演讲稿、工作总结。从已有内容提炼摘要用 summarizer，网文长篇连载用 web-novel-writer，技术文档/代码注释用 code-assistant，数据报表用 data-analyst。
version: 2.0.0
metadata:
  zhiwei:
    category: content-creation
    priority: normal
    tags:
      - writing
      - article
      - report
      - email
      - copywriting
      - blog
    suggested_tools:
      - web.search
      - web.fetch
      - file.read
      - file.write
      - knowledge.search
      - memory
---

# 内容创作指南

根据用户需求，通过结构化流程产出高质量文本内容。

## 适用场景

- 技术文章和博客撰写
- 工作报告和项目总结
- 邮件和商务文案起草
- 文档润色和改写
- 演讲稿和提案撰写

## 不适用场景

- 从已有内容提炼摘要 → 用 summarizer
- 网文长篇连载创作 → 用 web-novel-writer
- 技术文档/代码注释 → 用 code-assistant
- 数据报表 → 用 data-analyst

## 工作流

### 确认需求

和用户确认以下要素，缺失的主动询问：
- 内容类型（文章 / 报告 / 邮件 / 文案）
- 目标受众
- 篇幅要求
- 风格基调

### 素材收集

```
web.search(query="主题关键词")
web.fetch(url="参考文章URL", selector="article")
knowledge.search(query="相关主题")
file.read(path="用户提供的参考文件")
memory(action="search", query="写作风格偏好")
```

仅在需要外部信息时执行此步骤。用户已提供充分素材时跳过。

### 大纲构建

先输出大纲供用户确认，包含标题、各章节要点、预估篇幅。用户确认后再进入撰写；用户明确说"直接写"则跳过大纲。

### 撰写

按大纲逐节撰写。参考结构：

| 内容类型 | 结构 |
|---------|------|
| 技术文章 | 引言 → 现状分析 → 解决方案 → 实现细节 → 效果对比 → 总结 |
| 工作报告 | 摘要 → 本期完成 → 关键成果 → 问题与方案 → 下期计划 |
| 邮件 | 主旨 → 背景 → 要求/建议 → 收尾 |
| 文案 | 痛点引入 → 解决方案 → 价值主张 → 行动号召 |

### 交付

```
file.write(path="output/文章标题.md", content="最终内容")
```

## 规则

- 撰写前必须确认需求要素（类型、受众、篇幅、风格），不确认不动笔
- 引用外部数据时标注来源，不编造统计数字和案例
- 润色/改写任务保持原文的核心观点不变，只调整表达方式
- 长文（>2000 字）先出大纲再撰写，除非用户明确跳过
- 不主动添加原文没要求的章节

## 常见错误处理

- **需求不明确** → 先问清楚再写，不猜测需求
- **素材不足** → 用 `web.search` 补充，搜索失败则告知用户缺少哪些信息
- **风格不匹配** → 确认用户偏好后重写对应段落，不整篇推翻
