---
name: doc-processor
description: 当用户要生成 Word/Excel/PowerPoint、做文档格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）、PDF 文本提取、读写 Excel、合并文档或批量转换时使用。
version: 2.2.0
metadata:
  zhiwei:
    tags:
      - document
      - pandoc
      - pdf
      - docx
      - excel
      - conversion
    suggested_tools:
      - shell.exec
      - code
      - file.read
      - file.write
---

# 文档处理指南

文档处理优先走可执行工具链：`code` + Python 库（python-docx / openpyxl / python-pptx / pypdf）负责生成、编辑和精细处理；`shell.exec` + CLI（pandoc / poppler / wkhtmltopdf / libreoffice）负责格式转换和批处理。

当前 Agent 默认只注入少量核心工具。若运行时没有 `code`、`shell.exec`、`file.write` 等 schema，先用 `tool.search` 按工具名或能力检索，发现后再执行。

## 适用场景

- 生成 Word / Excel / PowerPoint（从 Markdown、CSV、JSON 或结构化数据）
- 格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）
- PDF 文本提取
- Excel 读写、多 sheet 处理
- 多文档合并 / 批量转换
- 从现有文档抽取内容，整理成 Markdown / CSV / JSON

## 不适用场景

- 纯文本 / Markdown 编辑 → `file.write`
- 内容创作(写文章 / 邮件 / 报告正文) → content-creator
- 数据分析(统计 / 可视化) → data-analyst
- 飞书 / 在线文档 → feishu 或对应渠道 Skill

## 工作流(按用户表达分流)

| 用户表达 | 路径 |
|---|---|
| 生成 Word | 优先 `{skill_scripts_dir}/md2docx.py` 或 `code` + python-docx |
| 生成 Excel | `{skill_scripts_dir}/xlsx_tools.py from-csv` 或 `code` + openpyxl |
| 生成 PPT / 幻灯片 / 演示文稿 | `code` + python-pptx |
| Markdown / HTML / DOCX / PDF 互转 | `shell.exec` + pandoc |
| PDF 文本提取 | `{skill_scripts_dir}/pdf_tools.py extract-text` 或 `pdftotext` |
| HTML → PDF | `shell.exec` + wkhtmltopdf / pandoc |
| 多 PDF 合并 / 拆分 | `{skill_scripts_dir}/pdf_tools.py merge/split` |
| 批量转换 | 先单文件验证，再 `shell.exec` 循环或写临时脚本 |

## 各路径决策点(本 Skill 独有)

- **先探测依赖**：第一次使用库 / CLI 前先跑 `python -c "import ..."` 或 `pandoc --version`。依赖缺失时说明安装命令；只有用户明确允许时才安装。
- **先生成中间格式**：复杂 Word/PDF 优先产出 Markdown/HTML/CSV 中间文件，确认内容结构后再转目标格式。
- **结果必须验证**：生成后用 `file.read` 抽样确认文本内容；对二进制 Office/PDF，可用脚本提取文本或列 sheet/页数。
- **批量先单后批**：批量转换前先跑 1 个样本，确认样式、编码、字体和路径规则后再循环。
- **加密 PDF**：让用户提供密码，或用 `qpdf --decrypt` 预处理；不要尝试绕过权限。
- **不要走 Java document 工具**：当前文档生成/转换不依赖 `document.create` / `document.edit`，应使用本 Skill 的脚本、Python 库或 CLI。

## 输出约定

- 产物路径优先放在用户指定目录；没有指定时放到当前 workspace 下可读写路径。
- 生成多个文件时同时输出一个简短清单，列文件名、格式、来源和验证结果。
- 若转换存在格式损失（分页、脚注、复杂表格、批注、宏、图表），在结果里明确说明。

## 详细参考

- 转换路径、命令模板、脚本清单：`{skill_dir}/references/doc-conversion.md`
- 依赖探测与安装建议：`{skill_dir}/references/dependency-setup.md`
- Office/PDF 处理配方：`{skill_dir}/references/office-recipes.md`
- 常见错误处理表：`{skill_dir}/references/troubleshooting.md`
