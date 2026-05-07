# 文档处理依赖探测

## 先探测

| 能力 | 探测命令 |
|---|---|
| Python Office/PDF 库 | `python -c "import docx, openpyxl, pptx, pypdf; print('ok')"` |
| pandoc | `pandoc --version` |
| poppler / pdftotext | `pdftotext -v` |
| wkhtmltopdf | `wkhtmltopdf --version` |
| libreoffice | `libreoffice --version` 或 `soffice --version` |

## 安装建议

依赖缺失时先把命令给用户确认。只有用户明确要求安装时再执行安装命令。

| 平台 | Python 库 | CLI |
|---|---|---|
| Windows | `python -m pip install python-docx openpyxl python-pptx pypdf` | `choco install pandoc poppler wkhtmltopdf libreoffice` |
| macOS | `python3 -m pip install python-docx openpyxl python-pptx pypdf` | `brew install pandoc poppler wkhtmltopdf libreoffice` |
| Ubuntu/Debian | `python3 -m pip install python-docx openpyxl python-pptx pypdf` | `sudo apt-get install pandoc poppler-utils wkhtmltopdf libreoffice` |

## 选择原则

- 用户要“格式基本正确”的转换：优先 pandoc。
- 用户要“可控样式/字段/表格”的生成：优先 Python 库。
- 用户只要文本抽取：优先 poppler 的 `pdftotext`；没有时用 `pypdf`。
- 用户要批量处理：先用 1 个样本验证，再批量跑。
