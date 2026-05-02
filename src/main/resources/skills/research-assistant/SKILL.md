---
name: research-assistant
description: 当用户要做多源搜索、交叉验证、调研行业动态、对比分析、技术选型、竞品分析或事实核查时使用。
version: 2.1.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - research
      - investigation
      - fact-check
      - trend-analysis
      - competitive-analysis
    suggested_tools:
      - web_search
      - web_fetch
      - memory
      - memory
      - file_write
---

# 信息调研指南

多源搜索 + 交叉验证 + 结构化整理。**核心约束：每条结论都能追溯到来源，矛盾信息必须明示。**

## 适用场景

- 技术选型（框架对比、方案评估）
- 竞品分析 / 市场调研
- 行业动态 / 趋势追踪
- 事实核查（"X 是真的吗 / 数据准吗"）
- 学术文献 / 技术文档检索
- 跨多个来源的对比分析

## 不适用场景

- 已知答案的简单问题 → 直接回答
- 代码库内搜索 → code-assistant
- 数据集统计 → data-analyst
- 已绑定知识库的精确查询 → 直接 `memory`

## 工作流（按调研深度分流）

| 调研深度 | 用户表达 | 路径 |
|---|---|---|
| **快速答** | "X 是什么 / 听说 X 是真的吗" | `web_search` 1 次 → 直接答（带来源） |
| **深度对比** | "X 和 Y 比 / 选 X 还是 Y / 主流方案有哪些" | 广→深→交叉→结构化 |
| **趋势 / 动态** | "X 最近怎么样 / 行业现在什么趋势" | `web_search` 限定时间窗 → 多源汇总 |
| **事实核查** | "这个数字对吗 / X 真的做了 Y 吗" | 至少 2 个独立来源验证，矛盾明示 |

各路径要点：

- **广度搜索**：`web_search` 拿全局概览，识别子话题
- **深度抓取**：高价值来源用 `web_fetch` 抓正文（不只看搜索摘要）
- **交叉验证**：关键数据 ≥ 2 个独立来源；冲突时明示分歧而非择一
- **结构化输出**：按场景选格式（对比表 / SWOT / 证据链 / 时间线），每条事实带来源 `[来源标题](URL)`
- **保存**：长期参考价值的 `file_write` 写报告 + `memory(action="create")` 记关键结论

## 详细参考

- 搜索命令模板、输出格式表、来源标注规范、错误处理：`{skill_dir}/references/research-workflow.md`
