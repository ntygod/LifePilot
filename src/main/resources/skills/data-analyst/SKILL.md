---
name: data-analyst
description: 当用户要在内存中对 CSV / JSON / Excel 做数据加载、清洗、统计分析、假设检验、图表可视化或数据质量检查时使用。关键词：分析这份数据、做个图表、统计一下、数据可视化、处理 CSV、处理 Excel、数据清洗、画图、pandas、matplotlib。数据库 SQL 查询用 database-query，日志文件分析用 log-analyzer。
version: 2.0.0
metadata:
  zhiwei:
    category: infrastructure
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

### 预览数据

大文件先预览结构，确认列名和格式：

```
file.read(path="data.csv", maxChars=2000)
```

### 加载与探索

使用持久内核保持变量跨调用共享：

```python
code.execute(language="python", kernelId="analysis", code="""
import pandas as pd
df = pd.read_csv('data.csv')
print(f'行数: {len(df)}, 列数: {len(df.columns)}')
print(df.dtypes)
print(df.describe())
print(df.isnull().sum())
""")
```

`kernelId` 相同的调用共享变量。不传则一次性沙箱。

### 数据清洗

```python
code.execute(language="python", kernelId="analysis", code="""
df = df.dropna(subset=['关键列'])
df['日期列'] = pd.to_datetime(df['日期列'])
df = df.drop_duplicates()
print(f'清洗后: {len(df)} 行')
""")
```

### 分析与可视化

```python
code.execute(language="python", kernelId="analysis", code="""
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
plt.rcParams['font.sans-serif'] = ['SimHei']

fig, ax = plt.subplots(figsize=(10, 6))
df.groupby('分类列')['数值列'].mean().plot(kind='bar', ax=ax)
ax.set_title('分类均值对比')
plt.tight_layout()
plt.savefig('output/chart.png', dpi=150)
print('图表已保存')
""")
```

### 保存结果

```python
code.execute(language="python", kernelId="analysis", code="df.to_csv('output/cleaned.csv', index=False)")
```

## 规则

- 大文件（>100MB）先用 `file.read` 预览，再用 `chunksize` 或 `usecols` 分块加载，不一次性全量读取
- 可视化使用 `Agg` 后端 + `plt.savefig()`，不用交互式显示
- 中文图表必须设置字体：`plt.rcParams['font.sans-serif'] = ['SimHei']`
- 分析结论必须有数据支撑，输出具体数值而非模糊描述
- 清洗操作前先打印清洗前后的行数变化，让用户知道丢失了多少数据

## 常见错误处理

- **编码错误** → 尝试 `encoding='utf-8'` 或 `encoding='gbk'`
- **内存不足** → 使用 `chunksize` 分块读取，或 `usecols` 只读需要的列
- **图表中文乱码** → 设置 matplotlib 中文字体
- **依赖未安装** → `shell.exec(command="pip install openpyxl")` 安装
