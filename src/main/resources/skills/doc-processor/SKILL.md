---
name: doc-processor
description: 当用户要做文档格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）、PDF 文本提取、Excel 读写、Word 程序化生成、多文档合并或批量转换时使用。关键词：转 PDF、提取 PDF 文本、Word 转 Markdown、合并文档、批量转换、处理 Excel、生成 Word、pandoc。纯文本/Markdown 编辑直接用 file.write，内容创作用 content-creator，数据分析用 data-analyst。
version: 2.0.0
metadata:
  zhiwei:
    category: content-creation
    priority: normal
    tags:
      - document
      - pandoc
      - pdf
      - docx
      - excel
      - conversion
    suggested_tools:
      - shell.exec
      - code.execute
      - file.read
      - file.write
---

# 文档处理指南

通过 CLI 工具和 Python 库完成文档格式转换、内容提取和批量处理。

## 适用场景

- 格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）
- PDF 文本提取和解析
- Excel 读写和数据导出
- Word 文档程序化生成
- 多文档合并
- 批量格式转换

## 不适用场景

- 纯文本/Markdown 文件编辑 → 直接用 `file.write`
- 内容创作 → 用 content-creator
- 数据分析 → 用 data-analyst
- 在线文档操作 → 用对应集成 Skill

## 工作流

### 工具选择决策

```
需要什么操作？
├── 格式转换（Markdown/HTML/DOCX/PDF）→ pandoc
├── PDF 文本提取 → pdftotext
├── HTML 转 PDF → wkhtmltopdf
├── Excel 读写 → Python openpyxl
├── Word 程序化生成 → Python python-docx
└── 批量处理 → shell 脚本循环
```

### 确认工具可用性

```bash
shell.exec(command="pandoc --version")
```

未安装时提示用户安装：
- pandoc：`choco install pandoc` / `brew install pandoc`
- pdftotext：安装 poppler-utils
- wkhtmltopdf：`choco install wkhtmltopdf`
- Python 库：`pip install openpyxl python-docx`

### 格式转换

```bash
shell.exec(command="pandoc input.md -o output.html --standalone")
shell.exec(command="pandoc input.md -o output.docx")
shell.exec(command="pandoc input.md -o output.pdf --pdf-engine=wkhtmltopdf")
shell.exec(command="pandoc input.html -t markdown -o output.md")
shell.exec(command="pandoc part1.md part2.md part3.md -o combined.pdf")
```

### PDF 处理

```bash
shell.exec(command="pdftotext input.pdf output.txt")
shell.exec(command="pdfinfo input.pdf")
```

### Excel 读写

```python
code.execute(language="python", code="
import openpyxl
wb = openpyxl.load_workbook('data.xlsx')
ws = wb.active
for row in ws.iter_rows(values_only=True):
    print(row)
")
```

### Word 程序化生成

```python
code.execute(language="python", code="
from docx import Document
doc = Document()
doc.add_heading('标题', 0)
doc.add_paragraph('正文内容')
doc.save('output.docx')
")
```

### 批量转换

```bash
shell.exec(command="for f in docs/*.md; do pandoc \"$f\" -o \"${f%.md}.html\" --standalone; done")
```

### 验证结果

```
file.read(path="output.html", maxChars=5000)
```

转换后读取确认内容正确。

## 规则

- 转换前确认输入文件存在且格式正确
- 转换后必须读取输出文件验证结果，不盲目报告"转换完成"
- 工具未安装时给出具体安装命令，不跳过
- PDF 加密文件需提示用户提供密码
- 批量操作先在单个文件上测试，成功后再批量执行

## 常见错误处理

- **工具未安装** → 给出对应平台的安装命令
- **编码问题** → 指定输入编码 `--from markdown+utf8`
- **PDF 加密** → 提示用户提供密码，或用 `qpdf --decrypt` 解密
- **格式丢失** → 复杂格式转换可能丢失样式，建议先转换单页预览确认
