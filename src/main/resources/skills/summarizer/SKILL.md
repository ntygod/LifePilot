---
name: summarizer
description: 当用户要从 URL、文件或知识库提取关键信息、生成结构化摘要、整理会议纪要、对比多份文档要点时使用。
version: 2.1.0
metadata:
  zhiwei:
    tags:
      - summary
      - abstract
      - meeting-notes
      - condense
      - extraction
    suggested_tools:
      - web_fetch
      - file_read
      - file_write
      - memory
---

# 内容摘要指南

从已有来源提取关键信息生成结构化摘要。**核心约束：每个观点必须能在原文找到对应段落，不添加原文没有的推断。**

## 适用场景

- URL 网页摘要（"这个链接讲了什么"）
- 本地文件（PDF / 文档 / 代码）摘要
- 会议纪要整理（录音转写后的会议记录）
- 多文档对比（找共识 / 分歧）
- 长文精简（保核心、删枝叶）

## 不适用场景

- 从零创作 → content-creator
- 数据统计报告 → data-analyst
- 跨多源调研 → research-assistant

## 工作流（按来源分流）

| 来源 | 取用方法 |
|---|---|
| URL | `web_fetch` 抓正文 |
| 本地文件（含 docx/pdf/xlsx） | `file_read`（已自动解析结构化文档） |
| 知识库 | `memory` |
| 会议录音 | 用户先转写，得到文本后走文件路径 |

通用要点：

- **选格式**：短摘要（3-5 句） / 深度摘要（章节结构） / 会议纪要（议题 / 决议 / 行动项 / 责任人） / 多文档对比（共识 / 分歧 / 来源标注）
- **抓不全要声明**：抓取空 / 不全 / 被付费墙挡时告知用户，不假装抓了全部
- **多文档对比**：每篇逐一读取后再生成，不靠单文档外推
- **来源标注**：URL 摘要开头 `[页面标题](URL)`；多文档版每条要点标"来自《X》"
- **保存**：长摘要 `file_write` 落盘，短摘要直接对话给

## 详细参考

- 各格式模板、会议纪要骨架、错误处理：`{skill_dir}/references/summary-formats.md`
