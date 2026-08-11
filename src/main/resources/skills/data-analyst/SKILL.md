---
name: data-analyst
description: 当用户要在内存中对 CSV / JSON / Excel 做数据加载、清洗、统计分析、假设检验、图表可视化或数据质量检查时使用。
version: 2.1.2
metadata:
  zhiwei:
    tags:
      - data-analysis
      - csv
      - excel
      - json
      - pandas
      - matplotlib
      - visualization
      - statistics
    suggested_tools:
      - code
      - file.read
      - file.write
      - shell.exec
    outputs:
      - text
      - file
---
# 数据分析指南

用户给数据要分析 / 看图 / 出结论时进入。多步分析用同一 `kernelId` 跨调用共享 dataframe，避免每步重新加载。

## 触发判断
- CSV / JSON / Excel 数据探索(列名、分布、缺失)
- 清洗与转换(去重、填空、类型转换、结构化)
- 统计分析(描述性、相关性、假设检验)
- 数据可视化(柱状 / 折线 / 散点 / 热力图)
- 时间序列分析
- 数据质量检查 / 多份数据对比
- 处理后导出回 CSV / Excel / JSON

不要触发：

- 简单数学计算 → 直接回答

## 决策路径

| 用户表达 | 路径 |
|---|---|
| "看下这份数据 / 列名是啥 / 多大" | 预览 → 加载探索(`describe` / `dtypes` / `isnull`) |
| "清洗一下 / 去重 / 填空 / 改类型" | 持久 kernel 加载 → 逐步转换,每步打印行数变化 |
| "统计 / 相关性 / 显著吗" | 加载后跑 `describe` / `corr` / `scipy.stats` |
| "画图 / 可视化 / 柱状图 / 趋势" | matplotlib Agg 后端 + 中文字体 + `savefig` 落盘 |
| "导出 / 存一份" | `to_csv` / `to_excel` / `to_json` 写到 cwd（绝对路径来自工具返回的 workingDirectory） |
| "对比这两份" | 同 kernel 加载两个 df,做 join / merge / diff |

## 各路径决策点(本 Skill 独有)

- **持久 kernel**:多步分析必须传同一 `kernelId`,跨调用复用 dataframe;一次性查询不传
- **大文件防 OOM**:文件 >500MB 用 `chunksize` 分块或 `usecols` 选列加载
- **中文字体**:可视化必须 `plt.rcParams['font.sans-serif'] = ['SimHei']` 或 `WenQuanYi`,否则中文乱码
- **结论给数值**:输出具体数值(均值 / 占比 / p 值 / 置信区间),禁止"差不多""挺多的"等模糊描述
- **清洗透明**:每一步打印 `原 N 行 → 清洗后 M 行`,让用户知道丢了多少
- **依赖未装**:预装栈未覆盖的库（如 plotly / dash / xgboost）才用 `shell.exec(command="pip install <pkg>")` 装,且需用户接受额外耗时,**不要静默失败**


## 输出标准

- 输出数据规模、字段、关键统计、图表路径和可复现分析步骤。
- 清洗结果要列原始行数、处理后行数、删除/填补规则和导出路径。
- 图表必须保存为文件，并说明图表含义和局限。


## 失败策略

- 文件缺失、编码错误、列名不明或数据过大时先诊断再处理。
- 依赖缺失时说明安装影响，未经允许不安装重库。
- 统计结论不显著或样本不足时明确说不支持强结论。

## 详细参考
- 各路径 Python 片段、命令模板、错误处理:`{skill_dir}/references/pandas-recipes.md`
