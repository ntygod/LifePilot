---
name: content-creator
description: 当用户要撰写文章、报告、邮件、博客、提案、周报或对已有文本做润色改写时使用。关键词：写文章、写报告、起草邮件、写文案、写周报、写月报、润色、改写、改得专业一点、博客、提案、演讲稿、工作总结、给我写一份、帮我改一下、来一篇。从已有内容提炼摘要用 summarizer，技术文档/代码注释用 code-assistant，数据报表用 data-analyst。
version: 2.1.0
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
      - polish
    suggested_tools:
      - web.search
      - web.fetch
      - file.read
      - file.write
      - knowledge.search
      - memory
---

# 内容创作指南

按"需求要素 → 素材 → 大纲 → 撰写 → 交付"产出文本。**核心约束：缺受众/篇幅/风格不动笔；引用外部数据必带来源、不编造。**

## 适用场景

- 技术文章 / 博客
- 工作报告 / 项目总结
- 邮件 / 商务文案 / 提案
- 演讲稿
- 文档润色与改写

## 不适用场景

- 从已有内容提炼摘要 → summarizer
- 技术文档 / 代码注释 → code-assistant
- 数据报表 → data-analyst

## 工作流（按用户表达分流）

| 用户表达 | 路径 |
|---|---|
| 从零创作（"写一份 X / 帮我起草"） | 确认需求 → 素材 → 大纲 → 撰写 → 交付 |
| 润色改写（"帮我改一下 / 优化下表达"） | 读原文 → 改 → 对比给用户看 |
| 短文 / 邮件（< 500 字） | 跳过大纲直接写 |

各路径要点：

- **需求要素（不可省）**：内容类型 / 受众 / 篇幅 / 风格。**缺一要件不动笔**，先问清楚
- **素材收集（按需）**：需要事实 → `web.search` / `web.fetch` / `knowledge.search`；用户已有文档 → `file.read`；用户偏好 / 历史观点 → `memory(action="search")`
- **大纲**：长文 > 2000 字先出大纲让用户对一下；用户明说"直接写"则跳过
- **撰写**：按大纲逐节产出；引用数据必标 `[来源](URL)`，不编造统计与案例
- **润色不改观点**：保持原文核心立场不变，只调整表达 / 节奏 / 措辞
- **交付**：`file.write` 保存最终稿；用户明说不存就在对话里直接给

## 详细参考

- 需求要素清单 / 各文体结构表 / 错误处理：`{skill_dir}/references/writing-patterns.md`
