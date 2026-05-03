# 文档处理参考

> pandoc/python-pptx/python-docx 命令直接用 code 或 shell_exec。本文档仅含格式兼容与依赖知识。

## 依赖工具

| 工具 | 用途 | 安装 |
|------|------|------|
| pandoc | Markdown↔HTML/DOCX/PDF 互转 | choco/brew/apt |
| pdftotext | PDF 文本提取 | poppler-utils |
| python-docx/openpyxl/python-pptx/pypdf | Word/Excel/PPT/PDF 读写 | `pip install` |

不存在时给用户对应平台安装命令，不自行安装。

## 格式兼容矩阵

| 源格式 | 目标格式 | 最佳路径 |
|--------|---------|---------|
| Markdown | HTML | pandoc |
| Markdown | DOCX | pandoc（简单）/ python-docx（精细） |
| Markdown | PDF | pandoc --pdf-engine=wkhtmltopdf |
| HTML | Markdown | pandoc -t markdown |
| PDF | 文本 | pdftotext（简单）/ pypdf（精细） |
| Excel | 结构化数据 | openpyxl |

## 验证

生成后必 file_read 确认格式正常。复杂格式转换先转一页预览，确认可接受再批量。

## 可用脚本

- `{skill_scripts_dir}/md2docx.py <input.md> [output.docx]` — Markdown 转 Word，支持标题层级/列表/代码块
