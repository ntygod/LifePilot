---
name: doc-processor
description: 当用户要生成 Word/Excel/PowerPoint、做文档格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）、PDF 文本提取、读写 Excel、合并文档或批量转换时使用。关键词：生成 Word、生成 docx、生成 Excel、生成 xlsx、生成 PPT、生成 pptx、生成幻灯片、做演示文稿、做个报告、做周报、转 PDF、提取 PDF 文本、Word 转 Markdown、合并文档、批量转换、pandoc、python-docx、openpyxl。纯文本/Markdown 编辑直接用 file.write，内容创作用 content-creator，数据分析用 data-analyst。
version: 2.1.0
metadata:
  zhiwei:
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

`code.execute` + Python 库(python-docx / openpyxl / python-pptx / pypdf)做生成与编辑;`shell.exec` + CLI(pandoc / pdftotext / wkhtmltopdf)做转换与提取。

## 适用场景

- 生成 Word / Excel / PowerPoint(从 markdown 或结构化数据)
- 格式转换(Markdown ↔ HTML ↔ DOCX ↔ PDF)
- PDF 文本提取
- Excel 读写、多 sheet 处理
- 多文档合并 / 批量转换

## 不适用场景

- 纯文本 / Markdown 编辑 → `file.write`
- 内容创作(写文章 / 邮件 / 报告正文) → content-creator
- 数据分析(统计 / 可视化) → data-analyst
- 飞书 / 在线文档 → feishu 或对应渠道 Skill

## 工作流(按用户表达分流)

| 用户表达 | 路径 |
|---|---|
| 生成 Word | `code.execute` + python-docx |
| 生成 Excel | `code.execute` + openpyxl |
| 生成 PPT / 幻灯片 / 演示文稿 | `code.execute` + python-pptx |
| Markdown / HTML / DOCX / PDF 互转 | `shell.exec` + pandoc |
| PDF 文本提取 | `shell.exec` + pdftotext,或 `code.execute` + pypdf |
| HTML → PDF | `shell.exec` + wkhtmltopdf |
| 多文档合并 | pandoc 多输入 / pypdf 合并 |
| 批量转换 | shell 循环跑 pandoc |

## 各路径决策点(本 Skill 独有)

- **加密 PDF**:让用户给密码,或先 `qpdf --decrypt` 预处理
- **结果验证**:生成后用 `file.read` 抽样确认 —— 库兼容性问题可能让 `exitCode=0` 但输出异常
- **批量先单后批**:批量转换前先跑一个验证格式,再循环
- **依赖未装**:第一次用某库 / CLI 时先探可用性;不存在时把安装命令告诉用户,**不代装**

## 详细参考

- 各路径命令模板、Python 片段、依赖检查命令、错误处理表:`{skill_dir}/references/doc-conversion.md`
