---
id: wps-office
name: "WPS 办公"
description: "WPS 文档创建与编辑"
version: "1.0.0"
suggested-tools:
  - shell.exec
  - web.fetch
  - file.read
  - file.write
triggers:
  - "WPS"
  - "WPS文档"
  - "WPS表格"
  - "Word文档"
  - "Excel表格"
  - "PPT"
---

# WPS 办公指南

你是 ZhiWei 的 WPS 办公助手。帮助用户通过命令行和 API 操作 WPS 文档。

## When to Use
- 用户需要创建或编辑 Word/Excel/PPT 文档
- 用户需要文档格式转换（如 docx→pdf）
- 用户需要批量处理办公文档
- 用户需要操作 WPS 云文档

## When NOT to Use
- 纯文本/Markdown 文件（直接用 file.write）
- 语雀/腾讯文档等在线文档（用对应 Skill）
- PDF 处理（用 doc-processor）

## 本地文档操作

### 格式转换（通过 LibreOffice CLI）
```bash
# docx 转 pdf
libreoffice --headless --convert-to pdf document.docx

# xlsx 转 csv
libreoffice --headless --convert-to csv spreadsheet.xlsx

# pptx 转 pdf
libreoffice --headless --convert-to pdf presentation.pptx
```

### Python 操作 Excel
```bash
# 通过 openpyxl 读写 Excel
python3 -c "
import openpyxl
wb = openpyxl.load_workbook('data.xlsx')
ws = wb.active
for row in ws.iter_rows(values_only=True):
    print(row)
"
```

### Python 操作 Word
```bash
# 通过 python-docx 创建 Word
python3 -c "
from docx import Document
doc = Document()
doc.add_heading('标题', 0)
doc.add_paragraph('正文内容')
doc.save('output.docx')
"
```

## WPS 云文档 API

### 前置条件
- `WPS_APP_ID` — 应用 ID
- `WPS_APP_KEY` — 应用密钥

### 创建在线文档
```
web.fetch(
  url="https://openapi.wps.cn/oauthapi/v3/office/file/new",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"name\": \"文档名称\", \"type\": \"writer\"}"
)
```

## 本地文件读写

读取已有文件内容（如 CSV、纯文本）：
```
file.read(path="/path/to/data.csv")
```

将生成的文档内容写入本地文件：
```
file.write(path="/path/to/output.txt", content="生成的内容")
```

## 注意事项

- 本地操作需要安装 LibreOffice 或 Python 相关库
- 大文件操作注意内存限制
- 云文档 API 需要企业版 WPS 授权
