# 数据分析 pandas / matplotlib 片段速查

## 1. 预览结构

大文件先采样确认列名、分隔符、编码:

```
file.read(path="<data.csv>", maxChars=2000)
```

## 2. 加载与探索

```python
code(language="python", kernelId="<analysis>", code="""
import pandas as pd
df = pd.read_csv('<data.csv>')
print(f'行数: {len(df)}, 列数: {len(df.columns)}')
print(df.dtypes)
print(df.describe())
print(df.isnull().sum())
""")
```

Excel / JSON 替换为 `pd.read_excel('<x.xlsx>', sheet_name='<sheet>')` / `pd.read_json('<x.json>')`。

## 3. 大文件加载(>500MB)

```python
code(language="python", kernelId="<analysis>", code="""
import pandas as pd
# 选列加载
df = pd.read_csv('<big.csv>', usecols=['<col1>', '<col2>'])

# 或分块累积
chunks = []
for chunk in pd.read_csv('<big.csv>', chunksize=100_000):
    chunks.append(chunk[chunk['<col>'] > <threshold>])
df = pd.concat(chunks, ignore_index=True)
""")
```

## 4. 清洗

每步打印行数变化:

```python
code(language="python", kernelId="<analysis>", code="""
print(f'原 {len(df)} 行')
df = df.dropna(subset=['<关键列>'])
print(f'去缺失 → {len(df)} 行')
df = df.drop_duplicates()
print(f'去重 → {len(df)} 行')
df['<日期列>'] = pd.to_datetime(df['<日期列>'])
df['<数值列>'] = pd.to_numeric(df['<数值列>'], errors='coerce')
""")
```

## 5. 统计与假设检验

```python
code(language="python", kernelId="<analysis>", code="""
print(df.describe())                          # 描述性统计
print(df[['<col1>', '<col2>']].corr())        # 相关系数

from scipy import stats
t, p = stats.ttest_ind(df[df['<group>']=='A']['<val>'],
                       df[df['<group>']=='B']['<val>'])
print(f't={t:.4f}, p={p:.4f}')
""")
```

## 6. 可视化(Agg 后端 + 中文字体)

```python
code(language="python", kernelId="<analysis>", code="""
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
plt.rcParams['font.sans-serif'] = ['SimHei']
plt.rcParams['axes.unicode_minus'] = False

fig, ax = plt.subplots(figsize=(10, 6))
df.groupby('<分类列>')['<数值列>'].mean().plot(kind='bar', ax=ax)
ax.set_title('<图标题>')
plt.tight_layout()
plt.savefig('<output/chart.png>', dpi=150)
print('图表已保存')
""")
```

常用图类型替换 `plot(kind=...)`:`bar` / `line` / `scatter` / `hist` / `box`。热力图用 `seaborn.heatmap(df.corr())`。

## 7. 导出

```python
code(language="python", kernelId="<analysis>", code="""
df.to_csv('<output/cleaned.csv>', index=False)
df.to_excel('<output/cleaned.xlsx>', index=False)
df.to_json('<output/cleaned.json>', orient='records', force_ascii=False)
""")
```

## 8. 多份对比

```python
code(language="python", kernelId="<analysis>", code="""
import pandas as pd
df_a = pd.read_csv('<a.csv>')
df_b = pd.read_csv('<b.csv>')

# 按 key 合并对比
merged = df_a.merge(df_b, on='<key>', how='outer', suffixes=('_a', '_b'))
print(f'仅 A: {merged["<col>_b"].isnull().sum()}')
print(f'仅 B: {merged["<col>_a"].isnull().sum()}')
print(f'共有: {merged.dropna().shape[0]}')
""")
```

## kernel 管理

多步分析共享 dataframe → 同一 `kernelId`;不传则一次性沙箱。

```
code(action="list")                          # 看活跃 kernel
code(action="inspect", kernelId="<id>")      # 看里面有哪些变量
code(action="reset", kernelId="<id>")        # 状态变脏时清空重来
```

`code` 也支持特殊 code:`'kernel:reset'` / `'kernel:inspect'`。

## 错误处理表

| 症状 | 对策 |
|---|---|
| `UnicodeDecodeError` | `read_csv(..., encoding='utf-8')` 或 `'gbk'` / `'gb18030'` |
| MemoryError 加载阶段 | `chunksize=100000` 分块 + `usecols=[...]` 选列 |
| 图表中文显示方框 | `plt.rcParams['font.sans-serif'] = ['SimHei']` 或 `WenQuanYi`;Linux 还需安装中文字体包 |
| `ModuleNotFoundError` | `shell.exec(command="pip install openpyxl scipy seaborn")` |
| `to_excel` 失败缺引擎 | `pip install openpyxl`(.xlsx)或 `xlwt`(.xls) |
| 相关性矩阵全 NaN | 列含字符串,先 `select_dtypes(include='number')` |
| `kernelId` 状态混乱 | `code(action="reset", kernelId="...")` 清空重跑 |
