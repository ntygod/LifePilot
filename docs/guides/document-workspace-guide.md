# 知微文档处理与工作区边界指南

> 本文档面向 Skill 作者、Agent 开发者和后端集成开发者。当前版本不再提供 `document.create` / `document.edit` Agent 工具；文档生成、转换和批处理统一走 `doc-processor` Skill。

## 1. 推荐路径

| 用户需求 | 推荐能力 |
|---|---|
| 生成 Word/Excel/PPT | `doc-processor` + `code` + python-docx/openpyxl/python-pptx |
| Markdown/HTML/DOCX/PDF 转换 | `doc-processor` + `shell.exec` + pandoc/wkhtmltopdf |
| PDF 文本抽取/合并/拆分 | `doc-processor` scripts + pypdf/poppler |
| Excel 读写/CSV 转换 | `doc-processor` scripts + openpyxl |
| 读取已有文档内容 | `file.read`，内部会走 `DocumentParserService` |

Agent 默认只注入核心工具。缺少 `code`、`shell.exec`、`file.write` 等 schema 时，先调用 `tool.search` 发现工具。

## 2. 保留的 Java 代码

### `DocumentParserService`

保留。它是 `file.read` 的结构化文档解析 facade，路由到知识库模块的 Markdown/Text/Word/PDF/Excel/PowerPoint parser。删除它会导致 `file.read` 读取 docx/pdf/xlsx/pptx 的体验退化。

### 文档工作区 REST/版本链

暂时保留。它服务已有 `session_documents` 工作副本和前端 DiffCard：

- `GET /api/documents/{id}`：元数据
- `GET /api/documents/{id}/download`：下载当前或历史版本
- `GET /api/documents/{id}/versions`：版本列表
- `GET /api/documents/{id}/diff`：版本 diff
- `POST /api/documents/{id}/commit`：用户确认后覆盖或另存
- `POST /api/documents/{id}/rollback`：回滚到历史版本
- `DELETE /api/documents/{id}/working-copy`：丢弃工作副本

这条链路不再是 Agent 处理文档的主路径；它只管理已经存在的工作副本资产。

## 3. 已删除 / 下架内容

- Java POI 生成器：`MarkdownToDocxGenerator`、`StructuredDataToXlsxGenerator`、`OutlineToPptxGenerator` 及其接口/中间模型。
- `document.create` / `document.edit` Agent 工具入口。

后续文档生成不要恢复这些工具；应补强 `doc-processor` 的脚本、参考文档和执行配方。

## 4. 开发约束

- 新增文档处理能力时优先放到 `src/main/resources/skills/doc-processor/scripts/` 或 references。
- Java 后端只保留与运行时基础设施强相关的能力，例如 `file.read` 多格式解析。
- 前端工作区若未来确认不再需要，应和后端版本链、迁移、测试一起整体删除。

## 5. 相关源码

- `src/main/java/com/lifepilot/document/parser/DocumentParserService.java`
- `src/main/java/com/lifepilot/meta/infra/file/FileReadToolExecutor.java`
- `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java`
- `src/main/resources/skills/doc-processor/SKILL.md`
