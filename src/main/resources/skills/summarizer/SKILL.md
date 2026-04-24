---
name: summarizer
description: 当用户要从 URL、文件或知识库提取关键信息、生成结构化摘要、整理会议纪要、对比多份文档要点时使用。关键词：总结、概括、提炼要点、摘要、这个链接讲了什么、会议纪要、对比文档、长文精简。从零创作用 content-creator，数据统计报告用 data-analyst，调研多个来源用 research-assistant。
version: 2.0.0
metadata:
  zhiwei:
    category: content-creation
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

### 获取内容

根据来源类型选择工具：

**URL 网页：**
```
web.fetch(url="目标URL")
```
内容过长时用选择器提取正文：
```
web.fetch(url="目标URL", selector="article, .content, main")
```

**本地文件：**
```
file.read(path="文件路径")
file.read(path="文件路径", maxChars=10000)
```

**知识库资料：**
```
knowledge.search(query="主题关键词")
```

### 选择摘要格式

| 场景 | 格式 |
|------|------|
| 快速了解 | 1-3 句短摘要 |
| 深度理解 | 核心观点 + 关键数据 + 结论 |
| 会议纪要 | 讨论要点 + 决策 + 行动项 |
| 多文档对比 | 对比表格 + 共识与分歧 |

### 生成摘要

按选定格式输出。会议纪要使用以下结构：

```
## 会议信息
- 时间：
- 参与者：

## 讨论要点
1. 议题 A — 结论/决策
2. 议题 B — 结论/决策

## 行动项
- [ ] 任务 1（负责人，截止日期）
```

### 保存（按需）

```
file.write(path="output/摘要.md", content="摘要内容")
```

## 规则

- 摘要中的每个观点必须能在原文中找到对应段落，不得添加原文没有的推断或评价
- URL 摘要必须在开头标注来源：`来源：[页面标题](URL)`
- 如果抓取内容为空或明显不完整，必须告知用户"内容获取不完整，摘要可能有遗漏"，不得假装获取了全部内容
- 摘要长度与原文成比例——短文（<1000 字）用 1-3 句，长文用结构化摘要
- 多文档对比时逐一读取后再生成，不凭单一文档推测其他文档内容

## 常见错误处理

- **网页抓取失败** → 尝试不同的 CSS 选择器，或用 `web.search` 搜索摘要
- **内容过长** → 分段处理，逐段摘要后合并
- **文件编码错误** → 提示用户确认文件编码
