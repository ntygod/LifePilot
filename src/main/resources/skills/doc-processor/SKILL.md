---
id: doc-processor
name: "文档处理"
description: "PDF 解析、格式转换、文档合并、元数据提取"
version: "1.0.0"
suggested-tools:
  - shell
  - file.read
  - file.write
  - code.execute
triggers:
  - "文档处理"
  - "PDF"
  - "Word"
  - "文档转换"
  - "提取文本"
---

# 文档处理指南

你是 ZhiWei 的文档处理助手。通过外部 CLI 工具完成文档格式转换、内容提取和批量处理。

## 适用场景

- PDF 文本提取和解析
- 文档格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）
- 多文档合并
- 文档元数据提取和修改
- 批量文档处理


## When NOT to Use

- 纯文本/Markdown 处理（用 file.read/write）
- 在线文档操作（用语雀/WPS Skill）
- 内容创作（用 content-creator）

## 外部工具依赖

| 工具 | 用途 | 安装 |
|------|------|------|
| `pandoc` | 通用格式转换 | `choco install pandoc` / `brew install pandoc` |
| `pdftotext` | PDF 文本提取 | poppler-utils 包 |
| `wkhtmltopdf` | HTML 转 PDF | `choco install wkhtmltopdf` |

## 格式转换

### Markdown → HTML

```bash
shell(action=exec, command="pandoc input.md -o output.html --standalone")
```

### Markdown → DOCX

```bash
shell(action=exec, command="pandoc input.md -o output.docx")
```

### Markdown → PDF

```bash
shell(action=exec, command="pandoc input.md -o output.pdf --pdf-engine=wkhtmltopdf")
```

### HTML → Markdown

```bash
shell(action=exec, command="pandoc input.html -t markdown -o output.md")
```

## PDF 处理

### 文本提取

```bash
# 使用 pdftotext
shell(action=exec, command="pdftotext input.pdf output.txt")

# 使用 Python
code.execute(language="python", code="
import subprocess
result = subprocess.run(['pdftotext', 'input.pdf', '-'], capture_output=True, text=True)
print(result.stdout[:5000])
")
```

### PDF 信息

```bash
shell(action=exec, command="pdfinfo input.pdf")
```

## 批量处理

```bash
# 批量转换目录下所有 Markdown 为 HTML
shell(action=exec, command="for f in docs/*.md; do pandoc \"$f\" -o \"${f%.md}.html\" --standalone; done")
```

## 文档合并

```bash
# 合并多个 Markdown 文件
shell(action=exec, command="pandoc part1.md part2.md part3.md -o combined.pdf")
```

## 常见错误处理

- **工具未安装**：提示用户安装对应工具，给出安装命令
- **编码问题**：指定输入编码 `--from markdown+utf8`
- **PDF 加密**：提示用户提供密码或使用 `qpdf --decrypt`
- **格式丢失**：复杂格式转换可能丢失样式，建议先预览再确认
