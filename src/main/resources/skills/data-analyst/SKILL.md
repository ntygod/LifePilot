---
name: data-analyst
description: 当用户要在内存中对 CSV / JSON / Excel 做数据加载、清洗、统计分析、假设检验、图表可视化或数据质量检查时使用。关键词：分析这份数据、做个图表、统计一下、数据可视化、处理 CSV、处理 Excel、数据清洗、画图、pandas、matplotlib。数据库 SQL 查询用 database-query，日志文件分析用 log-analyzer。
version: 2.0.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - data-analysis
      - csv
      - excel
      - pandas
      - matplotlib
      - visualization
      - statistics
    suggested_tools:
      - code.execute
      - file.read
      - file.write
      - file.list
      - shell.exec
---

# 数据分析指南

通过代码执行环境完成数据加载、分析和可视化任务。

## 适用场景

- CSV / JSON / Excel 数据探索和分析
- 数据清洗和转换
- 统计分析和假设检验
- 图表生成和数据可视化
- 数据质量检查

## 不适用场景

- 数据库 SQL 查询 → 用 database-query
- 日志文件分析 → 用 log-analyzer
- 简单数学计算 → 直接回答

## 工作流

1. **预览数据**：大文件先 `file.read` 采样 2000 字符确认列名格式
2. **持久内核**：`code.execute` 带相同 `kernelId` 跨调用共享变量
3. **加载探索**：`pd.read_csv` + `dtypes` + `describe` + `isnull().sum()`
4. **清洗**：打印清洗前后行数变化，让用户知道丢了多少数据
5. **可视化**：`matplotlib.use('Agg')` + `plt.savefig`，不交互式；中文必须设 `SimHei` 字体
6. **大文件防 OOM**：`chunksize` 或 `usecols` 分块/选列加载
7. **结论有数据支撑**：输出具体数值而非模糊描述

## 详细参考

- 预览 / 加载 / 清洗 / 可视化 Python 片段 + 错误处理：`{skill_dir}/references/pandas-recipes.md`
</content>
</invoke>