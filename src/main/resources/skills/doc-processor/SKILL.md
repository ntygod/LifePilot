---
name: doc-processor
description: 当用户要生成 Word/Excel/PowerPoint、做文档格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）、PDF 文本提取、读写 Excel、合并文档或批量转换时使用。关键词：生成 Word、生成 docx、生成 Excel、生成 xlsx、生成 PPT、生成 pptx、生成幻灯片、做演示文稿、做个报告、做周报、转 PDF、提取 PDF 文本、Word 转 Markdown、合并文档、批量转换、pandoc、python-docx、openpyxl。纯文本/Markdown 编辑直接用 file.write，内容创作用 content-creator，数据分析用 data-analyst。
version: 2.0.0
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

通过 `code.execute` + Python 库（python-docx / openpyxl / python-pptx / pypdf）以及 `shell.exec` + CLI 工具（pandoc / pdftotext）完成文档生成、转换和提取。

## 适用场景

- **生成文档**：Word（docx）/ Excel（xlsx）/ PowerPoint（pptx）从 markdown 或结构化数据生成
- 格式转换（Markdown ↔ HTML ↔ DOCX ↔ PDF）
- PDF 文本提取和解析
- Excel 读写和数据导出
- 多文档合并
- 批量格式转换

## 不适用场景

- 纯文本/Markdown 文件编辑 → 直接用 `file.write`
- 内容创作（写文章/邮件/报告正文） → 用 content-creator
- 数据分析（统计/可视化） → 用 data-analyst
- 飞书 / 在线文档 → 用 feishu 或对应渠道 Skill

## 工作流

1. **选路径**：
   - 生成 Word：`code.execute` + `python-docx`（标题/段落/列表/表格）
   - 生成 Excel：`code.execute` + `openpyxl`（表头/数据行/多 sheet）
   - 生成 PPT：`code.execute` + `python-pptx`（标题页/正文/要点/备注）
   - 格式转换：`shell.exec` + `pandoc`
   - PDF 提取：`shell.exec` + `pdftotext`，或 `code.execute` + `pypdf`
2. **检查可用性**：`shell.exec(command="pandoc --version")` 或 `code.execute(code="import docx; print(docx.__version__)")` 失败则提示用户安装（`pip install python-docx openpyxl python-pptx pypdf` / `choco install pandoc`）
3. **输入校验**：操作前确认输入文件存在且格式正确
4. **执行**：单命令或脚本循环批量
5. **验证结果**：必须读取输出文件确认内容正确，不盲目报告"已生成"
6. **加密 PDF**：提示用户提供密码或用 `qpdf --decrypt` 预处理
7. **批量前先试单个**，成功后再批量

## 详细参考

- 工具选择矩阵、命令模板、Python 片段、错误处理：`{skill_dir}/references/doc-conversion.md`
</content>
</invoke>