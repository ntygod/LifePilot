---
name: content-creator
description: 当用户要撰写文章、报告、邮件、博客、提案或对已有文本做润色改写时使用。关键词：写文章、写报告、起草邮件、写文案、润色、改写、博客、提案、演讲稿、工作总结。从已有内容提炼摘要用 summarizer，网文长篇连载用 web-novel-writer，技术文档/代码注释用 code-assistant，数据报表用 data-analyst。
version: 2.0.0
metadata:
  zhiwei:
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

1. **确认需求**：内容类型 / 受众 / 篇幅 / 风格，缺一要件不动笔
2. **素材收集**（按需）：`web.search` / `web.fetch` / `knowledge.search` / `file.read` / `memory.search`
3. **大纲**：长文（>2000 字）先出大纲供用户确认；用户明说"直接写"则跳过
4. **撰写**：按大纲逐节产出；引用外部数据必须标注来源，不编造统计数字和案例
5. **润色改写**：保持原文核心观点不变，只调整表达
6. **交付**：`file.write` 保存最终稿

## 详细参考

- 需求要素清单 / 素材命令模板 / 各文体结构表 / 错误处理：`{skill_dir}/references/writing-patterns.md`
</content>
</invoke>