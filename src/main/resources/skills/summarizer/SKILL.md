---
name: summarizer
description: 当用户要从 URL、文件或知识库提取关键信息、生成结构化摘要、整理会议纪要、对比多份文档要点时使用。关键词：总结、概括、提炼要点、摘要、这个链接讲了什么、会议纪要、对比文档、长文精简。从零创作用 content-creator，数据统计报告用 data-analyst，调研多个来源用 research-assistant。
version: 2.0.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - summary
      - abstract
      - meeting-notes
      - condense
      - extraction
    suggested_tools:
      - web.fetch
      - file.read
      - file.write
      - knowledge.search
---

# 内容摘要指南

从各种来源提取关键信息，生成结构化摘要。

## 适用场景

- URL 网页内容摘要
- 本地文件（PDF / 文档 / 代码）摘要
- 会议纪要整理
- 多文档对比摘要
- 长文精简

## 不适用场景

- 从零创作内容 → 用 content-creator
- 数据统计报告 → 用 data-analyst
- 调研多个来源 → 用 research-assistant

## 工作流

1. **获取内容**：按来源选工具（URL / 文件 / 知识库），命令模板见参考
2. **选摘要格式**：短摘要 / 深度摘要 / 会议纪要 / 多文档对比
3. **生成摘要**：每个观点必须能在原文中找到对应段落，不添加原文没有的推断
4. **标注来源**：URL 摘要开头注明来源 `[页面标题](URL)`
5. **内容不完整要声明**：抓取为空或不全时告知用户，不假装抓了全部
6. **多文档对比**：逐一读取后再生成，不凭单一文档推测其他

## 详细参考

- 工具命令模板、摘要格式表、会议纪要模板、错误处理：`{skill_dir}/references/summary-formats.md`
</content>
</invoke>