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

1. **选工具**：格式转换 `pandoc`；PDF 提取 `pdftotext`；Excel `openpyxl`；Word `python-docx`
2. **检查可用性**：`pandoc --version` 失败则提示用户安装，不跳过
3. **输入校验**：转换前确认输入文件存在且格式正确
4. **执行转换**：单命令或 shell 循环批量
5. **验证结果**：必须读取输出文件确认内容正确，不盲目报告"转换完成"
6. **加密 PDF**：提示用户提供密码或用 `qpdf --decrypt` 预处理
7. **批量前先试单个**，成功后再批量

## 详细参考

- 工具选择矩阵、命令模板、Python 片段、错误处理：`{skill_dir}/references/doc-conversion.md`
</content>
</invoke>