---
name: research-assistant
description: 当用户要做多源搜索、交叉验证、调研行业动态、对比分析、技术选型、竞品分析或事实核查时使用。关键词：调研、查资料、了解一下、对比分析、最新动态、行业趋势、技术选型、竞品分析、事实核查、搜一下。代码库内搜索用 code-assistant，数据集统计用 data-analyst，已绑定知识库的精确查询直接用 knowledge.search。
version: 2.0.0
metadata:
  zhiwei:
    category: content-creation
    priority: normal
    tags:
      - research
      - investigation
      - fact-check
      - trend-analysis
      - competitive-analysis
    suggested_tools:
      - web.search
      - web.fetch
      - knowledge.search
      - memory
      - file.write
---

# 信息调研指南

通过多源搜索、交叉验证和结构化整理，为用户提供准确、全面的调研结果。

## 适用场景

- 技术选型调研（框架对比、方案评估）
- 竞品分析和市场调研
- 行业动态和趋势追踪
- 事实核查和信息验证
- 学术文献和技术文档检索

## 不适用场景

- 已知答案的简单问题 → 直接回答
- 代码库内搜索 → 用 code-assistant
- 数据集统计分析 → 用 data-analyst
- 已绑定知识库的精确查询 → 直接用 `knowledge.search`

## 工作流

1. **明确调研目标**：问题模糊先澄清范围再搜
2. **广度搜索**：`web.search` 拿全局概览，识别子话题
3. **深度搜索**：对子话题 `web.search` 或 `knowledge.search`
4. **深度抓取**：高价值来源用 `web.fetch` 抓正文
5. **交叉验证**：关键数据至少 2 个独立来源，矛盾信息要明示
6. **结构化输出**：按调研类型选格式（对比表 / SWOT / 证据链 / 时间线）；每条事实标注来源
7. **保存**：`file.write` 写报告，`memory.create` 保留关键结论

## 详细参考

- 搜索命令模板、输出格式表、来源标注规范、错误处理：`{skill_dir}/references/research-workflow.md`
</content>
</invoke>