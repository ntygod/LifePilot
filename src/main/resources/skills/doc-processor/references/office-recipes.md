# Office 与 PDF 处理配方

## Markdown 生成 Word

优先脚本：

```bash
python {skill_scripts_dir}/md2docx.py report.md report.docx
```

若需要复杂样式，直接用 `code` 写 python-docx 逻辑，先把标题、段落、表格、图片分层处理。

## CSV / Excel

CSV 转 xlsx：

```bash
python {skill_scripts_dir}/xlsx_tools.py from-csv data.csv data.xlsx --sheet 数据
```

xlsx 转 CSV：

```bash
python {skill_scripts_dir}/xlsx_tools.py to-csv data.xlsx data.csv --sheet Sheet1
```

检查 workbook：

```bash
python {skill_scripts_dir}/xlsx_tools.py inspect data.xlsx
```

## PDF 文本抽取与合并

提取文本：

```bash
python {skill_scripts_dir}/pdf_tools.py extract-text input.pdf output.txt
```

合并 PDF：

```bash
python {skill_scripts_dir}/pdf_tools.py merge merged.pdf a.pdf b.pdf c.pdf
```

拆分 PDF：

```bash
python {skill_scripts_dir}/pdf_tools.py split input.pdf out_pages
```

## 验证

- Word/PDF：提取前几段文本，确认标题、关键数字和中文没有乱码。
- Excel：用 `inspect` 看 sheet 名、行列数，再抽样导出 CSV。
- 批量任务：保存转换日志，记录成功/失败文件清单。
