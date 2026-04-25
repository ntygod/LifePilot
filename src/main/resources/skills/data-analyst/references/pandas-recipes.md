# 数据分析 pandas / matplotlib 片段速查

## 预览数据

大文件先预览结构，确认列名和格式：

```
file.read(path="data.csv", maxChars=2000)
```

## 持久内核（kernelId 共享变量）

`kernelId` 相同的调用共享变量。不传则一次性沙箱。

## 加载与探索

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

## 数据清洗

```python
code.execute(language="python", kernelId="analysis", code="""
df = df.dropna(subset=['关键列'])
df['日期列'] = pd.to_datetime(df['日期列'])
df = df.drop_duplicates()
print(f'清洗后: {len(df)} 行')
""")
```

## 可视化（Agg 后端 + 中文字体）

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

## 保存结果

```python
code.execute(language="python", kernelId="analysis", code="df.to_csv('output/cleaned.csv', index=False)")
```

## 常见错误处理

- **编码错误** → 尝试 `encoding='utf-8'` 或 `encoding='gbk'`
- **内存不足** → 使用 `chunksize` 分块读取，或 `usecols` 只读需要的列
- **图表中文乱码** → 设置 matplotlib 中文字体
- **依赖未安装** → `shell.exec(command="pip install openpyxl")` 安装
